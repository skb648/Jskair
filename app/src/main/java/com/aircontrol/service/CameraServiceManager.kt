package com.aircontrol.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aircontrol.camera.CameraService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M-01 Fix: Centralized service manager for CameraService start/stop operations.
 * 
 * Previously, HomeViewModel and SettingsViewModel both had identical
 * startTrackingService() and stopTrackingService() methods. This duplication
 * was a maintenance hazard — if the start logic changed, it had to change in
 * two places. This manager provides a single source of truth.
 * 
 * Usage:
 * ```kotlin
 * @Inject lateinit var serviceManager: CameraServiceManager
 * 
 * fun enableTracking() {
 *     serviceManager.startTracking()
 * }
 * 
 * fun disableTracking() {
 *     serviceManager.stopTracking()
 * }
 * ```
 */
@Singleton
class CameraServiceManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    /**
     * Whether the accessibility service's watchdog may bring the camera session
     * back after it disappears.
     *
     * Normally true - that is the whole point of the watchdog (a session killed by
     * memory pressure or a camera-hal crash must not stay dead). It is turned off
     * while something else needs exclusive ownership of the camera, e.g. the debug
     * screen, which binds the camera itself and would otherwise lose it again five
     * seconds later to the watchdog. Deliberate user stops do not need this: they
     * turn the master switch off, which the watchdog already honours.
     */
    @Volatile
    var autoReviveEnabled: Boolean = true

    /**
     * Starts the camera tracking foreground service.
     * Safe to call multiple times — CameraService handles idempotency internally.
     */
    fun startTracking() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            }
            ContextCompat.startForegroundService(appContext, intent)
            Timber.i("Camera tracking foreground service start requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to start CameraService")
        }
    }

    /**
     * Stops the camera tracking foreground service.
     * Safe to call multiple times — CameraService handles idempotency internally.
     */
    fun stopTracking() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_STOP
            }
            appContext.startService(intent)
            Timber.i("Camera tracking foreground service stop requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to stop CameraService")
        }
    }

    /**
     * Pauses camera tracking (keeps service alive but stops processing).
     *
     * Fix (audit #21): [systemInitiated] marks screen-off pauses, which the
     * watchdog may auto-revive; a user pause (default) is sticky.
     */
    fun pauseTracking(systemInitiated: Boolean = false) {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = if (systemInitiated) CameraService.ACTION_SYSTEM_PAUSE else CameraService.ACTION_PAUSE
            }
            appContext.startService(intent)
            Timber.i("Camera tracking pause requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to pause CameraService")
        }
    }

    /**
     * Resumes camera tracking after pause.
     */
    fun resumeTracking() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_RESUME
            }
            appContext.startService(intent)
            Timber.i("Camera tracking resume requested")
        }.onFailure { error ->
            Timber.e(error, "Failed to resume CameraService")
        }
    }

    /**
     * Returns true if the camera service is currently running.
     */
    fun isTracking(): Boolean = CameraService.isRunning.value

    /**
     * Returns true if the camera service is currently paused.
     */
    fun isPaused(): Boolean = CameraService.isPaused.value
}
