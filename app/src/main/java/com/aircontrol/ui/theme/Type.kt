package com.aircontrol.ui.theme

// Fix #105: 16 identical "TODO: Add Inter font" comments reduced to a single
// declaration. Inter is not bundled (keeps APK size down); FontFamily.Default
// uses the system typeface (Roboto on stock Android).
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Uses system default (Roboto). Swap to Font(...) if a bundled typeface is added.
private val AirFont = FontFamily.Default

val AirControlTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AirFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp,
    ),
)
