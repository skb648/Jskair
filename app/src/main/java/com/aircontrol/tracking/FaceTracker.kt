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

/**
 * Gaze estimate derived from MediaPipe Face Landmarker iris landmarks.
 *
 * @param x Normalized horizontal gaze [0,1] where 0.5 = looking straight ahead.
 * @param y Normalized vertical gaze [0,1] where 0.5 = looking straight ahead.
 * @param ear Average Eye Aspect Ratio of both eyes (blink signal). 1f when unknown.
 * @param confidence 1f when a face with iris landmarks was detected, else 0f.
 */
data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    val confidence: Float,
) {
    val isDetected: Boolean get() = confidence > 0f

    companion object {
        val EMPTY = GazePoint(x = 0.5f, y = 0.5f, ear = 1f, confidence = 0f)
    }
}

/**
 * Wraps MediaPipe FaceLandmarker for "eye is mouse" gaze tracking.
 *
 * Mirrors the structure of [HandTracker]: LIVE_STREAM mode, GPU→CPU delegate
 * fallback, monotonic timestamps, and a FIFO timestamp queue for async callbacks.
 *
 * The iris landmarks (indices 468–477 in the 478-landmark face mesh) are used to
 * compute where the user is looking. Gaze coordinates are emitted as normalized
 * [GazePoint]s which the accessibility service maps to the screen cursor.
 */
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

    @Volatile
    private var _isInitialized = false
    @Volatile
    private var isClosing = false
    private var pendingCloseLatch: java.util.concurrent.CountDownLatch? = null

    private val _gazePoints = MutableSharedFlow<GazePoint>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gazePoints: SharedFlow<GazePoint> = _gazePoints.asSharedFlow()

    // Timestamp FIFO for LIVE_STREAM async callbacks (same pattern as HandTracker).
    private val timestampLock = Any()
    private val pendingFrameTimestampsMs = ArrayDeque<Long>()

    override fun initialize() {
        if (_isInitialized) {
            Timber.w("FaceTracker already initialized")
            return
        }
        if (!validateModelFile()) {
            Timber.e(
                "face_landmarker.task not found in assets. " +
                    "Download from: https://storage.googleapis.com/mediapipe-models/" +
                    "face_landmarker/face_landmarker/float16/latest/face_landmarker.task",
            )
            return
        }

        faceLandmarker = tryInitializeWithDelegate(Delegate.GPU)
            ?: tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                Timber.e("Failed to initialize FaceLandmarker with both GPU and CPU delegates")
                return
            }

        _isInitialized = true
        Timber.i("FaceTracker initialized successfully")
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long) {
        if (isClosing) return
        val landmarker = faceLandmarker ?: return
        if (!_isInitialized) return

        var timestampQueued = false
        val nanoTimeNs = System.nanoTime()
        val mediaPipeTimestampUs = nanoTimeNs / 1000L
        val frameTimestampMs = nanoTimeNs / 1_000_000L

        try {
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
                    if (pendingFrameTimestampsMs.isNotEmpty() &&
                        pendingFrameTimestampsMs.last() == frameTimestampMs
                    ) {
                        pendingFrameTimestampsMs.removeLast()
                    }
                }
            }
            Timber.e(e, "Error processing face frame at timestamp %d", frameTimestampMs)
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
        synchronized(timestampLock) { pendingFrameTimestampsMs.clear() }
        Timber.i("FaceTracker closed")
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: FaceLandmarkerResult) {
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

        val faceLandmarks = result.faceLandmarks()
        if (faceLandmarks.isEmpty()) {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f))
            return
        }

        val gaze = computeGaze(faceLandmarks[0])
        if (gaze != null) {
            _gazePoints.tryEmit(gaze)
        } else {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f))
        }
    }

    /**
     * Computes a normalized gaze point from face landmarks using the iris center
     * relative to the eye corners. Uses the standard MediaPipe 478-landmark indices:
     *
     * Left eye:  outer=33, inner=133, top=159, bottom=145, iris=468
     * Right eye: inner=362, outer=263, top=386, bottom=374, iris=473
     *
     * Returns null if the landmark list is incomplete (should be 478).
     */
    private fun computeGaze(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): GazePoint? {
        if (landmarks.size < 478) return null

        fun lm(i: Int) = landmarks[i]

        // ---- Horizontal gaze ratio (0 = inner/nose, 1 = outer) ----
        val leftIris = lm(468)
        val leftOuter = lm(33)
        val leftInner = lm(133)
        val leftH = gazeRatio(leftIris.x(), leftInner.x(), leftOuter.x())

        val rightIris = lm(473)
        val rightOuter = lm(263)
        val rightInner = lm(362)
        val rightH = gazeRatio(rightIris.x(), rightInner.x(), rightOuter.x())

        // ---- Vertical gaze ratio (0 = up, 1 = down) ----
        val leftTop = lm(159)
        val leftBottom = lm(145)
        val leftV = gazeRatio(leftIris.y(), leftTop.y(), leftBottom.y())

        val rightTop = lm(386)
        val rightBottom = lm(374)
        val rightV = gazeRatio(rightIris.y(), rightTop.y(), rightBottom.y())

        val h = ((leftH + rightH) / 2f).coerceIn(0f, 1f)
        val v = ((leftV + rightV) / 2f).coerceIn(0f, 1f)

        // Average Eye Aspect Ratio for blink detection.
        val ear = computeAverageEar(landmarks)

        return GazePoint(x = h, y = v, ear = ear, confidence = 1f)
    }

    /**
     * Eye Aspect Ratio averaged over both eyes.
     *
     * Left eye contour:  [33, 160, 158, 133, 153, 144]
     * Right eye contour: [263, 387, 385, 362, 380, 373]
     */
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
        if (horizontal < 1e-6f) return 1f
        return vertical / horizontal
    }

    /**
     * Maps a value linearly between [inner] and [outer] to a 0..1 ratio
     * (0 at inner, 1 at outer), guarded against divide-by-zero.
     */
    private fun gazeRatio(value: Float, inner: Float, outer: Float): Float {
        val span = outer - inner
        if (kotlin.math.abs(span) < EPSILON) return 0.5f
        return ((value - inner) / span).coerceIn(0f, 1f)
    }

    private fun validateModelFile(): Boolean {
        return try {
            val assetList = context.assets.list("") ?: emptyArray()
            MODEL_FILE in assetList
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
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { error ->
                    Timber.e(error, "FaceLandmarker error (delegate=%s)", delegate)
                }
                .build()

            val landmarker = FaceLandmarker.createFromOptions(context, options)
            Timber.i("FaceLandmarker initialized with %s delegate", delegate)
            landmarker
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize FaceLandmarker with %s delegate, will try fallback", delegate)
            null
        }
    }

    companion object {
        // Model source: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
        private const val MODEL_FILE = "face_landmarker.task"
        private const val NUM_FACES = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val MAX_PENDING_TIMESTAMPS = 8
        private const val EPSILON = 1e-6f
    }
}
