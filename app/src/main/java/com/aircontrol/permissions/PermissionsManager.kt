package com.aircontrol.permissions

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.aircontrol.accessibility.GestureControlAccessibilityService
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
            if (!notificationsGranted) add(MissingPermission.NOTIFICATIONS)
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

    private val _accessibilityEnabled = MutableStateFlow(checkAccessibilityPermission())
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled

    // Require both the persisted Android setting and a live bound service. This
    // distinguishes genuine readiness from Samsung/OEM "Not working" states.
    val accessibilityGranted: StateFlow<Boolean> = combine(
        _accessibilityEnabled,
        GestureControlAccessibilityService.isConnected,
    ) { enabled, connected -> enabled && connected }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = _accessibilityEnabled.value &&
                GestureControlAccessibilityService.isConnected.value,
        )

    private val _overlayGranted = MutableStateFlow(true)
    val overlayGranted: StateFlow<Boolean> = _overlayGranted

    private val _notificationsGranted = MutableStateFlow(checkNotificationPermission())
    val notificationsGranted: StateFlow<Boolean> = _notificationsGranted

    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _accessibilityEnabled.value = checkAccessibilityPermission()
        }
    }

    init {
        // Individual accessibility-service changes do not always trigger the global
        // AccessibilityStateChangeListener, so observe the authoritative secure list.
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessibilityObserver,
        )
    }

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
        _accessibilityEnabled.value = checkAccessibilityPermission()
        _notificationsGranted.value = checkNotificationPermission()
        Timber.d(
            "Permissions refreshed: camera=%s, accessibility=%s, notifications=%s",
            _cameraGranted.value,
            accessibilityGranted.value,
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

    fun requestOverlayPermission(): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
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
        val expected = ComponentName(context, GestureControlAccessibilityService::class.java)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        // Primary public API: use ALL_MASK and match the exact component, not only
        // package name. FEEDBACK_GENERIC is inconsistently filtered on some OEMs.
        val managerMatch = runCatching {
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    serviceInfo.packageName == expected.packageName &&
                        ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
                } == true
        }.getOrDefault(false)

        // OEM fallback: Samsung and others can expose an out-of-date manager list
        // briefly after returning from Settings. Parse the authoritative secure list.
        val secureMatch = runCatching {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return@runCatching false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(raw)
            var found = false
            while (splitter.hasNext()) {
                if (ComponentName.unflattenFromString(splitter.next()) == expected) {
                    found = true
                    break
                }
            }
            found
        }.getOrDefault(false)

        val enabled = managerMatch || secureMatch
        Timber.v(
            "Accessibility enabled check: manager=%s secure=%s connected=%s",
            managerMatch,
            secureMatch,
            GestureControlAccessibilityService.isConnected.value,
        )
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
