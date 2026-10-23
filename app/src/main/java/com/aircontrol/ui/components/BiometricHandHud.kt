package com.aircontrol.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.Pose
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary
import com.aircontrol.ui.theme.WarningOrange

private val HudBoneChains = arrayOf(
    intArrayOf(0, 1, 2, 3, 4),       // Thumb
    intArrayOf(0, 5, 6, 7, 8),       // Index
    intArrayOf(0, 9, 10, 11, 12),    // Middle
    intArrayOf(0, 13, 14, 15, 16),   // Ring
    intArrayOf(0, 17, 18, 19, 20),   // Pinky
    intArrayOf(5, 9, 13, 17, 0),     // Palm bridge
)

private val FingertipIndices = intArrayOf(4, 8, 12, 16, 20)

/**
 * UI representation of a single tracked hand for the 21-point Biometric HUD.
 */
data class HudHandTrack(
    val trackId: Int,
    val handedness: String,
    val confidence: Float,
    val isValid: Boolean,
    val isPrimaryPointer: Boolean,
    val landmarks: List<Pair<Float, Float>>,
    val pinchProximity: Float = 0f,
)

/**
 * Real-time 21-point MediaPipe Dual-Hand Skeleton & Tactical Biometric HUD.
 *
 * Displays:
 * - Tactical HUD corner brackets, radar crosshair, and scanning sweep
 * - Up to two simultaneous 21-landmark hand skeletons (Left and Right) with per-hand track IDs and confidence
 * - Dynamic pinch-tension vector line between thumb tip (4) and index tip (8) for each hand
 * - Telemetry strip for Engine State, Active Pose, Pinch Tension %, and Last Action
 */
@Composable
fun BiometricHandHud(
    landmarks: List<Pair<Float, Float>>,
    handDetected: Boolean,
    trackedHands: List<HudHandTrack> = emptyList(),
    engineState: GestureEngineState = GestureEngineState.DISARMED,
    currentPose: Pose = Pose.NONE,
    pinchProximity: Float = 0f,
    lastActionLabel: String = "",
    hudHeight: Dp = 190.dp,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hud_scan")
    val scanRatio by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan_line",
    )

    val effectiveHands = if (trackedHands.isNotEmpty()) {
        trackedHands.filter { it.landmarks.size >= 21 }
    } else if (handDetected && landmarks.size >= 21) {
        listOf(
            HudHandTrack(
                trackId = 1,
                handedness = "HAND",
                confidence = 1f,
                isValid = true,
                isPrimaryPointer = true,
                landmarks = landmarks,
                pinchProximity = pinchProximity,
            ),
        )
    } else {
        emptyList()
    }
    val anyHandVisible = effectiveHands.isNotEmpty()

    val stateColor = when (engineState) {
        GestureEngineState.ARMED,
        GestureEngineState.EXECUTING,
        GestureEngineState.COOLDOWN,
        -> SuccessGreen
        GestureEngineState.ARMING -> WarningOrange
        GestureEngineState.DISARMED -> ElectricBlue
    }

    val containerShape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(Color(0xFF090E1A))
            .border(
                width = 1.dp,
                color = if (anyHandVisible) stateColor.copy(alpha = 0.45f) else ElectricBlue.copy(alpha = 0.18f),
                shape = containerShape,
            )
            .padding(12.dp),
    ) {
        // Top HUD Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (anyHandVisible) stateColor else Color(0xFF475569)),
                )
                val headerTitle = when {
                    effectiveHands.size >= 2 -> "DUAL-HAND LOCK // 2×21-PT"
                    anyHandVisible -> "BIOMETRIC LOCK // 21-PT"
                    else -> "SCANNING OPTICAL FIELD"
                }
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    ),
                    color = if (anyHandVisible) stateColor else TextSecondary,
                )
            }

            val poseLabel = when {
                !anyHandVisible -> "NO TARGET"
                currentPose != Pose.NONE -> currentPose.name.replace('_', ' ')
                else -> engineState.name
            }
            Text(
                text = poseLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (anyHandVisible) ElectricBlue else TextSecondary,
            )
        }

        if (effectiveHands.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                effectiveHands.forEachIndexed { idx, hand ->
                    val badgeColor = if (idx == 0) ElectricBlue else Color(0xFF22D3EE)
                    val confPct = (hand.confidence * 100f).toInt().coerceIn(0, 100)
                    val pointerTag = if (hand.isPrimaryPointer) " [PTR]" else ""
                    val validTag = if (hand.isValid) "" else " [HOLD]"
                    Text(
                        text = "${hand.handedness} #${hand.trackId} ${confPct}%$pointerTag$validTag",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = badgeColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main 21-Point Skeleton Radar Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(hudHeight),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val density = this.density

                // 1. Subtle tactical grid lines
                val gridColor = ElectricBlue.copy(alpha = 0.07f)
                val gridStroke = 1f * density
                for (i in 1..3) {
                    val gx = w * (i / 4f)
                    val gy = h * (i / 4f)
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = gridStroke)
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = gridStroke)
                }

                // 2. Tactical corner brackets
                val bracketLen = 14f * density
                val bracketStroke = 1.8f * density
                val bracketColor = (if (anyHandVisible) stateColor else ElectricBlue).copy(alpha = 0.55f)
                drawLine(bracketColor, Offset(0f, 0f), Offset(bracketLen, 0f), bracketStroke)
                drawLine(bracketColor, Offset(0f, 0f), Offset(0f, bracketLen), bracketStroke)
                drawLine(bracketColor, Offset(w - bracketLen, 0f), Offset(w, 0f), bracketStroke)
                drawLine(bracketColor, Offset(w, 0f), Offset(w, bracketLen), bracketStroke)
                drawLine(bracketColor, Offset(0f, h - bracketLen), Offset(0f, h), bracketStroke)
                drawLine(bracketColor, Offset(0f, h), Offset(bracketLen, h), bracketStroke)
                drawLine(bracketColor, Offset(w - bracketLen, h), Offset(w, h), bracketStroke)
                drawLine(bracketColor, Offset(w, h - bracketLen), Offset(w, h), bracketStroke)

                if (anyHandVisible) {
                    val isDual = effectiveHands.size >= 2
                    effectiveHands.forEachIndexed { handIdx, handTrack ->
                        val handPts = handTrack.landmarks
                        var minX = 1f
                        var maxX = 0f
                        var minY = 1f
                        var maxY = 0f
                        for (pt in handPts) {
                            if (pt.first < minX) minX = pt.first
                            if (pt.first > maxX) maxX = pt.first
                            if (pt.second < minY) minY = pt.second
                            if (pt.second > maxY) maxY = pt.second
                        }
                        val spanX = (maxX - minX).coerceAtLeast(0.18f)
                        val spanY = (maxY - minY).coerceAtLeast(0.18f)
                        val cxNorm = (minX + maxX) * 0.5f
                        val cyNorm = (minY + maxY) * 0.5f

                        // When two hands are visible, place them in left/right sub-viewports ordered by horizontal camera position
                        // or track order so both 21-point skeletons are crisp and never overlap into an unreadable tangle.
                        val viewportCenterX = if (!isDual) {
                            w * 0.5f
                        } else {
                            if (cxNorm <= 0.5f) w * 0.27f else w * 0.73f
                        }
                        val availWidth = if (isDual) w * 0.44f else w
                        val scale = (minOf(availWidth, h) * 0.72f) / maxOf(spanX, spanY)
                        val primaryBoneColor = when {
                            !handTrack.isValid -> WarningOrange
                            handIdx == 0 -> ElectricBlue
                            else -> Color(0xFF22D3EE)
                        }

                        fun mapPt(idx: Int): Offset {
                            val pt = handPts[idx]
                            val px = viewportCenterX + (pt.first - cxNorm) * scale
                            val py = h * 0.5f + (pt.second - cyNorm) * scale
                            return Offset(px.coerceIn(8f, w - 8f), py.coerceIn(8f, h - 8f))
                        }

                        val boneStroke = 2.2f * density
                        val glowStroke = 5.5f * density
                        for (chain in HudBoneChains) {
                            for (i in 0 until chain.size - 1) {
                                val p1 = mapPt(chain[i])
                                val p2 = mapPt(chain[i + 1])
                                drawLine(
                                    color = primaryBoneColor.copy(alpha = 0.22f),
                                    start = p1,
                                    end = p2,
                                    strokeWidth = glowStroke,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    color = primaryBoneColor.copy(alpha = 0.9f),
                                    start = p1,
                                    end = p2,
                                    strokeWidth = boneStroke,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }

                        val handPinch = if (handTrack.pinchProximity > 0f) handTrack.pinchProximity else if (handIdx == 0) pinchProximity else 0f
                        val thumbTip = mapPt(4)
                        val indexTip = mapPt(8)
                        if (handPinch > 0.1f) {
                            val pinchColor = if (handPinch > 0.78f) WarningOrange else SuccessGreen
                            drawLine(
                                color = pinchColor.copy(alpha = (0.35f + 0.65f * handPinch).coerceIn(0f, 1f)),
                                start = thumbTip,
                                end = indexTip,
                                strokeWidth = (1.5f + 2.0f * handPinch) * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)),
                            )
                            val mid = Offset((thumbTip.x + indexTip.x) * 0.5f, (thumbTip.y + indexTip.y) * 0.5f)
                            drawCircle(
                                color = pinchColor.copy(alpha = 0.28f * handPinch),
                                radius = (14f * (1f - 0.5f * handPinch)) * density,
                                center = mid,
                            )
                        }

                        val jointRadius = 2.8f * density
                        val tipRadius = 4.2f * density
                        for (idx in 0 until 21) {
                            val pt = mapPt(idx)
                            val isTip = idx in FingertipIndices
                            val isPinchPair = idx == 4 || idx == 8
                            val nodeColor = when {
                                isPinchPair && handPinch > 0.75f -> WarningOrange
                                isTip -> SuccessGreen
                                idx == 0 -> primaryBoneColor
                                else -> Color.White
                            }
                            if (isTip) {
                                drawCircle(
                                    color = nodeColor.copy(alpha = 0.28f),
                                    radius = tipRadius * 1.9f,
                                    center = pt,
                                )
                            }
                            drawCircle(
                                color = nodeColor,
                                radius = if (isTip) tipRadius else jointRadius,
                                center = pt,
                            )
                        }
                    }
                } else {
                    // Idle scanning reticle + horizontal laser sweep
                    val scanY = h * scanRatio
                    drawLine(
                        color = ElectricBlue.copy(alpha = 0.28f),
                        start = Offset(w * 0.08f, scanY),
                        end = Offset(w * 0.92f, scanY),
                        strokeWidth = 1.5f * density,
                    )
                    val r = minOf(w, h) * 0.24f
                    drawCircle(
                        color = ElectricBlue.copy(alpha = 0.2f),
                        radius = r,
                        center = Offset(w * 0.5f, h * 0.5f),
                        style = Stroke(
                            width = 1.5f * density,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 6f * density)),
                        ),
                    )
                }
            }

            if (!anyHandVisible) {
                Text(
                    text = "Raise one or both hands in front of the camera",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom Telemetry Footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pinchPct = (pinchProximity * 100).toInt().coerceIn(0, 100)
            Text(
                text = "PINCH TENSION: $pinchPct%  •  HANDS: ${effectiveHands.size}/2",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                color = if (pinchPct > 75) WarningOrange else TextSecondary,
            )

            if (lastActionLabel.isNotBlank()) {
                Text(
                    text = "LAST: $lastActionLabel",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                    ),
                    color = SuccessGreen,
                )
            } else {
                Text(
                    text = "STATE: ${engineState.name}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = stateColor,
                )
            }
        }
    }
}
