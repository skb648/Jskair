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
import com.aircontrol.camera.CameraService
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.ui.navigation.AirControlNavHost
import com.aircontrol.ui.navigation.AirControlRoute
import com.aircontrol.ui.theme.AirControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    // Incoming deep-link flags, read in onCreate.
    private var startDestinationOverride: String? = null
    private var startTrackingOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming actions from BootCompletedReceiver / SettingsDeepLinkActivity.
        when (intent?.action) {
            ACTION_OPEN_SETTINGS -> startDestinationOverride = AirControlRoute.Settings.route
            ACTION_RESUME_TRACKING -> {
                startDestinationOverride = AirControlRoute.Home.route
                startTrackingOnResume = true
            }
        }

        val keepSplash = mutableStateOf(true)
        splashScreen.setKeepOnScreenCondition { keepSplash.value }

        // Fix #26: bail out to Home (which can show an error/retry) instead of
        // spinning forever if DataStore never emits.
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
        if (startTrackingOnResume) {
            startTrackingOnResume = false
            // Start the camera foreground service from the (now-foreground)
            // activity — this is the allowed path on Android 12+.
            startService(Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            })
        }
    }

    @Composable
    private fun AirControlContent(
        onPreferencesLoaded: () -> Unit,
        startDestinationOverride: String?,
    ) {
        // Fix #26: timeout with fallback to default preferences so the UI
        // never hangs forever on a corrupt DataStore.
        var timedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val loaded = withTimeoutOrNull(3000L) {
                settingsRepository.userPreferences.collect { } // first emit unblocks
            }
            if (loaded == null) timedOut = true
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
                // Fix #109: don't render an emoji inside the spinner; use the app name.
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
