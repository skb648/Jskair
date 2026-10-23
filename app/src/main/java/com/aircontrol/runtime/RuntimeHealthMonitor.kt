package com.aircontrol.runtime

import android.app.Application
import android.os.SystemClock
import com.aircontrol.accessibility.GestureControlAccessibilityService
import com.aircontrol.camera.CameraService
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.di.AccessibilityServiceEntryPoint
import com.aircontrol.util.CrashGuard
import com.aircontrol.util.launchGuarded
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Observes runtime signals and publishes readiness. This monitor is diagnostic-only:
 * failure to create or run it must never prevent the Application or AccessibilityService
 * from starting.
 */
object RuntimeHealthMonitor {
    private const val COMPONENT = "RuntimeHealthMonitor"
    private const val FRAME_STALE_MS = 2_500L

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start(application: Application) {
        if (job?.isActive == true) return

        // Protect the entire dependency resolution, not only EntryPointAccessors.
        // Calling entryPoint.handTracker()/settingsRepository() can instantiate Hilt
        // singletons and throw; previously those calls escaped Application.onCreate().
        val dependencies = try {
            val entryPoint = EntryPointAccessors.fromApplication(
                application,
                AccessibilityServiceEntryPoint::class.java,
            )
            entryPoint.handTracker() to entryPoint.settingsRepository()
        } catch (error: Throwable) {
            StageLog.failure(
                StageLog.Stage.DI_INIT,
                COMPONENT,
                "Unable to resolve runtime-health dependencies; continuing without monitor",
                error,
            )
            CrashGuard.report("$COMPONENT dependency initialization", error)
            return
        }

        val handTracker = dependencies.first
        val settingsRepository = dependencies.second
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashGuard.handler)

        job = scope.launchGuarded("$COMPONENT sampling", restart = true) {
            val latestFrameMs = AtomicLong(0L)
            val currentPrefs = AtomicReference(UserPreferences())

            // These collectors run as supervised, guarded children. A corrupt or
            // temporarily failing upstream flow is logged and retried with bounded
            // backoff instead of reaching the process-wide uncaught exception handler.
            launchGuarded("$COMPONENT preferences collector", restart = true) {
                settingsRepository.userPreferences.collect { prefs ->
                    currentPrefs.set(prefs)
                }
            }

            launchGuarded("$COMPONENT hand-frame collector", restart = true) {
                handTracker.handFrames.collect {
                    latestFrameMs.set(SystemClock.elapsedRealtime())
                }
            }

            while (isActive) {
                try {
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
                        service.actualState == com.aircontrol.camera.TrackingState.WAITING_FOR_CAMERA ||
                            service.actualState == com.aircontrol.camera.TrackingState.CAMERA_LOST -> "camera-waiting"
                        service.actualState == com.aircontrol.camera.TrackingState.STARTING -> "camera-starting"
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Keep monitoring after a single bad snapshot; the service's
                    // own lifecycle and tracking logic remain independent of this UI signal.
                    CrashGuard.report("$COMPONENT snapshot", error)
                }
                delay(500L)
            }
        }

        StageLog.success(StageLog.Stage.SERVICE_READY, COMPONENT, "runtime health observer started")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        RuntimeHealth.reset()
    }
}
