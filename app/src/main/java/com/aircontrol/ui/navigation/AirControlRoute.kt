package com.aircontrol.ui.navigation

sealed class AirControlRoute(val route: String) {
    data object Onboarding : AirControlRoute("onboarding")
    data object Home : AirControlRoute("home")
    data object GestureMap : AirControlRoute("gesture_map")
    data object Settings : AirControlRoute("settings")
    data object Calibration : AirControlRoute("calibration")
    data object Debug : AirControlRoute("debug")
}
