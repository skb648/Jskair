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
     */
    fun pauseTracking() {
        runCatching {
            val intent = Intent(appContext, CameraService::class.java).apply {
                action = CameraService.ACTION_PAUSE
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
