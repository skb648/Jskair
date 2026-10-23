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
import com.aircontrol.runtime.StageLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/** Real-time MediaPipe hand tracking wrapper. */
interface HandTracker {
    val handFrames: SharedFlow<HandFrame>
    fun initialize()

    /** Updates the preferred hand used when selecting among multiple detections. */
    fun setPreferredHand(preference: Handedness)

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

    /** Enables or disables GPU delegate hardware acceleration (with automatic CPU fallback). */
    fun setGpuAccelerationEnabled(enabled: Boolean) {}

    /** Active MediaPipe delegate name ("GPU" or "CPU"). */
    val activeDelegate: String get() = "CPU"

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
    private val pendingConsumed = InFlightCompletionSlot()

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
    private val multiHandAssociator = MultiHandAssociator()

    private val _handFrames = MutableSharedFlow<HandFrame>(
        // Fix (audit #16): deeper buffer — a stalled collector must not eat the
        // frame that completed a pinch or swipe.
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val handFrames: SharedFlow<HandFrame> = _handFrames.asSharedFlow()

    @Volatile private var preferredHand: Handedness = Handedness.UNKNOWN
    @Volatile private var gpuEnabled: Boolean = true
    @Volatile private var gpuRuntimeFailed: Boolean = false
    @Volatile private var _activeDelegate: String = "UNINITIALIZED"
    override val activeDelegate: String get() = _activeDelegate

    override fun setPreferredHand(preference: Handedness) {
        preferredHand = preference
        multiHandAssociator.onPreferredHandChanged()
    }

    override fun setGpuAccelerationEnabled(enabled: Boolean) {
        synchronized(closeLock) {
            if (gpuEnabled == enabled) return
            gpuEnabled = enabled
            if (enabled) {
                gpuRuntimeFailed = false
            }
            if (_isInitialized && !isClosing) {
                close()
                initialize()
            }
        }
    }

    override fun initialize() {
        synchronized(closeLock) {
            if (_isInitialized) {
                Timber.d("HandTracker already initialized — reusing the loaded model")
                return
            }
            if (!validateModelFile()) {
                _activeDelegate = "ERROR"
                com.aircontrol.runtime.PerfTelemetry.recordHandDelegate(_activeDelegate)
                StageLog.failure(StageLog.Stage.HAND_TRACKER_INIT, COMPONENT, "$MODEL_FILE not readable from assets")
                return
            }

            val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            val canTryGpu = gpuEnabled && !gpuRuntimeFailed && !isRobolectric
            var resolvedDelegateLabel = "CPU"
            val landmarker = if (canTryGpu) {
                tryInitializeWithDelegate(Delegate.GPU)?.also { resolvedDelegateLabel = "GPU" }
                    ?: tryInitializeWithDelegate(Delegate.CPU)?.also {
                        resolvedDelegateLabel = "CPU_FALLBACK"
                        gpuRuntimeFailed = true
                    }
            } else {
                tryInitializeWithDelegate(Delegate.CPU)?.also {
                    resolvedDelegateLabel = if (gpuEnabled && (gpuRuntimeFailed || isRobolectric)) "CPU_FALLBACK" else "CPU"
                }
            }
            if (landmarker == null) {
                _activeDelegate = "ERROR"
                com.aircontrol.runtime.PerfTelemetry.recordHandDelegate(_activeDelegate)
                StageLog.failure(StageLog.Stage.HAND_TRACKER_INIT, COMPONENT, "HandLandmarker.createFromOptions(CPU) failed", lastInitError)
                return
            }
            handLandmarker = landmarker
            _activeDelegate = resolvedDelegateLabel
            _isInitialized = true
            multiHandAssociator.reset()
            com.aircontrol.runtime.PerfTelemetry.recordHandDelegate(_activeDelegate)
            StageLog.success(StageLog.Stage.HAND_TRACKER_INIT, COMPONENT, "delegate=${_activeDelegate} model=$MODEL_FILE")
            com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
                "hand-initialized(${_activeDelegate})",
                android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)?): Boolean {
        // Saturation check FIRST, before the lock: a device that cannot keep up must shed frames
        // instead of queueing them, and the shed has to be as cheap as possible.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val reservationToken = inFlight.tryReserve(nowMs) ?: run {
            onConsumed?.invoke()
            return false
        }
        if (isClosing || !_isInitialized) {
            inFlight.release(reservationToken)
            onConsumed?.invoke()
            return false
        }
        // Perf audit P7: submission and close share one lock, so a submission can never interleave
        // with landmarker.close() on another thread (a native use-after-close window).
        var accepted = false
        try {
            synchronized(closeLock) {
                if (!isClosing) {
                    val landmarker = handLandmarker
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
                        pendingConsumed.replace(
                            InFlightCompletionSlot.Pending(
                                reservationToken = reservationToken,
                                mediaPipeTimestampMs = mediaPipeTimestampMs,
                                onConsumed = onConsumed,
                            ),
                        )?.onConsumed?.invoke()
                        landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
                        accepted = true
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error processing hand frame: submission failed")
        }
        if (!accepted) {
            inFlight.release(reservationToken)
            (pendingConsumed.cancelForToken(reservationToken)?.onConsumed ?: onConsumed)?.invoke()
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
        synchronized(closeLock) {
            if (!_isInitialized && handLandmarker == null) return // idempotent
            isClosing = true
            try {
                handLandmarker?.close()
            } catch (e: Throwable) {
                Timber.e(e, "Error closing HandLandmarker")
            }
            handLandmarker = null
            _isInitialized = false
            _activeDelegate = "UNINITIALIZED"
            isClosing = false
            multiHandAssociator.reset()
        }
        inFlight.reset()
        pendingConsumed.clear()?.onConsumed?.invoke()
        com.aircontrol.runtime.PerfTelemetry.recordHandDelegate("UNINITIALIZED")

        Timber.i("HandTracker closed")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "hand-closed",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: HandLandmarkerResult, resultTimestampMs: Long) {
        val completion = pendingConsumed.takeForTimestamp(resultTimestampMs) ?: return
        inFlight.release(completion.reservationToken)
        completion.onConsumed?.invoke()
        if (isClosing) {
            return
        }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val latencyMs = nowMs - resultTimestampMs
        if (latencyMs in 0..60_000L) {
            com.aircontrol.runtime.PerfTelemetry.recordHandInference(latencyMs, nowMs)
        }

        val timestampMs = resultTimestampMs
        val landmarkLists = result.landmarks()
        val handednessLists = result.handednesses()

        val rawDetections = if (landmarkLists.isEmpty()) {
            emptyList()
        } else {
            landmarkLists.indices.mapNotNull { idx ->
                val lms = landmarkLists[idx]
                if (lms.isEmpty()) {
                    null
                } else {
                    val landmark3DList = lms.map { lm ->
                        Landmark3D(x = lm.x(), y = lm.y(), z = lm.z())
                    }
                    val category = if (handednessLists.size > idx && handednessLists[idx].isNotEmpty()) {
                        when (handednessLists[idx][0].categoryName().uppercase()) {
                            "LEFT" -> Handedness.LEFT
                            "RIGHT" -> Handedness.RIGHT
                            else -> Handedness.UNKNOWN
                        }
                    } else {
                        Handedness.UNKNOWN
                    }
                    val score = if (handednessLists.size > idx && handednessLists[idx].isNotEmpty()) {
                        handednessLists[idx][0].score()
                    } else {
                        0f
                    }
                    RawHandDetection(
                        landmarks = landmark3DList,
                        handedness = category,
                        confidence = score,
                    )
                }
            }
        }

        val associatedFrame = multiHandAssociator.associate(
            rawDetections = rawDetections,
            timestampMs = timestampMs,
            frameAspectRatio = lastFrameAspectRatio,
            preferredHand = preferredHand,
        )
        _handFrames.tryEmit(associatedFrame)
    }

    internal fun handleRuntimeDelegateError(delegate: Delegate, error: Throwable) {
        Timber.e(error, "HandLandmarker runtime error (delegate=%s)", delegate)
        // Release any wedged in-flight buffer immediately so the bitmap pool cannot starve
        inFlight.reset()
        pendingConsumed.clear()?.onConsumed?.invoke()
        if (delegate == Delegate.GPU && !gpuRuntimeFailed) {
            gpuRuntimeFailed = true
            _isInitialized = false
            com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
                "hand-gpu-runtime-fallback",
                android.os.SystemClock.elapsedRealtime(),
            )
            // Never call handLandmarker.close() synchronously inside MediaPipe's
            // ErrorListener callback: closing the native CalculatorGraph from its
            // own callback thread deadlocks or aborts (pthread_join on self).
            Thread({
                runCatching {
                    synchronized(closeLock) {
                        if (!isClosing) {
                            close()
                            initialize()
                        }
                    }
                }.onFailure { Timber.e(it, "Async CPU fallback for HandLandmarker failed") }
            }, "hand-gpu-fallback").apply {
                isDaemon = true
            }.start()
        }
    }

    /** Last exception from [tryInitializeWithDelegate], for the stage record. */
    @Volatile internal var lastInitError: Throwable? = null

    private fun validateModelFile(): Boolean {
        return try {
            context.assets.open(MODEL_FILE).close()
            true
        } catch (e: Throwable) {
            lastInitError = e
            Timber.e(e, "Error opening hand model file %s", MODEL_FILE)
            false
        }
    }

    internal fun tryInitializeWithDelegate(delegate: Delegate): HandLandmarker? {
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
                    handleRuntimeDelegateError(delegate, error)
                }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                Timber.i("HandLandmarker initialized with %s delegate", delegate)
            }
        } catch (e: Throwable) {
            lastInitError = e
            Timber.w(e, "Failed to initialize with %s delegate, initialization failed", delegate)
            null
        }
    }

    companion object {
        private const val COMPONENT = "HandTrackerImpl"
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val NUM_HANDS = 2
        private const val MIN_DETECTION_CONFIDENCE = 0.40f
        private const val MIN_TRACKING_CONFIDENCE = 0.35f
        private const val MIN_PRESENCE_CONFIDENCE = 0.38f
    }
}
