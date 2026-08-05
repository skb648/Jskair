package com.aircontrol.data.model

data class UserPreferences(
    val gesturesEnabled: Boolean = true,
    val sensitivity: Int = 50,
    val handPreference: HandPreference = HandPreference.ANY,
    val analysisFps: Int = 24,
    val cursorEnabled: Boolean = true,
    val hapticFeedback: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val cursorSpeed: Int = 50,
    val holdDuration: Int = 600,
    val batterySaver: Boolean = false,
    val startOnBoot: Boolean = false,
    val statusPillEnabled: Boolean = true,
    val calibratedHandSizeMm: Float = 0f,
    val calibratedPinchDistanceMm: Float = 0f,
    val isCalibrated: Boolean = false,
)
