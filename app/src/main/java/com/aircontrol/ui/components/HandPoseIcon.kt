package com.aircontrol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aircontrol.ui.theme.ElectricBlue

/**
 * Custom Canvas-drawn hand pose icons for each gesture trigger.
 * No emoji — purely vector illustrations.
 */

@Composable
fun HandPoseIcon(
    poseKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = ElectricBlue,
) {
    Canvas(
        modifier = modifier.size(size),
    ) {
        val s = size.toPx()
        val cx = s / 2f
        val cy = s / 2f

        when (poseKey) {
            "swipe_left" -> drawSwipeLeft(s, cx, cy, color)
            "swipe_right" -> drawSwipeRight(s, cx, cy, color)
            "swipe_up" -> drawSwipeUp(s, cx, cy, color)
            "swipe_down" -> drawSwipeDown(s, cx, cy, color)
            "pose_pinch" -> drawPinch(s, cx, cy, color)
            "pose_pointing" -> drawPointing(s, cx, cy, color)
            "pose_victory" -> drawVictory(s, cx, cy, color)
            "pose_thumb_up" -> drawThumbUp(s, cx, cy, color)
            "pose_thumb_down" -> drawThumbDown(s, cx, cy, color)
            else -> drawOpenPalm(s, cx, cy, color)
        }
    }
}

// ===== Swipe icons =====

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSwipeLeft(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.06f
    val arrowLen = s * 0.35f
    val headLen = s * 0.12f

    // Arrow shaft
    drawLine(
        color = color,
        start = Offset(cx + arrowLen / 2, cy),
        end = Offset(cx - arrowLen / 2, cy),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    // Arrow head
    drawLine(
        color = color,
        start = Offset(cx - arrowLen / 2, cy),
        end = Offset(cx - arrowLen / 2 + headLen, cy - headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx - arrowLen / 2, cy),
        end = Offset(cx - arrowLen / 2 + headLen, cy + headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    // Motion lines
    drawLine(
        color = color.copy(alpha = 0.4f),
        start = Offset(cx + arrowLen * 0.15f, cy - s * 0.15f),
        end = Offset(cx + arrowLen * 0.45f, cy - s * 0.15f),
        strokeWidth = strokeW * 0.6f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = 0.4f),
        start = Offset(cx + arrowLen * 0.2f, cy + s * 0.15f),
        end = Offset(cx + arrowLen * 0.5f, cy + s * 0.15f),
        strokeWidth = strokeW * 0.6f,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSwipeRight(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.06f
    val arrowLen = s * 0.35f
    val headLen = s * 0.12f

    drawLine(
        color = color,
        start = Offset(cx - arrowLen / 2, cy),
        end = Offset(cx + arrowLen / 2, cy),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx + arrowLen / 2, cy),
        end = Offset(cx + arrowLen / 2 - headLen, cy - headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx + arrowLen / 2, cy),
        end = Offset(cx + arrowLen / 2 - headLen, cy + headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = 0.4f),
        start = Offset(cx - arrowLen * 0.45f, cy - s * 0.15f),
        end = Offset(cx - arrowLen * 0.15f, cy - s * 0.15f),
        strokeWidth = strokeW * 0.6f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color.copy(alpha = 0.4f),
        start = Offset(cx - arrowLen * 0.5f, cy + s * 0.15f),
        end = Offset(cx - arrowLen * 0.2f, cy + s * 0.15f),
        strokeWidth = strokeW * 0.6f,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSwipeUp(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.06f
    val arrowLen = s * 0.35f
    val headLen = s * 0.12f

    drawLine(
        color = color,
        start = Offset(cx, cy + arrowLen / 2),
        end = Offset(cx, cy - arrowLen / 2),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy - arrowLen / 2),
        end = Offset(cx - headLen, cy - arrowLen / 2 + headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy - arrowLen / 2),
        end = Offset(cx + headLen, cy - arrowLen / 2 + headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSwipeDown(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.06f
    val arrowLen = s * 0.35f
    val headLen = s * 0.12f

    drawLine(
        color = color,
        start = Offset(cx, cy - arrowLen / 2),
        end = Offset(cx, cy + arrowLen / 2),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy + arrowLen / 2),
        end = Offset(cx - headLen, cy + arrowLen / 2 - headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(cx, cy + arrowLen / 2),
        end = Offset(cx + headLen, cy + arrowLen / 2 - headLen),
        strokeWidth = strokeW,
        cap = StrokeCap.Round,
    )
}

// ===== Pose icons =====

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOpenPalm(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f
    val palmH = s * 0.25f
    val palmW = s * 0.3f
    val fingerH = s * 0.22f
    val fingerSpacing = palmW / 5f

    // Palm outline
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - palmW / 2, cy),
        size = androidx.compose.ui.geometry.Size(palmW, palmH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f),
        style = Stroke(width = strokeW),
    )

    // Five fingers
    val fingerBaseY = cy
    for (i in 0..4) {
        val fx = cx - palmW / 2 + fingerSpacing * (i + 0.5f)
        val fh = when (i) {
            0 -> fingerH * 0.7f  // thumb
            1 -> fingerH * 1.1f  // index
            2 -> fingerH * 1.2f  // middle
            3 -> fingerH * 1.0f  // ring
            4 -> fingerH * 0.8f  // pinky
            else -> fingerH
        }
        drawLine(
            color = color,
            start = Offset(fx, fingerBaseY),
            end = Offset(fx, fingerBaseY - fh),
            strokeWidth = strokeW * 1.2f,
            cap = StrokeCap.Round,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPinch(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f
    val radius = s * 0.18f

    // Thumb (curved from bottom-left)
    val thumbStart = Offset(cx - s * 0.25f, cy + s * 0.1f)
    val thumbEnd = Offset(cx - s * 0.06f, cy - s * 0.02f)
    drawLine(color, thumbStart, thumbEnd, strokeWidth = strokeW * 1.3f, cap = StrokeCap.Round)

    // Index finger (curved from bottom-right)
    val indexStart = Offset(cx + s * 0.25f, cy + s * 0.1f)
    val indexEnd = Offset(cx + s * 0.06f, cy - s * 0.02f)
    drawLine(color, indexStart, indexEnd, strokeWidth = strokeW * 1.3f, cap = StrokeCap.Round)

    // Pinch point circle
    drawCircle(
        color = color.copy(alpha = 0.4f),
        radius = s * 0.05f,
        center = Offset(cx, cy - s * 0.02f),
    )

    // Other fingers (curled, small arcs at bottom)
    drawArc(
        color = color.copy(alpha = 0.5f),
        startAngle = 10f,
        sweepAngle = 160f,
        useCenter = false,
        topLeft = Offset(cx - s * 0.12f, cy + s * 0.08f),
        size = androidx.compose.ui.geometry.Size(s * 0.24f, s * 0.1f),
        style = Stroke(width = strokeW * 0.8f, cap = StrokeCap.Round),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPointing(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f
    val palmH = s * 0.22f
    val palmW = s * 0.26f

    // Palm
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - palmW / 2, cy + s * 0.05f),
        size = androidx.compose.ui.geometry.Size(palmW, palmH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.03f),
        style = Stroke(width = strokeW),
    )

    // Extended index finger
    val indexX = cx - palmW * 0.15f
    drawLine(
        color = color,
        start = Offset(indexX, cy + s * 0.05f),
        end = Offset(indexX, cy - s * 0.3f),
        strokeWidth = strokeW * 1.3f,
        cap = StrokeCap.Round,
    )

    // Finger tip dot
    drawCircle(
        color = color,
        radius = s * 0.025f,
        center = Offset(indexX, cy - s * 0.3f),
    )

    // Curled fingers (small bumps)
    for (i in 1..3) {
        val fx = cx - palmW * 0.15f + palmW * 0.2f * i
        drawArc(
            color = color.copy(alpha = 0.5f),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(fx - s * 0.03f, cy + s * 0.01f),
            size = androidx.compose.ui.geometry.Size(s * 0.06f, s * 0.05f),
            style = Stroke(width = strokeW * 0.7f, cap = StrokeCap.Round),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVictory(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f
    val palmH = s * 0.22f
    val palmW = s * 0.26f

    // Palm
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - palmW / 2, cy + s * 0.05f),
        size = androidx.compose.ui.geometry.Size(palmW, palmH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.03f),
        style = Stroke(width = strokeW),
    )

    // V sign: index and middle fingers spread
    val spread = s * 0.08f
    val fingerLen = s * 0.28f
    val baseY = cy + s * 0.05f

    // Index finger (angled left)
    drawLine(
        color = color,
        start = Offset(cx - spread * 0.3f, baseY),
        end = Offset(cx - spread, baseY - fingerLen),
        strokeWidth = strokeW * 1.3f,
        cap = StrokeCap.Round,
    )

    // Middle finger (angled right)
    drawLine(
        color = color,
        start = Offset(cx + spread * 0.3f, baseY),
        end = Offset(cx + spread, baseY - fingerLen),
        strokeWidth = strokeW * 1.3f,
        cap = StrokeCap.Round,
    )

    // Curled ring and pinky
    drawArc(
        color = color.copy(alpha = 0.5f),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx + s * 0.04f, cy + s * 0.01f),
        size = androidx.compose.ui.geometry.Size(s * 0.1f, s * 0.06f),
        style = Stroke(width = strokeW * 0.7f, cap = StrokeCap.Round),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumbUp(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f

    // Fist body (rounded rectangle, vertical)
    val fistW = s * 0.24f
    val fistH = s * 0.28f
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - fistW / 2, cy + s * 0.02f),
        size = androidx.compose.ui.geometry.Size(fistW, fistH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f),
        style = Stroke(width = strokeW),
    )

    // Thumb pointing up
    drawLine(
        color = color,
        start = Offset(cx - fistW * 0.25f, cy + s * 0.02f),
        end = Offset(cx - fistW * 0.25f, cy - s * 0.22f),
        strokeWidth = strokeW * 1.4f,
        cap = StrokeCap.Round,
    )

    // Thumb tip
    drawCircle(
        color = color,
        radius = s * 0.03f,
        center = Offset(cx - fistW * 0.25f, cy - s * 0.22f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumbDown(
    s: Float, cx: Float, cy: Float, color: Color,
) {
    val strokeW = s * 0.05f

    // Fist body
    val fistW = s * 0.24f
    val fistH = s * 0.28f
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - fistW / 2, cy - s * 0.12f),
        size = androidx.compose.ui.geometry.Size(fistW, fistH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f),
        style = Stroke(width = strokeW),
    )

    // Thumb pointing down
    drawLine(
        color = color,
        start = Offset(cx + fistW * 0.25f, cy - s * 0.12f),
        end = Offset(cx + fistW * 0.25f, cy + s * 0.22f),
        strokeWidth = strokeW * 1.4f,
        cap = StrokeCap.Round,
    )

    // Thumb tip
    drawCircle(
        color = color,
        radius = s * 0.03f,
        center = Offset(cx + fistW * 0.25f, cy + s * 0.22f),
    )
}
