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

        // M-03 Fix: Wrap loading screen in AirControlTheme so users don't see a flash
        // of the default light theme before the dark theme appears.
        AirControlTheme {
            if (preferences == null) {
                // Show loading/splash screen while preferences load
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = com.aircontrol.ui.theme.ElectricBlue,
                        )
                    }
                }
            } else {
                val startDestination = if (preferences!!.onboardingCompleted) {
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
