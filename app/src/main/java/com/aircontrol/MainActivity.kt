package com.aircontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.permissions.PermissionsManager
import com.aircontrol.service.CameraServiceManager
import com.aircontrol.ui.navigation.AirControlNavHost
import com.aircontrol.ui.navigation.AirControlRoute
import com.aircontrol.ui.theme.AirControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var permissionsManager: PermissionsManager

    @Inject
    lateinit var cameraServiceManager: CameraServiceManager

    private var startDestinationOverride: String? = null
    private var startTrackingOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        when (intent?.action) {
            ACTION_OPEN_SETTINGS -> startDestinationOverride = AirControlRoute.Settings.route
            ACTION_RESUME_TRACKING -> {
                startDestinationOverride = AirControlRoute.Home.route
                startTrackingOnResume = true
            }
        }

        val keepSplash = mutableStateOf(true)
        splashScreen.setKeepOnScreenCondition { keepSplash.value }

        lifecycleScope.launch {
            delay(SPLASH_MAX_WAIT_MS)
            keepSplash.value = false
        }

        setContent {
            AirControlContent(
                onPreferencesLoaded = { keepSplash.value = false },
                startDestinationOverride = startDestinationOverride,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val explicitResume = startTrackingOnResume
        startTrackingOnResume = false

        // Camera permission is while-in-use on Android 14+. Start the camera FGS
        // from this visible activity rather than relying only on a background
        // AccessibilityService callback, which Samsung and other OEMs may reject.
        lifecycleScope.launch {
            permissionsManager.refreshAllPermissions()
            val ready = withTimeoutOrNull(2_500L) {
                permissionsManager.permissionStates.first { it.allGranted }
            }
            val prefs = settingsRepository.userPreferences.first()
            if (ready != null && (prefs.gesturesEnabled || explicitResume) &&
                !cameraServiceManager.isTracking()) {
                cameraServiceManager.startTracking()
            }
        }
    }

    @Composable
    private fun AirControlContent(
        onPreferencesLoaded: () -> Unit,
        startDestinationOverride: String?,
    ) {
        var timedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            runCatching {
                settingsRepository.userPreferences.first()
            }.onSuccess {
                onPreferencesLoaded()
            }.onFailure {
                timedOut = true
            }
        }

        val preferences by settingsRepository.userPreferences.collectAsStateWithLifecycle(
            initialValue = if (timedOut) com.aircontrol.data.model.UserPreferences() else null,
        )

        LaunchedEffect(preferences) {
            if (preferences != null) onPreferencesLoaded()
        }

        AirControlTheme {
            when (val prefs = preferences) {
                null -> LoadingScreen()
                else -> {
                    val startDestination = startDestinationOverride ?: run {
                        if (prefs.onboardingCompleted) AirControlRoute.Home.route
                        else AirControlRoute.Onboarding.route
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AirControlNavHost(startDestination = startDestination)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_OPEN_SETTINGS = "com.aircontrol.action.OPEN_SETTINGS"
        const val ACTION_RESUME_TRACKING = "com.aircontrol.action.RESUME_TRACKING"
        private const val SPLASH_MAX_WAIT_MS = 1500L
    }
}

@Composable
private fun LoadingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
