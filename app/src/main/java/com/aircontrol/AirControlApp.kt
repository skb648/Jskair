package com.aircontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import com.aircontrol.camera.CameraService
import com.aircontrol.runtime.RuntimeHealthMonitor
dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class AirControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initTimber()
        initStrictMode()
        initNotificationChannels()
        RuntimeHealthMonitor.start(this)
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("AirControl application initialized")
        } else {
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
