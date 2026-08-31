package com.aircontrol.ui.customgesture

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aircontrol.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.accessibility.displayNameRes
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.model.CustomGestureTrigger as Trigger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomGestureScreen(
    onNavigateBack: () -> Unit,
    viewModel: CustomGestureViewModel = hiltViewModel(),
) {
    val customGestures by viewModel.customGestures.collectAsState()
    val creatorState by viewModel.creatorState.collectAsState()
    var showCreator by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_gestures_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.custom_gestures_back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.resetCreator()
                        showCreator = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.custom_gestures_add_cd))
                    }
                },
            )
        },
    ) { padding ->
        if (showCreator) {
            CustomGestureCreatorPanel(
                state = creatorState,
                onNameChange = viewModel::updateName,
                onDescriptionChange = viewModel::updateDescription,
                onPoseChange = viewModel::updatePose,
                onDirectionChange = viewModel::updateDirection,
                onActionChange = viewModel::updateAction,
                onSave = {
                    viewModel.saveGesture()
                    showCreator = false
                },
                onCancel = {
                    showCreator = false
                    viewModel.resetCreator()
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (customGestures.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.custom_gestures_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.custom_gestures_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    customGestures.forEach { gesture ->
                        CustomGestureItem(
                            gesture = gesture,
                            onToggle = { enabled ->
                                viewModel.toggleGesture(gesture.id, enabled)
                            },
                            onEdit = {
                                viewModel.startEditing(gesture)
                                showCreator = true
                            },
                            onDelete = {
                                viewModel.deleteGesture(gesture.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomGestureItem(
    gesture: CustomGesture,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabledLabel = stringResource(if (gesture.isEnabled) R.string.custom_gestures_enabled else R.string.custom_gestures_disabled)
    val itemContentDescription = stringResource(R.string.custom_gestures_item_cd, gesture.name, enabledLabel)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics {
                contentDescription = itemContentDescription
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gesture.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTriggerDisplay(gesture.triggerPose) +
                        stringResource(R.string.custom_gestures_trigger_arrow) +
                        stringResource(gesture.action.displayNameRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = gesture.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.custom_gestures_edit_cd), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.custom_gestures_delete_cd))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomGestureCreatorPanel(
    state: CustomGestureCreatorState,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPoseChange: (CustomGesturePose) -> Unit,
    onDirectionChange: (CustomGestureDirection) -> Unit,
    onActionChange: (GestureAction) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(if (state.isEditing) R.string.custom_gestures_edit_title else R.string.custom_gestures_create_title),
            style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.gesture_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.gesture_description_label)) },
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.custom_trigger_pose), style = MaterialTheme.typography.titleSmall)
        CustomGesturePose.entries.forEach { pose ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = state.selectedPose == pose,
                    onClick = { onPoseChange(pose) },
                )
                Text(stringResource(pose.displayNameRes()), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text(stringResource(R.string.custom_direction_optional), style = MaterialTheme.typography.titleSmall)
        CustomGestureDirection.entries.forEach { direction ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = state.selectedDirection == direction,
                    onClick = { onDirectionChange(direction) },
                )
                Text(stringResource(direction.displayNameRes()), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text(stringResource(R.string.custom_action_label), style = MaterialTheme.typography.titleSmall)
        val availableActions = GestureAction.entries.filter { it != GestureAction.NONE }
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = stringResource(state.selectedAction.displayNameRes()),
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableActions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(action.displayNameRes())) },
                        onClick = {
                            onActionChange(action)
                            expanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onSave,
                enabled = state.isValid,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(if (state.isEditing) R.string.custom_gestures_update_button else R.string.custom_gestures_create_button)) }
        }
    }
}

/** Formats the trigger for display in the gesture list. */
@Composable
private fun formatTriggerDisplay(trigger: Trigger): String {
    return when (trigger) {
        is Trigger.PoseWithDirection -> {
            if (trigger.direction == CustomGestureDirection.NONE) {
                stringResource(trigger.pose.displayNameRes())
            } else {
                stringResource(trigger.pose.displayNameRes()) +
                    stringResource(R.string.custom_gestures_trigger_plus) +
                    stringResource(trigger.direction.displayNameRes())
            }
        }
        is Trigger.FingerCount -> {
            stringResource(R.string.custom_gestures_fingers_count, trigger.extendedFingers)
        }
        is Trigger.LandmarkTemplateTrigger -> {
            stringResource(R.string.custom_gestures_template_prefix, trigger.template.name)
        }
    }
}
