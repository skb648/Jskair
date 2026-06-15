package com.aircontrol.ui.customgesture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomGestureScreen(
    onNavigateBack: () -> Unit,
    viewModel: CustomGestureViewModel = hiltViewModel(),
) {
    val customGestures by viewModel.customGestures.collectAsState()
    val creatorState by viewModel.creatorState.collectAsState()
    var showCreator by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Gestures") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.resetCreator()
                        showCreator = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add gesture")
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
                                "No custom gestures yet",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tap + to create a custom gesture",
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                    text = "${gesture.triggerPose.displayName()} → ${gesture.action.displayName()}",
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
                Text("✏", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

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
            if (state.isEditing) "Edit Gesture" else "Create Gesture",
            style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("Gesture Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Trigger Pose", style = MaterialTheme.typography.titleSmall)
        CustomGesturePose.entries.forEach { pose ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = state.selectedPose == pose,
                    onClick = { onPoseChange(pose) },
                )
                Text(pose.displayName(), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text("Direction (optional)", style = MaterialTheme.typography.titleSmall)
        CustomGestureDirection.entries.forEach { direction ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = state.selectedDirection == direction,
                    onClick = { onDirectionChange(direction) },
                )
                Text(direction.displayName(), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text("Action", style = MaterialTheme.typography.titleSmall)
        val availableActions = GestureAction.entries.filter { it != GestureAction.NONE }
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = state.selectedAction.displayName(),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableActions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.displayName()) },
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
            ) { Text("Cancel") }
            Button(
                onClick = onSave,
                enabled = state.isValid,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.isEditing) "Update" else "Create") }
        }
    }
}

private fun CustomGestureTrigger.displayName(): String {
    return when (this) {
        is CustomGestureTrigger.PoseWithDirection -> {
            if (direction == CustomGestureDirection.NONE) {
                pose.displayName()
            } else {
                "${pose.displayName()} + ${direction.displayName()}"
            }
        }
        is CustomGestureTrigger.FingerCount -> {
            "$extendedFingers fingers"
        }
    }
}

private fun CustomGesturePose.displayName(): String = when (this) {
    CustomGesturePose.OPEN_PALM -> "Open Palm"
    CustomGesturePose.FIST -> "Fist"
    CustomGesturePose.PINCH -> "Pinch"
    CustomGesturePose.POINTING -> "Pointing"
    CustomGesturePose.VICTORY -> "Victory (Peace)"
    CustomGesturePose.THUMB_UP -> "Thumb Up"
    CustomGesturePose.THUMB_DOWN -> "Thumb Down"
    CustomGesturePose.THREE_FINGERS -> "Three Fingers"
    CustomGesturePose.FOUR_FINGERS -> "Four Fingers"
}

private fun CustomGestureDirection.displayName(): String = when (this) {
    CustomGestureDirection.NONE -> "No Direction"
    CustomGestureDirection.LEFT -> "Swipe Left"
    CustomGestureDirection.RIGHT -> "Swipe Right"
    CustomGestureDirection.UP -> "Swipe Up"
    CustomGestureDirection.DOWN -> "Swipe Down"
}

private fun GestureAction.displayName(): String = when (this) {
    GestureAction.NONE -> "None"
    GestureAction.SCROLL_UP -> "Scroll Up"
    GestureAction.SCROLL_DOWN -> "Scroll Down"
    GestureAction.SCROLL_LEFT -> "Scroll Left"
    GestureAction.SCROLL_RIGHT -> "Scroll Right"
    GestureAction.BACK -> "Back"
    GestureAction.HOME -> "Home"
    GestureAction.RECENTS -> "Recents"
    GestureAction.NOTIFICATIONS -> "Notifications"
    GestureAction.QUICK_SETTINGS -> "Quick Settings"
    GestureAction.VOLUME_UP -> "Volume Up"
    GestureAction.VOLUME_DOWN -> "Volume Down"
    GestureAction.MEDIA_PLAY_PAUSE -> "Play/Pause"
    GestureAction.SCREENSHOT -> "Screenshot"
    GestureAction.LOCK_SCREEN -> "Lock Screen"
    GestureAction.TAP -> "Tap"
    GestureAction.LONG_PRESS -> "Long Press"
    GestureAction.DRAG -> "Drag"
}
