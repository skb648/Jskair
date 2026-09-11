package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The one authoritative statement of what every gaze number means.
 *
 * Two layers feed the cursor, and a value must never silently change layer:
 *
 * ```
 * RAW GAZE      : camera-space eye geometry          → [RawIrisGaze]
 *   ↓ calibration ([GazeCalibration], gain/invert)   → screen-normalized
 * PERSONALIZED  : feature vector → ridge regression  → screen-normalized
 * ```
 *
 * Both paths must arrive at the SAME representation before the cursor consumes
 * them, which is why [GazePoint.personalized] exists: the flag says "this value
 * already crossed a calibration transform, do not apply another one".
 *
 * | representation | space | x axis | y axis | units | range |
 * |---|---|---|---|---|---|
 * | MediaPipe landmarks | normalized tracker image | image right | down | fraction of tracker width / height | [0,1] |
 * | canonical tracker frame | as above, front-camera mirror undone | **viewer right** | down | — | [0,1] |
 * | per-eye iris ratios ([EyeFeatures.irisAlongAxis]) | eye-local | **this eye's temporal corner** | perpendicular | eye widths | [0,1] |
 * | [RawIrisGaze] | canonical viewer frame | viewer right | down | half-apertures, folded into [0,1] | [0,1] |
 * | [GazePoint] | **screen-normalized** | left→right edge | top→bottom edge | screen fraction | [0,1] |
 * | cursor pixel position | display | — | — | px | [0,width) |
 *
 * Mirroring happens exactly once, in `CameraService.convertIntoLeasedBitmap`
 * (`m.postScale(-1f, 1f, ...)`), and is undone exactly once, in
 * [EyeFeatureExtractor]/[HeadPoseEstimator] via [FaceLandmarkFrame.isFrontCameraMirrored].
 * No other stage may flip an axis. The `gazeInvertX` preference compensates for
 * the *raw* path's historical direction convention and is therefore never applied
 * to a personalized prediction (that one already encodes the screen in its fit).
 */
object GazeCoordinateContract {
    /**
     * The single declaration of the front-camera mirror. `CameraService` applies
     * the horizontal flip to the analysis bitmap if and only if this is true, and
     * `FaceTracker` reports it to the landmark pipeline from this same symbol.
     *
     * Before this existed the flag was a literal `true` in one file and an
     * unconditional `postScale(-1, 1)` in another: identical behaviour, but nothing
     * linked them, so changing either one alone would have silently inverted gaze
     * (Phase 2's "exactly one intentional interpretation of mirror direction").
     */
    const val ANALYSIS_MIRRORED_HORIZONTALLY = true
}

/**
 * RAW GAZE — horizontal/vertical gaze in canonical viewer space, derived purely
 * from eye geometry. No calibration, no smoothing, no screen mapping.
 *
 * Fix (audit A1): the previous legacy path averaged the two eyes'
 * `irisAlongAxis`-style ratios directly. That value counts *away from each eye's
 * own nose*, so the eyes move in opposite signed directions for one gaze shift
 * and their mean was ≈ constant: horizontal gaze was arithmetically cancelled
 * while vertical survived (both eyes share top→bottom ordering). [RawIrisGaze]
 * converts both eyes into the shared viewer frame first (via
 * [EyeFeatures.axisSign]) and only then combines them, so contributions ADD.
 *
 * Neutral gaze maps to exactly 0.5 on both axes because each offset is measured
 * from the eye's own center, not from an arbitrary image position — this value is
 * therefore stable across face size, face position and camera crop, which is what
 * makes it a sane input to calibration.
 */
data class RawIrisGaze(
    /** Horizontal gaze in [0,1]; 0.5 neutral, increasing toward the viewer's right. */
    val h: Float,
    /** Vertical gaze in [0,1]; 0.5 neutral, increasing downward. */
    val v: Float,
    /** Signed horizontal iris offset, in half-aperture units, before clamping (telemetry + jump policy). */
    val signedTravelX: Float,
    /** Signed vertical iris offset, in half-aperture units, before clamping. */
    val signedTravelY: Float,
    /** Mean canonical six-point EAR of the eyes actually used (blink path). */
    val eyeOpenness: Float,
    /** Eye-geometry quality only — deliberately NOT mixed with head pose or model confidence. */
    val eyeQuality: Float,
    /** 1 when both eyes report the same gaze, 0 when they disagree by a full half-aperture. */
    val binocularAgreement: Float,
    /** 1 or 2 — how many eyes contributed. Monocular gaze is still valid gaze. */
    val eyesUsed: Int,
) {
    val isValid: Boolean
        get() = h.isFinite() && v.isFinite() && eyesUsed in 1..2
}

/**
 * Builds [RawIrisGaze] from [BinocularEyeFeatures].
 *
 * Every normalizer here is an ANATOMICAL extent measured from the same frame,
 * not a tuned constant:
 *  - horizontal travel is expressed in units of the eye's own half-aperture
 *    (the iris physically cannot leave the palpebral aperture), and
 *  - vertical travel in units of half the measured lid opening.
 * Both eyes therefore become dimensionless gaze fractions that are directly
 * comparable and directly addable.
 */
object RawIrisGazeExtractor {

    /** Iris at the eye corner is ±0.5 eye widths from the eye center: the half-aperture. */
    private const val HORIZONTAL_HALF_APERTURE = 0.5f

    fun from(features: BinocularEyeFeatures): RawIrisGaze? {
        val eyes = listOfNotNull(features.left, features.right).filter { eye ->
            eye.irisViewerX.isFinite() && eye.irisViewerY.isFinite() && eye.quality > MIN_EYE_QUALITY
        }
        if (eyes.isEmpty()) return null

        var weightedX = 0f
        var weightedY = 0f
        var totalWeight = 0f
        var sumQuality = 0f
        var sumEar = 0f
        for (eye in eyes) {
            val horizontalHalf = HORIZONTAL_HALF_APERTURE
            val verticalHalf = max(eye.eyelidOpening * 0.5f, MIN_VERTICAL_HALF_APERTURE)
            val posX = eye.irisViewerX / horizontalHalf
            val posY = eye.irisViewerY / verticalHalf
            // Fix #9: Quality-weighted blending: a well-lit eye dominates an eye in shadow/glare
            val weight = eye.quality.coerceAtLeast(0.01f)
            weightedX += posX * weight
            weightedY += posY * weight
            totalWeight += weight
            sumQuality += eye.quality
            sumEar += eye.ear
        }
        val n = eyes.size
        val meanX = if (totalWeight > 0f) weightedX / totalWeight else 0f
        val meanY = if (totalWeight > 0f) weightedY / totalWeight else 0f

        // Binocular agreement: both eyes rotate together during a version, so a
        // disagreement approaching one half-aperture means one iris was mislocated.
        val agreementX = if (eyes.size == 2) {
            val spread = abs(eyes[0].irisViewerX / HORIZONTAL_HALF_APERTURE - eyes[1].irisViewerX / HORIZONTAL_HALF_APERTURE)
            (1f - (spread / SPREAD_FULL_REJECT).coerceIn(0f, 1f))
        } else {
            // A single usable eye has no cross-check; it is neither trusted nor
            // rejected extra — 0.5 leaves the quality weighting as the only gate.
            0.5f
        }

        return RawIrisGaze(
            h = (0.5f + meanX * 0.5f).coerceIn(0f, 1f),
            v = (0.5f + meanY * 0.5f).coerceIn(0f, 1f),
            signedTravelX = meanX,
            signedTravelY = meanY,
            eyeOpenness = sumEar / n,
            eyeQuality = (sumQuality / n).coerceIn(0f, 1f),
            binocularAgreement = agreementX.coerceIn(0f, 1f),
            eyesUsed = n,
        )
    }

    /** Below this an eye's landmark ring is too corrupted to say anything about gaze. */
    private const val MIN_EYE_QUALITY = 0.05f

    /** Guards against a closed/squinted eye (lid opening ~0) exploding the vertical ratio. */
    private const val MIN_VERTICAL_HALF_APERTURE = 0.02f

    /** Half-aperture spread at which binocular agreement reaches zero. */
    private const val SPREAD_FULL_REJECT = 1.0f
}
