package com.aircontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aircontrol.camera.CameraService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receiver for BOOT_COMPLETED that auto-starts the camera tracking service
 * ONLY if the user had it active before reboot and all required permissions are intact.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: com.aircontrol.data.repository.SettingsRepository

    @Inject
    lateinit var permissionsManager: com.aircontrol.permissions.PermissionsManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        Timber.i("Boot completed received, checking if auto-start is required")

        scope.launch {
            try {
                val prefs = settingsRepository.userPreferences.first()

                if (!prefs.startOnBoot) {
                    Timber.d("Start on boot is disabled, skipping auto-start")
                    return@launch
                }

                if (!prefs.gesturesEnabled) {
                    Timber.d("Gestures were not enabled before reboot, skipping auto-start")
                    return@launch
                }

                // Refresh and check permissions
                permissionsManager.refreshAllPermissions()
                val permStates = permissionsManager.permissionStates.first()

                if (!permStates.allGranted) {
                    Timber.w(
                        "Cannot auto-start: missing permissions camera=%s a11y=%s overlay=%s",
                        permStates.cameraGranted,
                        permStates.accessibilityGranted,
                        permStates.overlayGranted,
                    )
                    return@launch
                }

                Timber.i("All conditions met, starting camera service on boot")
                val serviceIntent = Intent(context, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
