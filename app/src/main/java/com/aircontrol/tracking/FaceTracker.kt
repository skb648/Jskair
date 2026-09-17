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
import com.aircontrol.runtime.StageLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile
import kotlin.math.max


data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    val confidence: Float,
    val actionConfidence: Float = confidence,
    val eligibility: GazeEligibility = GazeEligibility.VISIBLE,
    val timestampMs: Long = 0L,
    val space: GazePointSpace = GazePointSpace.CAMERA_RAW,
) {
    val isDetected: Boolean get() = eligibility.showsCursor
    val isActionable: Boolean get() = eligibility.canAct && actionConfidence >= ACTION_CONFIDENCE_FLOOR
    val personalized: Boolean get() = space == GazePointSpace.SCREEN_NORMALIZED
    companion object {
        const val ACTION_CONFIDENCE_FLOOR = GazeEligibilityPolicy.ACTION_QUALITY
        const val MIN_GAZE_CONFIDENCE = GazeEligibilityPolicy.ACTION_QUALITY
        val EMPTY = GazePoint(0.5f, 0.5f, confidence = 0f, eligibility = GazeEligibility.NOTHING)
    }
}

data class GazeObservation(
    val rawX: Float,
    val rawY: Float,
    val ear: Float,
    val quality: Float,
    val poseValid: Boolean,
    val poseConfidence: Float = 0f,
    val binocularAgreement: Float = 0f,
    val featureVector: CalibrationFeatureVector?,
    val timestampMs: Long,
    val faceDetected: Boolean,
)

interface FaceTracker {
    val gazePoints: SharedFlow<GazePoint>
    val gazeObservations: SharedFlow<GazeObservation>
    fun initialize()
    fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)? = null): Boolean
    fun close()
    fun isInitialized(): Boolean
    fun inFlightStats(nowMs: Long): InFlightStats
    fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?)
    fun setCalibrationCollecting(active: Boolean)
    val diagnostics: GazeDiagnostics
}

@Singleton
class FaceTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceTracker {
    private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var _isInitialized = false
    @Volatile private var isClosing = false
    private val inFlight = InFlightGate()
    private val pendingConsumed = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    private val closeLock = Any()
    @Volatile private var lastSubmittedTimestampMs = Long.MIN_VALUE
    @Volatile private var calibrationCollecting = false
    @Volatile private var personalizedModel: PersonalizedGazeCalibrationModel? = null
    private val jumpPolicy = GazeJumpPolicy()
    @Volatile private var modelReliability: Float? = null
    private val _diagnostics = GazeDiagnostics()
    override val diagnostics: GazeDiagnostics get() = _diagnostics
    private val diagnosticsEnabled = com.aircontrol.BuildConfig.DEBUG
    @Volatile private var lastImageWidthPx: Int = 0
    @Volatile private var lastImageHeightPx: Int = 0

    private val _gazePoints = MutableSharedFlow<GazePoint>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val gazePoints: SharedFlow<GazePoint> = _gazePoints.asSharedFlow()
    private val _gazeObservations = MutableSharedFlow<GazeObservation>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val gazeObservations: SharedFlow<GazeObservation> = _gazeObservations.asSharedFlow()

    override fun initialize() {
        if (_isInitialized) return
        if (!validateModelFile()) {
            StageLog.failure(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "$MODEL_FILE not readable from assets")
            return
        }
        faceLandmarker = tryInitializeWithDelegate(Delegate.CPU) ?: run {
            StageLog.failure(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "FaceLandmarker.createFromOptions(CPU) failed", lastInitError)
            return
        }
        lastSubmittedTimestampMs = Long.MIN_VALUE
        _isInitialized = true
        StageLog.success(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "delegate=CPU model=$MODEL_FILE matrix=true")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent("face-initialized", android.os.SystemClock.elapsedRealtime())
    }

    override fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?) {
        jumpPolicy.reset()
        personalizedModel = model
        modelReliability = model?.let { (1f - (it.validationMetrics.p95NormalizedError / WORST_ACCEPTED_P95_ERROR).toFloat()).coerceIn(0f, 1f) }
    }

    override fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)?): Boolean {
        if (!inFlight.tryReserve(android.os.SystemClock.elapsedRealtime())) {
            onConsumed?.invoke(); return false
        }
        if (isClosing || !_isInitialized) {
            inFlight.release(); onConsumed?.invoke(); return false
        }
        var accepted = false
        try {
            synchronized(closeLock) {
                if (!isClosing) {
                    val landmarker = faceLandmarker
                    if (landmarker != null) {
                        lastImageWidthPx = mpImage.width
                        lastImageHeightPx = mpImage.height
                        val mediaPipeTimestampMs = if (timestampMs <= lastSubmittedTimestampMs) lastSubmittedTimestampMs + 1L else timestampMs
                        lastSubmittedTimestampMs = mediaPipeTimestampMs
                        pendingConsumed.getAndSet(onConsumed)?.invoke()
                        landmarker.detectAsync(mpImage, mediaPipeTimestampMs)
                        accepted = true
                    }
                }
            }
        } catch (e: Exception) { Timber.e(e, "Error processing face frame: submission failed") }
        if (!accepted) {
            inFlight.release(); (pendingConsumed.getAndSet(null) ?: onConsumed)?.invoke()
        }
        return accepted
    }

    override fun inFlightStats(nowMs: Long): InFlightStats {
        val stats = inFlight.stats()
        return InFlightStats(inFlight.isBusy(nowMs), stats.submitted, stats.refused, stats.expired, inFlight.isStalled(nowMs))
    }

    override fun close() {
        synchronized(closeLock) {
            if (!_isInitialized && faceLandmarker == null) return
            isClosing = true
            runCatching { faceLandmarker?.close() }.onFailure { Timber.e(it, "Error closing FaceLandmarker") }
            faceLandmarker = null; _isInitialized = false; isClosing = false; lastSubmittedTimestampMs = Long.MIN_VALUE
        }
        inFlight.reset(); pendingConsumed.getAndSet(null)?.invoke()
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent("face-closed", android.os.SystemClock.elapsedRealtime())
    }

    override fun setCalibrationCollecting(active: Boolean) { calibrationCollecting = active }
    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: FaceLandmarkerResult, resultTimestampMs: Long) {
        inFlight.release(); pendingConsumed.getAndSet(null)?.invoke()
        if (isClosing) return
        val latencyMs = android.os.SystemClock.elapsedRealtime() - resultTimestampMs
        if (latencyMs in 0..60_000L) com.aircontrol.runtime.PerfTelemetry.recordFaceInference(latencyMs)
        val faceLandmarks = result.faceLandmarks()
        val width = lastImageWidthPx; val height = lastImageHeightPx
        if (faceLandmarks.isEmpty()) {
            val ts = resultTimestampMs.coerceAtLeast(0L)
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, confidence = 0f, eligibility = GazeEligibility.NOTHING, timestampMs = ts))
            _gazeObservations.tryEmit(GazeObservation(0.5f, 0.5f, 1f, 0f, false, timestampMs = ts, featureVector = null, faceDetected = false))
            return
        }
        val landmarks = faceLandmarks[0]
        val ts = resultTimestampMs.coerceAtLeast(0L)
        var features: BinocularEyeFeatures? = null
        var pose: HeadPoseEstimate? = null
        var featureVector: CalibrationFeatureVector? = null
        val needsFeatureVector = personalizedModel != null || calibrationCollecting
        if (landmarks.size >= CanonicalEyes.MIN_LANDMARK_COUNT && width > 0 && height > 0) {
            runCatching {
                val frame = buildFaceLandmarkFrame(result, landmarks, ts, width, height)
                val extracted = EyeFeatureExtractor.extract(frame)
                features = extracted
                val estimated = HeadPoseEstimator.estimate(frame, extracted)
                pose = estimated
                if (needsFeatureVector && estimated.isValid) featureVector = GazeCalibrationFeatureVectorBuilder.from(HeadPoseNormalizer.normalize(extracted, estimated))
            }.onFailure { Timber.e(it, "Gaze feature pipeline failed") }
        }
        val raw = features?.let(RawIrisGazeExtractor::from)
        val headAngleDeg = pose?.takeIf { it.isValid }?.let { max(kotlin.math.abs(it.yawDeg), kotlin.math.abs(it.pitchDeg)) }
        val eligibility = GazeEligibilityPolicy.evaluate(GazeUncertainty(
            faceDetected = true,
            eyeQuality = raw?.eyeQuality ?: 0f,
            binocularAgreement = raw?.binocularAgreement ?: 0f,
            poseConfidence = pose?.takeIf { it.isValid }?.confidence,
            headAngleDeg = headAngleDeg,
            poseValid = pose?.isValid == true,
            modelQuality = modelReliability,
            modelAbsent = personalizedModel == null,
        ))
        var screenX = 0.5f; var screenY = 0.5f; var space = GazePointSpace.CAMERA_RAW; var jumpFactor = 1f
        if (raw != null) {
            val headCompX = if (pose?.isValid == true) pose!!.yawDeg * HEAD_POSE_COMPENSATION_FACTOR else 0f
            val headCompY = if (pose?.isValid == true) pose!!.pitchDeg * HEAD_POSE_COMPENSATION_FACTOR else 0f
            screenX = (raw.h + headCompX).coerceIn(0f, 1f); screenY = (raw.v + headCompY).coerceIn(0f, 1f)
            val model = personalizedModel
            val prediction = if (model != null && featureVector != null) runCatching { model.predict(featureVector!!) }.getOrNull() else null
            if (prediction != null) {
                val decision = jumpPolicy.evaluate(prediction.first.coerceIn(0f, 1f), prediction.second.coerceIn(0f, 1f), raw.signedTravelX, raw.signedTravelY, ts)
                screenX = decision.x; screenY = decision.y; space = GazePointSpace.SCREEN_NORMALIZED; jumpFactor = decision.actionConfidenceFactor
            }
        }
        val eyeQuality = raw?.eyeQuality ?: 0f
        val confidence = if (raw == null || eligibility.eligibility == GazeEligibility.NOTHING) 0f else eyeQuality.coerceIn(0f, 1f)
        val actionConfidence = (confidence * jumpFactor).coerceIn(0f, 1f)
        val ear = raw?.eyeOpenness ?: 1f
        _gazeObservations.tryEmit(GazeObservation(raw?.h ?: 0.5f, raw?.v ?: 0.5f, ear, eyeQuality, pose?.isValid == true, pose?.takeIf { it.isValid }?.confidence ?: 0f, raw?.binocularAgreement ?: 0f, featureVector, ts, true))
        _gazePoints.tryEmit(GazePoint(screenX, screenY, ear, confidence, actionConfidence, eligibility.eligibility, ts, space))
    }

    private fun buildFaceLandmarkFrame(result: FaceLandmarkerResult, landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, timestampMs: Long, widthPx: Int, heightPx: Int): FaceLandmarkFrame = checkNotNull(
        FaceLandmarkFrame.fromReader(
            frameId = timestampMs,
            timestampNs = timestampMs * 1_000_000L,
            timestampMs = timestampMs,
            trackerWidthPx = widthPx,
            trackerHeightPx = heightPx,
            isFrontCameraMirrored = GazeCoordinateContract.ANALYSIS_MIRRORED_HORIZONTALLY,
            facialTransformationMatrix = facialTransformationMatrix(result),
            reader = object : LandmarkReader {
                override val size: Int get() = landmarks.size
                override fun x(index: Int): Float = landmarks[index].x()
                override fun y(index: Int): Float = landmarks[index].y()
                override fun z(index: Int): Float = landmarks[index].z()
            },
        ),
    ) { "face landmark frame requires >= ${CanonicalEyes.MIN_LANDMARK_COUNT} landmarks" }

    private fun facialTransformationMatrix(result: FaceLandmarkerResult): FloatArray? = runCatching {
        val optional = result.facialTransformationMatrixes()
        if (!optional.isPresent || optional.get().isEmpty()) return@runCatching null
        optional.get()[0].takeIf { it.size == 16 && it.all(Float::isFinite) }
    }.getOrNull()

    @Volatile private var lastInitError: Throwable? = null
    private fun validateModelFile(): Boolean = try { context.assets.open(MODEL_FILE).close(); true } catch (e: Exception) {
        lastInitError = e; Timber.e(e, "Error opening face model file %s", MODEL_FILE); false
    }
    private fun tryInitializeWithDelegate(delegate: Delegate): FaceLandmarker? = try {
        val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_FILE).setDelegate(delegate).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(NUM_FACES)
            .setMinFaceDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> handleResult(result, result.timestampMs()) }
            .setErrorListener { error -> Timber.e(error, "FaceLandmarker error (delegate=%s)", delegate) }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        lastInitError = e; Timber.w(e, "Failed to initialize FaceLandmarker with %s delegate, initialization failed", delegate); null
    }
    companion object {
        private const val COMPONENT = "FaceTrackerImpl"
        private const val MODEL_FILE = "face_landmarker.task"
        private const val NUM_FACES = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val HEAD_POSE_COMPENSATION_FACTOR = 0.0035f
        private const val WORST_ACCEPTED_P95_ERROR = 0.10
    }
}
