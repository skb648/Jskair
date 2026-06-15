package com.aircontrol.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Controls analysis frame rate adaptively:
 * - Full configured FPS when hand is detected
 * - Drops to scan mode (5 fps) after [noHandTimeoutMs] with no detection (battery saver)
 * - Instantly restores full FPS on detection
 */
class AdaptiveFpsController(
    private val scope: CoroutineScope,
    configuredFps: Int = DEFAULT_FPS,
    private val scanFps: Int = SCAN_FPS,
    private val noHandTimeoutMs: Long = 3000L,
) {
    private var configuredFps: Int = configuredFps.coerceToSupportedFps()

    private val _currentFps = MutableStateFlow(this.configuredFps)
    val currentFps: StateFlow<Int> = _currentFps

    private val _isHandDetected = MutableStateFlow(false)
    val isHandDetected: StateFlow<Boolean> = _isHandDetected

    private var downgradeJob: Job? = null

    val analysisIntervalMs: Long
        get() = 1000L / _currentFps.value.coerceAtLeast(1)

    fun onHandDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        _isHandDetected.value = true

        // Cancel any pending downgrade
        downgradeJob?.cancel()
        downgradeJob = null

        // Restore full FPS if we were in scan/thermal/battery saver mode
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Hand detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onHandLost(timestampMs: Long) {
        _isHandDetected.value = false

        // Schedule downgrade after timeout
        downgradeJob?.cancel()
        downgradeJob = scope.launch {
            delay(noHandTimeoutMs)
            _currentFps.value = scanFps
            Timber.d(
                "No hand since %d for %d ms - dropping to scan FPS: %d",
                timestampMs,
                noHandTimeoutMs,
                scanFps,
            )
        }
    }

    fun reset() {
        downgradeJob?.cancel()
        downgradeJob = null
        _currentFps.value = configuredFps
        _isHandDetected.value = false
    }

    /**
     * Updates the configured full-speed FPS. If currently at full-speed (not in
     * scan mode), apply it immediately; otherwise it will be restored on the
     * next hand detection.
     */
    fun updateConfiguredFps(fps: Int) {
        val oldConfiguredFps = configuredFps
        val validFps = fps.coerceToSupportedFps()
        configuredFps = validFps

        if (_currentFps.value == oldConfiguredFps || _currentFps.value > validFps) {
            _currentFps.value = validFps
        }
        Timber.d("Configured FPS updated to: %d", validFps)
    }

    private fun Int.coerceToSupportedFps(): Int = when {
        this <= 5 -> 5
        this <= 10 -> 10
        this <= 15 -> 15
        this <= 24 -> 24
        else -> 30
    }

    companion object {
        private const val DEFAULT_FPS = 24
        private const val SCAN_FPS = 5
    }
}
