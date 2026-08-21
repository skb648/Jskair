package com.aircontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import com.aircontrol.camera.CameraService
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AirControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initTimber()
        initStrictMode()
        initNotificationChannels()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("AirControl application initialized")
        } else {
            // Fix #115/#116: release tree only logs WARN+ERROR to avoid leaking
            // a per-gesture activity trail to logcat (the privacy copy says no
            // data is recorded; verbose/info logging in release violates that).
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

        // Fix #120: the tracking channel is the only control surface during an
        // active camera session; use IMPORTANCE_LOW (silent but visible in the
        // shade and status bar). Channels are immutable once created except on
        // app data clear, so this value only matters for first install.
        val trackingChannel = NotificationChannel(
            CameraService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        // Fix #66: propagate creation failures but do not crash the app.
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
            // Fix #115: only log WARN, ERROR, WTF in release builds to avoid
            // leaking user activity traces.
            if (priority < android.util.Log.WARN) return
            android.util.Log.println(priority, tag, message)
            if (t != null) {
                android.util.Log.println(android.util.Log.ERROR, tag, t.stackTraceToString())
            }
        }
    }

    companion object {
        // Fix #57: share channel ID with CameraService via the class constant.
        const val BOOT_RESUME_CHANNEL_ID = "aircontrol_boot_resume"
    }
}
