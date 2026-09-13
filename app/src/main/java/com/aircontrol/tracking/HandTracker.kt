package com.aircontrol.tracking

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/** Real-time MediaPipe hand tracking wrapper. */
interface HandTracker {
    val handFrames: SharedFlow<HandFrame>
    fun initialize()

    /**
     * Submits [mpImage] to the graph, **only if no submission is outstanding**.
     *
     * [onConsumed] is invoked exactly once per call: immediately when the frame is refused or the
     * submission fails, otherwise when MediaPipe delivers the result. That callback is the
     * buffer's lifetime signal — the caller may not redraw or release the pixels behind
     * [mpImage] before it runs, because `LIVE_STREAM` inference reads them on its own thread after
     * this function has already returned.
     *
     * @return true when the graph accepted the frame. A false return is not an error: it means
     * "this channel is saturated, drop this frame", which is what keeps a slow device from
     * accumulating an ever-older queue instead of just running at a lower rate.
     */
    fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)? = null): Boolean

    fun close()
    fun isInitialized(): Boolean

    /** Backpressure counters for telemetry: outstanding submission, refusals, reclaimed wedges. */
    fun inFlightStats(nowMs: Long): InFlightStats
}

/** What one channel's [InFlightGate] currently looks like. Values, not a live reference. */
data class InFlightStats(
    val busy: Boolean,
    val submitted: Long,
    val refused: Long,
    val expired: Long,
    val stalled: Boolean = false,
)

@Singleton
class HandTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HandTracker {

    private var handLandmarker: HandLandmarker? = null
    @Volatile private var _isInitialized = false
    @Volatile private var isClosing = false

    /** One outstanding submission, and its caller's completion hook (see processFrame). */
    private val inFlight = InFlightGate()
    private val pendingConsumed = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    // Perf audit P7: guards detectAsync submission against close().
    private val closeLock = Any()
    @Volatile private var lastSubmittedTimestampMs = Long.MIN_VALUE

    /**
     * Width/height of the analysis image the last frame was built from, so the emitted
     * [HandFrame] can say what its normalised coordinates are normalised AGAINST.
     * MediaPipe divides x by the image width and y by its height, so the two axes are
     * different physical distances and the gesture engine needs this ratio to compare
     * them (see HandInput.frameAspectRatio).
     */
    @Volatile private var lastFrameAspectRatio = 1f
    @Volatile private var lastTrackedWristX = -1f
    @Volatile private var lastTrackedWristY = -1f

    private val _handFrames = MutableSharedFlow<HandFrame>(
        // Fix (audit #16): deeper buffer — a stalled collector must not eat the
        // frame that completed a pinch or swipe.
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val handFrames: SharedFlow<HandFrame> = _handFrames.asSharedFlow()

    override fun initialize() {
        if (_isInitialized) {
            // Perf audit P2: reuse across service stop/start is now the NORMAL
            // path (models survive a session stop), so this is informational,
            // not a warning.
            Timber.d("HandTracker already initialized — reusing the loaded model")
            return
        }
        if (!validateModelFile()) {
            Timber.e("hand_landmarker.task not found in assets")
            return
        }

        // CPU is the portable production default. GPU delegates can terminate the
        // entire process inside OEM graphics drivers before Kotlin can catch an error.
        handLandmarker = tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                Timber.e("Failed to initialize HandLandmarker with the portable CPU delegate")
                return
            }

        lastSubmittedTimestampMs = Long.MIN_VALUE
        _isInitialized = true
        Timber.i("HandTracker initialized successfully")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "hand-initialized",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)?): Boolean {
        // Saturation check FIRST, before the lock: a device that cannot keep up must shed frames
        // instead of queueing them, and the shed has to be as cheap as possible.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (!inFlight.tryReserve(nowMs)) {
            onConsumed?.invoke()
            return false
        }
        if (isClosing || !_isInitialized) {
            inFlight.release()
            onConsumed?.invoke()
            return false
        }
        // Perf audit P7: submission and close share one lock, so a submission can never interleave
        // with landmarker.close() on another thread (a native use-after-close window).
        var accepted = false
        try {
            synchronized(closeLock) {
                if (!isClosing) {
                    val landmarker = handLandmarker ?: null
                    if (landmarker != null) {
                        val mediaPipeTimestampMs = if (timestampMs <= lastSubmittedTimestampMs) {
                            lastSubmittedTimestampMs + 1L
                        } else {
                            timestampMs
                        }
                        lastSubmittedTimestampMs = mediaPipeTimestampMs
                        runCatching {
                            val h = mpImage.height
                            if (h > 0) lastFrameAspectRatio = mpImage.width.toFloat() / h
                        }
                        // Store ours, fire the previous one: if the reservation above was reclaimed
                        // from a wedged graph, that owner must not be left holding its buffer.
                        // Storing BEFORE submitting is what lets a result that arrives immediately
                        // consume this callback instead of losing it.
                        pendingConsumed.getAndSet(onConsumed)?.invoke()
                        landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
                        accepted = true
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing hand frame: submission failed")
            }
        if (!accepted) {
            inFlight.release()
            // Exactly-once, whatever happened above: if our hook was already stored it is handed
            // back here; if the failure happened before the store, the caller's hook runs directly.
            (pendingConsumed.getAndSet(null) ?: onConsumed)?.invoke()
        }
        return accepted
    }

    override fun inFlightStats(nowMs: Long): InFlightStats {
        val stats = inFlight.stats()
        return InFlightStats(
            busy = inFlight.isBusy(nowMs),
            submitted = stats.submitted,
            refused = stats.refused,
            expired = stats.expired,
            stalled = inFlight.isStalled(nowMs),
        )
    }

    override fun close() {
        // Perf audit P7: the old close() waited a fixed 200 ms on a latch that
        // only counted down when an async *result* happened to arrive — every
        // close with no in-flight inference blocked the caller a full 200 ms
        // (on the MAIN thread via DebugViewModel). MediaPipe's own close()
        // drains the graph, so the wait bought nothing. The lock guarantees no
        // submission is in flight while the native landmarker is released.
        synchronized(closeLock) {
            if (!_isInitialized && handLandmarker == null) return // idempotent
            isClosing = true
            try {
                handLandmarker?.close()
            } catch (e: Exception) {
                Timber.e(e, "Error closing HandLandmarker")
            }
            handLandmarker = null
            _isInitialized = false
            isClosing = false
            lastSubmittedTimestampMs = Long.MIN_VALUE
            lastTrackedWristX = -1f
            lastTrackedWristY = -1f
        }
        // P0-2: a close while a frame is in flight must not strand its lease. No result callback is
        // coming for that frame, and the graph is already torn down, so the gate is cleared and the
        // caller's buffer is handed back here instead.
        inFlight.reset()
        pendingConsumed.getAndSet(null)?.invoke()

        Timber.i("HandTracker closed")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "hand-closed",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: HandLandmarkerResult, resultTimestampMs: Long) {
        // Release the frame before doing any work: the buffer's owner is waiting on this, and the
        // queue must drain at inference speed rather than at "inference + everything the result
        // handler does" speed. Also fires on the isClosing path below, deliberately.
        inFlight.release()
        pendingConsumed.getAndSet(null)?.invoke()
        if (isClosing) {
            return
        }
        // Perf audit P18: end-to-end inference latency — the result timestamp
        // echoes the elapsedRealtime value submitted with the frame.
        val latencyMs = android.os.SystemClock.elapsedRealtime() - resultTimestampMs
        if (latencyMs in 0..60_000L) {
            com.aircontrol.runtime.PerfTelemetry.recordHandInference(latencyMs)
        }

        val timestampMs = resultTimestampMs
        if (result.landmarks().isEmpty()) {
            lastTrackedWristX = -1f
            lastTrackedWristY = -1f
            _handFrames.tryEmit(
                HandFrame(
                    landmarks = emptyList(),
                    handedness = Handedness.UNKNOWN,
                    timestampMs = timestampMs,
                    confidence = 0f,
                    frameAspectRatio = lastFrameAspectRatio,
                ),
            )
            return
        }

        // Multi-hand spatial continuity: if multiple hands are present,
        // stick to the hand closest to the last tracked wrist/palm position.
        // This prevents the cursor from teleporting when a second hand enters or moves.
        val selectedIdx = if (result.landmarks().size > 1 && lastTrackedWristX >= 0f) {
            var bestIdx = 0
            var bestDist = Float.MAX_VALUE
            for (i in result.landmarks().indices) {
                val lms = result.landmarks()[i]
                if (lms.isNotEmpty()) {
                    val dx = lms[0].x() - lastTrackedWristX
                    val dy = lms[0].y() - lastTrackedWristY
                    val distSq = dx * dx + dy * dy
                    if (distSq < bestDist) {
                        bestDist = distSq
                        bestIdx = i
                    }
                }
            }
            bestIdx
        } else {
            0
        }

        val landmarks = result.landmarks()[selectedIdx]
        if (landmarks.isNotEmpty()) {
            lastTrackedWristX = landmarks[0].x()
            lastTrackedWristY = landmarks[0].y()
        }
        val handedness = result.handednesses()
        val landmark3DList = landmarks.map { lm ->
            Landmark3D(x = lm.x(), y = lm.y(), z = lm.z())
        }

        // Fix A-21: the labels used to be swapped here ("LEFT" -> RIGHT), on the
        // assumption that MediaPipe reports anatomy for an unmirrored image. It
        // does not: the Hand landmarker defines handedness for a *selfie* (already
        // mirrored) input, and CameraService mirrors the frame before handing it
        // over (postScale(-1, 1) in CameraService.convertIntoLeasedBitmap). The double flip made
        // "use my left hand only" accept the right hand and reject the left, which
        // is exactly what users hit when they set a hand preference.
        val handednessCategory = if (handedness.size > selectedIdx && handedness[selectedIdx].isNotEmpty()) {
            when (handedness[selectedIdx][0].categoryName().uppercase()) {
                "LEFT" -> Handedness.LEFT
                "RIGHT" -> Handedness.RIGHT
                else -> Handedness.UNKNOWN
            }
        } else {
            Handedness.UNKNOWN
        }

        val confidence = if (handedness.size > selectedIdx && handedness[selectedIdx].isNotEmpty()) {
            handedness[selectedIdx][0].score()
        } else {
            0f
        }

        _handFrames.tryEmit(
            HandFrame(
                landmarks = landmark3DList,
                handedness = handednessCategory,
                timestampMs = timestampMs,
                confidence = confidence,
                frameAspectRatio = lastFrameAspectRatio,
            ),
        )
    }

    private fun validateModelFile(): Boolean {
        return try {
            try { context.assets.open(MODEL_FILE).close(); true } catch (_: Exception) { false }
        } catch (e: Exception) {
            Timber.e(e, "Error checking hand model file")
            false
        }
    }

    private fun tryInitializeWithDelegate(delegate: Delegate): HandLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(delegate)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(NUM_HANDS)
                .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setResultListener { result, _ ->
                    handleResult(result, result.timestampMs())
                }
                .setErrorListener { error ->
                    Timber.e(error, "HandLandmarker error (delegate=%s)", delegate)
                }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                Timber.i("HandLandmarker initialized with %s delegate", delegate)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize with %s delegate, initialization failed", delegate)
            null
        }
    }

    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val NUM_HANDS = 2
        private const val MIN_DETECTION_CONFIDENCE = 0.40f
        private const val MIN_TRACKING_CONFIDENCE = 0.35f
        private const val MIN_PRESENCE_CONFIDENCE = 0.38f
    }
}
