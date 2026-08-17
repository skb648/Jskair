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
            // DirectBoot: getString may fail before credential decrypt, use hardcoded fallback
            val trackingName = try { getString(R.string.notification_channel_name) } catch (_: Exception) { "Hand Tracking" }
            val trackingDesc = try { getString(R.string.notification_channel_description) } catch (_: Exception) { "Controls for the hand tracking foreground service" }
            val trackingChannel = android.app.NotificationChannel(
                "aircontrol_tracking",
                trackingName,
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = trackingDesc
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
            try { manager?.createNotificationChannels(listOf(trackingChannel, bootChannel)) } catch (_: Exception) {}
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only log warn/error/info in release; debug/verbose stripped by R8
            if (priority >= android.util.Log.INFO) {
                android.util.Log.println(priority, tag, message)
                // Release log only; Crashlytics can be added via FirebaseCrashlytics.getInstance().recordException(t) if needed
            }
        }
    }
}
