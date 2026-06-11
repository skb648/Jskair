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
    val overlayGranted: Boolean = false,
) {
    val allGranted: Boolean get() = cameraGranted && accessibilityGranted && overlayGranted

    val missingPermissions: List<MissingPermission>
        get() = buildList {
            if (!cameraGranted) add(MissingPermission.CAMERA)
            if (!accessibilityGranted) add(MissingPermission.ACCESSIBILITY)
            if (!overlayGranted) add(MissingPermission.OVERLAY)
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

    val permissionStates: StateFlow<PermissionStates> = combine(
        _cameraGranted,
        _accessibilityGranted,
        _overlayGranted,
    ) { camera, accessibility, overlay ->
        PermissionStates(
            cameraGranted = camera,
            accessibilityGranted = accessibility,
            overlayGranted = overlay,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Lazily,
        initialValue = PermissionStates(
            cameraGranted = checkCameraPermission(),
            accessibilityGranted = checkAccessibilityPermission(),
            overlayGranted = checkOverlayPermission(),
        ),
    )

    fun refreshAllPermissions() {
        _cameraGranted.value = checkCameraPermission()
        _accessibilityGranted.value = checkAccessibilityPermission()
        _overlayGranted.value = checkOverlayPermission()
        Timber.d(
            "Permissions refreshed: camera=%s, accessibility=%s, overlay=%s",
            _cameraGranted.value,
            _accessibilityGranted.value,
            _overlayGranted.value,
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
        val granted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
        Timber.v("Overlay permission check: %s", granted)
        return granted
    }
}
