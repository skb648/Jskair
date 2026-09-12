package com.aircontrol.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

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
    // Bug #24 Fix: Increased from 3000ms (3s) to 5000ms (5s). The old 3-second
    // timeout was too aggressive — natural hand absences like reaching for a
    // drink, scratching an itch, or briefly dropping the hand below the camera
    // would instantly drop the system to the laggy 5 FPS scan mode. When the
    // hand returned, the scan-mode detection latency (up to 200ms at 5fps) made
    // the system feel unresponsive. 5 seconds is long enough to tolerate natural
    // micro-absences without dropping to scan mode, while still saving battery
    // during genuine "walked away from the phone" scenarios.
    private val noHandTimeoutMs: Long = 5000L,
) {
    @Volatile
    private var configuredFps: Int = configuredFps.coerceToSupportedFps()

    private val _currentFps = MutableStateFlow(this.configuredFps)
    val currentFps: StateFlow<Int> = _currentFps

    private val _isHandDetected = MutableStateFlow(false)
    val isHandDetected: StateFlow<Boolean> = _isHandDetected

    @Volatile
    private var isHandPresent = false
    @Volatile
    private var isFacePresent = false

    private val isUserPresent: Boolean
        get() = isHandPresent || isFacePresent

    private val downgradeJob = AtomicReference<Job?>(null)

    val analysisIntervalMs: Long
        get() = (1000f / _currentFps.value.coerceAtLeast(1)).toLong()

    fun onHandDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        isHandPresent = true
        _isHandDetected.value = true

        downgradeJob.getAndSet(null)?.cancel()

        // Restore full FPS if we were in scan/thermal/battery saver mode
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Hand detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onFaceDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        isFacePresent = true

        downgradeJob.getAndSet(null)?.cancel()

        // Restore full FPS if we were in scan/thermal/battery saver mode
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Face detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onHandLost(timestampMs: Long) {
        isHandPresent = false
        _isHandDetected.value = false

        if (isUserPresent) return
        if (downgradeJob.get() != null) return

        downgradeJob.set(scope.launch {
            delay(noHandTimeoutMs)
            downgradeJob.set(null)
            // A user presence that came back in the meantime wins.
            if (isUserPresent) return@launch
            _currentFps.value = scanFps
            Timber.d(
                "No user interaction since %d for %d ms - dropping to scan FPS: %d",
                timestampMs,
                noHandTimeoutMs,
                scanFps,
            )
        })
    }

    fun onFaceLost(timestampMs: Long) {
        isFacePresent = false

        if (isUserPresent) return
        if (downgradeJob.get() != null) return

        downgradeJob.set(scope.launch {
            delay(noHandTimeoutMs)
            downgradeJob.set(null)
            if (isUserPresent) return@launch
            _currentFps.value = scanFps
            Timber.d(
                "No user interaction since %d for %d ms - dropping to scan FPS: %d",
                timestampMs,
                noHandTimeoutMs,
                scanFps,
            )
        })
    }

    fun reset() {
        downgradeJob.get()?.cancel()
        downgradeJob.set(null)
        _currentFps.value = configuredFps
        isHandPresent = false
        isFacePresent = false
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
        // Perf audit P18: observe configured-vs-actual for the telemetry
        // snapshot. Controller logic itself is untouched.
        com.aircontrol.runtime.PerfTelemetry.recordConfiguredFps(validFps)
    }

    private fun Int.coerceToSupportedFps(): Int {
        val supported = listOf(5, 10, 15, 24, 30)
        return supported.minByOrNull { kotlin.math.abs(it - this) } ?: 30
    }

    companion object {
        private const val DEFAULT_FPS = 24

        // Fix (audit #18): 5 FPS idle scan made hand RE-acquisition feel laggy
        // (up to 200ms before the hand is even seen). 10 FPS halves that worst
        // case for a modest idle-cost increase — reacquisition now feels
        // immediate while still saving most of the full-speed battery.
        private const val SCAN_FPS = 10
    }
}
