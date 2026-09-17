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

/** Controls adaptive analysis FPS without ever rounding a caller's cap upward. */
class AdaptiveFpsController(
    private val scope: CoroutineScope,
    configuredFps: Int = DEFAULT_FPS,
    private val scanFps: Int = SCAN_FPS,
    private val noHandTimeoutMs: Long = 5000L,
) {
    @Volatile
    private var configuredFps: Int = coerceToSupportedFps(configuredFps)

    private val _currentFps = MutableStateFlow(this.configuredFps)
    val currentFps: StateFlow<Int> = _currentFps

    private val _isHandDetected = MutableStateFlow(false)
    val isHandDetected: StateFlow<Boolean> = _isHandDetected

    @Volatile private var isHandPresent = false
    @Volatile private var isFacePresent = false
    private val isUserPresent: Boolean get() = isHandPresent || isFacePresent
    private val downgradeJob = AtomicReference<Job?>(null)

    val analysisIntervalMs: Long
        get() = (1000f / _currentFps.value.coerceAtLeast(1)).toLong()

    fun onHandDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        isHandPresent = true
        _isHandDetected.value = true
        downgradeJob.getAndSet(null)?.cancel()
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Hand detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onFaceDetected(timestampMs: Long) {
        val wasInScanMode = _currentFps.value != configuredFps
        isFacePresent = true
        downgradeJob.getAndSet(null)?.cancel()
        if (wasInScanMode) {
            _currentFps.value = configuredFps
            Timber.d("Face detected at %d - restoring full FPS: %d", timestampMs, configuredFps)
        }
    }

    fun onHandLost(timestampMs: Long) {
        isHandPresent = false
        _isHandDetected.value = false
        scheduleDowngrade(timestampMs)
    }

    fun onFaceLost(timestampMs: Long) {
        isFacePresent = false
        scheduleDowngrade(timestampMs)
    }

    private fun scheduleDowngrade(timestampMs: Long) {
        if (isUserPresent || downgradeJob.get() != null) return
        downgradeJob.set(scope.launch {
            delay(noHandTimeoutMs)
            downgradeJob.set(null)
            if (isUserPresent) return@launch
            val idleFps = minOf(scanFps, configuredFps)
            _currentFps.value = idleFps
            Timber.d(
                "No user interaction since %d for %d ms - dropping to scan FPS: %d",
                timestampMs,
                noHandTimeoutMs,
                idleFps,
            )
        })
    }

    fun reset() {
        downgradeJob.getAndSet(null)?.cancel()
        _currentFps.value = configuredFps
        isHandPresent = false
        isFacePresent = false
        _isHandDetected.value = false
    }

    fun updateConfiguredFps(fps: Int) {
        val oldConfiguredFps = configuredFps
        val validFps = coerceToSupportedFps(fps)
        configuredFps = validFps
        if (_currentFps.value == oldConfiguredFps || _currentFps.value > validFps) {
            _currentFps.value = validFps
        }
        Timber.d("Configured FPS updated to: %d (requested=%d)", validFps, fps)
        com.aircontrol.runtime.PerfTelemetry.recordConfiguredFps(validFps)
    }

    companion object {
        private const val DEFAULT_FPS = 24
        private const val SCAN_FPS = 10
        private val SUPPORTED_FPS = intArrayOf(5, 10, 15, 24, 30)

        /** Highest supported operating point that does not exceed [requested]. */
        internal fun coerceToSupportedFps(requested: Int): Int {
            if (requested <= SUPPORTED_FPS.first()) return SUPPORTED_FPS.first()
            return SUPPORTED_FPS.lastOrNull { it <= requested } ?: SUPPORTED_FPS.first()
        }
    }
}
