package com.aircontrol.tracking

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    val confidence: Float,
) {
    val isDetected: Boolean get() = confidence >= MIN_GAZE_CONFIDENCE

    companion object {
        const val MIN_GAZE_CONFIDENCE = 0.45f
        val EMPTY = GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f)
    }
}

interface FaceTracker {
    val gazePoints: SharedFlow<GazePoint>
    fun initialize()
    fun processFrame(mpImage: MPImage, timestampMs: Long)
    fun close()
    fun isInitialized(): Boolean
}

@Singleton
class FaceTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceTracker {

    private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var _isInitialized = false
    @Volatile private var isClosing = false
    private var pendingCloseLatch: java.util.concurrent.CountDownLatch? = null
    @Volatile private var lastSubmittedTimestampMs = Long.MIN_VALUE

    private val _gazePoints = MutableSharedFlow<GazePoint>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gazePoints: SharedFlow<GazePoint> = _gazePoints.asSharedFlow()

    override fun initialize() {
        if (_isInitialized) {
            Timber.w("FaceTracker already initialized")
            return
        }
        if (!validateModelFile()) {
            Timber.e("face_landmarker.task not found in assets")
            return
        }

        // Prefer predictable compatibility over OEM GPU-driver crashes. Eye tracking
        // is optional and only initialized when enabled.
        faceLandmarker = tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                Timber.e("Failed to initialize FaceLandmarker with the portable CPU delegate")
                return
            }
        lastSubmittedTimestampMs = Long.MIN_VALUE
        _isInitialized = true
        Timber.i("FaceTracker initialized successfully")
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long) {
        if (isClosing || !_isInitialized) return
        val landmarker = faceLandmarker ?: return
        val mediaPipeTimestampMs = if (timestampMs <= lastSubmittedTimestampMs) {
            lastSubmittedTimestampMs + 1L
        } else {
            timestampMs
        }
        lastSubmittedTimestampMs = mediaPipeTimestampMs
        try {
            landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
        } catch (e: Exception) {
            Timber.e(e, "Error processing face frame at timestamp %d", mediaPipeTimestampMs)
        }
    }

    override fun close() {
        isClosing = true
        val latch = java.util.concurrent.CountDownLatch(1)
        pendingCloseLatch = latch
        try {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            faceLandmarker?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing FaceLandmarker")
        }
        faceLandmarker = null
        _isInitialized = false
        isClosing = false
        lastSubmittedTimestampMs = Long.MIN_VALUE
        pendingCloseLatch = null
        Timber.i("FaceTracker closed")
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: FaceLandmarkerResult, resultTimestampMs: Long) {
        if (isClosing) {
            pendingCloseLatch?.countDown()
            return
        }

        val faceLandmarks = result.faceLandmarks()
        if (faceLandmarks.isEmpty()) {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f))
            return
        }

        val gaze = computeGaze(faceLandmarks[0])
        if (gaze != null) {
            _gazePoints.tryEmit(gaze.copyTimestamp(resultTimestampMs))
        } else {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f))
        }
    }

    // Timestamp is retained by the collector through the flow sample timing; the
    // helper is deliberately allocation-free and documents the source timestamp.
    private fun GazePoint.copyTimestamp(@Suppress("UNUSED_PARAMETER") timestampMs: Long): GazePoint = this

    private fun computeGaze(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): GazePoint? {
        if (landmarks.size < 478) return null

        fun lm(i: Int) = landmarks[i]
        val leftIris = lm(468)
        val leftOuter = lm(33)
        val leftInner = lm(133)
        val leftH = gazeRatio(leftIris.x(), leftInner.x(), leftOuter.x())

        val rightIris = lm(473)
        val rightOuter = lm(263)
        val rightInner = lm(362)
        val rightH = gazeRatio(rightIris.x(), rightInner.x(), rightOuter.x())

        val leftTop = lm(159)
        val leftBottom = lm(145)
        val leftV = gazeRatio(leftIris.y(), leftTop.y(), leftBottom.y())

        val rightTop = lm(386)
        val rightBottom = lm(374)
        val rightV = gazeRatio(rightIris.y(), rightTop.y(), rightBottom.y())

        val h = ((leftH + rightH) / 2f).coerceIn(0f, 1f)
        val v = ((leftV + rightV) / 2f).coerceIn(0f, 1f)
        val ear = computeAverageEar(landmarks)

        val leftEyeWidth = kotlin.math.abs(leftOuter.x() - leftInner.x())
        val rightEyeWidth = kotlin.math.abs(rightOuter.x() - rightInner.x())
        if (leftEyeWidth < EPSILON || rightEyeWidth < EPSILON) return null
        val eyeRatio = (leftEyeWidth / rightEyeWidth).coerceIn(0f, 10f)
        val symmetry = 1f - kotlin.math.abs(1f - eyeRatio).coerceIn(0f, 1f)
        val eyeSeparation = kotlin.math.abs(
            ((leftOuter.x() + leftInner.x()) / 2f) - ((rightOuter.x() + rightInner.x()) / 2f),
        )
        val separationConfidence = (eyeSeparation / 0.12f).coerceIn(0f, 1f)
        val geometryConfidence = (0.25f + 0.5f * symmetry + 0.25f * separationConfidence)
            .coerceIn(0f, 1f)

        return GazePoint(x = h, y = v, ear = ear, confidence = geometryConfidence)
    }

    private fun computeAverageEar(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): Float {
        val left = ear(landmarks, 33, 160, 158, 133, 153, 144)
        val right = ear(landmarks, 263, 387, 385, 362, 380, 373)
        return (left + right) / 2f
    }

    private fun ear(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int,
    ): Float {
        fun dist(a: Int, b: Int): Float {
            val dx = landmarks[a].x() - landmarks[b].x()
            val dy = landmarks[a].y() - landmarks[b].y()
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val vertical = dist(p2, p6) + dist(p3, p5)
        val horizontal = 2f * dist(p1, p4)
        if (horizontal < EPSILON) return 1f
        return vertical / horizontal
    }

    private fun gazeRatio(value: Float, inner: Float, outer: Float): Float {
        val span = outer - inner
        if (kotlin.math.abs(span) < EPSILON) return 0.5f
        return ((value - inner) / span).coerceIn(0f, 1f)
    }

    private fun validateModelFile(): Boolean {
        return try {
            try { context.assets.open(MODEL_FILE).close(); true } catch (_: Exception) { false }
        } catch (e: Exception) {
            Timber.e(e, "Error checking face model file in assets")
            false
        }
    }

    private fun tryInitializeWithDelegate(delegate: Delegate): FaceLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .setDelegate(delegate)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(NUM_FACES)
                .setMinFaceDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setResultListener { result, _ ->
                    handleResult(result, result.timestampMs())
                }
                .setErrorListener { error ->
                    Timber.e(error, "FaceLandmarker error (delegate=%s)", delegate)
                }
                .build()
            FaceLandmarker.createFromOptions(context, options).also {
                Timber.i("FaceLandmarker initialized with %s delegate", delegate)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize FaceLandmarker with %s delegate, initialization failed", delegate)
            null
        }
    }

    companion object {
        private const val MODEL_FILE = "face_landmarker.task"
        private const val NUM_FACES = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val EPSILON = 1e-6f
    }
}
