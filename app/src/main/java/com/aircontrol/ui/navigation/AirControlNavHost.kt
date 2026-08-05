package com.aircontrol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aircontrol.ui.calibration.CalibrationScreen
import com.aircontrol.ui.customgesture.CustomGestureScreen
import com.aircontrol.ui.debug.DebugScreen
import com.aircontrol.ui.gesturemap.GestureMapScreen
import com.aircontrol.ui.home.HomeScreen
import com.aircontrol.ui.onboarding.OnboardingScreen
import com.aircontrol.ui.settings.SettingsScreen

@Composable
fun AirControlNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(AirControlRoute.Onboarding.route) {
            OnboardingScreen(
                onGetStarted = {
                    navController.navigate(AirControlRoute.Home.route) {
                        popUpTo(AirControlRoute.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }
        composable(AirControlRoute.Home.route) {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate(AirControlRoute.Settings.route)
                },
                onNavigateToGestureMap = {
                    navController.navigate(AirControlRoute.GestureMap.route)
                },
                onNavigateToCalibration = {
                    navController.navigate(AirControlRoute.Calibration.route)
                },
                onNavigateToOnboarding = {
                    navController.navigate(AirControlRoute.Onboarding.route) {
                        popUpTo(AirControlRoute.Home.route) { inclusive = true }
                    }
                },
                onNavigateToDebug = {
                    navController.navigate(AirControlRoute.Debug.route)
                },
                onNavigateToCustomGesture = {
                    navController.navigate(AirControlRoute.CustomGesture.route)
                },
            )
        }
        composable(AirControlRoute.GestureMap.route) {
            GestureMapScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(AirControlRoute.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(AirControlRoute.Calibration.route) {
            CalibrationScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(AirControlRoute.Debug.route) {
            DebugScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(AirControlRoute.CustomGesture.route) {
            CustomGestureScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
