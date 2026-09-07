package com.aircontrol.runtime

/**
 * In-memory performance & lifecycle telemetry.
 *
 * Motivation: the 2026-09-07 audit could only *infer* frame-interval health,
 * allocation pressure and watchdog behavior from logcat after the fact. This
 * makes the pipeline instrument itself: frame intervals, analyzer and inference
 * durations, configured-vs-actual FPS, throttle drops, thermal/power/memory
 * events, watchdog actions and camera lifecycle transitions are recorded in a
 * bounded ring buffer, exposed to the Debug screen and (only in debug builds)
 * summarized to the log at most once every [LOG_INTERVAL_MS].
 *
 * Design rules:
 * - **Hot-path safe**: recording methods are synchronized short integer/long
 *   operations with zero allocations; safe to call per frame (≤30 Hz).
 * - **Rate-limited logging**: [maybeLogSummary] emits at most one summary per
 *   [LOG_INTERVAL_MS] and only while [enableLogging] is true. Production sets
 *   `enableLogging = BuildConfig.DEBUG` and the release log tree is WARN+, so
 *   telemetry can never become a logging performance problem in release.
 * - **Deterministic**: every method takes its timestamp as a parameter, so
 *   unit tests control time exactly.
 *
 * Pure Kotlin (no Android imports) — testable on the JVM.
 */
object PerfTelemetry {

    private const val FRAME_WINDOW = 128
    private const val EVENT_WINDOW = 64
    private const val LOG_INTERVAL_MS = 30_000L

    /** Debug builds only — set from `AirControlApp.onCreate`. */
    @Volatile
    var enableLogging: Boolean = false

    /** Log sink (Timber in production). Swapped in tests. */
    @Volatile
    var loggingSink: (String) -> Unit = {}

    // ---- frame interval window -------------------------------------------
    private val frameIntervals = LongArray(FRAME_WINDOW)
    private var frameIntervalCount = 0
    private var frameIntervalWrite = 0

    // -1 = "no baseline yet". A 0L sentinel would swallow a first frame at
    // elapsedRealtime()==0 and is ambiguous with a real timestamp in tests.
    private var lastFrameAtMs = -1L

    // ---- duration accumulators (reset on every summary) -------------------
    private var analyzerSamples = 0L
    private var analyzerSumMs = 0L
    private var analyzerMaxMs = 0L
    private var handInferSamples = 0L
    private var handInferSumMs = 0L
    private var handInferMaxMs = 0L
    private var faceInferSamples = 0L
    private var faceInferSumMs = 0L
    private var faceInferMaxMs = 0L

    // ---- counters ----------------------------------------------------------
    private var framesProcessed = 0L
    private var framesDroppedThrottle = 0L
    private var framesDroppedNoTracker = 0L
    private var watchdogActions = 0L
    private var configuredFps = 0
    private var lastActualFps = 0f

    // ---- event ring --------------------------------------------------------
    private val events = ArrayDeque<String>(EVENT_WINDOW)

    // Null until the first summary — 0L would be ambiguous with "emitted at
    // elapsedRealtime()==0" and would break the rate window after the first
    // log at t=0.
    private var lastSummaryAtMs: Long? = null

    // ---- recording API (hot path) ------------------------------------------

    /** One frame entered the analyzer. Computes the inter-frame interval. */
    @Synchronized
    fun recordFrameProcessed(nowMs: Long) {
        if (lastFrameAtMs < 0L) {
            // First frame: establish the baseline, no interval yet.
            lastFrameAtMs = nowMs
        } else if (nowMs > lastFrameAtMs) {
            val interval = nowMs - lastFrameAtMs
            frameIntervals[frameIntervalWrite] = interval
            frameIntervalWrite = (frameIntervalWrite + 1) % FRAME_WINDOW
            if (frameIntervalCount < FRAME_WINDOW) frameIntervalCount++
            lastFrameAtMs = nowMs
        }
        // Equal or backwards timestamps (a tracking dropout echoed late) are
        // ignored entirely — they must neither create a bogus interval nor
        // drag the baseline down (dropout-hardening rule, cf. bug #11).
        framesProcessed++
    }

    /** A frame reached the analyzer but was not processed (FPS throttle). */
    @Synchronized
    fun recordFrameDroppedThrottle() {
        framesDroppedThrottle++
    }

    /** A frame could not be processed because no tracker was available. */
    @Synchronized
    fun recordFrameDroppedNoTracker() {
        framesDroppedNoTracker++
    }

    @Synchronized
    fun recordAnalyzerDuration(durationMs: Long) {
        analyzerSamples++
        analyzerSumMs += durationMs
        if (durationMs > analyzerMaxMs) analyzerMaxMs = durationMs
    }

    @Synchronized
    fun recordHandInference(durationMs: Long) {
        handInferSamples++
        handInferSumMs += durationMs
        if (durationMs > handInferMaxMs) handInferMaxMs = durationMs
    }

    @Synchronized
    fun recordFaceInference(durationMs: Long) {
        faceInferSamples++
        faceInferSumMs += durationMs
        if (durationMs > faceInferMaxMs) faceInferMaxMs = durationMs
    }

    @Synchronized
    fun recordConfiguredFps(fps: Int) {
        configuredFps = fps
    }

    // ---- recording API (low-frequency events) -------------------------------

    @Synchronized
    fun recordThermalTransition(from: String, to: String, nowMs: Long) {
        addEvent("thermal $from→$to", nowMs)
    }

    @Synchronized
    fun recordPowerSaveChanged(powerSave: Boolean, nowMs: Long) {
        addEvent("powerSave=$powerSave", nowMs)
    }

    @Synchronized
    fun recordMemoryTrim(level: Int, releasedTrackers: Boolean, nowMs: Long) {
        addEvent("trim=$level released=$releasedTrackers", nowMs)
    }

    @Synchronized
    fun recordWatchdogAction(action: String, nowMs: Long) {
        watchdogActions++
        addEvent("watchdog:$action", nowMs)
    }

    @Synchronized
    fun recordCameraLifecycle(event: String, nowMs: Long) {
        addEvent("camera:$event", nowMs)
    }

    @Synchronized
    fun recordTrackerEvent(event: String, nowMs: Long) {
        addEvent("tracker:$event", nowMs)
    }

    // ---- read API ------------------------------------------------------------

    /** Immutable view for the Debug screen / tests. */
    data class Snapshot(
        val framesProcessed: Long,
        val framesDroppedThrottle: Long,
        val framesDroppedNoTracker: Long,
        val intervalMinMs: Long,
        val intervalP50Ms: Long,
        val intervalP95Ms: Long,
        val intervalMaxMs: Long,
        val actualFps: Float,
        val configuredFps: Int,
        val analyzerAvgMs: Long,
        val analyzerMaxMs: Long,
        val handInferAvgMs: Long,
        val handInferMaxMs: Long,
        val faceInferAvgMs: Long,
        val faceInferMaxMs: Long,
        val watchdogActions: Long,
        val events: List<String>,
    )

    @Synchronized
    fun snapshot(): Snapshot {
        val sorted = frameIntervals.copyOf(frameIntervalCount).also { it.sort() }
        val min = sorted.firstOrNull() ?: 0L
        val max = sorted.lastOrNull() ?: 0L
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val p50Fps = if (p50 > 0L) 1000f / p50 else 0f
        lastActualFps = p50Fps
        return Snapshot(
            framesProcessed = framesProcessed,
            framesDroppedThrottle = framesDroppedThrottle,
            framesDroppedNoTracker = framesDroppedNoTracker,
            intervalMinMs = min,
            intervalP50Ms = p50,
            intervalP95Ms = p95,
            intervalMaxMs = max,
            actualFps = p50Fps,
            configuredFps = configuredFps,
            analyzerAvgMs = if (analyzerSamples > 0) analyzerSumMs / analyzerSamples else 0L,
            analyzerMaxMs = analyzerMaxMs,
            handInferAvgMs = if (handInferSamples > 0) handInferSumMs / handInferSamples else 0L,
            handInferMaxMs = handInferMaxMs,
            faceInferAvgMs = if (faceInferSamples > 0) faceInferSumMs / faceInferSamples else 0L,
            faceInferMaxMs = faceInferMaxMs,
            watchdogActions = watchdogActions,
            events = events.toList(),
        )
    }

    /**
     * Emits the rate-limited summary line. Returns true if a line was emitted.
     * Callers invoke this from low-frequency paths (e.g. the frame watchdog),
     * never per frame.
     */
    @Synchronized
    fun maybeLogSummary(nowMs: Long): Boolean {
        if (!enableLogging) return false
        val last = lastSummaryAtMs
        if (last != null && nowMs - last < LOG_INTERVAL_MS) return false
        lastSummaryAtMs = nowMs
        val s = snapshot()
        loggingSink(
            "perf: frames=${s.framesProcessed} drop(throttle=${s.framesDroppedThrottle}," +
                "noTracker=${s.framesDroppedNoTracker}) fps(actual=${"%.1f".format(s.actualFps)}," +
                "cfg=${s.configuredFps}) interval(p50=${s.intervalP50Ms}ms,p95=${s.intervalP95Ms}ms," +
                "max=${s.intervalMaxMs}ms) analyzer(avg=${s.analyzerAvgMs}ms,max=${s.analyzerMaxMs}ms)" +
                " hand(avg=${s.handInferAvgMs}ms,max=${s.handInferMaxMs}ms)" +
                " face(avg=${s.faceInferAvgMs}ms,max=${s.faceInferMaxMs}ms)" +
                " watchdog=${s.watchdogActions}",
        )
        // Reset the accumulators so each window is fresh; the event ring and
        // cumulative counters stay.
        analyzerSamples = 0; analyzerSumMs = 0; analyzerMaxMs = 0
        handInferSamples = 0; handInferSumMs = 0; handInferMaxMs = 0
        faceInferSamples = 0; faceInferSumMs = 0; faceInferMaxMs = 0
        framesDroppedThrottle = 0; framesDroppedNoTracker = 0
        return true
    }

    /** Test hook: clears every counter, window and event. */
    @Synchronized
    fun reset() {
        frameIntervalCount = 0
        frameIntervalWrite = 0
        lastFrameAtMs = -1L
        analyzerSamples = 0; analyzerSumMs = 0; analyzerMaxMs = 0
        handInferSamples = 0; handInferSumMs = 0; handInferMaxMs = 0
        faceInferSamples = 0; faceInferSumMs = 0; faceInferMaxMs = 0
        framesProcessed = 0
        framesDroppedThrottle = 0
        framesDroppedNoTracker = 0
        watchdogActions = 0
        configuredFps = 0
        lastActualFps = 0f
        events.clear()
        lastSummaryAtMs = null
    }

    private fun addEvent(event: String, nowMs: Long) {
        if (events.size >= EVENT_WINDOW) events.removeFirst()
        events.addLast("@${nowMs % 1_000_000L} $event")
    }

    private fun percentile(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        // Ceiling-rank interpolation: with 95×40ms + 5×400ms, p95 must land
        // ON the spike (index 95), not below it — truncation would report a
        // healthy p95 while 5% of frames stall.
        val index = kotlin.math.ceil((sorted.size - 1) * p).toInt()
            .coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
