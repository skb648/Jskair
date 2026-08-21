package com.aircontrol.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
 * On API 29+ uses [PowerManager.currentThermalStatus].
 * On API 26–28 (where the thermal-status API is unavailable), falls back to
 * temperature heuristics from [BatteryManager] so thermal protection isn't dead
 * on those devices (fix #23).
 */
enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

class ThermalMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    // Fix #41: default interval 5s; callers that want desync pass an explicit value.
    private val pollingIntervalMs: Long = 5000L,
) {
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NONE)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    private var monitoringJob: Job? = null

    // Battery-temperature based fallback receiver for API < 29.
    private var batteryTempReceiver: BroadcastReceiver? = null

    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch(Dispatchers.Default) {
            while (true) {
                checkThermalStatus()
                delay(pollingIntervalMs)
            }
        }

        // On older APIs, also listen for battery temperature changes to get
        // coarse thermal data between polls.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            registerBatteryTempFallback()
        }

        Timber.i("Thermal monitoring started (interval=%dms, API=%d)", pollingIntervalMs, Build.VERSION.SDK_INT)
    }

    /**
     * @param resetStatus If true, status is reset to NONE (useful for tests).
     *   Pass false from production shutdown paths to avoid spurious "recovered"
     *   transitions (fix #43).
     */
    fun stopMonitoring(resetStatus: Boolean = true) {
        monitoringJob?.cancel()
        monitoringJob = null
        unregisterBatteryTempFallback()
        if (resetStatus) {
            _thermalStatus.value = ThermalStatus.NONE
        }
        Timber.i("Thermal monitoring stopped")
    }

    private fun checkThermalStatus() {
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = try {
                powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            } catch (e: Exception) {
                Timber.w(e, "Failed to read thermal status")
                PowerManager.THERMAL_STATUS_NONE
            }
            when (status) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
                else -> ThermalStatus.NONE
            }
        } else {
            readBatteryTempStatus()
        }

        if (_thermalStatus.value != thermalStatus) {
            Timber.i("Thermal status changed: %s → %s", _thermalStatus.value, thermalStatus)
            _thermalStatus.value = thermalStatus
        }
    }

    /**
     * Map battery temperature (in tenths of °C) to a coarse thermal status on
     * API < 29. Thresholds align with the qualitative guidance from AOSP:
     * - >= 50°C → CRITICAL (device will likely throttle or shut down)
     * - >= 45°C → SEVERE
     * - >= 40°C → MODERATE
     * - >= 35°C → LIGHT
     * - else    → NONE
     */
    private fun readBatteryTempStatus(): ThermalStatus {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tempC = tempTenths / 10f
            when {
                tempC >= 50f -> ThermalStatus.CRITICAL
                tempC >= 45f -> ThermalStatus.SEVERE
                tempC >= 40f -> ThermalStatus.MODERATE
                tempC >= 35f -> ThermalStatus.LIGHT
                else -> ThermalStatus.NONE
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read battery temp on API<29")
            ThermalStatus.NONE
        }
    }

    private fun registerBatteryTempFallback() {
        if (batteryTempReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) checkThermalStatus()
            }
        }
        batteryTempReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    private fun unregisterBatteryTempFallback() {
        batteryTempReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        batteryTempReceiver = null
    }
}
