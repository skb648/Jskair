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

/**
 * Wraps MediaPipe HandLandmarker for real-time hand tracking.
 *
 * Uses LIVE_STREAM mode for async processing. Attempts GPU delegate first,
 * falls back to CPU if GPU initialization fails.
 *
 * Model file: hand_landmarker.task (must be in assets/)
 * Source: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
 *
 * Timestamp mapping:
 * MediaPipe's LIVE_STREAM mode requires monotonically increasing timestamps.
 * We map our system timestamps to MediaPipe's timeline and track the mapping
 * so that result callbacks can recover the original frame timestamp.
 */
interface HandTracker {
    val handFrames: SharedFlow<HandFrame>
    fun initialize()
    fun processFrame(mpImage: MPImage, timestampMs: Long)
    fun close()
    fun isInitialized(): Boolean
}

@Singleton
class HandTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HandTracker {

    private var handLandmarker: HandLandmarker? = null
    @Volatile
    private var _isInitialized = false
    @Volatile
    private var isClosing = false
    private var pendingCloseLatch: java.util.concurrent.CountDownLatch? = null

    private val _handFrames = MutableSharedFlow<HandFrame>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val handFrames: SharedFlow<HandFrame> = _handFrames.asSharedFlow()

    private val handFrameFilter = HandFrameFilter(
        minCutoff = 1.5f,
        beta = 0.0f,
    )

    // Timestamp mapping: MediaPipe uses monotonic timestamps in microseconds.
    // We track the offset between our system time (ms) and MediaPipe time (us).
    @Volatile
    private var mediaPipeTimestampBaseUs: Long = 0L
    @Volatile
    private var systemTimestampBaseMs: Long = 0L

    // Timestamps for result callbacks. MediaPipe LIVE_STREAM callbacks are
    // asynchronous, so using only the last submitted timestamp can attach the
    // wrong time to older results. Keep a small FIFO queue instead.
    private val timestampLock = Any()
    private val pendingFrameTimestampsMs = ArrayDeque<Long>()

    override fun initialize() {
        if (_isInitialized) {
            Timber.w("HandTracker already initialized")
            return
        }

        // Validate model file exists in assets
        if (!validateModelFile()) {
            Timber.e("hand_landmarker.task not found in assets. " +
                "Download from: https://storage.googleapis.com/mediapipe-models/" +
                "hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task")
            return
        }

        // Initialize timestamps using monotonic clock (System.nanoTime)
        // to avoid issues with wall-clock adjustments (e.g., NTP sync).
        systemTimestampBaseMs = System.nanoTime() / 1_000_000L
        mediaPipeTimestampBaseUs = System.nanoTime() / 1000L

        handLandmarker = tryInitializeWithDelegate(Delegate.GPU)
            ?: tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                Timber.e("Failed to initialize HandLandmarker with both GPU and CPU delegates")
                return
            }

        _isInitialized = true
        Timber.i("HandTracker initialized successfully")
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long) {
        if (isClosing) return

        val landmarker = handLandmarker ?: run {
            Timber.v("HandLandmarker not initialized, skipping frame")
            return
        }

        if (!_isInitialized) return

        var timestampQueued = false
        try {
            // Use monotonic clock (System.nanoTime) for MediaPipe timestamps
            // to guarantee strictly increasing values required by LIVE_STREAM mode.
            val nanoTimeNs = System.nanoTime()
            val mediaPipeTimestampUs = nanoTimeNs / 1000L
            val frameTimestampMs = nanoTimeNs / 1_000_000L

            synchronized(timestampLock) {
                pendingFrameTimestampsMs.addLast(frameTimestampMs)
                timestampQueued = true
                while (pendingFrameTimestampsMs.size > MAX_PENDING_TIMESTAMPS) {
                    pendingFrameTimestampsMs.removeFirst()
                }
            }

            landmarker.detectAsync(mpImage, mediaPipeTimestampUs)
        } catch (e: Exception) {
            if (timestampQueued) {
                synchronized(timestampLock) {
                    if (pendingFrameTimestampsMs.isNotEmpty() && pendingFrameTimestampsMs.last() == frameTimestampMs) {
                        pendingFrameTimestampsMs.removeLast()
                    }
                }
            }
            Timber.e(e, "Error processing frame at timestamp %d", frameTimestampMs)
        }
    }

    override fun close() {
        isClosing = true
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingCloseLatch = latch
        try {
            // Wait for pending async callbacks to complete (max 200ms)
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            handLandmarker?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing HandLandmarker")
        }
        handLandmarker = null
        _isInitialized = false
        isClosing = false
        handFrameFilter.reset()
        synchronized(timestampLock) { pendingFrameTimestampsMs.clear() }
        Timber.i("HandTracker closed")
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: HandLandmarkerResult) {
        // Signal pending close latch if we're closing
        if (isClosing) {
            pendingCloseLatch?.countDown()
        }

        val systemTimestampMs = synchronized(timestampLock) {
            if (pendingFrameTimestampsMs.isNotEmpty()) {
                pendingFrameTimestampsMs.removeFirst()
            } else {
                System.nanoTime() / 1_000_000L
            }
        }

        if (result.landmarks().isEmpty()) {
            // No hand detected - emit empty frame with correct timestamp
            _handFrames.tryEmit(
                HandFrame(
                    landmarks = emptyList(),
                    handedness = Handedness.UNKNOWN,
                    timestampMs = systemTimestampMs,
                    confidence = 0f,
                ),
            )
            return
        }

        val landmarks = result.landmarks()[0]
        val handedness = result.handednesses()

        val landmark3DList = landmarks.map { lm ->
            Landmark3D(
                x = lm.x(),
                y = lm.y(),
                z = lm.z(),
            )
        }

        val handednessCategory = if (handedness.isNotEmpty() && handedness[0].isNotEmpty()) {
            val category = handedness[0][0]
            val label = category.categoryName()
            when (label.uppercase()) {
                // Front camera image is mirrored, so swap handedness
                "LEFT" -> Handedness.RIGHT
                "RIGHT" -> Handedness.LEFT
                else -> Handedness.UNKNOWN
            }
        } else {
            Handedness.UNKNOWN
        }

        val confidence = if (handedness.isNotEmpty() && handedness[0].isNotEmpty()) {
            handedness[0][0].score()
        } else {
            0f
        }

        val rawFrame = HandFrame(
            landmarks = landmark3DList,
            handedness = handednessCategory,
            timestampMs = systemTimestampMs,
            confidence = confidence,
        )

        // Apply One Euro Filter for jitter reduction
        val filteredFrame = handFrameFilter.filter(rawFrame)

        _handFrames.tryEmit(filteredFrame)
    }

    /**
     * Validates that the hand_landmarker.task model file exists in the assets directory.
     */
    private fun validateModelFile(): Boolean {
        return try {
            val assetList = context.assets.list("") ?: emptyArray()
            MODEL_FILE in assetList
        } catch (e: Exception) {
            Timber.e(e, "Error checking model file in assets")
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
                .setResultListener { result, _ ->
                    handleResult(result)
                }
                .setErrorListener { error ->
                    Timber.e(error, "HandLandmarker error (delegate=%s)", delegate)
                }
                .build()

            val landmarker = HandLandmarker.createFromOptions(context, options)
            Timber.i("HandLandmarker initialized with %s delegate", delegate)
            landmarker
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize with %s delegate, will try fallback", delegate)
            null
        }
    }

    companion object {
        // Model source: https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val NUM_HANDS = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.6f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val MAX_PENDING_TIMESTAMPS = 8
    }
}
