package com.aircontrol.gestures

import com.aircontrol.gesture.GestureEngine
import com.aircontrol.gesture.detection.DynamicGestureDetector
import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/**
 * Bridges the Android tracking layer (HandFrame from MediaPipe)
 * with the pure-Kotlin gesture engine (HandInput).
 *
 * Converts [HandFrame] → [HandInput] and exposes the engine's
 * [GestureEvent] flow and [GestureEngineState] for UI consumption.
 */
interface GestureDetector : AutoCloseable {
    val gestureEvents: SharedFlow<GestureEvent>
    val engineState: StateFlow<GestureEngineState>
    val currentPose: StateFlow<Pose>
    val armingProgress: StateFlow<Float>
    fun processHandFrame(frame: HandFrame)
    fun updateSensitivity(sensitivity: Int)

    /**
     * Fix A-11: whether a swipe requires the open-palm pose. Forwarded to the
     * engine so that moving the cursor can never scroll the page.
     */
    fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean)

    /**
     * Bug: Custom Gestures Not Triggering Fix — Updates the dynamic list of
     * user-defined landmark templates that the engine matches against live
     * hand frames. The templates are converted from app-layer
     * [com.aircontrol.data.model.CustomGesture] objects by the caller.
     *
     * Safe to call from any thread. The templates are applied atomically on
     * the next frame.
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>)

    /**
     * Personalizes pinch detection using user-measured calibration data.
     * Pass 0f (or handSizeMm <= 0) to clear calibration.
     */
    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float)

    /**
     * Debug-only visibility into the swipe state machine (phase, intent score against
     * both thresholds, candidate age and the explicit hold reason). `null` until the
     * first hand frame, and never updated in a release build, because the whole
     * channel is attached only when `BuildConfig.DEBUG` is set.
     */
    val swipeDebug: StateFlow<DynamicGestureDetector.SwipeDebugInfo?>

    /**
     * How many times each rejection has *begun* this session. Onset-counted on
     * purpose: a hand held mid-sweep for forty frames is one rejection the user
     * experienced, not forty, and a counter that tracks frames would make every
     * ordinary gesture look like a systemic failure.
     */
    val swipeRejectionCounts: StateFlow<Map<String, Int>>

    /** Most recent swipe verdicts that changed state, newest first, capped. */
    val swipeDebugLog: StateFlow<List<String>>

    fun reset()

    override fun close() {
        // Default no-op; implementations should cancel their coroutine scope
    }
}

@Singleton
class GestureDetectorImpl @Inject constructor() : GestureDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // AtomicReference overkill but kept for thread-safe swap; could be @Volatile var
    private val engineRef = AtomicReference(GestureEngine(GestureEngineConfig()))
    private val engine: GestureEngine get() = engineRef.get()
    private var engineEventsJob: Job? = null
    @Volatile
    private var currentSensitivity: Int = 70

    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        // Fix (audit #16): deeper buffer so pinch start/move/end sequences can
        // never be dropped by a transient slow collector (see GestureEngine).
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()

    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    override val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()

    private val _currentPose = MutableStateFlow(Pose.NONE)
    override val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()

    private val _armingProgress = MutableStateFlow(0f)
    override val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()

    private val debugInstrumentation = com.aircontrol.BuildConfig.DEBUG

    private val _swipeDebug = MutableStateFlow<DynamicGestureDetector.SwipeDebugInfo?>(null)
    override val swipeDebug: StateFlow<DynamicGestureDetector.SwipeDebugInfo?> = _swipeDebug.asStateFlow()

    private val _swipeRejections = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val swipeRejectionCounts: StateFlow<Map<String, Int>> = _swipeRejections.asStateFlow()

    private val _swipeLog = MutableStateFlow<List<String>>(emptyList())
    override val swipeDebugLog: StateFlow<List<String>> = _swipeLog.asStateFlow()

    private val swipeCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val swipeLog = ArrayDeque<String>()
    private var lastRecordedHoldReason: String? = null

    init {
        collectEngineEvents()
        if (debugInstrumentation) attachSwipeDecisionHook()
    }

    /**
     * The engine's decision channel carries what the detector cannot know: a swipe
     * that *was* committed, and a swipe the cross-modal arbiter took away because an
     * active pinch or drag owns the same motion. Both are the answers users ask for
     * ("it saw me and did nothing" vs "the pointer stole it"), so they go to the same
     * capped log and counters. In a release build this hook is never assigned, which
     * leaves `GestureEngine.onSwipeDecision` null and its three call sites a single
     * null check - no allocation, no logcat, no per-frame work on the frame thread.
     */
    private fun attachSwipeDecisionHook() {
        engine.onSwipeDecision = { detected, direction, confidence, reason, _, _, timestampMs ->
            val outcome = if (detected) "COMMITTED ${direction?.name}" else "REJECTED ${reason ?: "UNCLAIMED"}"
            recordSwipeVerdict(
                "$outcome score=${(confidence * 100f).toInt() / 100f} at=$timestampMs",
                countKey = if (detected) null else "ENGINE:$outcome",
            )
        }
    }

    /**
     * Read the swipe machine's snapshot after the engine has consumed the frame, so the
     * values shown are the state it ended the frame in. Only the *transition* into a
     * hold reason is counted or logged; the latest snapshot itself is always published,
     * and StateFlow's equality conflation means a held reason republishes nothing to the
     * UI, so an uninteresting 40-frame sweep costs 40 field writes and zero emissions.
     */
    private fun publishSwipeDebug() {
        val info = engine.swipeDebugInfo()
        _swipeDebug.value = info
        val key = info.holdReason?.name
        if (key == lastRecordedHoldReason) return
        lastRecordedHoldReason = key
        if (key != null) recordSwipeVerdict(info.format(), countKey = key)
    }

    private fun recordSwipeVerdict(line: String, countKey: String?) {
        if (countKey != null) {
            val total = swipeCounters.computeIfAbsent(countKey) { AtomicInteger() }.incrementAndGet()
            _swipeRejections.value = swipeCounters.entries
                .associate { it.key to it.value.get() }
                .toSortedMap()
            recordSwipeLog("$line  x$total")
        } else {
            recordSwipeLog(line)
        }
    }

    /** Fixed-capacity, newest first: a session of any length cannot grow this. */
    private fun recordSwipeLog(line: String) {
        synchronized(swipeLog) {
            if (swipeLog.size == SWIPE_LOG_CAPACITY) swipeLog.removeLast()
            swipeLog.addFirst(line)
            _swipeLog.value = swipeLog.toList()
        }
        Timber.tag("SwipeIntent").d(line)
    }

    override fun processHandFrame(frame: HandFrame) {
        val input = frame.toHandInput()
        engine.processFrame(input)

        // Forward state from engine
        _engineState.value = engine.engineState.value
        _currentPose.value = engine.currentPose.value
        _armingProgress.value = engine.armingProgress.value
        if (debugInstrumentation) publishSwipeDebug()
    }

    override fun updateSensitivity(sensitivity: Int) {
        val clamped = sensitivity.coerceIn(0, 100)
        if (clamped == currentSensitivity) return

        Timber.i("Updating gesture engine sensitivity to %d (without engine recreation)", clamped)
        currentSensitivity = clamped
        // H-06 Fix: Update sensitivity without recreating the entire engine.
        // The old code destroyed and recreated GestureEngine on every slider change,
        // which lost all in-progress gesture state (arming, pinch, swipe detection).
        // Now we call the engine's updateSensitivity() which propagates the new config
        // to all detectors while preserving state.
        engine.updateSensitivity(clamped)
    }

    /**
     * Bug: Custom Gestures Not Triggering Fix — Delegates to the engine's
     * [GestureEngine.updateCustomTemplates]. The templates are forwarded as-is
     * (they're already converted from app-layer CustomGesture objects by the
     * caller in GestureControlAccessibilityService).
     */
    override fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        engine.updateCustomTemplates(templates)
    }

    /**
     * Fix A-11: forward the user's "swipes need an open palm" setting to the
     * engine. Without this the engine kept its compiled-in default, so the
     * Settings switch did nothing and pointer travel kept scrolling pages.
     */
    override fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean) {
        Timber.i("Updating swipe pose gate: requiresOpenHand=%s", requiresOpenHand)
        engine.updateSwipeRequiresOpenHand(requiresOpenHand)
    }

    override fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        engine.updateCalibration(handSizeMm, pinchDistanceMm)
    }

    override fun reset() {
        engine.reset()
        resetStateFlows()
        if (debugInstrumentation) clearSwipeDebug()
        Timber.d("Gesture detector reset")
    }

    private fun collectEngineEvents() {
        engineEventsJob = scope.launch {
            engine.gestureEvents.collect { event ->
                _gestureEvents.tryEmit(event)
            }
        }
    }

    /**
     * Rejections and verdicts are per-session on purpose - the same convention as the
     * gaze ring buffer in [com.aircontrol.tracking.GazeDiagnostics] - so a counter that
     * says "4 NO_TRAVEL" describes the minutes the user just tried, not everything
     * since install.
     */
    private fun clearSwipeDebug() {
        lastRecordedHoldReason = null
        swipeCounters.clear()
        _swipeRejections.value = emptyMap()
        synchronized(swipeLog) {
            swipeLog.clear()
            _swipeLog.value = emptyList()
        }
    }

    private fun resetStateFlows() {
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _armingProgress.value = 0f
    }

    override fun close() {
        engine.stop()
        scope.cancel()
        Timber.d("GestureDetector closed and scope cancelled")
    }

    /**
     * Maps [HandFrame] from the tracking layer to [HandInput] for the gesture engine.
     * This is the only place where Android tracking types touch the pure-Kotlin engine.
     */
    private fun HandFrame.toHandInput(): HandInput {
        return HandInput(
            landmarks = landmarks.map { lm ->
                com.aircontrol.gesture.model.Landmark3D(
                    x = lm.x,
                    y = lm.y,
                    z = lm.z,
                )
            },
            handedness = when (handedness) {
                com.aircontrol.tracking.Handedness.LEFT -> com.aircontrol.gesture.model.Handedness.LEFT
                com.aircontrol.tracking.Handedness.RIGHT -> com.aircontrol.gesture.model.Handedness.RIGHT
                com.aircontrol.tracking.Handedness.UNKNOWN -> com.aircontrol.gesture.model.Handedness.UNKNOWN
            },
            timestampMs = timestampMs,
            confidence = confidence,
            frameAspectRatio = frameAspectRatio,
        )
    }

    private companion object {
        /** Debug verdict history: 12 lines is what fits above the overlay, and it bounds memory. */
        const val SWIPE_LOG_CAPACITY = 12
    }
}
