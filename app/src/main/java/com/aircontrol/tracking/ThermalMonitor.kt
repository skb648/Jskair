package com.aircontrol.tracking

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.ThermalStatusListener
import android.os.ThermalStatusManager
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
 * Thermal thresholds:
 * - THROTTLING_MODERATE → Auto-drop FPS to reduce thermal load
 * - SEVERE → Pause tracking with user notification
 * - NORMAL/None → Resume normal operation
 *
 * Android 17+ compatibility:
 * - Uses ThermalStatusManager API (available from Android 14+, required for Android 17+)
 * - Falls back to legacy PowerManager API for Android 10-13
 * - Skips monitoring on Android 9 and below
 */
enum class ThermalStatus {
    NONE,       // No thermal stress
    LIGHT,      // Light throttling
    MODERATE,   // Moderate throttling - reduce FPS
    SEVERE,     // Severe throttling - pause tracking
}

class ThermalMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pollingIntervalMs: Long = 5000L,
) {
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var thermalStatusManager: ThermalStatusManager? = null
    
    // Android 14+ thermal listener for real-time updates
    private var thermalListener: ThermalStatusListener? = null

    private val _thermalStatus = MutableStateFlow(ThermalStatus.NONE)
    val thermalStatus: StateFlow<ThermalStatus> = _thermalStatus.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: Use ThermalStatusManager
            thermalStatusManager = context.getSystemService(ThermalStatusManager::class.java)
        }
    }

    fun startMonitoring() {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                Timber.i("Thermal status API not available (API < 29), skipping monitoring")
                return
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // Android 14+: Register thermal listener for real-time updates
                registerThermalListener()
            }
            else -> {
                // Android 10-13: Use legacy polling
                startLegacyPolling()
            }
        }
    }

    /**
     * Android 14+: Register ThermalStatusListener for real-time thermal updates.
     * This is more efficient than polling and provides instant notifications.
     */
    private fun registerThermalListener() {
        val manager = thermalStatusManager ?: run {
            Timber.w("ThermalStatusManager not available, falling back to legacy polling")
            startLegacyPolling()
            return
        }

        try {
            thermalListener = ThermalStatusListener { status ->
                val thermalStatus = mapThermalStatus(status)
                if (_thermalStatus.value != thermalStatus) {
                    Timber.i("Thermal status changed (listener): %s → %s", _thermalStatus.value, thermalStatus)
                    _thermalStatus.value = thermalStatus
                }
            }
            manager.registerThermalStatusListener(thermalListener!!)
            Timber.i("Thermal monitoring started with ThermalStatusListener (Android 14+)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to register ThermalStatusListener, falling back to polling")
            startLegacyPolling()
        }
    }

    /**
     * Android 10-13: Legacy polling-based thermal monitoring.
     */
    private fun startLegacyPolling() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch(Dispatchers.Default) {
            while (true) {
                checkThermalStatusLegacy()
                delay(pollingIntervalMs)
            }
        }
        Timber.i("Thermal monitoring started with legacy polling (Android 10-13)")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        
        // Unregister thermal listener on Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                thermalStatusManager?.unregisterThermalStatusListener(thermalListener)
            } catch (e: Exception) {
                Timber.w(e, "Error unregistering thermal listener")
            }
            thermalListener = null
        }
        
        _thermalStatus.value = ThermalStatus.NONE
        Timber.i("Thermal monitoring stopped")
    }

    /**
     * Android 10-13: Check thermal status using legacy PowerManager API.
     */
    private fun checkThermalStatusLegacy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val status = try {
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } catch (e: Exception) {
            Timber.w(e, "Failed to read thermal status")
            PowerManager.THERMAL_STATUS_NONE
        }

        val thermalStatus = mapLegacyThermalStatus(status)

        if (_thermalStatus.value != thermalStatus) {
            Timber.i("Thermal status changed (polling): %s → %s", _thermalStatus.value, thermalStatus)
            _thermalStatus.value = thermalStatus
        }
    }

    /**
     * Android 14+: Map ThermalStatusManager status to our ThermalStatus enum.
     */
    private fun mapThermalStatus(status: Int): ThermalStatus {
        return when (status) {
            0 -> ThermalStatus.NONE           // STATUS_NONE
            1 -> ThermalStatus.LIGHT          // STATUS_LIGHT
            2 -> ThermalStatus.MODERATE       // STATUS_MODERATE
            3, 4, 5, 6 -> ThermalStatus.SEVERE // STATUS_SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
            else -> ThermalStatus.NONE
        }
    }

    /**
     * Android 10-13: Map PowerManager thermal status to our ThermalStatus enum.
     */
    private fun mapLegacyThermalStatus(status: Int): ThermalStatus {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SEVERE
            else -> ThermalStatus.NONE
        }
    }
}
