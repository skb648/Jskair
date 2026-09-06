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

    private val downgradeJob = AtomicReference<Job?>(null)

    val analysisIntervalMs: Long
        get() = (1000f / _currentFps.value.coerceAtLeast(1)).toLong()

    fun onHandDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        _isHandDetected.value = true

        // Fix B-5: cancelling is cheap and idempotent (no coroutine is created),
        // but the old code also *re-launched* a job on every frame of every
        // state, 24 times a second, for a timer whose deadline should not move.
        downgradeJob.getAndSet(null)?.cancel()

        // Restore full FPS if we were in scan/thermal/battery saver mode
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Hand detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onHandLost(timestampMs: Long) {
        // Fix B-5 (battery): this used to re-arm the "no hand for N ms" timer on
        // *every* frame without a hand. Frames keep arriving while the camera runs
        // (that is the whole point of scan mode), so the deadline was pushed back
        // ~24 times a second and never expired: AirControl never dropped to
        // [scanFps] while you sat there with no hand in view, which is exactly the
        // drain this class exists to prevent. Arm the timer once and let it run to
        // completion.
        _isHandDetected.value = false

        // A downgrade is already counting down: leave it alone. This is the actual
        // fix - re-arming on each lost frame pushed the deadline forward forever,
        // so scan mode never engaged while the camera kept producing frames.
        if (downgradeJob.get() != null) return

        downgradeJob.set(scope.launch {
            delay(noHandTimeoutMs)
            downgradeJob.set(null)
            // A hand that came back in the meantime wins.
            if (_isHandDetected.value) return@launch
            _currentFps.value = scanFps
            Timber.d(
                "No hand since %d for %d ms - dropping to scan FPS: %d",
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
