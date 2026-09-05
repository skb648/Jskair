package com.aircontrol.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedGazeCalibrationTest {
    private val transform = CoordinateTransform(1280, 720, CropRect(100, 50, 640, 480), Rotation.DEG_0, false)

    @Test fun featureVectorOrderingIsDeterministic() {
        val a = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())!!
        val b = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())!!
        assertEquals(GazeCalibrationFeatureSchema.VERSION, a.schemaVersion)
        assertEquals(23, a.values.size)
        assertArrayEquals(a.values, b.values, 0f)
        assertEquals("left_iris_along", GazeCalibrationFeatureSchema.NAMES[0])
        assertEquals("right_eye_center_from_face_y", GazeCalibrationFeatureSchema.NAMES[13])
        assertEquals("head_yaw_deg", GazeCalibrationFeatureSchema.NAMES[17])
        assertEquals("face_scale_norm", GazeCalibrationFeatureSchema.NAMES[22])
    }

    @Test fun featureSchemaVersionIsStable() = assertEquals(1, GazeCalibrationFeatureSchema.VERSION)

    @Test fun validSampleCreation() {
        val vector = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())
        val result = CalibrationSample.create(CalibrationTarget.CENTER, vector, 100L, 0.9f, poseValid = true)
        assertTrue(result is CalibrationSample.Companion.Result.Accepted)
    }

    @Test fun invalidSampleRejectedWithoutFabrication() {
        val result = CalibrationSample.create(CalibrationTarget.CENTER, null, 100L, 0.9f, poseValid = true)
        assertTrue(result is CalibrationSample.Companion.Result.Rejected)
        assertEquals(CalibrationSample.Companion.RejectionReason.INVALID_FEATURES, (result as CalibrationSample.Companion.Result.Rejected).reason)
    }

    @Test fun nonFiniteFeatureRejected() {
        try {
            CalibrationFeatureVector(GazeCalibrationFeatureSchema.VERSION, FloatArray(23) { if (it == 3) Float.NaN else 0f })
            throw AssertionError("expected non-finite feature rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun polynomialFeatureGenerationHasExactlyThreeHundredTerms() {
        val input = DoubleArray(23) { it + 2.0 }
        val expanded = QuadraticPolynomialFeatures.expand(input)
        assertEquals(300, expanded.size)
        assertEquals(1.0, expanded[0], 0.0)
        assertEquals(2.0, expanded[1], 0.0)
        assertEquals(3.0, expanded[2], 0.0)
    }

    @Test fun insufficientDataFailsExplicitly() {
        val result = PersonalizedGazeCalibrationFitter.fit(
            rawSamples = calibrationFixtureSamples(totalPerTarget = 99),
            transform = transform,
            createdAtMs = 1L,
        )
        assertFalse(result.isSuccess)
        assertTrue(result.failure!!.contains("insufficient"))
    }

    @Test fun ridgeFittingProducesFiniteTwoAxisModelWithNineHundredObservations() {
        val result = PersonalizedGazeCalibrationFitter.fit(
            rawSamples = calibrationFixtureSamples(totalPerTarget = 100),
            transform = transform,
            regularization = 0.1,
            screenWidthPx = 1920,
            screenHeightPx = 1080,
            createdAtMs = 10L,
        )
        assertTrue(result.isSuccess)
        val model = result.model!!
        assertEquals(300, model.coefficientsX.size)
        assertEquals(300, model.coefficientsY.size)
        assertTrue(model.coefficientsX.all { it.isFinite() })
        assertTrue(model.coefficientsY.all { it.isFinite() })
        assertEquals(720, model.trainingMetrics.sampleCount)
        assertEquals(180, model.validationMetrics.sampleCount)
        assertEquals(180, model.validationMetrics.validationSampleCount)
        assertTrue(model.validationMetrics.meanPixelError!!.isFinite())
    }

    @Test fun fittingIsDeterministic() {
        val samples = calibrationFixtureSamples(100)
        val a = PersonalizedGazeCalibrationFitter.fit(samples, transform, regularization = 0.1, createdAtMs = 10L).model!!
        val b = PersonalizedGazeCalibrationFitter.fit(samples, transform, regularization = 0.1, createdAtMs = 10L).model!!
        assertArrayEquals(a.standardization.means, b.standardization.means, 0.0)
        assertArrayEquals(a.standardization.stdDevs, b.standardization.stdDevs, 0.0)
        assertArrayEquals(a.coefficientsX, b.coefficientsX, 0.0)
        assertArrayEquals(a.coefficientsY, b.coefficientsY, 0.0)
        assertEquals(a.predict(samples.first().features), b.predict(samples.first().features))
    }

    @Test fun trainingValidationSplitIsDisjointAndBalanced() {
        val split = TargetBalancedValidationSplit.split(calibrationFixtureSamples(100), 0.20)
        assertEquals(720, split.training.size)
        assertEquals(180, split.validation.size)
        assertTrue(split.training.groupBy { it.target }.values.all { it.size == 80 })
        assertTrue(split.validation.groupBy { it.target }.values.all { it.size == 20 })
        val validationKeys = split.validation.map { it.target.ordinal to it.timestampMs }.toSet()
        assertTrue(split.training.none { (it.target.ordinal to it.timestampMs) in validationKeys })
    }

    @Test fun validationChangesDoNotChangeTrainingPreprocessingOrCoefficients() {
        val baseline = calibrationFixtureSamples(100)
        val split = TargetBalancedValidationSplit.split(baseline, 0.20)
        val changedValidation = split.validation.map { sample ->
            val changedValues = sample.features.copyValues().also { values -> values[0] += 1000f; values[1] -= 1000f }
            sample.copy(features = CalibrationFeatureVector(GazeCalibrationFeatureSchema.VERSION, changedValues))
        }
        val alteredDataset = split.training + changedValidation
        val a = PersonalizedGazeCalibrationFitter.fit(baseline, transform, regularization = 0.1, createdAtMs = 10L).model!!
        val b = PersonalizedGazeCalibrationFitter.fit(alteredDataset, transform, regularization = 0.1, createdAtMs = 10L).model!!
        assertArrayEquals(a.standardization.means, b.standardization.means, 0.0)
        assertArrayEquals(a.standardization.stdDevs, b.standardization.stdDevs, 0.0)
        assertArrayEquals(a.coefficientsX, b.coefficientsX, 0.0)
        assertArrayEquals(a.coefficientsY, b.coefficientsY, 0.0)
        assertNotEquals(a.validationMetrics.meanNormalizedError, b.validationMetrics.meanNormalizedError)
    }

    @Test fun robustAggregationRejectsExtremeOutlier() {
        val normal = calibrationFixtureSamples(100).filter { it.target == CalibrationTarget.CENTER }.take(90)
        val outlierValues = normal.first().features.copyValues().also { values -> values[0] = 50f; values[1] = -50f }
        val outlier = normal.first().copy(timestampMs = 999999L, features = CalibrationFeatureVector(1, outlierValues))
        val result = RobustCalibrationSampleAggregator.filter(normal + outlier)
        assertTrue(result.rejected.any { it.timestampMs == 999999L })
    }

    @Test fun targetLayoutIsNinePointDeterministic() {
        assertEquals(9, CalibrationTargets.ALL.size)
        assertEquals(CalibrationTarget.BOTTOM_RIGHT, CalibrationTargets.ALL.last())
    }

    @Test fun serializationRoundTripAndCompatibilityRejection() {
        val model = calibrationModelFixture()
        val raw = model.toSerialized()
        val loaded = PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedTransform = transform)
        assertTrue(loaded is CalibrationLoadResult.Loaded)
        val restored = (loaded as CalibrationLoadResult.Loaded).model
        assertArrayEquals(model.coefficientsX, restored.coefficientsX, 0.0)
        assertArrayEquals(model.coefficientsY, restored.coefficientsY, 0.0)
        assertEquals(model.transformSignature, restored.transformSignature)
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedTransform = CoordinateTransform(1280, 720, CropRect(100, 50, 640, 480), Rotation.DEG_90, false)) is CalibrationLoadResult.Invalid)
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedFeatureSchemaVersion = 99, expectedTransform = transform) is CalibrationLoadResult.Invalid)
    }

    @Test fun corruptedSerializedModelRejected() {
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize("{not-json", expectedTransform = transform) is CalibrationLoadResult.Invalid)
    }

    @Test fun coefficientNonFinitenessIsRejected() {
        val size = QuadraticPolynomialFeatures.size(23)
        try {
            PersonalizedGazeCalibrationModel(
                1, 1, PersonalizedGazeCalibrationModel.MODEL_TYPE, 0.1, transform.signature,
                Standardization(DoubleArray(23), DoubleArray(23) { 1.0 }),
                DoubleArray(size) { if (it == 2) Double.NaN else 0.0 }, DoubleArray(size), metrics(), metrics(), createdAtMs = 1L,
            )
            throw AssertionError("expected coefficient rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun normalizedFixture(frameWidth: Int = 800, frameHeight: Int = 600, scale: Float = 120f): NormalizedBinocularEyeFeatures {
        fun eye(offset: Float) = NormalizedEyeFeatures(
            eyeCenterX = 0.4f + offset,
            eyeCenterY = 0.5f,
            irisCenterX = 0.42f + offset,
            irisCenterY = 0.5f,
            irisAlongAxis = 0.52f,
            irisPerpendicular = 0.02f,
            irisDiameterOverEyeWidth = 0.3f,
            eyelidOpening = 0.4f,
            ear = 0.38f,
            eyeCenterFromFaceCenterX = offset,
            eyeCenterFromFaceCenterY = 0f,
            quality = 0.95f,
        )
        val pose = HeadPoseEstimate(0f, 0f, 0f, 0f, 0f, frameWidth, frameHeight, scale, 0.95f, HeadPoseSource.MATRIX, true)
        return NormalizedBinocularEyeFeatures(eye(0.1f), eye(-0.1f), pose)
    }

    private fun sample(target: CalibrationTarget, timestamp: Long, sequence: Int): CalibrationSample {
        val base = timestamp % 997L
        val v = FloatArray(23) { i ->
            val phase = (sequence + i * 7 + target.ordinal * 13) % 101
            when (i) {
                0 -> target.x + phase * 0.0007f
                1 -> target.y + phase * 0.0005f
                7 -> target.x + (100 - phase) * 0.0006f
                8 -> target.y + (phase - 50) * 0.0004f
                17 -> (target.x - 0.5f) * 20f + phase * 0.01f
                18 -> (target.y - 0.5f) * 20f - phase * 0.008f
                19 -> (sequence - 49.5f) * 0.005f
                else -> ((base + phase + i * 3) % 97L).toFloat() * 0.01f
            }
        }
        return CalibrationSample(target, target.x, target.y, CalibrationFeatureVector(1, v), timestamp, 0.95f)
    }

    private fun calibrationFixtureSamples(totalPerTarget: Int): List<CalibrationSample> = buildList {
        var timestamp = 1L
        for (target in CalibrationTargets.ALL) repeat(totalPerTarget) { sequence -> add(sample(target, timestamp++, sequence)) }
    }

    private fun calibrationModelFixture(): PersonalizedGazeCalibrationModel {
        val size = QuadraticPolynomialFeatures.size(23)
        return PersonalizedGazeCalibrationModel(
            1,
            1,
            PersonalizedGazeCalibrationModel.MODEL_TYPE,
            0.1,
            transform.signature,
            Standardization(DoubleArray(23), DoubleArray(23) { 1.0 }),
            DoubleArray(size),
            DoubleArray(size),
            metrics(),
            metrics(),
            1920,
            1080,
            1L,
        )
    }

    private fun metrics() = CalibrationMetrics(0.01, 0.01, 0.02, 0.03, 0.01, 0.01, 720, 0, 20.0, 20.0, 30.0, 40.0)
}
