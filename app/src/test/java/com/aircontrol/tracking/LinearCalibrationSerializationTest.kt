package com.aircontrol.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinearCalibrationSerializationTest {
    @Test
    fun linearModelRoundTripsAndRetainsBasisType() {
        val dimension = GazeCalibrationFeatureSchema.DIMENSION
        val coefficients = PersonalizedGazeCalibrationModel.basisSize(
            PersonalizedGazeCalibrationModel.LINEAR_MODEL_TYPE,
            dimension,
        )
        val metrics = CalibrationMetrics(
            0.01, 0.01, 0.02, 0.03, 0.01, 0.01,
            sampleCount = 100, validationSampleCount = 20,
        )
        val model = PersonalizedGazeCalibrationModel(
            modelVersion = PersonalizedGazeCalibrationModel.MODEL_VERSION,
            featureSchemaVersion = GazeCalibrationFeatureSchema.VERSION,
            modelType = PersonalizedGazeCalibrationModel.LINEAR_MODEL_TYPE,
            regularization = 0.1,
            transformSignature = "sig-v1",
            standardization = Standardization(
                means = DoubleArray(dimension),
                stdDevs = DoubleArray(dimension) { 1.0 },
            ),
            coefficientsX = DoubleArray(coefficients) { it * 0.001 },
            coefficientsY = DoubleArray(coefficients) { it * 0.002 },
            trainingMetrics = metrics,
            validationMetrics = metrics,
            screenWidthPx = 1920,
            screenHeightPx = 1080,
            createdAtMs = 100L,
        )

        val raw = model.toSerialized()
        val result = PersonalizedGazeCalibrationSerializer.deserialize(
            raw,
            expectedTransformSignature = "sig-v1",
        )
        assertTrue(result is CalibrationLoadResult.Loaded)
        val restored = (result as CalibrationLoadResult.Loaded).model
        assertEquals(PersonalizedGazeCalibrationModel.LINEAR_MODEL_TYPE, restored.modelType)
        assertArrayEquals(model.coefficientsX, restored.coefficientsX, 0.0)
        assertArrayEquals(model.coefficientsY, restored.coefficientsY, 0.0)
        assertEquals(24, restored.coefficientsX.size)
    }
}
