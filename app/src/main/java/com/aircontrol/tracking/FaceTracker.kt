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

    /** One outstanding submission for the eye channel, plus its caller's completion hook. */
    private val inFlight = InFlightGate()
    private val pendingConsumed = InFlightCompletionSlot()

    // Perf audit P7: guards detectAsync submission against close().
    private val closeLock = Any()
    @Volatile private var lastSubmittedTimestampMs = Long.MIN_VALUE

    // Perf audit P8: true only while a gaze-calibration session is actively
    // collecting. Together with a non-null personalized model it decides
    // whether the per-frame feature-vector tier runs at all.
    @Volatile private var calibrationCollecting = false

    // Fix A5: the active personalized model (null = legacy ratio mode).
    @Volatile private var personalizedModel: PersonalizedGazeCalibrationModel? = null

    // Fix (audit A4): the personalized prediction is checked against the measured
    // iris motion by a stateful policy that HOLDS instead of dropping frames.
    private val jumpPolicy = GazeJumpPolicy()

    /**
     * Reliability of the installed personalized model, in [0,1].
     *
     * Not a new threshold: it is the model's own cross-validated p95 error mapped
     * against [WORST_ACCEPTED_P95_ERROR] — the very bound
     * `PersonalizedGazeCalibrationFitter` uses to decide whether to keep a fit at
     * all. A model that barely passed is allowed to move the cursor but is not
     * trusted for blind taps, which is what the previous shared 0.45 gate got wrong
     * in the other direction (it rejected GOOD raw gaze because a 0.75 pose factor
     * was multiplied into it).
     */
    @Volatile private var modelReliability: Float? = null

    /** Phase 1 diagnostics. Written only when [diagnosticsEnabled] (debug builds). */
    private val _diagnostics = GazeDiagnostics()
    override val diagnostics: GazeDiagnostics get() = _diagnostics
    private val diagnosticsEnabled = com.aircontrol.BuildConfig.DEBUG

    // Dimensions of the last submitted image (the mirrored/rotated analysis
    // bitmap), needed to build aspect-correct FaceLandmarkFrames.
    @Volatile private var lastImageWidthPx: Int = 0
    @Volatile private var lastImageHeightPx: Int = 0

    private val _gazePoints = MutableSharedFlow<GazePoint>(
        // P0-3: the cursor channel is latest-wins, not a queue. A 64-slot buffer is >3 s of eye
        // frames, and a collector that is even slightly behind replays them in order — which is
        // what made the pointer walk through the recent past instead of pointing where the eyes are
        // now. One slot means the consumer paints the newest sample and skips whatever piled up;
        // overflow dropping is then a no-op rather than a policy.
        extraBufferCapacity = 1,
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
            Timber.d("FaceTracker already initialized — reusing the loaded model")
            return
        }
        if (!validateModelFile()) {
            StageLog.failure(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "$MODEL_FILE not readable from assets")
            return
        }

        // isClosing stays true after close so late MediaPipe callbacks are inert.
        isClosing = false
        faceLandmarker = tryInitializeWithDelegate(Delegate.CPU)
            ?: run {
                StageLog.failure(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "FaceLandmarker.createFromOptions(CPU) failed", lastInitError)
                return
            }
        _isInitialized = true
        StageLog.success(StageLog.Stage.FACE_TRACKER_INIT, COMPONENT, "delegate=CPU model=$MODEL_FILE matrix=true")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "face-initialized",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?) {
        jumpPolicy.reset()
        personalizedModel = model
        modelReliability = model?.let {
            (1f - (it.validationMetrics.p95NormalizedError / WORST_ACCEPTED_P95_ERROR).toFloat())
                .coerceIn(0f, 1f)
        }
        Timber.i(
            if (model != null) "Personalized gaze model activated (trained %d, val p95 %.4f)"
                .format(model.createdAtMs, model.validationMetrics.p95NormalizedError)
            else "Personalized gaze model cleared — falling back to iris ratios",
        )
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
                    val landmarker = faceLandmarker
                    if (landmarker != null) {
                        lastImageWidthPx = mpImage.width
                        lastImageHeightPx = mpImage.height
                        val mediaPipeTimestampMs = if (timestampMs <= lastSubmittedTimestampMs) {
                            lastSubmittedTimestampMs + 1L
                        } else {
                            timestampMs
                        }
                        lastSubmittedTimestampMs = mediaPipeTimestampMs
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
        } catch (e: Exception) {
            Timber.e(e, "Error processing face frame: submission failed")
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
        // Perf audit P7: no unconditional 200 ms latch wait — MediaPipe's own
        // close() drains the graph, and the lock keeps submissions out.
        synchronized(closeLock) {
            if (!_isInitialized && faceLandmarker == null) {
                isClosing = true
                return
            }
            isClosing = true
            try {
                faceLandmarker?.close()
            } catch (e: Exception) {
                Timber.e(e, "Error closing FaceLandmarker")
            }
            faceLandmarker = null
            _isInitialized = false
            // Intentionally keep isClosing=true until the next initialize().
            lastSubmittedTimestampMs = Long.MIN_VALUE
        }
        inFlight.reset()
        pendingConsumed.clear()?.onConsumed?.invoke()

        Timber.i("FaceTracker closed")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "face-closed",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun setCalibrationCollecting(active: Boolean) {
        // Perf audit P8: a calibration session needs the per-frame feature
        // vectors; nothing else does unless a personalized model is installed.
        calibrationCollecting = active
    }

    override fun isInitialized(): Boolean = _isInitialized

    @Suppress("DEPRECATION")
    private fun handleResult(result: FaceLandmarkerResult, resultTimestampMs: Long) {
        // Buffer lifetime first, before any early return (see HandTracker for the reasoning).
        val completion = pendingConsumed.takeForTimestamp(resultTimestampMs) ?: return
        inFlight.release(completion.reservationToken)
        completion.onConsumed?.invoke()
        if (isClosing || !_isInitialized) {
            return
        }
        // Perf audit P18: end-to-end inference latency (the result timestamp
        // echoes the elapsedRealtime value submitted with the frame).
        val latencyMs = android.os.SystemClock.elapsedRealtime() - resultTimestampMs
        if (latencyMs in 0..60_000L) {
            com.aircontrol.runtime.PerfTelemetry.recordFaceInference(latencyMs)
        }

        val faceLandmarks = result.faceLandmarks()
        val width = lastImageWidthPx
        val height = lastImageHeightPx
        if (faceLandmarks.isEmpty()) {
            val ts = resultTimestampMs.coerceAtLeast(0L)
            _gazePoints.tryEmit(
                GazePoint(
                    x = 0.5f, y = 0.5f, ear = 1f, confidence = 0f,
                    eligibility = GazeEligibility.NOTHING, timestampMs = ts,
                ),
            )
            _gazeObservations.tryEmit(
                GazeObservation(
                    rawX = 0.5f, rawY = 0.5f, ear = 1f, quality = 0f,
                    poseValid = false, featureVector = null, timestampMs = ts,
                    faceDetected = false,
                ),
            )
            if (diagnosticsEnabled) {
                _diagnostics.record(
                    GazeDiagnostics.Sample(
                        timestampMs = ts, faceDetected = false, leftEyeValid = false, rightEyeValid = false,
                        leftEyeQuality = 0f, rightEyeQuality = 0f, ear = 1f, rawIrisX = 0.5f, rawIrisY = 0.5f,
                        headYawDeg = Float.NaN, headPitchDeg = Float.NaN, headPoseValid = false,
                        headPoseConfidence = 0f, calibrationActive = calibrationCollecting,
                        personalizedModelActive = personalizedModel != null,
                        personalizedPredictionX = Float.NaN, personalizedPredictionY = Float.NaN,
                        rawConfidence = 0f, finalConfidence = 0f,
                        smoothingInputX = Float.NaN, smoothingInputY = Float.NaN,
                        smoothingOutputX = Float.NaN, smoothingOutputY = Float.NaN,
                        rejectionReason = GazeDiagnostics.RejectionReason.FACE_LOST,
                        gazeCursorX = Float.NaN, gazeCursorY = Float.NaN,
                    ),
                )
            }
            return
        }

        val landmarks = faceLandmarks[0]
        val ts = resultTimestampMs.coerceAtLeast(0L)

        // ---- ONE geometry source for every consumer (Phase 2/3) ----
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
                if (needsFeatureVector && estimated.isValid) {
                    featureVector = GazeCalibrationFeatureVectorBuilder.from(
                        HeadPoseNormalizer.normalize(extracted, estimated),
                    )
                }
            }.onFailure { Timber.e(it, "Gaze feature pipeline failed") }
        }

        val raw = features?.let(RawIrisGazeExtractor::from)

        // ---- VALIDITY + QUALITY, decided once (Phase 5/6) ----
        val headAngleDeg = pose?.takeIf { it.isValid }?.let {
            max(kotlin.math.abs(it.yawDeg), kotlin.math.abs(it.pitchDeg))
        }
        val model = personalizedModel
        val uncertainty = GazeUncertainty(
            faceDetected = true,
            eyeQuality = raw?.eyeQuality ?: 0f,
            binocularAgreement = raw?.binocularAgreement ?: 0f,
            poseConfidence = pose?.takeIf { it.isValid }?.confidence,
            headAngleDeg = headAngleDeg,
            poseValid = pose?.isValid == true,
            modelQuality = modelReliability,
            modelAbsent = model == null,
            eyesUsed = raw?.eyesUsed ?: 0,
        )
        val eligibility = GazeEligibilityPolicy.evaluate(uncertainty)

        // ---- TARGET ESTIMATION: raw gaze -> screen-normalized ----
        var screenX = 0.5f
        var screenY = 0.5f
        var space = GazePointSpace.CAMERA_RAW
        var jumpFactor = 1f
        var jumpHeld = false
        if (raw != null) {
            // Head-pose continuous compensation: cancels head rotation/tilt drift
            val headCompX = if (pose != null && pose.isValid) pose.yawDeg * HEAD_POSE_COMPENSATION_FACTOR else 0f
            val headCompY = if (pose != null && pose.isValid) pose.pitchDeg * HEAD_POSE_COMPENSATION_FACTOR else 0f
            screenX = (raw.h + headCompX).coerceIn(0f, 1f)
            screenY = (raw.v + headCompY).coerceIn(0f, 1f)
            val prediction = if (model != null && featureVector != null) {
                runCatching { model.predict(featureVector!!) }.getOrNull()
            } else {
                null
            }
            if (prediction != null) {
                val decision = jumpPolicy.evaluate(
                    predictedX = prediction.first.coerceIn(0f, 1f),
                    predictedY = prediction.second.coerceIn(0f, 1f),
                    irisX = raw.signedTravelX,
                    irisY = raw.signedTravelY,
                    timestampMs = ts,
                )
                screenX = decision.x
                screenY = decision.y
                space = GazePointSpace.SCREEN_NORMALIZED
                jumpFactor = decision.actionConfidenceFactor
                jumpHeld = decision.outcome == GazeJumpPolicy.JumpOutcome.HOLD
            }
        }

        val eyeQuality = raw?.eyeQuality ?: 0f
        val confidence = when {
            raw == null -> 0f
            eligibility.eligibility == GazeEligibility.NOTHING -> 0f
            else -> eyeQuality.coerceIn(0f, 1f)
        }
        val actionConfidence = (confidence * jumpFactor).coerceIn(0f, 1f)
        val rejection = when {
            jumpHeld -> GazeDiagnostics.RejectionReason.PREDICTION_SUPPRESSED
            raw == null -> GazeDiagnostics.RejectionReason.LOW_EYE_QUALITY
            else -> eligibility.rejectionReason
        }

        // ---- EMIT: a face frame ALWAYS produces an observation ----
        val ear = raw?.eyeOpenness ?: 1f
        _gazeObservations.tryEmit(
            GazeObservation(
                rawX = raw?.h ?: 0.5f,
                rawY = raw?.v ?: 0.5f,
                ear = ear,
                quality = eyeQuality,
                poseValid = pose?.isValid == true,
                poseConfidence = pose?.takeIf { it.isValid }?.confidence ?: 0f,
                binocularAgreement = raw?.binocularAgreement ?: 0f,
                featureVector = featureVector,
                timestampMs = ts,
                faceDetected = true,
            ),
        )
        if (raw != null) {
            _gazePoints.tryEmit(
                GazePoint(
                    x = screenX,
                    y = screenY,
                    ear = ear,
                    confidence = confidence,
                    actionConfidence = actionConfidence,
                    eligibility = eligibility.eligibility,
                    timestampMs = ts,
                    space = space,
                ),
            )
        } else {
            _gazePoints.tryEmit(GazePoint(0.5f, 0.5f, ear = ear, confidence = 0f, eligibility = GazeEligibility.NOTHING, timestampMs = ts))
        }

        if (diagnosticsEnabled) {
            recordDiagnostics(
                timestampMs = ts,
                features = features,
                raw = raw,
                pose = pose,
                eligibility = eligibility,
                rejection = rejection,
                screenX = screenX,
                screenY = screenY,
                confidence = confidence,
                actionConfidence = actionConfidence,
                modelActive = model != null,
                featureVector = featureVector,
            )
        }
    }

    /** Debug-only: writes one [GazeDiagnostics.Sample]; no allocation outside this branch. */
    private fun recordDiagnostics(
        timestampMs: Long,
        features: BinocularEyeFeatures?,
        raw: RawIrisGaze?,
        pose: HeadPoseEstimate?,
        eligibility: GazeEligibilityPolicy.Decision,
        rejection: GazeDiagnostics.RejectionReason?,
        screenX: Float,
        screenY: Float,
        confidence: Float,
        actionConfidence: Float,
        modelActive: Boolean,
        featureVector: CalibrationFeatureVector?,
    ) {
        _diagnostics.record(
            GazeDiagnostics.Sample(
                timestampMs = timestampMs,
                faceDetected = features != null,
                leftEyeValid = features?.left != null,
                rightEyeValid = features?.right != null,
                leftEyeQuality = features?.left?.quality ?: 0f,
                rightEyeQuality = features?.right?.quality ?: 0f,
                ear = raw?.eyeOpenness ?: 0f,
                rawIrisX = raw?.h ?: 0f,
                rawIrisY = raw?.v ?: 0f,
                headYawDeg = pose?.yawDeg ?: Float.NaN,
                headPitchDeg = pose?.pitchDeg ?: Float.NaN,
                headPoseValid = pose?.isValid == true,
                headPoseConfidence = pose?.confidence ?: 0f,
                calibrationActive = calibrationCollecting,
                personalizedModelActive = modelActive,
                personalizedPredictionX = if (modelActive) screenX else Float.NaN,
                personalizedPredictionY = if (modelActive) screenY else Float.NaN,
                rawConfidence = confidence,
                finalConfidence = actionConfidence,
                smoothingInputX = screenX,
                smoothingInputY = screenY,
                smoothingOutputX = screenX,
                smoothingOutputY = screenY,
                rejectionReason = rejection ?: eligibility.rejectionReason
                    ?: GazeDiagnostics.RejectionReason.CURSOR_UPDATED,
                gazeCursorX = screenX,
                gazeCursorY = screenY,
            ),
        )
        // `featureVector` is only reported through the sample's model fields: a
        // model that is installed but never fed a valid vector shows up as
        // personalizedPredictionX = NaN, which is the actionable signal.
    }

    private fun buildFaceLandmarkFrame(
        result: FaceLandmarkerResult,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        timestampMs: Long,
        widthPx: Int,
        heightPx: Int,
    ): FaceLandmarkFrame = checkNotNull(
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
    ) {
        "face landmark frame requires >= ${CanonicalEyes.MIN_LANDMARK_COUNT} landmarks"
    }

    private fun facialTransformationMatrix(result: FaceLandmarkerResult): FloatArray? = runCatching {
        val optional = result.facialTransformationMatrixes()
        if (!optional.isPresent || optional.get().isEmpty()) return@runCatching null
        optional.get()[0].takeIf { it.size == 16 && it.all(Float::isFinite) }
    }.getOrNull()

    /** Last exception from [tryInitializeWithDelegate], for the stage record. */
    @Volatile private var lastInitError: Throwable? = null

    private fun validateModelFile(): Boolean {
        return try {
            context.assets.open(MODEL_FILE).close()
            true
        } catch (e: Exception) {
            lastInitError = e
            Timber.e(e, "Error opening face model file %s", MODEL_FILE)
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
                .setOutputFacialTransformationMatrixes(true)
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
            lastInitError = e
            Timber.w(e, "Failed to initialize FaceLandmarker with %s delegate, initialization failed", delegate)
            null
        }
    }

    companion object {
        private const val COMPONENT = "FaceTrackerImpl"
        private const val MODEL_FILE = "face_landmarker.task"
        private const val NUM_FACES = 1
        private const val MIN_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
        private const val HEAD_POSE_COMPENSATION_FACTOR = 0.0035f

        /** `PersonalizedGazeCalibrationFitter.WORST_TARGET_MAX_NORMALIZED_ERROR`, in words. */
        private const val WORST_ACCEPTED_P95_ERROR = 0.10
    }
}
