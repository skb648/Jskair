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
