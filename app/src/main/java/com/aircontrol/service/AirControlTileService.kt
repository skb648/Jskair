package com.aircontrol.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aircontrol.AirControlApp
import com.aircontrol.MainActivity
import com.aircontrol.R
import com.aircontrol.camera.CameraService
import com.aircontrol.di.AccessibilityServiceEntryPoint
import com.aircontrol.util.CrashGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Android Quick Settings Tile for 1-tap global toggling of AirControl gesture & eye tracking
 * from any app without opening the main UI.
 */
class AirControlTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CrashGuard.handler)
    private var stateListenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as? AirControlApp ?: return
        val entryPoint = runCatching { AccessibilityServiceEntryPoint.getFromApplication(app) }.getOrNull() ?: return
        val settingsRepository = entryPoint.settingsRepository()
        val permissionsManager = entryPoint.permissionsManager()
        permissionsManager.refreshAllPermissions()

        stateListenJob?.cancel()
        stateListenJob = tileScope.launch {
            combine(
                settingsRepository.userPreferences,
                CameraService.isRunning,
                CameraService.isPaused,
                permissionsManager.permissionStates,
            ) { prefs, isRunning, isPaused, perms ->
                TileSnapshot(
                    gesturesEnabled = prefs.gesturesEnabled,
                    isRunning = isRunning,
                    isPaused = isPaused,
                    allPermissionsGranted = perms.allGranted,
                    profileName = prefs.cursorResponseProfile.name,
                )
            }.collect { snap ->
                updateQsTile(snap)
            }
        }
    }

    override fun onStopListening() {
        stateListenJob?.cancel()
        stateListenJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext as? AirControlApp ?: return
        val entryPoint = runCatching { AccessibilityServiceEntryPoint.getFromApplication(app) }.getOrNull() ?: return
        val settingsRepository = entryPoint.settingsRepository()
        val cameraServiceManager = entryPoint.cameraServiceManager()
        val permissionsManager = entryPoint.permissionsManager()

        tileScope.launch {
            permissionsManager.refreshAllPermissions()
            val perms = permissionsManager.permissionStates.value
            if (!perms.allGranted) {
                launchMainActivityFromTile()
                return@launch
            }

            val prefs = settingsRepository.userPreferences.first()
            val isRunning = CameraService.isRunning.value
            val isPaused = CameraService.isPaused.value

            when {
                isRunning && !isPaused -> {
                    settingsRepository.updateGesturesEnabled(false)
                    cameraServiceManager.stopTracking()
                }
                isRunning && isPaused -> {
                    settingsRepository.updateGesturesEnabled(true)
                    cameraServiceManager.resumeTracking()
                }
                else -> {
                    val enable = !prefs.gesturesEnabled || !isRunning
                    settingsRepository.updateGesturesEnabled(enable)
                    if (enable) {
                        runCatching { cameraServiceManager.startTracking() }
                            .onFailure {
                                Timber.w(it, "Background FGS start restricted from Quick Settings; launching MainActivity")
                                launchMainActivityFromTile()
                            }
                    } else {
                        cameraServiceManager.stopTracking()
                    }
                }
            }
        }
    }

    private fun launchMainActivityFromTile() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateQsTile(snap: TileSnapshot) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        when {
            !snap.allPermissionsGranted -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_setup)
                }
            }
            snap.isRunning && !snap.isPaused -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_active, snap.profileName)
                }
            }
            snap.isRunning && snap.isPaused -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_paused)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_subtitle_off)
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        stateListenJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }

    private data class TileSnapshot(
        val gesturesEnabled: Boolean,
        val isRunning: Boolean,
        val isPaused: Boolean,
        val allPermissionsGranted: Boolean,
        val profileName: String,
    )
}
