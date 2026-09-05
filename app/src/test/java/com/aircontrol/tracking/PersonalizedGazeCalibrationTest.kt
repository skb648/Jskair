package com.aircontrol.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedGazeCalibrationTest {
    @Test fun featureVectorOrderingIsDeterministic() {
        val a = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())!!
        val b = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())!!
        assertEquals(GazeCalibrationFeatureSchema.VERSION, a.schemaVersion)
        assertEquals(GazeCalibrationFeatureSchema.DIMENSION, a.values.size)
        assertArrayEquals(a.values, b.values, 0f)
        assertEquals("left_iris_along", GazeCalibrationFeatureSchema.NAMES[0])
        assertEquals("head_yaw_deg", GazeCalibrationFeatureSchema.NAMES[17])
    }

    @Test fun featureSchemaVersionIsStable() = assertEquals(1, GazeCalibrationFeatureSchema.VERSION)

    @Test fun validSampleCreation() {
        val vector = GazeCalibrationFeatureVectorBuilder.from(normalizedFixture())
        val result = CalibrationSample.create(CalibrationTarget.CENTER, vector, 100L, 0.9f, poseValid = true)
        assertTrue(result is CalibrationSample.Result.Accepted)
    }

    @Test fun invalidSampleRejectedWithoutFabrication() {
        val result = CalibrationSample.create(CalibrationTarget.CENTER, null, 100L, 0.9f, poseValid = true)
        assertTrue(result is CalibrationSample.Result.Rejected)
        assertEquals(CalibrationSample.Companion.RejectionReason.INVALID_FEATURES, (result as CalibrationSample.Result.Rejected).reason)
    }

    @Test fun nonFiniteFeatureRejected() {
        assertFails { CalibrationFeatureVector(GazeCalibrationFeatureSchema.VERSION, FloatArray(GazeCalibrationFeatureSchema.DIMENSION) { if (it == 3) Float.NaN else 0f }) }
    }

    @Test fun insufficientSampleFittingFailsExplicitly() {
        val result = PersonalizedGazeCalibrationFitter.fit(
            rawSamples = listOf(sample(CalibrationTarget.CENTER, 0)), transformSignature = "sig-v1", createdAtMs = 1L,
        )
        assertFalse(result.isSuccess)
        assertTrue(result.failure!!.contains("insufficient"))
    }

    @Test fun polynomialFeatureGenerationIsStable() {
        val input = DoubleArray(GazeCalibrationFeatureSchema.DIMENSION) { it + 2.0 }
        val expanded = QuadraticPolynomialFeatures.expand(input)
        assertEquals(1 + GazeCalibrationFeatureSchema.DIMENSION + 23 * 24 / 2, expanded.size)
        assertEquals(1.0, expanded[0], 0.0)
        assertEquals(2.0, expanded[1], 0.0)
        assertEquals(3.0, expanded[2], 0.0)
        assertEquals(4.0, expanded[24], 0.0)
        assertEquals(6.0, expanded[25], 0.0)
    }

    @Test fun ridgeRegularizationProducesFiniteModelOnCollinearData() {
        val fit = PersonalizedGazeCalibrationFitter.fit(calibrationFixtureSamples(), regularization = 0.1, transformSignature = "sig-v1", createdAtMs = 10L)
        assertTrue(fit.isSuccess)
        assertTrue(fit.model!!.coefficientsX.all { it.isFinite() })
    }

    @Test fun deterministicFittingAndPrediction() {
        val samples = calibrationFixtureSamples()
        val a = PersonalizedGazeCalibrationFitter.fit(samples, regularization = 0.1, transformSignature = "sig-v1", createdAtMs = 10L).model!!
        val b = PersonalizedGazeCalibrationFitter.fit(samples, regularization = 0.1, transformSignature = "sig-v1", createdAtMs = 10L).model!!
        assertArrayEquals(a.coefficientsX, b.coefficientsX, 0.0)
        assertArrayEquals(a.coefficientsY, b.coefficientsY, 0.0)
        assertEquals(a.predict(samples.first().features), b.predict(samples.first().features))
    }

    @Test fun coefficientNonFinitenessIsRejected() {
        val size = QuadraticPolynomialFeatures.size(GazeCalibrationFeatureSchema.DIMENSION)
        assertFails {
            PersonalizedGazeCalibrationModel(
                1, 1, PersonalizedGazeCalibrationModel.MODEL_TYPE, 0.1, "sig-v1",
                Standardization(DoubleArray(23), DoubleArray(23) { 1.0 }),
                DoubleArray(size) { if (it == 2) Double.NaN else 0.0 }, DoubleArray(size), metrics(), metrics(), createdAtMs = 1L,
            )
        }
    }

    @Test fun validationSplitIsTargetBalancedAndSeparated() {
        val split = TargetBalancedValidationSplit.split(calibrationFixtureSamples(), 0.20)
        assertEquals(144, split.training.size)
        assertEquals(36, split.validation.size)
        assertTrue(split.training.groupBy { it.target }.values.all { it.size == 16 })
        assertTrue(split.validation.groupBy { it.target }.values.all { it.size == 4 })
        assertTrue(split.training.none { t -> split.validation.any { it.timestampMs == t.timestampMs && it.target == t.target } })
    }

    @Test fun trainingAndValidationMetricsAreSeparate() {
        val model = PersonalizedGazeCalibrationFitter.fit(calibrationFixtureSamples(), transformSignature = "sig-v1", createdAtMs = 1L).model!!
        assertEquals(144, model.trainingMetrics.sampleCount)
        assertEquals(0, model.trainingMetrics.validationSampleCount)
        assertEquals(36, model.validationMetrics.sampleCount)
        assertEquals(36, model.validationMetrics.validationSampleCount)
        assertTrue(model.validationMetrics.meanNormalizedError.isFinite())
        assertTrue(model.validationMetrics.p95NormalizedError.isFinite())
    }

    @Test fun robustAggregationRejectsExtremeOutlier() {
        val normal = calibrationFixtureSamples().filter { it.target == CalibrationTarget.CENTER }.take(15)
        val outlier = sample(CalibrationTarget.CENTER, 10000, valueOffset = 50f)
        val result = RobustCalibrationSampleAggregator.filter(normal + outlier)
        assertTrue(result.rejected.any { it.timestampMs == outlier.timestampMs })
    }

    @Test fun ninePointTargetsAreDeterministic() {
        assertEquals(9, CalibrationTargets.ALL.size)
        assertEquals(listOf(0.1f,0.5f,0.9f), CalibrationTargets.ALL.take(3).map { it.x })
        assertEquals(CalibrationTarget.BOTTOM_RIGHT, CalibrationTargets.ALL.last())
    }

    @Test fun serializationRoundTripAndCompatibilityValidation() {
        val model = calibrationModelFixture()
        val raw = model.toSerialized()
        val loaded = PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedTransformSignature = "sig-v1")
        assertTrue(loaded is CalibrationLoadResult.Loaded)
        val restored = (loaded as CalibrationLoadResult.Loaded).model
        assertArrayEquals(model.coefficientsX, restored.coefficientsX, 0.0)
        assertArrayEquals(model.coefficientsY, restored.coefficientsY, 0.0)
        assertEquals(model.transformSignature, restored.transformSignature)
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedTransformSignature = "other") is CalibrationLoadResult.Invalid)
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedFeatureSchemaVersion = 99, expectedTransformSignature = "sig-v1") is CalibrationLoadResult.Invalid)
    }

    @Test fun corruptedSerializedModelRejected() {
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize("{not-json", expectedTransformSignature = "sig-v1") is CalibrationLoadResult.Invalid)
    }

    @Test fun modelVersionCompatibilityIsExplicit() {
        val raw = calibrationModelFixture().toSerialized()
        assertTrue(PersonalizedGazeCalibrationSerializer.deserialize(raw, expectedModelVersion = 99, expectedTransformSignature = "sig-v1") is CalibrationLoadResult.Invalid)
    }

    @Test fun pixelErrorConversionIsPresentWhenScreenDimensionsAreProvided() {
        val model = PersonalizedGazeCalibrationFitter.fit(
            calibrationFixtureSamples(), transformSignature = "sig-v1", screenWidthPx = 1920, screenHeightPx = 1080, createdAtMs = 1L,
        ).model!!
        assertTrue(model.validationMetrics.meanPixelError!!.isFinite())
        assertTrue(model.validationMetrics.p95PixelError!!.isFinite())
        assertTrue(model.validationMetrics.maxPixelError!!.isFinite())
        assertTrue(model.validationMetrics.meanPixelError!! >= model.validationMetrics.meanNormalizedError)
    }

    @Test fun scaleNormalizedFeatureConsistency() {
        val a = normalizedFixture(frameWidth = 800, frameHeight = 600, scale = 120f, translationX = 80f, translationY = 60f)
        val b = normalizedFixture(frameWidth = 1600, frameHeight = 1200, scale = 240f, translationX = 160f, translationY = 120f)
        val va = GazeCalibrationFeatureVectorBuilder.from(a)!!
        val vb = GazeCalibrationFeatureVectorBuilder.from(b)!!
        assertArrayEquals(va.values, vb.values, 1e-6f)
    }

    private fun normalizedFixture(frameWidth: Int = 800, frameHeight: Int = 600, scale: Float = 120f, translationX: Float = 0f, translationY: Float = 0f): NormalizedBinocularEyeFeatures {
        fun eye(offset: Float) = NormalizedEyeFeatures(
            eyeCenterX = 0.4f + offset, eyeCenterY = 0.5f, irisCenterX = 0.42f + offset, irisCenterY = 0.5f,
            irisAlongAxis = 0.52f, irisPerpendicular = 0.02f, irisDiameterOverEyeWidth = 0.3f,
            eyelidOpening = 0.4f, ear = 0.38f, eyeCenterFromFaceCenterX = offset, eyeCenterFromFaceCenterY = 0f, quality = 0.95f,
        )
        val pose = HeadPoseEstimate(0f, 0f, 0f, translationX, translationY, frameWidth, frameHeight, scale, 0.95f, HeadPoseSource.MATRIX, true)
        return NormalizedBinocularEyeFeatures(eye(0.1f), eye(-0.1f), pose)
    }

    private fun sample(target: CalibrationTarget, timestamp: Long, valueOffset: Float = 0f): CalibrationSample {
        val v = FloatArray(GazeCalibrationFeatureSchema.DIMENSION) { i -> when (i) { 0 -> target.x + valueOffset; 1 -> target.y + valueOffset; 7 -> target.x; 8 -> target.y; 17 -> (target.x - .5f) * 20f; 18 -> (target.y - .5f) * 20f; else -> (i + 1) * 0.01f } }
        return CalibrationSample(target, target.x, target.y, CalibrationFeatureVector(GazeCalibrationFeatureSchema.VERSION, v), timestamp, 0.95f)
    }

    private fun calibrationFixtureSamples(): List<CalibrationSample> = buildList {
        var timestamp = 1L
        for (target in CalibrationTargets.ALL) repeat(20) { index -> add(sample(target, timestamp++, valueOffset = (index - 9.5f) * 0.0001f)) }
    }

    private fun calibrationModelFixture(): PersonalizedGazeCalibrationModel {
        val size = QuadraticPolynomialFeatures.size(GazeCalibrationFeatureSchema.DIMENSION)
        return PersonalizedGazeCalibrationModel(1, 1, PersonalizedGazeCalibrationModel.MODEL_TYPE, 0.1, "sig-v1",
            Standardization(DoubleArray(23), DoubleArray(23) { 1.0 }), DoubleArray(size), DoubleArray(size), metrics(), metrics(), 1920, 1080, 1L)
    }

    private fun metrics() = CalibrationMetrics(0.01, 0.01, 0.02, 0.03, 0.01, 0.01, 144, 0, 10.0, 10.0, 20.0, 30.0)
    private fun assertFails(block: () -> Unit) { try { block(); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) {} }
}
