package com.aircontrol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.rememberNavController
import com.aircontrol.ui.calibration.CalibrationScreen
import com.aircontrol.ui.customgesture.CustomGestureScreen
import com.aircontrol.ui.debug.DebugScreen
import com.aircontrol.ui.gazecalibration.GazeCalibrationScreen
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
        enterTransition = { slideInHorizontally { it / 3 } },
        exitTransition = { slideOutHorizontally { -it / 3 } },
        popEnterTransition = { slideInHorizontally { -it / 3 } },
        popExitTransition = { slideOutHorizontally { it / 3 } },
    ) {
        composable(AirControlRoute.Onboarding.route) {
            val onboardingContext = androidx.compose.ui.platform.LocalContext.current
            OnboardingScreen(
                onGetStarted = {
                    // Fix (verified critical #3): in "Re-run setup" Home stays on
                    // the back stack — pop to it; first launch has nothing to
                    // pop, so navigate to Home and drop Onboarding.
                    if (!navController.popBackStack()) {
                        navController.navigate(AirControlRoute.Home.route) {
                            popUpTo(AirControlRoute.Onboarding.route) { inclusive = true }
                        }
                    }
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        // First run with an empty stack: normal back = leave app.
                        runCatching {
                            (onboardingContext as? android.app.Activity)?.moveTaskToBack(true)
                        }
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
                    // Fix (verified critical #3): keep Home on the back stack so
                    // Back/Skip from a re-run setup returns to the app instead
                    // of exiting.
                    navController.navigate(AirControlRoute.Onboarding.route)
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
                onNavigateToGazeCalibration = {
                    navController.navigate(AirControlRoute.GazeCalibration.route)
                },
            )
        }
        composable(AirControlRoute.GazeCalibration.route) {
            GazeCalibrationScreen(
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
