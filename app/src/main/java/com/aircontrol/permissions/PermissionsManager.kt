package com.aircontrol.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Singleton

data class PermissionStates(
    val cameraGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    // Kept for UI/backward compatibility. AirControl uses TYPE_ACCESSIBILITY_OVERLAY
    // on minSdk 26+, so SYSTEM_ALERT_WINDOW is not required for runtime.
    val overlayGranted: Boolean = true,
    val notificationsGranted: Boolean = true,
) {
    val allGranted: Boolean get() = cameraGranted && accessibilityGranted

    val missingPermissions: List<MissingPermission>
        get() = buildList {
            if (!cameraGranted) add(MissingPermission.CAMERA)
            if (!accessibilityGranted) add(MissingPermission.ACCESSIBILITY)
        }
}

enum class MissingPermission {
    CAMERA,
    ACCESSIBILITY,
    OVERLAY,
}

@Singleton
class PermissionsManager constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _cameraGranted = MutableStateFlow(checkCameraPermission())
    val cameraGranted: StateFlow<Boolean> = _cameraGranted

    private val _accessibilityGranted = MutableStateFlow(checkAccessibilityPermission())
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted

    private val _overlayGranted = MutableStateFlow(checkOverlayPermission())
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _notificationsGranted = MutableStateFlow(checkNotificationPermission())
    val notificationsGranted: StateFlow<Boolean> = _notificationsGranted

    val permissionStates: StateFlow<PermissionStates> = combine(
        _cameraGranted,
        _accessibilityGranted,
        _overlayGranted,
        _notificationsGranted,
    ) { camera, accessibility, overlay, notifications ->
        PermissionStates(
            cameraGranted = camera,
            accessibilityGranted = accessibility,
            overlayGranted = overlay,
            notificationsGranted = notifications,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Lazily,
        initialValue = PermissionStates(
            cameraGranted = checkCameraPermission(),
            accessibilityGranted = checkAccessibilityPermission(),
            overlayGranted = checkOverlayPermission(),
            notificationsGranted = checkNotificationPermission(),
        ),
    )

    fun refreshAllPermissions() {
        _cameraGranted.value = checkCameraPermission()
        _accessibilityGranted.value = checkAccessibilityPermission()
        _overlayGranted.value = checkOverlayPermission()
        _notificationsGranted.value = checkNotificationPermission()
        Timber.d(
            "Permissions refreshed: camera=%s, accessibility=%s, overlay=%s notifications=%s",
            _cameraGranted.value,
            _accessibilityGranted.value,
            _overlayGranted.value,
            _notificationsGranted.value,
        )
    }

    fun requestCameraPermission() {
        Timber.d("Camera permission request initiated (handled by Accompanist in UI)")
    }

    fun updateCameraGranted(granted: Boolean) {
        _cameraGranted.value = granted
        Timber.d("Camera permission updated: %s", granted)
    }

    fun requestAccessibilityPermission(): Intent {
        Timber.d("Opening accessibility settings")
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun requestOverlayPermission(): Intent {
        Timber.d("Opening overlay settings")
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAppSettings(): Intent {
        Timber.d("Opening app settings")
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun checkCameraPermission(): Boolean {
        val result = context.checkCallingOrSelfPermission(android.Manifest.permission.CAMERA)
        val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Camera permission check: %s", granted)
        return granted
    }

    private fun checkAccessibilityPermission(): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        val enabled = accessibilityManager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )?.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == context.packageName
        } ?: false
        Timber.v("Accessibility permission check: %s", enabled)
        return enabled
    }

    private fun checkOverlayPermission(): Boolean {
        // minSdk is 26 and overlays are added as TYPE_ACCESSIBILITY_OVERLAY from
        // the enabled accessibility service, so the separate draw-over-apps
        // permission is not needed.
        Timber.v("Overlay permission check: true (accessibility overlay)")
        return true
    }

    private fun checkNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        val result = context.checkCallingOrSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Notification permission check: %s", granted)
        return granted
    }
}
