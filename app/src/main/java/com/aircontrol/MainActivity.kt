package com.aircontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.ui.navigation.AirControlNavHost
import com.aircontrol.ui.navigation.AirControlRoute
import com.aircontrol.ui.theme.AirControlTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirControlApp()
        }
    }

    @Composable
    private fun AirControlApp() {
        val preferences by settingsRepository.userPreferences.collectAsState(
            initial = null,
        )

        if (preferences == null) {
            // Show loading/splash screen while preferences load
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        val startDestination = if (preferences!!.onboardingCompleted) {
            AirControlRoute.Home.route
        } else {
            AirControlRoute.Onboarding.route
        }

        AirControlTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AirControlNavHost(startDestination = startDestination)
            }
        }
    }
}
