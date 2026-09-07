package com.aircontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import com.aircontrol.camera.CameraService
import com.aircontrol.di.AccessibilityServiceEntryPoint
import com.aircontrol.runtime.PerfTelemetry
import com.aircontrol.runtime.ResourceGovernor
import com.aircontrol.runtime.RuntimeHealthMonitor
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AirControlApp : Application() {

    // Perf audit P9: memory-pressure policy. Kept separate from thermal and
    // power-save handling on purpose — they are different signals with
    // different remedies (see ResourceGovernor).
    private val resourceGovernor = ResourceGovernor()

    override fun onCreate() {
        super.onCreate()
        initTimber()
        initStrictMode()
        initNotificationChannels()
        RuntimeHealthMonitor.start(this)
        // Perf audit P18: telemetry logs only in debug builds, and the summary
        // is rate-limited internally (≥30 s) — release builds never pay for it
        // (the release tree is WARN+ anyway).
        PerfTelemetry.enableLogging = BuildConfig.DEBUG
        PerfTelemetry.loggingSink = { message -> Timber.tag("PerfTelemetry").d(message) }
    }

    /**
     * Perf audit P9: the app previously ignored memory pressure entirely (no
     * onTrimMemory / ComponentCallbacks2 anywhere in app/src/main). The idle
     * MediaPipe trackers are the largest native allocations AirControl holds,
     * so under real memory pressure — and only while no camera session and no
     * exclusive camera user (debug screen) needs them — release them. The
     * normal start path re-initializes on demand.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val memoryLevel = resourceGovernor.classifyTrim(level)
        val trackersIdle = runCatching {
            val entryPoint = AccessibilityServiceEntryPoint.getFromApplication(this)
            !CameraService.isRunning.value &&
                // autoReviveEnabled == false marks an exclusive camera owner
                // (the debug screen) — its trackers are in use, not idle.
                entryPoint.cameraServiceManager().autoReviveEnabled &&
                (entryPoint.handTracker().isInitialized() || entryPoint.faceTracker().isInitialized())
        }.getOrDefault(false)

        var released = false
        if (trackersIdle && resourceGovernor.shouldReleaseIdleTrackers(memoryLevel)) {
            released = true
            // Tracker close is native work — never on the main thread.
            Thread {
                runCatching {
                    val entryPoint = AccessibilityServiceEntryPoint.getFromApplication(this)
                    if (!CameraService.isRunning.value) {
                        entryPoint.handTracker().close()
                        entryPoint.faceTracker().close()
                        Timber.i("Memory pressure (level=%d): released idle trackers", level)
                    }
                }.onFailure { Timber.e(it, "Could not release trackers under memory pressure") }
            }.apply {
                name = "trim-memory-release"
                isDaemon = true
            }.start()
        }
        PerfTelemetry.recordMemoryTrim(level, released, SystemClock.elapsedRealtime())
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("AirControl application initialized")
        } else {
            // Fix #115/#116: release tree only logs WARN+ERROR to avoid leaking
            // a per-gesture activity trail to logcat.
            Timber.plant(ReleaseTree())
        }
    }

    private fun initStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    private fun initNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return

        val trackingChannel = NotificationChannel(
            CameraService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        runCatching { manager.createNotificationChannel(trackingChannel) }
            .onFailure { Timber.e(it, "Failed to create tracking notification channel") }

        val bootResumeChannel = NotificationChannel(
            BOOT_RESUME_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(bootResumeChannel) }
            .onFailure { Timber.e(it, "Failed to create boot-resume notification channel") }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.WARN) return
            android.util.Log.println(priority, tag, message)
            if (t != null) {
                android.util.Log.println(android.util.Log.ERROR, tag, t.stackTraceToString())
            }
        }
    }

    companion object {
        const val BOOT_RESUME_CHANNEL_ID = "aircontrol_boot_resume"
    }
}
