package com.aircontrol.ui.customgesture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.ui.Suppression
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.tracking.HandTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.math.hypot

/** State for the custom gesture creation/edit screen. */
data class CustomGestureCreatorState(
    val name: String = "",
    val description: String = "",
    val selectedPose: CustomGesturePose = CustomGesturePose.PINCH,
    val selectedDirection: CustomGestureDirection = CustomGestureDirection.NONE,
    val selectedAction: GestureAction = GestureAction.TAP,
    val isEditing: Boolean = false,
    val editingGestureId: String? = null,
    val isEditingFingerCount: Boolean = false,
    val isEditingLandmarkTemplate: Boolean = false,
    // Fix (user test: custom gestures "bilkul dead"): live template recording.
    val capturedTemplate: CustomGestureTrigger.LandmarkTemplateTrigger? = null,
    val isCapturingTemplate: Boolean = false,
    val templateCaptureFailed: Boolean = false,
    val isValid: Boolean = false,
    val isSaved: Boolean = false,
)

@HiltViewModel
class CustomGestureViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val handTracker: HandTracker,
) : ViewModel() {

    init {
        // Fix B-3: while a setup flow is on screen, the accessibility service
        // must not act on the gestures the user is making *for* that flow. Without
        // this, pinching to press "Next" also sent a tap through to the
        // calibration screen, and swiping to test a pose scrolled the app out from
        // under the finger. The service reads [Suppression.isSuppressed].
        Suppression.acquire()
    }

    override fun onCleared() {
        Suppression.release()
        super.onCleared()
    }

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
        when (gesture.triggerPose) {
            is CustomGestureTrigger.FingerCount -> {
                // Keep FingerCount trigger, don't default to PoseWithDirection
                _creatorState.value = CustomGestureCreatorState(
                    name = gesture.name,
                    description = gesture.description,
                    selectedPose = CustomGesturePose.PINCH,
                    selectedDirection = CustomGestureDirection.NONE,
                    selectedAction = gesture.action,
                    isEditing = true,
                    editingGestureId = gesture.id,
                    isEditingFingerCount = true,
                    isValid = true,
                )
                return // Don't continue with PoseWithDirection flow
            }
            is CustomGestureTrigger.LandmarkTemplateTrigger -> {
                // Keep the recorded landmark template. Previously this fell through
                // to the PoseWithDirection branch and silently replaced the recorded
                // template with a PINCH pose, destroying the custom gesture.
                _creatorState.value = CustomGestureCreatorState(
                    name = gesture.name,
                    description = gesture.description,
                    selectedPose = CustomGesturePose.PINCH,
                    selectedDirection = CustomGestureDirection.NONE,
                    selectedAction = gesture.action,
                    isEditing = true,
                    editingGestureId = gesture.id,
                    isEditingLandmarkTemplate = true,
                    isValid = true,
                )
            }
            else -> {
                val trigger = gesture.triggerPose as? CustomGestureTrigger.PoseWithDirection
                _creatorState.value = CustomGestureCreatorState(
                    name = gesture.name,
                    description = gesture.description,
                    selectedPose = trigger?.pose ?: CustomGesturePose.PINCH,
                    selectedDirection = trigger?.direction ?: CustomGestureDirection.NONE,
                    selectedAction = gesture.action,
                    isEditing = true,
                    editingGestureId = gesture.id,
                    isEditingFingerCount = false,
                    isValid = true,
                )
            }
        }
    }

    fun resetCreator() {
        _creatorState.value = CustomGestureCreatorState()
    }

    /**
     * Fix (user test + audit #11/#12: custom gestures were effectively dead — a
     * template trigger could only ever come from legacy persisted JSON; NOTHING
     * in the app could record one). Samples the live hand tracker for a couple
     * of seconds and averages the SAME normalization the engine matcher uses
     * (pair distances / wrist→middle-MCP span), so the recorded template and
     * live matching live in exactly one coordinate space.
     */
    fun captureTemplate() {
        if (_creatorState.value.isCapturingTemplate) return
        _creatorState.value = _creatorState.value.copy(
            isCapturingTemplate = true,
            templateCaptureFailed = false,
            capturedTemplate = null,
        )
        viewModelScope.launch {
            val frames = withTimeoutOrNull(TEMPLATE_CAPTURE_TIMEOUT_MS) {
                handTracker.handFrames
                    .filter { it.isDetected && it.landmarks.size >= 21 }
                    .take(TEMPLATE_CAPTURE_FRAMES)
                    .toList()
            }
            if (frames == null || frames.size < TEMPLATE_CAPTURE_FRAMES) {
                _creatorState.value = _creatorState.value.copy(
                    isCapturingTemplate = false,
                    templateCaptureFailed = true,
                )
                Timber.w("Template capture failed: only %d usable frames", frames?.size ?: 0)
                return@launch
            }
            val acc = FloatArray(LandmarkTemplate.EXPECTED_DISTANCE_COUNT)
            for (frame in frames) {
                val lm = frame.landmarks
                // Same hand-size normalization as StaticPoseClassifier.
                val handSize = hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y)
                if (handSize < 1e-3f) continue
                for (i in LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS.indices) {
                    val (a, b) = LandmarkTemplate.TEMPLATE_LANDMARK_PAIRS[i]
                    acc[i] += hypot(lm[a].x - lm[b].x, lm[a].y - lm[b].y) / handSize
                }
            }
            val averaged = acc.map { it / TEMPLATE_CAPTURE_FRAMES }
            val template = LandmarkTemplate(
                gestureId = UUID.randomUUID().toString(),
                name = _creatorState.value.name.trim().ifBlank { "Shape" },
                normalizedDistances = averaged,
            )
            _creatorState.value = _creatorState.value.copy(
                isCapturingTemplate = false,
                capturedTemplate = CustomGestureTrigger.LandmarkTemplateTrigger(template),
                isValid = _creatorState.value.name.isNotBlank(),
            )
            Timber.i("Hand-shape template recorded (%d frames)", TEMPLATE_CAPTURE_FRAMES)
        }
    }

    fun saveGesture() {
        val state = _creatorState.value
        if (!state.isValid) return

        // When editing, preserve the original isEnabled state and FingerCount trigger
        val originalGesture = if (state.isEditing && state.editingGestureId != null) {
            customGestures.value.find { it.id == state.editingGestureId }
        } else {
            null
        }

        // Preserve the original trigger when editing FingerCount or recorded
        // LandmarkTemplate gestures (neither is editable via this screen's
        // pose/direction pickers, so rebuilding one would destroy it).
        val triggerPose = when {
            state.capturedTemplate != null -> state.capturedTemplate!!
            state.isEditingFingerCount && originalGesture?.triggerPose is CustomGestureTrigger.FingerCount ->
                originalGesture.triggerPose
            state.isEditingLandmarkTemplate && originalGesture?.triggerPose is CustomGestureTrigger.LandmarkTemplateTrigger ->
                originalGesture.triggerPose
            else -> CustomGestureTrigger.PoseWithDirection(
                pose = state.selectedPose,
                direction = state.selectedDirection,
            )
        }

        val gesture = CustomGesture(
            id = state.editingGestureId ?: UUID.randomUUID().toString(),
            name = state.name.trim(),
            description = state.description.trim(),
            triggerPose = triggerPose,
            action = state.selectedAction,
            isEnabled = originalGesture?.isEnabled ?: true,
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

    companion object {
        /** ~2s of usable frames at ~15fps of clean hand detections. */
        private const val TEMPLATE_CAPTURE_FRAMES = 30
        private const val TEMPLATE_CAPTURE_TIMEOUT_MS = 4_000L
    }
}
