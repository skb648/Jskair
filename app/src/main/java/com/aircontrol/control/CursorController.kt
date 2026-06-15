package com.aircontrol.control

import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class CursorState(
    val x: Float,
    val y: Float,
    val isVisible: Boolean,
    val isPressed: Boolean,
)

interface CursorController {
    val cursorState: StateFlow<CursorState>
    fun updatePosition(handFrame: HandFrame)
    fun performClick()
    fun releaseClick()
    fun show()
    fun hide()
}

@Singleton
class CursorControllerImpl @Inject constructor() : CursorController {

    private val _cursorState = MutableStateFlow(
        CursorState(
            x = 0.5f,
            y = 0.5f,
            isVisible = false,
            isPressed = false,
        ),
    )
    override val cursorState: StateFlow<CursorState> = _cursorState

    override fun updatePosition(handFrame: HandFrame) {
        if (!handFrame.isDetected) {
            hide()
            return
        }
        val indexTip = handFrame.landmarks.getOrNull(8)
        if (indexTip != null) {
            _cursorState.update { it.copy(
                x = indexTip.x,
                y = indexTip.y,
                isVisible = true,
            ) }
        }
    }

    override fun performClick() {
        _cursorState.update { it.copy(isPressed = true) }
        Timber.d("Cursor click performed")
        // Haptic feedback is performed centrally by ActionDispatcher after actions.
    }

    override fun releaseClick() {
        _cursorState.update { it.copy(isPressed = false) }
        Timber.d("Cursor click released")
    }

    override fun show() {
        _cursorState.update { it.copy(isVisible = true, isPressed = false) }
        Timber.d("Cursor shown")
    }

    override fun hide() {
        _cursorState.update { it.copy(isVisible = false) }
        Timber.d("Cursor hidden")
    }
}
