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
    fun processFrame(mpImage: MPImage, timestampMs: Long)
    fun close()
    fun isInitialized(): Boolean
}

@Singleton
class HandTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : HandTracker {

    private var handLandmarker: HandLandmarker? = null
    @Volatile private var _isInitialized = false
    @Volatile private var isClosing = false
    private var pendingCloseLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var lastSubmittedTimestampMs = Long.MIN_VALUE

    private val _handFrames = MutableSharedFlow<HandFrame>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val handFrames: SharedFlow<HandFrame> = _handFrames.asSharedFlow()

    override fun initialize() {
        if (_isInitialized) {
            Timber.w("HandTracker already initialized")
            return
        }
        if (!validateModelFile()) {
            Timber.e("hand_landmarker.task not found in assets")
            return
        }

        handLandmarker = tryInitializeWithDelegate(Delegate.GPU)
            ?: tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                Timber.e("Failed to initialize HandLandmarker with both GPU and CPU delegates")
                return
            }

        lastSubmittedTimestampMs = Long.MIN_VALUE
        _isInitialized = true
        Timber.i("HandTracker initialized successfully")
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long) {
        if (isClosing || !_isInitialized) return
        val landmarker = handLandmarker ?: return

        // MediaPipe LIVE_STREAM timestamps must be monotonically increasing.
        // Use the camera pipeline's elapsedRealtime timestamp as the authoritative
        // value instead of generating another clock value here.
        val mediaPipeTimestampMs = if (timestampMs <= lastSubmittedTimestampMs) {
            lastSubmittedTimestampMs + 1L
        } else {
            timestampMs
        }
        lastSubmittedTimestampMs = mediaPipeTimestampMs

        try {
            landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
        } catch (e: Exception) {
            Timber.e(e, "Error processing hand frame at timestamp %d", mediaPipeTimestampMs)
        }
    }

    override fun close() {
        isClosing = true
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingCloseLatch = latch
        try {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            handLandmarker?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing HandLandmarker")
        }
        handLandmarker = null
        _isInitialized = false
        isClosing = false
        lastSubmittedTimestampMs = Long.MIN_VALUE
        pendingCloseLatch = null
        Timber.i("HandTracker closed")
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: HandLandmarkerResult, resultTimestampMs: Long) {
        if (isClosing) {
            pendingCloseLatch?.countDown()
            return
        }

        val timestampMs = resultTimestampMs
        if (result.landmarks().isEmpty()) {
            _handFrames.tryEmit(
                HandFrame(
                    landmarks = emptyList(),
                    handedness = Handedness.UNKNOWN,
                    timestampMs = timestampMs,
                    confidence = 0f,
                ),
            )
            return
        }

        val landmarks = result.landmarks()[0]
        val handedness = result.handednesses()
        val landmark3DList = landmarks.map { lm ->
            Landmark3D(x = lm.x(), y = lm.y(), z = lm.z())
        }

        val handednessCategory = if (handedness.isNotEmpty() && handedness[0].isNotEmpty()) {
            when (handedness[0][0].categoryName().uppercase()) {
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

        _handFrames.tryEmit(
            HandFrame(
                landmarks = landmark3DList,
                handedness = handednessCategory,
                timestampMs = timestampMs,
                confidence = confidence,
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
                .setResultListener { result, resultTimestampMs ->
                    handleResult(result, resultTimestampMs)
                }
                .setErrorListener { error ->
                    Timber.e(error, "HandLandmarker error (delegate=%s)", delegate)
                }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                Timber.i("HandLandmarker initialized with %s delegate", delegate)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize with %s delegate, will try fallback", delegate)
            null
        }
    }

    companion object {
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val NUM_HANDS = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.6f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }
}
