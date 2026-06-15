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
import com.aircontrol.camera.CameraService
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
 * Android 14+ restricts starting while-in-use foreground services, including
 * camera services, directly from background boot broadcasts. AirControl avoids
 * starting the camera here and posts a user-visible resume notification instead.
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
        Timber.i("Boot completed received, checking if resume notification is required")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        scope.launch {
            try {
                withTimeout(8_000) {
                    val prefs = settingsRepository.userPreferences.first()

                if (!prefs.startOnBoot || !prefs.gesturesEnabled) {
                    Timber.d("Boot resume skipped: startOnBoot=%s gesturesEnabled=%s", prefs.startOnBoot, prefs.gesturesEnabled)
                    return@withTimeout
                }

                permissionsManager.refreshAllPermissions()
                val permStates = permissionsManager.permissionStates.first()

                if (!permStates.allGranted) {
                    Timber.w(
                        "Cannot offer boot resume: missing permissions camera=%s a11y=%s overlay=%s",
                        permStates.cameraGranted,
                        permStates.accessibilityGranted,
                        permStates.overlayGranted,
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Cannot post boot resume notification: POST_NOTIFICATIONS denied")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CameraService.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CameraService.CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    setShowBadge(false)
                },
            )
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            BOOT_RESUME_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CameraService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentTitle(context.getString(R.string.boot_resume_notification_title))
            .setContentText(context.getString(R.string.boot_resume_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        NotificationManagerCompat.from(context).notify(BOOT_RESUME_NOTIFICATION_ID, notification)
        Timber.i("Posted boot resume notification instead of starting camera foreground service")
    }

    companion object {
        private const val BOOT_RESUME_NOTIFICATION_ID = 1002
        private const val BOOT_RESUME_REQUEST_CODE = 2002
    }
}
