package com.aircontrol.runtime

import android.app.Application
import android.os.SystemClock
import com.aircontrol.accessibility.GestureControlAccessibilityService
import com.aircontrol.camera.CameraService
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.di.AccessibilityServiceEntryPoint
import com.aircontrol.tracking.HandTracker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/** Observes real runtime signals and continuously publishes one readiness state. */
object RuntimeHealthMonitor {
    private const val FRAME_STALE_MS = 2_500L
    private var job: Job? = null

    fun start(application: Application) {
        if (job?.isActive == true) return

        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(
                application,
                AccessibilityServiceEntryPoint::class.java,
            )
        }.getOrElse {
            Timber.e(it, "Runtime health monitor DI failed")
            return
        }

        val handTracker = entryPoint.handTracker()
        val settingsRepository = entryPoint.settingsRepository()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            @Volatile var latestFrameMs = 0L

            launch {
                handTracker.handFrames.collect { _ ->
                    latestFrameMs = SystemClock.elapsedRealtime()
                }
            }

            while (true) {
                val prefs = settingsRepository.userPreferences.value
                val service = CameraService.serviceState.value
                val accessibility = GestureControlAccessibilityService.isConnected.value
                val trackerReady = handTracker.isInitialized()
                val freshFrames = service.isRunning &&
                    latestFrameMs > 0L &&
                    SystemClock.elapsedRealtime() - latestFrameMs <= FRAME_STALE_MS

                val reason = when {
                    !prefs.gesturesEnabled -> null
                    !accessibility -> "accessibility-disconnected"
                    !service.isRunning && !service.isPaused -> "camera-not-running"
                    !trackerReady -> "tracker-not-ready"
                    !freshFrames -> "frames-stale"
                    else -> null
                }

                RuntimeHealth.update(
                    trackingRequested = prefs.gesturesEnabled,
                    accessibilityConnected = accessibility,
                    cameraRunning = service.isRunning,
                    cameraPaused = service.isPaused,
                    handTrackerReady = trackerReady,
                    freshFrames = freshFrames,
                    reason = reason,
                )
                delay(500L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        RuntimeHealth.reset()
    }
}
