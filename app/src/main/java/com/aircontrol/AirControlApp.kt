package com.aircontrol

import android.app.Application
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
            // In release, plant a tree that reports to crash analytics if available,
            // but keeps info/warn/error for diagnostics. Debug/Verbose stripped by R8.
            Timber.plant(ReleaseTree())
        }
    }

    private fun initStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    private fun initNotificationChannels() {
        // Create notification channels early so foreground service never fails
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            val trackingChannel = android.app.NotificationChannel(
                "aircontrol_tracking",
                getString(R.string.notification_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
            }
            val bootChannel = android.app.NotificationChannel(
                "aircontrol_boot",
                "Boot Resume",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Resume gesture tracking after reboot"
                setShowBadge(false)
            }
            manager?.createNotificationChannels(listOf(trackingChannel, bootChannel))
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only log warn/error/info in release; debug/verbose stripped by R8
            if (priority >= android.util.Log.INFO) {
                android.util.Log.println(priority, tag, message)
                // TODO: Integrate with Firebase Crashlytics if needed
            }
        }
    }
}
