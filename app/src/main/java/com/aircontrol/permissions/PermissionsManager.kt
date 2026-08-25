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
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionStates(
    val cameraGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    // TYPE_ACCESSIBILITY_OVERLAY does not require SYSTEM_ALERT_WINDOW when a11y is on.
    val overlayGranted: Boolean = true,
    // Notifications are optional; the foreground camera service remains functional
    // without POST_NOTIFICATIONS, although boot/resume notifications will be hidden.
    val notificationsGranted: Boolean = false,
) {
    val allGranted: Boolean
        get() = cameraGranted && accessibilityGranted

    val missingPermissions: List<MissingPermission>
        get() = buildList {
            if (!cameraGranted) add(MissingPermission.CAMERA)
            if (!accessibilityGranted) add(MissingPermission.ACCESSIBILITY)
        }
}

enum class MissingPermission {
    CAMERA,
    ACCESSIBILITY,
    NOTIFICATIONS,
}

@Singleton
class PermissionsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _cameraGranted = MutableStateFlow(checkCameraPermission())
    val cameraGranted: StateFlow<Boolean> = _cameraGranted

    private val _accessibilityGranted = MutableStateFlow(checkAccessibilityPermission())
    val accessibilityGranted: StateFlow<Boolean> = _accessibilityGranted

    private val _overlayGranted = MutableStateFlow(true)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _notificationsGranted = MutableStateFlow(checkNotificationPermission())
    val notificationsGranted: StateFlow<Boolean> = _notificationsGranted

    val permissionStates: StateFlow<PermissionStates> = combine(
        _cameraGranted,
        _accessibilityGranted,
        _notificationsGranted,
    ) { camera, accessibility, notifications ->
        PermissionStates(
            cameraGranted = camera,
            accessibilityGranted = accessibility,
            overlayGranted = true,
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
        _notificationsGranted.value = checkNotificationPermission()
        Timber.d(
            "Permissions refreshed: camera=%s, accessibility=%s, notifications=%s",
            _cameraGranted.value,
            _accessibilityGranted.value,
            _notificationsGranted.value,
        )
    }

    fun requestCameraPermission(): Intent = requestCameraPermissionIntent()

    fun requestCameraPermissionIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun updateCameraGranted(granted: Boolean) {
        _cameraGranted.value = granted
    }

    fun requestAccessibilityPermission(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun requestNotificationPermissionIntent(): Intent? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openAppSettings(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun checkCameraPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Camera permission check: %s", granted)
        return granted
    }

    private fun checkAccessibilityPermission(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabled = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        )?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } ?: false
        Timber.v("Accessibility permission check: %s", enabled)
        return enabled
    }

    private fun checkNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Timber.v("Notification permission check: %s", granted)
        return granted
    }
}
