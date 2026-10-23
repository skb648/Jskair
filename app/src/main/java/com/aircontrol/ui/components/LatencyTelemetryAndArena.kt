package com.aircontrol.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aircontrol.R
import com.aircontrol.control.CursorState
import com.aircontrol.data.model.CursorResponseProfile
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.home.LivePipelineTelemetry
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.SuccessGreen
import com.aircontrol.ui.theme.TextSecondary
import com.aircontrol.ui.theme.WarningOrange
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Live Photon-to-Cursor Telemetry HUD + Quick Response Profile Selector.
 */
@Composable
fun LivePipelineTelemetryHud(
    telemetry: LivePipelineTelemetry,
    selectedProfile: CursorResponseProfile,
    onSelectProfile: (CursorResponseProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGpu = telemetry.activeDelegate.equals("GPU", ignoreCase = true)
    val badgeColor by animateColorAsState(
        targetValue = if (isGpu) SuccessGreen else ElectricBlue,
        label = "delegateBadgeColor",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_pipeline_telemetry_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingMedium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacing8))
                    Text(
                        text = stringResource(R.string.home_telemetry_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .border(1.dp, badgeColor.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag("telemetry_delegate_badge"),
                ) {
                    Text(
                        text = stringResource(R.string.home_telemetry_delegate_badge, telemetry.activeDelegate),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TelemetryMetricPill(
                    label = stringResource(R.string.home_telemetry_fps_label),
                    value = stringResource(
                        R.string.home_telemetry_fps_value,
                        telemetry.actualFps,
                        telemetry.configuredFps,
                    ),
                    accent = if (telemetry.actualFps >= (telemetry.configuredFps * 0.8f).toInt()) {
                        SuccessGreen
                    } else {
                        ElectricBlue
                    },
                )
                TelemetryMetricPill(
                    label = stringResource(R.string.home_telemetry_inference_label),
                    value = stringResource(R.string.home_telemetry_ms_value, telemetry.inferenceMs),
                    accent = if (telemetry.inferenceMs in 1..20) SuccessGreen else ElectricBlue,
                )
                TelemetryMetricPill(
                    label = stringResource(R.string.home_telemetry_latency_label),
                    value = stringResource(R.string.home_telemetry_ms_value, telemetry.photonToCursorMs),
                    accent = when {
                        telemetry.photonToCursorMs in 1..25 -> SuccessGreen
                        telemetry.photonToCursorMs in 26..45 -> ElectricBlue
                        telemetry.photonToCursorMs > 45 -> WarningOrange
                        else -> ElectricBlue
                    },
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            SegmentedButtonGroup(
                options = CursorResponseProfile.entries,
                selectedOption = selectedProfile,
                onOptionSelected = onSelectProfile,
                labelMapper = { profile ->
                    when (profile) {
                        CursorResponseProfile.INSTANT -> stringResource(R.string.profile_instant)
                        CursorResponseProfile.BALANCED -> stringResource(R.string.profile_balanced)
                        CursorResponseProfile.PRECISION -> stringResource(R.string.profile_precision)
                    }
                },
            )
        }
    }
}

@Composable
private fun TelemetryMetricPill(
    label: String,
    value: String,
    accent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/**
 * Interactive In-App Cursor Latency Test Arena on the Home screen.
 * Allows users to immediately benchmark their air cursor's responsiveness (hover or pinch/blink tap)
 * without leaving the app.
 */
@Composable
fun CursorLatencyArenaCard(
    cursorState: CursorState,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var arenaActive by remember { mutableStateOf(false) }
    var targetX by remember { mutableFloatStateOf(0.5f) }
    var targetY by remember { mutableFloatStateOf(0.5f) }
    var targetSpawnMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var hitsCount by remember { mutableIntStateOf(0) }
    var totalReactionMs by remember { mutableLongStateOf(0L) }
    var bestReactionMs by remember { mutableLongStateOf(0L) }

    val pulseTransition = rememberInfiniteTransition(label = "arenaPulse")
    val pulseRadiusScale by pulseTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    fun spawnNextTarget() {
        // Keep target comfortably away from edges and at least 0.22 normalized distance from previous
        var nextX: Float
        var nextY: Float
        var attempts = 0
        do {
            nextX = Random.nextFloat() * 0.72f + 0.14f
            nextY = Random.nextFloat() * 0.64f + 0.18f
            attempts++
        } while (attempts < 8 && hypot(nextX - targetX, nextY - targetY) < 0.22f)
        targetX = nextX
        targetY = nextY
        targetSpawnMs = SystemClock.elapsedRealtime()
    }

    fun recordHit() {
        val now = SystemClock.elapsedRealtime()
        val reaction = (now - targetSpawnMs).coerceIn(12L, 5000L)
        hitsCount++
        totalReactionMs += reaction
        if (bestReactionMs == 0L || reaction < bestReactionMs) {
            bestReactionMs = reaction
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        spawnNextTarget()
    }

    // Live air cursor hit detection when arena is active
    LaunchedEffect(arenaActive, cursorState.x, cursorState.y, cursorState.isVisible) {
        if (arenaActive && cursorState.isVisible) {
            val dist = hypot(cursorState.x - targetX, cursorState.y - targetY)
            if (dist <= ARENA_HIT_RADIUS_NORM) {
                recordHit()
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cursor_latency_arena_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingMedium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacing4))
                        Text(
                            text = stringResource(R.string.home_arena_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_arena_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }

                Spacer(modifier = Modifier.width(Dimens.spacing8))

                FilledTonalButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (!arenaActive) {
                            hitsCount = 0
                            totalReactionMs = 0L
                            bestReactionMs = 0L
                            arenaActive = true
                            spawnNextTarget()
                        } else {
                            arenaActive = false
                        }
                    },
                    modifier = Modifier.testTag("arena_toggle_button"),
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                ) {
                    Text(
                        text = stringResource(
                            if (arenaActive) R.string.home_arena_stop else R.string.home_arena_start,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing12))

            // Metrics bar
            val avgMs = if (hitsCount > 0) (totalReactionMs / hitsCount).toInt() else 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.home_arena_hits, hitsCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = ElectricBlue,
                )
                Text(
                    text = stringResource(R.string.home_arena_avg_reaction, avgMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.home_arena_best_reaction, bestReactionMs.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = SuccessGreen,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing8))

            // Interactive target arena canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B101B))
                    .border(1.dp, ElectricBlue.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .testTag("arena_canvas_box")
                    .pointerInput(arenaActive) {
                        detectTapGestures { tapOffset ->
                            if (!arenaActive) {
                                arenaActive = true
                                hitsCount = 0
                                totalReactionMs = 0L
                                bestReactionMs = 0L
                                spawnNextTarget()
                            } else {
                                val normTapX = (tapOffset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val normTapY = (tapOffset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                if (hypot(normTapX - targetX, normTapY - targetY) <= ARENA_HIT_RADIUS_NORM * 1.4f) {
                                    recordHit()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Subtle tactical grid
                    val gridStep = 28.dp.toPx()
                    var gx = gridStep
                    while (gx < w) {
                        drawLine(
                            color = ElectricBlue.copy(alpha = 0.07f),
                            start = Offset(gx, 0f),
                            end = Offset(gx, h),
                            strokeWidth = 1f,
                        )
                        gx += gridStep
                    }
                    var gy = gridStep
                    while (gy < h) {
                        drawLine(
                            color = ElectricBlue.copy(alpha = 0.07f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = 1f,
                        )
                        gy += gridStep
                    }

                    if (arenaActive) {
                        val center = Offset(targetX * w, targetY * h)
                        val baseR = 20.dp.toPx()

                        // Outer pulsing halo
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ElectricBlue.copy(alpha = 0.45f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = baseR * pulseRadiusScale * 1.6f,
                            ),
                            radius = baseR * pulseRadiusScale * 1.6f,
                            center = center,
                        )

                        // Outer ring
                        drawCircle(
                            color = ElectricBlue,
                            radius = baseR * pulseRadiusScale,
                            center = center,
                            style = Stroke(width = 2.dp.toPx()),
                        )

                        // Bullseye core
                        drawCircle(
                            color = SuccessGreen,
                            radius = 6.dp.toPx(),
                            center = center,
                        )
                    }

                    // Live mirrored air cursor indicator inside the arena
                    if (cursorState.isVisible) {
                        val cursorPos = Offset(cursorState.x * w, cursorState.y * h)
                        drawCircle(
                            color = Color.White,
                            radius = 5.dp.toPx(),
                            center = cursorPos,
                        )
                        drawCircle(
                            color = ElectricBlue,
                            radius = 9.dp.toPx(),
                            center = cursorPos,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }

                if (!arenaActive) {
                    Text(
                        text = stringResource(R.string.home_arena_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = Dimens.paddingMedium),
                    )
                }
            }
        }
    }
}

private const val ARENA_HIT_RADIUS_NORM = 0.11f
