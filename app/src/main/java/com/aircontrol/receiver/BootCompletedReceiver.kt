package com.aircontrol.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aircontrol.MainActivity
import com.aircontrol.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

/**
 * Receiver for BOOT_COMPLETED.
 *
 * Android 12+ forbids starting a foreground service (camera) from the
 * background. Instead of starting [com.aircontrol.camera.CameraService]
 * directly (fix #16: this threw ForegroundServiceStartNotAllowedException),
 * we post a notification that opens MainActivity / dispatches through the
 * activity (which is allowed to start FGS from the foreground).
 *
 * Fix #17: receiver is NOT direct-boot-aware and does NOT listen for
 * LOCKED_BOOT_COMPLETED, so it will never run before credential unlock and
 * can safely touch credential-encrypted DataStore.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: com.aircontrol.data.repository.SettingsRepository

    @Inject
    lateinit var permissionsManager: com.aircontrol.permissions.PermissionsManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        Timber.i("Boot completed received")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        scope.launch {
            try {
                withTimeout(8_000) {
                    val prefs = settingsRepository.userPreferences.first()

                    if (!prefs.startOnBoot || !prefs.gesturesEnabled) {
                        Timber.d("Boot resume skipped: startOnBoot=%s gesturesEnabled=%s",
                            prefs.startOnBoot, prefs.gesturesEnabled)
                        return@withTimeout
                    }

                    permissionsManager.refreshAllPermissions()
                    val permStates = permissionsManager.permissionStates.first()

                    if (!permStates.allGranted) {
                        Timber.w(
                            "Cannot offer boot resume: missing permissions camera=%s a11y=%s notif=%s",
                            permStates.cameraGranted,
                            permStates.accessibilityGranted,
                            permStates.notificationsGranted,
                        )
                        return@withTimeout
                    }

                    postResumeNotification(context)
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private fun postResumeNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post boot resume notification: POST_NOTIFICATIONS denied")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        // The boot-resume channel is created up-front by AirControlApp; if it's
        // missing for some reason (data cleared / crash), create it here lazily.
        if (manager.getNotificationChannel(com.aircontrol.AirControlApp.BOOT_RESUME_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    com.aircontrol.AirControlApp.BOOT_RESUME_CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    setShowBadge(false)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                },
            )
        }

        // Fix #16: instead of PendingIntent.getService (which would throw on
        // Android 12+ for a camera FGS started from the background), open
        // MainActivity which is in the foreground and can start the service.
        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_RESUME_TRACKING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val resumePendingIntent = PendingIntent.getActivity(
            context,
            BOOT_RESUME_ACTION_REQUEST_CODE,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            BOOT_RESUME_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, com.aircontrol.AirControlApp.BOOT_RESUME_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(context.getString(R.string.boot_resume_notification_title))
            .setContentText(context.getString(R.string.boot_resume_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.boot_resume_notification_text_extended))
            )
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_tracking_notification,
                context.getString(R.string.boot_resume_action_button),
                resumePendingIntent,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(BOOT_RESUME_NOTIFICATION_ID, notification)
        Timber.i("Posted boot resume notification")
    }

    companion object {
        private const val BOOT_RESUME_NOTIFICATION_ID = 1002
        private const val BOOT_RESUME_REQUEST_CODE = 2002
        private const val BOOT_RESUME_ACTION_REQUEST_CODE = 2003
    }
}
