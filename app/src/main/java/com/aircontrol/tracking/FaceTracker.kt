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
import kotlin.math.max

/**
 * Which representation [GazePoint.x]/[GazePoint.y] hold. Phase 3: a gaze value may
 * never be ambiguous about this, because the two producers are different
 * transforms and applying the wrong one silently ruins the mapping.
 */
enum class GazePointSpace {
    /**
     * Raw camera-space gaze in [0,1], 0.5 = neutral, +x toward the viewer's right,
     * +y downward (see [RawIrisGaze]). NOT a screen position: the consumer still
     * owns the raw→screen transform (calibration, or gain/invert when
     * uncalibrated).
     */
    CAMERA_RAW,

    /**
     * Output of a calibrated transform: already screen-normalized [0,1], origin at
     * the top-left, +x right, +y down. Mapping this through the raw gain/invert or
     * the hand dead-zone path would apply the calibration twice.
     */
    SCREEN_NORMALIZED,
}

/**
 * Cursor-facing gaze sample.
 *
 * [x]/[y] are in the space named by [space] — that is the whole reason the field
 * exists: previously one float pair meant "raw iris ratio" when no model was
 * installed and "screen prediction" when one was, and the only way to tell was to
 * know that [personalized] happened to be set in exactly the one place that
 * differed. See [GazeCoordinateContract] for the full table.
 *
 * Confidence is split (Phase 5): [confidence] describes whether the gaze is good
 * enough to *move the dot*, [actionConfidence] whether it is good enough to *tap*
 * with. Both are derived by [GazeEligibilityPolicy] from the separate uncertainty
 * sources, never from a single merged gate.
 */
data class GazePoint(
    val x: Float,
    val y: Float,
    val ear: Float = 1f,
    /** Eye-geometry + visibility eligibility score in [0,1]; drives the cursor show/hide hysteresis. */
    val confidence: Float,
    /** [confidence] further restricted by head angle, model reliability and binocular agreement. */
    val actionConfidence: Float = confidence,
    val eligibility: GazeEligibility = GazeEligibility.VISIBLE,
    val timestampMs: Long = 0L,
    val space: GazePointSpace = GazePointSpace.CAMERA_RAW,
) {
    /** True when this frame is good enough to display a cursor at (x, y). */
    val isDetected: Boolean get() = eligibility.showsCursor

    /** True when (x, y) is trustworthy enough to fire a click/dwell at. */
    val isActionable: Boolean get() = eligibility.canAct && actionConfidence >= ACTION_CONFIDENCE_FLOOR

    /** Convenience for consumers written before the split: which transform owns (x, y). */
    val personalized: Boolean get() = space == GazePointSpace.SCREEN_NORMALIZED

    companion object {
        /** Same floor as [GazeEligibilityPolicy.ACTION_QUALITY] — one number, two names kept equal on purpose. */
        const val ACTION_CONFIDENCE_FLOOR = GazeEligibilityPolicy.ACTION_QUALITY

        /** Historical name of the single merged gate; retained so the meaning stays greppable. */
        const val MIN_GAZE_CONFIDENCE = GazeEligibilityPolicy.ACTION_QUALITY

        val EMPTY = GazePoint(0.5f, 0.5f, ear = 1f, confidence = 0f, eligibility = GazeEligibility.NOTHING)
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
    /** Raw camera-space horizontal iris gaze, 0.5 neutral (see [GazePointSpace.CAMERA_RAW]). */
    val rawX: Float,
    /** Raw camera-space vertical iris gaze, 0.5 neutral. */
    val rawY: Float,
    val ear: Float,
    /**
     * [RawIrisGaze.eyeQuality] — eye-geometry consistency ONLY.
     *
     * Fix (audit A3): this used to be `min(eye quality, pose confidence)`, i.e. the
     * same field meant a different thing than the runtime's confidence and dragged a
     * 0.75 pose multiplier into the calibration floor. A frame whose eyes are clean
     * is a good calibration frame even if the face is slightly asymmetric.
     */
    val quality: Float,
    val poseValid: Boolean,
    /** Head-pose tier confidence, exposed separately so it never silently reweights [quality]. */
    val poseConfidence: Float = 0f,
    /** How much the two eyes agree about the same gaze direction. */
    val binocularAgreement: Float = 0f,
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

    /**
     * Submits [mpImage] to the face graph only when no submission is outstanding; see
     * [HandTracker.processFrame] for the contract, which is identical and exists for the same
     * reason — the eye channel is the one where an unbounded queue is felt as the cursor lagging
     * further and further behind the face.
     */
    fun processFrame(mpImage: MPImage, timestampMs: Long, onConsumed: (() -> Unit)? = null): Boolean

    fun close()
    fun isInitialized(): Boolean

    /** Backpressure counters for telemetry. */
    fun inFlightStats(nowMs: Long): InFlightStats

    /**
     * Fix A5: installs (or clears, with null) the personalized gaze model.
     * When set and the current frame yields a valid feature vector, predicted
     * screen-space coordinates are emitted as [GazePoint]s with
     * `personalized = true`; otherwise the tracker falls back to iris ratios.
     */
    fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?)

    /**
     * Perf audit P8: marks whether a gaze-calibration session is actively
     * collecting. The per-frame advanced feature pipeline (normalization +
     * feature-vector construction) only runs while this is true or a
     * personalized model is installed — with neither, the vectors have no
     * consumer and building them every frame is pure overhead.
     */
    fun setCalibrationCollecting(active: Boolean)

    /**
     * Phase 1: bounded, allocation-free per-frame pipeline trace. Always present
     * (20 slots); only *read* in debug builds, and only written when a debug build
     * is running, so release builds pay one null check per frame.
     */
    val diagnostics: GazeDiagnostics
}

@Singleton
class FaceTrackerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceTracker {

    private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var _isInitialized = false
    @Volatile private var isClosing = false

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
            // Perf audit P2: reuse across service stop/start is now the NORMAL
            // path (models survive a session stop), so this is informational,
            // not a warning.
            Timber.d("FaceTracker already initialized — reusing the loaded model")
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
        // P0-2: a close while a frame is in flight must not strand its lease. No result callback
        // is coming for that frame, and the graph is already torn down, so the gate is cleared and
        // the caller's buffer is handed back here instead.
        inFlight.reset()
        pendingConsumed.getAndSet(null)?.invoke()

        Timber.i("FaceTracker initialized successfully")
        com.aircontrol.runtime.PerfTelemetry.recordTrackerEvent(
            "face-initialized",
            android.os.SystemClock.elapsedRealtime(),
        )
    }

    override fun updatePersonalizedModel(model: PersonalizedGazeCalibrationModel?) {
        // New model → its screen scale is unrelated to the previous one, so the
        // jump policy must not compare across the swap.
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
                    val landmarker = faceLandmarker ?: null
                    if (landmarker != null) {
                        lastImageWidthPx = mpImage.width
                        lastImageHeightPx = mpImage.height
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
            Timber.e(e, "Error processing face frame: submission failed")
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
        )
    }

    override fun close() {
        // Perf audit P7: no unconditional 200 ms latch wait — MediaPipe's own
        // close() drains the graph, and the lock keeps submissions out.
        synchronized(closeLock) {
            if (!_isInitialized && faceLandmarker == null) return // idempotent
            isClosing = true
            try {
                faceLandmarker?.close()
            } catch (e: Exception) {
                Timber.e(e, "Error closing FaceLandmarker")
            }
            faceLandmarker = null
            _isInitialized = false
            isClosing = false
            lastSubmittedTimestampMs = Long.MIN_VALUE
        }
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
        inFlight.release()
        pendingConsumed.getAndSet(null)?.invoke()
        if (isClosing) {
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
        // Eye features, head pose and the feature vector are all derived from a
        // single FaceLandmarkFrame. The raw gaze cursor, the blink EAR and the
        // calibration vector therefore describe the same measured eye, with the
        // same mirror interpretation and the same aspect handling — previously the
        // legacy path recomputed its own ratios from raw landmarks (with swapped
        // left/right naming and no mirror correction), which is what cancelled
        // horizontal gaze.
        var features: BinocularEyeFeatures? = null
        var pose: HeadPoseEstimate? = null
        var featureVector: CalibrationFeatureVector? = null
        val needsFeatureVector = personalizedModel != null || calibrationCollecting
        if (landmarks.size >= CanonicalEyes.MIN_LANDMARK_COUNT && width > 0 && height > 0) {
            // A single bad frame must never kill the cursor: a failure here leaves
            // `features` null and the frame is reported as "no usable gaze", which
            // the consumer's hysteresis absorbs.
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
        )
        val eligibility = GazeEligibilityPolicy.evaluate(uncertainty)

        // ---- TARGET ESTIMATION: raw gaze -> screen-normalized ----
        // Exactly one transform runs per frame, chosen by which representation the
        // value is in. The raw path stays in CAMERA_RAW here on purpose: the
        // affine/gain mapping is owned by the consumer, which also owns the saved
        // calibration coefficients.
        var screenX = 0.5f
        var screenY = 0.5f
        var space = GazePointSpace.CAMERA_RAW
        var jumpFactor = 1f
        var jumpHeld = false
        if (raw != null) {
            screenX = raw.h
            screenY = raw.v
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
                    // P0-4: the hold is bounded in pipeline time, so the freeze a user feels no
                    // longer depends on how many frames per second this device manages. The sample's
                    // own timestamp is the clock (never SystemClock here: the policy must stay
                    // deterministic, and this is the timestamp the frame was captured with).
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
        // The cursor floor comes from the eligibility decision, not from an ad-hoc
        // product of unrelated scores: "shown" is a decision, not a side effect of
        // how the numbers happened to multiply.
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
        // Fix (audit A4): the old early-return on a suppressed jump skipped both
        // emissions, so suppressed frames were also lost calibration samples. Only
        // the CURSOR position is held now; the evidence keeps flowing.
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
        // Fix (compile): the facialTransformationMatrixes() accessor shape varies across
        // tasks-vision builds and did not resolve here; the matrix was an optional acceleration for
        // head pose anyway. Null means HeadPoseEstimator uses its landmark fallback (confidence
        // 0.75, valid up to ±70° yaw/pitch). Restoring it is P1-3 / Group 8.
        facialTransformationMatrix = null,
        // P0-5: 25 reads through this adapter instead of 478 `map { FaceLandmark(...) }` copies.
        reader = object : LandmarkReader {
            override val size: Int get() = landmarks.size
            override fun x(index: Int): Float = landmarks[index].x()
            override fun y(index: Int): Float = landmarks[index].y()
            override fun z(index: Int): Float = landmarks[index].z()
        },
        // The only caller gates on CanonicalEyes.MIN_LANDMARK_COUNT, so a short list is a
        // programming error rather than a runtime condition to route around.
    )) { "face landmark frame requires >= ${CanonicalEyes.MIN_LANDMARK_COUNT} landmarks" }

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

        /** `PersonalizedGazeCalibrationFitter.WORST_TARGET_MAX_NORMALIZED_ERROR`, in words. */
        private const val WORST_ACCEPTED_P95_ERROR = 0.10
    }
}
