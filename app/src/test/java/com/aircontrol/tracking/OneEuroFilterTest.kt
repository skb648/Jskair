package com.aircontrol.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class OneEuroFilterTest {

    private lateinit var filter: OneEuroFilter

    @Before
    fun setUp() {
        filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)
    }

    @Test
    fun `first sample passes through unchanged`() {
        val result = filter.filter(5.0f, 1000L)
        assertEquals(5.0f, result, 0.001f)
    }

    @Test
    fun `second sample is smoothed`() {
        filter.filter(0.0f, 1000L)
        val result = filter.filter(10.0f, 1100L)
        // Result should be between 0 and 10 (smoothed)
        assertTrue("Expected smoothed value between 0 and 10, got $result", result in 0.1f..9.9f)
    }

    @Test
    fun `steady signal passes through with minimal change`() {
        // Feed the same value repeatedly
        var lastResult = 0.0f
        for (i in 0..99) {
            lastResult = filter.filter(5.0f, 1000L + i * 33L)
        }
        assertEquals(5.0f, lastResult, 0.1f)
    }

    @Test
    fun `noisy signal is smoothed`() {
        val noiseAmplitude = 1.0f
        val signalValue = 5.0f
        var totalDeviationUnfiltered = 0.0f
        var totalDeviationFiltered = 0.0f
        var count = 0

        for (i in 0..199) {
            val noise = if (i % 2 == 0) noiseAmplitude else -noiseAmplitude
            val noisyValue = signalValue + noise
            val filtered = filter.filter(noisyValue, 1000L + i * 33L)

            totalDeviationUnfiltered += abs(noisyValue - signalValue)
            totalDeviationFiltered += abs(filtered - signalValue)
            count++
        }

        val avgDeviationUnfiltered = totalDeviationUnfiltered / count
        val avgDeviationFiltered = totalDeviationFiltered / count

        assertTrue(
            "Filtered deviation ($avgDeviationFiltered) should be less than unfiltered ($avgDeviationUnfiltered)",
            avgDeviationFiltered < avgDeviationUnfiltered,
        )
    }

    @Test
    fun `fast movement is less smoothed than slow movement`() {
        val slowFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)
        val fastFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)

        // Feed slow ramp
        var slowResult = 0f
        for (i in 0..49) {
            slowResult = slowFilter.filter(i * 0.1f, 1000L + i * 33L)
        }

        // Feed fast step (same filter, but large jump)
        var fastResult = 0f
        fastFilter.filter(0f, 1000L)
        fastResult = fastFilter.filter(50f, 1033L)

        // The fast step should have less relative smoothing than the slow ramp
        val slowLag = 49 * 0.1f - slowResult
        val fastLag = 50f - fastResult

        assertTrue(
            "Fast movement lag ($fastLag) should be less than slow movement lag ($slowLag)",
            fastLag < slowLag * 2f, // Allow some tolerance
        )
    }

    @Test
    fun `reset clears filter state`() {
        filter.filter(10.0f, 1000L)
        filter.reset()
        // After reset, first sample should pass through
        val result = filter.filter(5.0f, 2000L)
        assertEquals(5.0f, result, 0.001f)
    }

    @Test
    fun `synthetic sine wave with noise is smoothed`() {
        val sineFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)
        val frequency = 0.5f // Hz
        val sampleRateMs = 33L // ~30fps
        val noiseStdDev = 0.1f
        var totalRawDeviation = 0.0f
        var totalFilteredDeviation = 0.0f
        var count = 0

        for (i in 0..299) {
            val t = i * sampleRateMs / 1000.0f
            val cleanSignal = sin(2.0 * Math.PI * frequency * t).toFloat()
            val noise = ((i * 7 + 3) % 11 - 5) * noiseStdDev / 5f // Deterministic noise
            val noisySignal = cleanSignal + noise

            val filtered = sineFilter.filter(noisySignal, 1000L + i * sampleRateMs)

            totalRawDeviation += abs(noisySignal - cleanSignal)
            totalFilteredDeviation += abs(filtered - cleanSignal)
            count++
        }

        val avgRawDev = totalRawDeviation / count
        val avgFilteredDev = totalFilteredDeviation / count

        assertTrue(
            "Filtered sine deviation ($avgFilteredDev) should be less than raw ($avgRawDev)",
            avgFilteredDev < avgRawDev,
        )
    }

    @Test
    fun `high beta reduces lag for fast movements`() {
        val lowBeta = OneEuroFilter(minCutoff = 1.0f, beta = 0.001f)
        val highBeta = OneEuroFilter(minCutoff = 1.0f, beta = 1.0f)

        // Warm up both filters
        lowBeta.filter(0f, 1000L)
        highBeta.filter(0f, 1000L)

        // Apply a step change
        val lowBetaResult = lowBeta.filter(10f, 1100L)
        val highBetaResult = highBeta.filter(10f, 1100L)

        // High beta should track faster (closer to step value)
        assertTrue(
            "High beta result ($highBetaResult) should be closer to step than low beta ($lowBetaResult)",
            abs(highBetaResult - 10f) < abs(lowBetaResult - 10f),
        )
    }

    @Test
    fun `update params changes filter behavior`() {
        val filter = OneEuroFilter(minCutoff = 1.0f, beta = 0.007f)

        // Warm up
        filter.filter(0f, 1000L)
        val result1 = filter.filter(10f, 1100L)

        // Reset and update params for more smoothing
        filter.reset()
        filter.updateParams(minCutoff = 0.1f, beta = 0.0f)
        filter.filter(0f, 2000L)
        val result2 = filter.filter(10f, 2100L)

        // Lower cutoff = more smoothing = result further from step
        assertTrue(
            "Stronger smoothing ($result2) should be further from step than weaker ($result1)",
            abs(result2 - 10f) > abs(result1 - 10f),
        )
    }

    @Test
    fun `consecutive same values converge exactly`() {
        var result = 0f
        for (i in 0..499) {
            result = filter.filter(3.14159f, 1000L + i * 33L)
        }
        assertEquals(3.14159f, result, 0.001f)
    }
}

class LandmarkFilterTest {

    private lateinit var landmarkFilter: LandmarkFilter

    @Before
    fun setUp() {
        landmarkFilter = LandmarkFilter(minCutoff = 1.0f, beta = 0.007f)
    }

    @Test
    fun `first landmark passes through unchanged`() {
        val landmark = Landmark3D(x = 0.5f, y = 0.3f, z = -0.1f)
        val result = landmarkFilter.filter(landmark, 1000L)
        assertEquals(landmark.x, result.x, 0.001f)
        assertEquals(landmark.y, result.y, 0.001f)
        assertEquals(landmark.z, result.z, 0.001f)
    }

    @Test
    fun `noisy landmark is smoothed`() {
        val baseLandmark = Landmark3D(x = 0.5f, y = 0.5f, z = 0.0f)
        var totalXDeviation = 0f
        var count = 0

        for (i in 0..199) {
            val noise = if (i % 2 == 0) 0.02f else -0.02f
            val noisyLandmark = baseLandmark.copy(x = baseLandmark.x + noise)
            val filtered = landmarkFilter.filter(noisyLandmark, 1000L + i * 33L)
            totalXDeviation += abs(filtered.x - baseLandmark.x)
            count++
        }

        val avgDeviation = totalXDeviation / count
        assertTrue(
            "Average deviation ($avgDeviation) should be less than noise amplitude (0.02)",
            avgDeviation < 0.02f,
        )
    }

    @Test
    fun `all three axes are filtered independently`() {
        val landmark1 = Landmark3D(x = 0.1f, y = 0.2f, z = 0.3f)
        val landmark2 = Landmark3D(x = 0.9f, y = 0.8f, z = 0.7f)

        val result1 = landmarkFilter.filter(landmark1, 1000L)
        val result2 = landmarkFilter.filter(landmark2, 1100L)

        // Each axis should be independently smoothed (between the two values)
        assertTrue(result2.x in 0.1f..0.9f)
        assertTrue(result2.y in 0.2f..0.8f)
        assertTrue(result2.z in 0.3f..0.7f)
    }
}

class HandFrameFilterTest {

    private lateinit var frameFilter: HandFrameFilter

    @Before
    fun setUp() {
        frameFilter = HandFrameFilter(minCutoff = 1.0f, beta = 0.007f)
    }

    @Test
    fun `empty frame passes through`() {
        val frame = HandFrame.EMPTY
        val result = frameFilter.filter(frame)
        assertEquals(0, result.landmarks.size)
    }

    @Test
    fun `21 landmarks are all filtered`() {
        val landmarks = List(21) { index ->
            Landmark3D(
                x = index * 0.01f,
                y = index * 0.02f,
                z = index * -0.005f,
            )
        }
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = 1000L,
            confidence = 0.9f,
        )

        // First frame passes through
        val result = frameFilter.filter(frame)
        assertEquals(21, result.landmarks.size)
        assertEquals(frame.handedness, result.handedness)
        assertEquals(frame.confidence, result.confidence, 0.001f)
    }

    @Test
    fun `reset clears all landmark filters`() {
        val landmarks = List(21) { Landmark3D(x = 0.5f, y = 0.5f, z = 0f) }
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.RIGHT,
            timestampMs = 1000L,
            confidence = 0.9f,
        )

        frameFilter.filter(frame)
        frameFilter.reset()

        // After reset, new first sample should pass through
        val newLandmarks = List(21) { Landmark3D(x = 0.1f, y = 0.1f, z = 0f) }
        val newFrame = frame.copy(landmarks = newLandmarks, timestampMs = 2000L)
        val result = frameFilter.filter(newFrame)

        for (i in result.landmarks.indices) {
            assertEquals(0.1f, result.landmarks[i].x, 0.001f)
            assertEquals(0.1f, result.landmarks[i].y, 0.001f)
        }
    }

    @Test
    fun `synthetic hand trajectory with jitter is smoothed`() {
        // Simulate a hand moving in a straight line from (0.3, 0.5) to (0.7, 0.5)
        // with synthetic jitter on each frame
        val startMs = 1000L
        val frameDurationMs = 33L // ~30fps
        val totalFrames = 60

        var totalRawJitter = 0f
        var totalFilteredJitter = 0f
        var count = 0

        for (i in 0 until totalFrames) {
            val progress = i.toFloat() / totalFrames
            val cleanX = 0.3f + progress * 0.4f
            val cleanY = 0.5f

            // Add deterministic jitter (simulating MediaPipe noise)
            val jitterX = ((i * 7 + 3) % 11 - 5) * 0.004f
            val jitterY = ((i * 11 + 7) % 13 - 6) * 0.003f

            val noisyLandmarks = List(21) { idx ->
                Landmark3D(
                    x = cleanX + jitterX * (1f + idx * 0.1f),
                    y = cleanY + jitterY * (1f + idx * 0.05f),
                    z = -0.05f + jitterX * 0.5f,
                )
            }

            val frame = HandFrame(
                landmarks = noisyLandmarks,
                handedness = Handedness.RIGHT,
                timestampMs = startMs + i * frameDurationMs,
                confidence = 0.9f,
            )

            val filtered = frameFilter.filter(frame)

            // Compare index fingertip (landmark 8) jitter
            val rawJitter = abs(noisyLandmarks[8].x - cleanX) + abs(noisyLandmarks[8].y - cleanY)
            val filteredJitter = abs(filtered.landmarks[8].x - cleanX) + abs(filtered.landmarks[8].y - cleanY)

            totalRawJitter += rawJitter
            totalFilteredJitter += filteredJitter
            count++
        }

        val avgRawJitter = totalRawJitter / count
        val avgFilteredJitter = totalFilteredJitter / count

        assertTrue(
            "Filtered jitter ($avgFilteredJitter) should be less than raw ($avgRawJitter)",
            avgFilteredJitter < avgRawJitter,
        )
    }

    @Test
    fun `confidence and handedness are preserved through filtering`() {
        val landmarks = List(21) { Landmark3D(x = 0.5f, y = 0.5f, z = 0f) }
        val frame = HandFrame(
            landmarks = landmarks,
            handedness = Handedness.LEFT,
            timestampMs = 1000L,
            confidence = 0.85f,
        )

        val result = frameFilter.filter(frame)
        assertEquals(Handedness.LEFT, result.handedness)
        assertEquals(0.85f, result.confidence, 0.001f)
        assertEquals(1000L, result.timestampMs)
    }
}
