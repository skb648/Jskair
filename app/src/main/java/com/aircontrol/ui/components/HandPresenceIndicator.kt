package com.aircontrol.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen

/**
 * Pulsing dot indicator for hand presence.
 * Pulses with a green glow when a hand is detected, dim when no hand.
 */
@Composable
fun HandPresenceIndicator(
    handDetected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    activeColor: Color = SuccessGreen,
    inactiveColor: Color = Color.Gray,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hand_pulse")

    val pulseAlpha by if (handDetected) {
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_alpha",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val pulseRadius by if (handDetected) {
        infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_radius",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Canvas(
        modifier = modifier.size(size * 2),
    ) {
        val s = size.toPx()
        val center = Offset(s, s)

        if (handDetected) {
            // Outer pulse glow
            drawCircle(
                color = activeColor.copy(alpha = pulseAlpha * 0.3f),
                radius = s * pulseRadius,
                center = center,
            )
        }

        // Core dot
        drawCircle(
            color = if (handDetected) activeColor else inactiveColor,
            radius = s * 0.5f,
            center = center,
        )

        if (handDetected) {
            // Inner bright core
            drawCircle(
                color = Color.White.copy(alpha = 0.6f),
                radius = s * 0.2f,
                center = center,
            )
        }
    }
}
