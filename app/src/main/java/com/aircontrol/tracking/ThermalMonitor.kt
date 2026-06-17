package com.aircontrol.tracking

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Monitors device thermal status and reports it as a flow.
 *
 * Thermal thresholds (Bug #5 Fix — graduated degradation instead of pause on SEVERE):
 * - LIGHT       → Proactive FPS reduction to 2/3 of configured (only if not already throttled).
 * - MODERATE    → Reduce FPS to 1/2 of configured (clamped 5..15).
 * - SEVERE      → Dynamic frame skipping: reduce FPS to 5 FPS, but keep tracking alive.
 *                 Notification reads "Performance reduced due to heat". No pauseTracking().
 * - CRITICAL    → Pause tracking entirely (covers PowerManager CRITICAL/EMERGENCY/SHUTDOWN).
 * - NORMAL/None → Resume normal operation (with a 30s gradual FPS ramp).
 *
 * On API < 29 (before PowerManager thermal status API), polling is skipped
 * and the monitor reports THERMAL_NONE always.
 */
enum class ThermalStatus {
    NONE,       // No thermal stress
    LIGHT,      // Light throttling
    MODERATE,   // Moderate throttling - reduce FPS to 1/2
    SEVERE,     // Severe throttling - dynamic frame skipping at 5 FPS (no pause)
    CRITICAL,   // Critical/Emergency/Shutdown - pause tracking entirely
}

class ThermalMonitor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
    private val pollingIntervalMs: Long = 5000L,
) {
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NONE)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    private var monitoringJob: Job? = null

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Timber.i("Thermal status API not available (API < 29), skipping monitoring")
            return
        }
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch(Dispatchers.Default) {
            while (true) {
                checkThermalStatus()
                delay(pollingIntervalMs)
            }
        }
        Timber.i("Thermal monitoring started")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        _thermalStatus.value = ThermalStatus.NONE
        Timber.i("Thermal monitoring stopped")
    }

    private fun checkThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val status = try {
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thermal status")
            PowerManager.THERMAL_STATUS_NONE
        }

        val thermalStatus = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            // Bug #5 Fix: SEVERE is now a "frame-skip" state, not a pause state.
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            // Critical / Emergency / Shutdown — pause tracking entirely to protect device.
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
            else -> ThermalStatus.NONE
        }

        if (_thermalStatus.value != thermalStatus) {
            Timber.i("Thermal status changed: %s → %s", _thermalStatus.value, thermalStatus)
            _thermalStatus.value = thermalStatus
        }
    }
}
