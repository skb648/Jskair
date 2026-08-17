package com.aircontrol.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
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
    // Always true on minSdk 26+ because overlays use TYPE_ACCESSIBILITY_OVERLAY
    // which does not require SYSTEM_ALERT_WINDOW when accessibility service is enabled.
    val overlayGranted: Boolean = true,
    val notificationsGranted: Boolean = true,
) {
    val allGranted: Boolean get() = cameraGranted && accessibilityGranted

    val missingPermissions: List<MissingPermission>
        get() = buildList {
            if (!cameraGranted) add(MissingPermission.CAMERA)
            if (!accessibilityGranted) add(MissingPermission.ACCESSIBILITY)
            // Overlay intentionally excluded - uses accessibility overlay, not SYSTEM_ALERT_WINDOW
        }
}

enum class MissingPermission {
    CAMERA,
    ACCESSIBILITY,
}

@Singleton
class PermissionsManager @javax.inject.Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _cameraGranted = MutableStateFlow(checkCameraPermission())
    val cameraGranted: StateFlow<Boolean> = _cameraGranted

    private val _accessibilityGranted = MutableStateFlow(checkAccessibilityPermission())
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted

    // Kept for binary compat; not used in allGranted, always true for TYPE_ACCESSIBILITY_OVERLAY
    private val _overlayGranted = MutableStateFlow(true)
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
        started = SharingStarted.Eagerly,
        initialValue = PermissionStates(
            cameraGranted = _cameraGranted.value,
            accessibilityGranted = _accessibilityGranted.value,
            overlayGranted = true,
            notificationsGranted = _notificationsGranted.value,
        ),
    )

    fun refreshAllPermissions() {
        _cameraGranted.value = checkCameraPermission()
        _accessibilityGranted.value = checkAccessibilityPermission()
        _overlayGranted.value = true
        _notificationsGranted.value = checkNotificationPermission()
        Timber.d(
            "Permissions refreshed: camera=%s, accessibility=%s, notifications=%s",
            _cameraGranted.value,
            _accessibilityGranted.value,
            _notificationsGranted.value,
        )
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
        // For TYPE_ACCESSIBILITY_OVERLAY this is not needed, but keep for fallback
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
        val result = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
        val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Camera permission check: %s", granted)
        return granted
    }

    private fun checkAccessibilityPermission(): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        // Use FEEDBACK_GENERIC to match our service's feedbackType
        val enabled = accessibilityManager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )?.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == context.packageName
        } ?: false
        Timber.v("Accessibility permission check: %s", enabled)
        return enabled
    }

    private fun checkNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        val result = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
        val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Notification permission check: %s", granted)
        return granted
    }
}
