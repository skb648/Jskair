package com.aircontrol.runtime

import android.app.Application
import android.os.SystemClock
import com.aircontrol.accessibility.GestureControlAccessibilityService
import com.aircontrol.camera.CameraService
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.di.AccessibilityServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
            val latestFrameMs = AtomicLong(0L)
            val currentPrefs = AtomicReference(UserPreferences())

            launch {
                settingsRepository.userPreferences.collect { prefs ->
                    currentPrefs.set(prefs)
                }
            }

            launch {
                handTracker.handFrames.collect { _ ->
                    latestFrameMs.set(SystemClock.elapsedRealtime())
                }
            }

            while (true) {
                val prefs = currentPrefs.get()
                val service = CameraService.serviceState.value
                val accessibility = GestureControlAccessibilityService.isConnected.value
                val trackerReady = handTracker.isInitialized()
                val lastFrame = latestFrameMs.get()
                val freshFrames = service.isRunning &&
                    lastFrame > 0L &&
                    SystemClock.elapsedRealtime() - lastFrame <= FRAME_STALE_MS

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
