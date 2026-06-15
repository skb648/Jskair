package com.aircontrol.ui.customgesture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/** State for the custom gesture creation/edit screen. */
data class CustomGestureCreatorState(
    val name: String = "",
    val description: String = "",
    val selectedPose: CustomGesturePose = CustomGesturePose.PINCH,
    val selectedDirection: CustomGestureDirection = CustomGestureDirection.NONE,
    val selectedAction: GestureAction = GestureAction.TAP,
    val isEditing: Boolean = false,
    val editingGestureId: String? = null,
    val isValid: Boolean = false,
    val isSaved: Boolean = false,
)

@HiltViewModel
class CustomGestureViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val customGestures = settingsRepository.customGestures
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _creatorState = MutableStateFlow(CustomGestureCreatorState())
    val creatorState: StateFlow<CustomGestureCreatorState> = _creatorState.asStateFlow()

    fun updateName(name: String) {
        _creatorState.value = _creatorState.value.copy(
            name = name,
            isValid = name.isNotBlank(),
        )
    }

    fun updateDescription(description: String) {
        _creatorState.value = _creatorState.value.copy(description = description)
    }

    fun updatePose(pose: CustomGesturePose) {
        _creatorState.value = _creatorState.value.copy(selectedPose = pose)
    }

    fun updateDirection(direction: CustomGestureDirection) {
        _creatorState.value = _creatorState.value.copy(selectedDirection = direction)
    }

    fun updateAction(action: GestureAction) {
        _creatorState.value = _creatorState.value.copy(selectedAction = action)
    }

    fun startEditing(gesture: CustomGesture) {
        val trigger = gesture.triggerPose as? CustomGestureTrigger.PoseWithDirection
        _creatorState.value = CustomGestureCreatorState(
            name = gesture.name,
            description = gesture.description,
            selectedPose = trigger?.pose ?: CustomGesturePose.PINCH,
            selectedDirection = trigger?.direction ?: CustomGestureDirection.NONE,
            selectedAction = gesture.action,
            isEditing = true,
            editingGestureId = gesture.id,
            isValid = true,
        )
    }

    fun resetCreator() {
        _creatorState.value = CustomGestureCreatorState()
    }

    fun saveGesture() {
        val state = _creatorState.value
        if (!state.isValid) return

        val gesture = CustomGesture(
            id = state.editingGestureId ?: UUID.randomUUID().toString(),
            name = state.name.trim(),
            description = state.description.trim(),
            triggerPose = CustomGestureTrigger.PoseWithDirection(
                pose = state.selectedPose,
                direction = state.selectedDirection,
            ),
            action = state.selectedAction,
            isEnabled = true,
        )

        viewModelScope.launch {
            if (state.isEditing) {
                settingsRepository.updateCustomGesture(gesture)
            } else {
                settingsRepository.addCustomGesture(gesture)
            }
            _creatorState.value = _creatorState.value.copy(isSaved = true)
            Timber.i("Custom gesture saved: %s -> %s", gesture.name, gesture.action)
        }
    }

    fun deleteGesture(gestureId: String) {
        viewModelScope.launch {
            settingsRepository.deleteCustomGesture(gestureId)
        }
    }

    fun toggleGesture(gestureId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.enableCustomGesture(gestureId, enabled)
        }
    }
}
