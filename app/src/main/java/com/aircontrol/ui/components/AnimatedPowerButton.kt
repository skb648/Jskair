package com.aircontrol.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.ElectricBlueVariant
import com.aircontrol.ui.theme.ErrorRed
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary
import com.aircontrol.ui.theme.WarningOrange

/**
 * Animated power button with a rotating gradient ring when active.
 *
 * States:
 * - Active: Green ring, gradient rotates continuously, pulsing glow
 * - Paused: Orange ring, no rotation, static
 * - Off: Red ring, no rotation, dimmed
 */
@Composable
fun AnimatedPowerButton(
    isActive: Boolean,
    isPaused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    contentDescription: String = "Power toggle",
) {
    val shouldRotate = isActive && !isPaused

    val infiniteTransition = rememberInfiniteTransition(label = "power_rotation")

    val rotation by if (shouldRotate) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "power_ring_rotation",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val ringColor = when {
        isActive && !isPaused -> SuccessGreen
        isPaused -> WarningOrange
        else -> ErrorRed
    }

    val secondaryColor = when {
        isActive && !isPaused -> ElectricBlue
        isPaused -> WarningOrange.copy(alpha = 0.5f)
        else -> ErrorRed.copy(alpha = 0.3f)
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = when {
                    isActive && !isPaused -> "Active"
                    isPaused -> "Paused"
                    else -> "Off"
                }
            }
            .clickable(
                role = Role.Switch,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Rotating gradient ring
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidth = size.toPx() * 0.08f
            val arcSize = Size(
                size.toPx() - strokeWidth,
                size.toPx() - strokeWidth,
            )
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

            if (shouldRotate) {
                rotate(rotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                ringColor,
                                secondaryColor,
                                ringColor.copy(alpha = 0.3f),
                                ringColor,
                            ),
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }

                // Glow effect
                drawArc(
                    color = ringColor.copy(alpha = 0.15f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth * 2.5f, cap = StrokeCap.Round),
                )
            } else {
                // Static ring when off or paused
                drawArc(
                    color = ringColor.copy(alpha = 0.6f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        // Power icon in center
        Canvas(modifier = Modifier.size(size * 0.4f)) {
            val iconSize = size.toPx() * 0.2f
            val iconColor = if (isActive) ringColor else TextSecondary

            // Vertical line at top
            drawLine(
                color = iconColor,
                start = Offset(center.x, center.y - iconSize),
                end = Offset(center.x, center.y - iconSize * 0.2f),
                strokeWidth = size.toPx() * 0.04f,
                cap = StrokeCap.Round,
            )

            // Arc
            drawArc(
                color = iconColor,
                startAngle = -135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - iconSize, center.y - iconSize),
                size = Size(iconSize * 2, iconSize * 2),
                style = Stroke(width = size.toPx() * 0.04f, cap = StrokeCap.Round),
            )
        }
    }
}
