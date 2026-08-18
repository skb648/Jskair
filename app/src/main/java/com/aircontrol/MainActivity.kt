package com.aircontrol

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.ui.navigation.AirControlNavHost
import com.aircontrol.ui.navigation.AirControlRoute
import com.aircontrol.ui.theme.AirControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val keepSplash = mutableStateOf(true)
        splashScreen.setKeepOnScreenCondition { keepSplash.value }

        // Never leave the platform splash screen waiting indefinitely for a data-store
        // emission. The in-app LoadingScreen remains available after this timeout.
        lifecycleScope.launch {
            delay(SPLASH_MAX_WAIT_MS)
            keepSplash.value = false
        }

        setContent {
            AirControlContent(
                onPreferencesLoaded = { keepSplash.value = false },
            )
        }
    }

    @Composable
    private fun AirControlContent(onPreferencesLoaded: () -> Unit) {
        val preferences by settingsRepository.userPreferences.collectAsStateWithLifecycle(
            initialValue = null,
        )

        LaunchedEffect(preferences) {
            if (preferences != null) {
                onPreferencesLoaded()
            }
        }

        AirControlTheme {
            when (val prefs = preferences) {
                null -> LoadingScreen()
                else -> {
                    val startDestination = if (prefs.onboardingCompleted) {
                        AirControlRoute.Home.route
                    } else {
                        AirControlRoute.Onboarding.route
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
        private const val SPLASH_MAX_WAIT_MS = 1500L
    }
}

@Preview(showBackground = true)
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
                    modifier = Modifier.size(96.dp).progressSemantics(),
                )
                Text(
                    text = "✋",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
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
