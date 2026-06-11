package com.aircontrol.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aircontrol.ui.Dimens
import com.aircontrol.ui.theme.ElectricBlue
import com.aircontrol.ui.theme.ErrorRed

/**
 * Reusable setting row with a switch toggle.
 */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.paddingSmall),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.iconMedium),
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacing12))
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = ElectricBlue,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier,
            )
        }
    }
}

/**
 * Reusable slider card with a live value label.
 */
@Composable
fun SettingSliderCard(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingMedium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = ElectricBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElectricBlue.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            androidx.compose.material3.Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Segmented button group for choosing among a small set of options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedButtonGroup(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelMapper: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selectedOption,
                onClick = { onOptionSelected(option) },
                label = { Text(labelMapper(option)) },
                leadingIcon = {
                    AnimatedVisibility(
                        visible = option == selectedOption,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSmall),
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                    selectedLabelColor = ElectricBlue,
                ),
            )
        }
    }
}

/**
 * Permission warning card with fix action.
 */
@Composable
fun PermissionWarningCard(
    title: String,
    message: String,
    onFixClick: () -> Unit,
    onReRunOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = ErrorRed.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingMedium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ErrorRed),
                )
                Spacer(modifier = Modifier.width(Dimens.spacing8))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ErrorRed,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing12))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacing8),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onFixClick,
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = ErrorRed,
                    ),
                ) {
                    Text(text = "Fix Now")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onReRunOnboarding,
                    shape = RoundedCornerShape(Dimens.buttonCornerRadius),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(text = "Re-run Setup")
                }
            }
        }
    }
}
