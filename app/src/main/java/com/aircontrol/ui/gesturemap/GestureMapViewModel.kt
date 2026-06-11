package com.aircontrol.ui.gesturemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.accessibility.ActionDispatcher
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.GestureMapEntry
import com.aircontrol.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ConflictInfo(
    val existingKey: String,
    val existingLabel: String,
    val existingAction: GestureAction,
    val newKey: String,
    val newAction: GestureAction,
)

@HiltViewModel
class GestureMapViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val gestureMapConfig: StateFlow<GestureMapConfig> = settingsRepository.gestureMapConfig
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GestureMapConfig(),
        )

    private val _selectedEntry = MutableStateFlow<GestureMapEntry?>(null)
    val selectedEntry: StateFlow<GestureMapEntry?> = _selectedEntry.asStateFlow()

    private val _showActionSheet = MutableStateFlow(false)
    val showActionSheet: StateFlow<Boolean> = _showActionSheet.asStateFlow()

    private val _conflictInfo = MutableStateFlow<ConflictInfo?>(null)
    val conflictInfo: StateFlow<ConflictInfo?> = _conflictInfo.asStateFlow()

    private val _showResetSnackbar = MutableStateFlow(false)
    val showResetSnackbar: StateFlow<Boolean> = _showResetSnackbar.asStateFlow()

    private var _undoConfig: GestureMapConfig? = null

    fun onEntryClicked(entry: GestureMapEntry) {
        _selectedEntry.value = entry
        _showActionSheet.value = true
        _conflictInfo.value = null
    }

    fun onActionSelected(action: GestureAction) {
        val entry = _selectedEntry.value ?: return

        // Check for conflicts: is this action already assigned elsewhere?
        val currentConfig = gestureMapConfig.value
        val existingEntry = currentConfig.entries.find {
            it.key != entry.key && it.action == action && action != GestureAction.NONE
        }

        if (existingEntry != null) {
            // Show conflict dialog
            _conflictInfo.value = ConflictInfo(
                existingKey = existingEntry.key,
                existingLabel = existingEntry.label,
                existingAction = existingEntry.action,
                newKey = entry.key,
                newAction = action,
            )
        } else {
            // No conflict, apply directly
            applyAction(entry.key, action)
            dismissActionSheet()
        }
    }

    fun onConflictResolved(swap: Boolean) {
        val conflict = _conflictInfo.value ?: return
        val entry = _selectedEntry.value ?: return

        if (swap) {
            // Swap: new entry gets the action, old entry gets NONE
            viewModelScope.launch {
                settingsRepository.updateGestureAction(conflict.existingKey, GestureAction.NONE.name)
                settingsRepository.updateGestureAction(entry.key, conflict.newAction.name)
            }
        } else {
            // Cancel: don't apply
            Timber.d("Conflict resolution cancelled")
        }
        _conflictInfo.value = null
        dismissActionSheet()
    }

    fun resetToDefaults() {
        _undoConfig = gestureMapConfig.value
        viewModelScope.launch {
            settingsRepository.resetGestureMapToDefaults()
            _showResetSnackbar.value = true
        }
    }

    fun undoReset() {
        val config = _undoConfig ?: return
        viewModelScope.launch {
            // Re-apply each entry from the saved config
            config.entries.forEach { entry ->
                settingsRepository.updateGestureAction(entry.key, entry.action.name)
            }
        }
        _undoConfig = null
        _showResetSnackbar.value = false
    }

    fun dismissSnackbar() {
        _showResetSnackbar.value = false
        _undoConfig = null
    }

    fun dismissActionSheet() {
        _showActionSheet.value = false
        _selectedEntry.value = null
        _conflictInfo.value = null
    }

    private fun applyAction(key: String, action: GestureAction) {
        viewModelScope.launch {
            settingsRepository.updateGestureAction(key, action.name)
        }
        Timber.d("Applied action %s to gesture %s", action, key)
    }
}
