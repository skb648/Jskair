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
 * Cursor-facing gaze sample.
 *
 * - Uncalibrated: (x, y) are raw iris-ratio coordinates in [0,1] (camera space).
 * - Personalized model active ([personalized] = true): (x, y) are the model's
 *   prediction already in *screen-normalized* space — consumers must map them
 *   straight to pixels, never through the hand-cursor dead-zone mapping.
 *
 * Fix D1: the sample now carries its own monotonic [timestampMs] (previously a
 * no-op `copyTimestamp()` lost the result timestamp, and downstream filters had
 * no time base for dt-aware smoothing).
 */
data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    val confidence: Float,
    val timestampMs: Long = 0L,
    val personalized: Boolean = false,
) {
    val isDetected: Boolean get() = confidence >= MIN_GAZE_CONFIDENCE

    companion object {
        const val MIN_GAZE_CONFIDENCE = 0.45f
        val EMPTY = GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f)
    }
}

/**
 * A raw per-frame observation for calibration and face-presence tracking.
 *
 * Always carries the legacy iris ratios (`rawX`, `rawY`) so the 5-point affine
 * fallback can be fitted from the same session, plus the advanced feature
 * vector (head-pose normalized) used by the personalized model.
 */
data class GazeObservation(
    val rawX: Float,
    val rawY: Float,
    val ear: Float,
    /** Min(binocular eye quality, head-pose confidence); 0 when no face. */
    val quality: Float,
    val poseValid: Boolean,
    val featureVector: CalibrationFeatureVector?,
    val timestampMs: Long,
    val faceDetected: Boolean,
)

interface FaceTracker {
    val gazePoints: SharedFlow<GazePoint>

    /**
     * Per-frame raw observations (face presence, iris ratios, head-pose
     * normalized feature vectors). Emitted for every analyzed frame while the
     * face tracker is initialized — including "no face" frames, which the
     * camera service uses to drive the adaptive FPS controller (Fix A4).
     */
    val gazeObservations: SharedFlow<GazeObservation>

    fun initialize()
    fun processFrame(mpImage: MPImage, timestampMs: Long)
    fun close()
    fun isInitialized(): Boolean

    /**
     * Fix A5: installs (or clears, with null) the personalized gaze model.
     * When set and the current frame yields a valid feature vector, predicted
     * screen-space coordinates are emitted as [GazePoint]s with
     * `personalized = true`; otherwise the tracker falls back to iris ratios.
     */
    fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?)
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

    // Fix A5: the active personalized model (null = legacy ratio mode).
    @Volatile private var personalizedModel: PersonalizedGazeCalibrationModel? = null

    // Fix (user test: random gaze wander): continuity bookkeeping for the
    // personalized-prediction jump suppressor (see handleResult).
    @Volatile private var lastPersonalizedFeatures: FloatArray? = null
    @Volatile private var lastPersonalizedX: Float = 0.5f
    @Volatile private var lastPersonalizedY: Float = 0.5f

    // Dimensions of the last submitted image (the mirrored/rotated analysis
    // bitmap), needed to build aspect-correct FaceLandmarkFrames.
    @Volatile private var lastImageWidthPx: Int = 0
    @Volatile private var lastImageHeightPx: Int = 0

    private val _gazePoints = MutableSharedFlow<GazePoint>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gazePoints: SharedFlow<GazePoint> = _gazePoints.asSharedFlow()

    private val _gazeObservations = MutableSharedFlow<GazeObservation>(
        // Fix (audit #16): calibration collect windows must not lose samples to a
        // busy collector; 64 slots ≈ >3s of 20fps eye-mode frames.
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val gazeObservations: SharedFlow<GazeObservation> = _gazeObservations.asSharedFlow()

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

    override fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?) {
        // New model → reset the jump-suppressor history.
        lastPersonalizedFeatures = null
        lastPersonalizedX = 0.5f
        lastPersonalizedY = 0.5f
        personalizedModel = model
        Timber.i(
            if (model != null) "Personalized gaze model activated (trained %d, val p95 %.4f)"
                .format(model.createdAtMs, model.validationMetrics.p95NormalizedError)
            else "Personalized gaze model cleared — falling back to iris ratios",
        )
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long) {
        if (isClosing || !_isInitialized) return
        val landmarker = faceLandmarker ?: return
        lastImageWidthPx = mpImage.width
        lastImageHeightPx = mpImage.height
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
            val ts = resultTimestampMs.coerceAtLeast(0L)
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f, timestampMs = ts))
            _gazeObservations.tryEmit(
                GazeObservation(0.5f, 0.5f, 1f, 0f, false, null, ts, faceDetected = false),
            )
            return
        }

        val landmarks = faceLandmarks[0]
        val ts = resultTimestampMs.coerceAtLeast(0L)
        // Fix (audit #6): the main EAR (blink path!) must be aspect-correct too —
        // normalized x (across width) vs y (across height) mix distorted EAR on
        // non-square camera aspects and made blink detection device-dependent.
        val frameAspectRatio = if (lastImageHeightPx > 0) {
            lastImageWidthPx.toFloat() / lastImageHeightPx.toFloat()
        } else {
            1f
        }
        val ear = computeAverageEar(landmarks, frameAspectRatio)

        // ---- Fix A5: advanced pipeline (features + head pose + normalization) ----
        var featureVector: CalibrationFeatureVector? = null
        var poseValid = false
        var advancedQuality = 0f
        var poseAnglesKnown = false
        var poseYawDeg = 0f
        var posePitchDeg = 0f
        val width = lastImageWidthPx
        val height = lastImageHeightPx
        if (landmarks.size >= CanonicalEyes.MIN_LANDMARK_COUNT && width > 0 && height > 0) {
            // A single bad frame must never kill the cursor: any failure here
            // simply falls back to the legacy iris-ratio gaze below.
            runCatching {
                val frame = buildFaceLandmarkFrame(result, landmarks, ts, width, height)
                val features = EyeFeatureExtractor.extract(frame)
                val pose = HeadPoseEstimator.estimate(frame, features)
                val normalized = HeadPoseNormalizer.normalize(features, pose)
                val vector = GazeCalibrationFeatureVectorBuilder.from(normalized)
                if (vector != null) {
                    featureVector = vector
                    poseValid = pose.isValid
                    val eyeQuality = minOf(
                        features.left?.quality ?: 0f,
                        features.right?.quality ?: 0f,
                    )
                    advancedQuality = minOf(eyeQuality, pose.confidence).coerceIn(0f, 1f)
                    if (pose.isValid) {
                        poseAnglesKnown = true
                        poseYawDeg = pose.yawDeg
                        posePitchDeg = pose.pitchDeg
                    }
                }
            }.onFailure { Timber.e(it, "Advanced gaze pipeline failed — using iris-ratio fallback") }
        }

        // ---- Personalized prediction wins whenever it is available ----
        val model = personalizedModel
        if (model != null && featureVector != null) {
            val prediction = runCatching { model.predict(featureVector!!) }
            prediction.getOrNull()?.let { (px, py) ->
                val x = px.coerceIn(0f, 1f)
                val y = py.coerceIn(0f, 1f)

                // Fix (user test: "eye cursor kahin bhi hilta rehta hai"):
                // an ill-conditioned model can swing its OUTPUT wildly on a
                // tiny feature change — the eye barely moved but the cursor
                // jumped. If the feature vector is essentially unchanged since
                // the last accepted prediction, yet the prediction leapt, this
                // frame is model noise, not a gaze change: skip it entirely
                // (the consumer's miss-hysteresis tolerates single skips, so
                // the cursor simply holds still instead of wandering).
                if (lastPersonalizedFeatures != null) {
                    var featureDelta = 0f
                    val prev = lastPersonalizedFeatures!!
                    val curr = featureVector!!.values
                    for (i in curr.indices) featureDelta += kotlin.math.abs(curr[i] - prev[i])
                    val outputJump = kotlin.math.hypot(
                        (x - lastPersonalizedX).toDouble(),
                        (y - lastPersonalizedY).toDouble(),
                    ).toFloat()
                    if (featureDelta < STABLE_FEATURE_DELTA && outputJump > PREDICTION_JUMP) {
                        Timber.d("Personalized gaze jump suppressed (dFeat=%.3f jump=%.3f)", featureDelta, outputJump)
                        return
                    }
                }
                lastPersonalizedFeatures = featureVector!!.values.copyOf()
                lastPersonalizedX = x
                lastPersonalizedY = y

                _gazePoints.tryEmit(
                    GazePoint(
                        x = x,
                        y = y,
                        ear = ear,
                        // Honest quality — never force-detected. The consumer's
                        // hysteresis decides visibility from real signal quality.
                        confidence = advancedQuality,
                        timestampMs = ts,
                        personalized = true,
                    ),
                )
                _gazeObservations.tryEmit(
                    GazeObservation(
                        rawX = x, rawY = y, ear = ear, quality = advancedQuality,
                        poseValid = poseValid, featureVector = featureVector, timestampMs = ts,
                        faceDetected = true,
                    ),
                )
                return
            }
        }

        // ---- Legacy iris-ratio gaze (fallback + affine calibration source) ----
        val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1f
        val gaze = computeGaze(
            landmarks,
            aspectRatio = aspectRatio,
            headYawDeg = if (poseAnglesKnown) poseYawDeg else 0f,
            headPitchDeg = if (poseAnglesKnown) posePitchDeg else 0f,
        )
        if (gaze != null) {
            _gazePoints.tryEmit(gaze.copy(timestampMs = ts))
            _gazeObservations.tryEmit(
                GazeObservation(
                    rawX = gaze.x, rawY = gaze.y, ear = ear,
                    quality = gaze.confidence,
                    poseValid = poseValid,
                    featureVector = featureVector,
                    timestampMs = ts,
                    faceDetected = true,
                ),
            )
        } else {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f, timestampMs = ts))
            _gazeObservations.tryEmit(
                GazeObservation(0.5f, 0.5f, ear, 0f, poseValid, featureVector, ts, faceDetected = true),
            )
        }
    }

    private fun buildFaceLandmarkFrame(
        result: FaceLandmarkerResult,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        timestampMs: Long,
        widthPx: Int,
        heightPx: Int,
    ): FaceLandmarkFrame = FaceLandmarkFrame(
        frameId = timestampMs,
        timestampNs = timestampMs * 1_000_000L,
        timestampMs = timestampMs,
        trackerWidthPx = widthPx,
        trackerHeightPx = heightPx,
        isFrontCameraMirrored = true,
        landmarks = landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) },
        // Fix (compile): the facialTransformationMatrixes() accessor shape varies
        // across tasks-vision builds and did not resolve here; the matrix was an
        // optional acceleration for head pose anyway. Pass null —
        // HeadPoseEstimator then uses its fully functional landmark fallback
        // (confidence 0.75, valid up to ±70° yaw/pitch).
        facialTransformationMatrix = null,
    )

    private fun computeGaze(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        aspectRatio: Float = 1f,
        headYawDeg: Float = 0f,
        headPitchDeg: Float = 0f,
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
        val ear = computeAverageEar(landmarks, aspectRatio)

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

        // Fix (audit #5): the legacy ratio map has no head-pose compensation, so
        // turning the head read as gaze movement and the cursor drifted. Degrade
        // confidence as the head rotates away — the consumer's detection gate
        // (>= MIN_GAZE_CONFIDENCE) then hides the cursor on unreliable head
        // angles instead of letting it drift. Full confidence up to
        // HEAD_POSE_GRACE_DEG, linearly to zero at HEAD_POSE_REJECT_DEG.
        val maxHeadAngle = kotlin.math.abs(headYawDeg).coerceAtLeast(kotlin.math.abs(headPitchDeg))
        val headStability = if (headYawDeg == 0f && headPitchDeg == 0f) 1f
        else (1f - (maxHeadAngle - HEAD_POSE_GRACE_DEG) / (HEAD_POSE_REJECT_DEG - HEAD_POSE_GRACE_DEG))
            .coerceIn(0f, 1f)

        return GazePoint(x = h, y = v, ear = ear, confidence = geometryConfidence * headStability)
    }

    private fun computeAverageEar(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        aspectRatio: Float = 1f,
    ): Float {
        val left = ear(landmarks, 33, 160, 158, 133, 153, 144, aspectRatio)
        val right = ear(landmarks, 263, 387, 385, 362, 380, 373, aspectRatio)
        return (left + right) / 2f
    }

    private fun ear(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int,
        aspectRatio: Float,
    ): Float {
        fun dist(a: Int, b: Int): Float {
            // Fix (audit #6): x is normalized across the image WIDTH and y across
            // the HEIGHT — mixing them raw distorts EAR on any non-square camera
            // aspect and made blink detection device-dependent. Scale x into the
            // same physical unit as y first (the common factor cancels in the
            // vertical/horizontal ratio, so only the aspect matters).
            val dx = (landmarks[a].x() - landmarks[b].x()) * aspectRatio
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
                // Fix A5 note: head pose is derived from the landmark fallback in
                // HeadPoseEstimator (matrix extraction kept out — see
                // buildFaceLandmarkFrame).
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

        // Jump-suppressor tuning: total |Δfeature| across all 23 dims below this
        // means "the eyes/head essentially did not move"; a prediction leap
        // larger than PREDICTION_JUMP then is model noise, not gaze.
        private const val STABLE_FEATURE_DELTA = 0.8f
        private const val PREDICTION_JUMP = 0.12f

        // Fix (audit #5): legacy-path head-pose stability envelope (degrees).
        private const val HEAD_POSE_GRACE_DEG = 15f
        private const val HEAD_POSE_REJECT_DEG = 60f
    }
}
