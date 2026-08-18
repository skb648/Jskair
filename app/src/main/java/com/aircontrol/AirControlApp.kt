package com.aircontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
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
        val trackingName = runCatching { getString(R.string.notification_channel_name) }
            .getOrDefault("Hand Tracking")
        val trackingDesc = runCatching { getString(R.string.notification_channel_description) }
            .getOrDefault("Controls for the hand tracking foreground service")

        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    "aircontrol_tracking",
                    trackingName,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = trackingDesc
                    setShowBadge(false)
                    enableVibration(false)
                },
            )
        }.onFailure { error ->
            Timber.e(error, "Failed to create tracking notification channel")
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.INFO) {
                android.util.Log.println(priority, tag, message)
            }
            if (t != null && priority >= android.util.Log.ERROR) {
                android.util.Log.println(android.util.Log.ERROR, tag, t.stackTraceToString())
            }
        }
    }
}
