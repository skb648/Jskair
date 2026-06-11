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
    private val configuredFps: Int = 24,
    private val scanFps: Int = 5,
    private val noHandTimeoutMs: Long = 3000L,
) {
    private val _currentFps = MutableStateFlow(configuredFps)
    val currentFps: StateFlow<Int> = _currentFps

    private val _isHandDetected = MutableStateFlow(false)
    val isHandDetected: StateFlow<Boolean> = _isHandDetected

    private var downgradeJob: Job? = null
    private var lastDetectionTimestampMs: Long = 0L

    val analysisIntervalMs: Long
        get() = 1000L / _currentFps.value

    fun onHandDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        _isHandDetected.value = true
        lastDetectionTimestampMs = timestampMs

        // Cancel any pending downgrade
        downgradeJob?.cancel()
        downgradeJob = null

        // Restore full FPS if we were in scan mode
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Hand detected - restoring full FPS: %d", configuredFps)
        }
    }

    fun onHandLost(timestampMs: Long) {
        _isHandDetected.value = false
        lastDetectionTimestampMs = timestampMs

        // Schedule downgrade after timeout
        downgradeJob?.cancel()
        downgradeJob = scope.launch {
            delay(noHandTimeoutMs)
            _currentFps.value = scanFps
            Timber.d("No hand for %d ms - dropping to scan FPS: %d", noHandTimeoutMs, scanFps)
        }
    }

    fun reset() {
        downgradeJob?.cancel()
        downgradeJob = null
        _currentFps.value = configuredFps
        _isHandDetected.value = false
        lastDetectionTimestampMs = 0L
    }

    fun updateConfiguredFps(fps: Int) {
        val validFps = if (fps in setOf(15, 24, 30)) fps else 24
        if (_currentFps.value == configuredFps) {
            _currentFps.value = validFps
        }
        Timber.d("Configured FPS updated to: %d", validFps)
    }
}
