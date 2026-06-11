package com.aircontrol.control

import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            _cursorState.value = _cursorState.value.copy(
                x = indexTip.x,
                y = indexTip.y,
                isVisible = true,
            )
        }
        Timber.v("Cursor position updated")
    }

    override fun performClick() {
        _cursorState.value = _cursorState.value.copy(isPressed = true)
        Timber.d("Cursor click performed")
        // TODO: Trigger haptic feedback if enabled
    }

    override fun show() {
        _cursorState.value = _cursorState.value.copy(isVisible = true)
        Timber.d("Cursor shown")
    }

    override fun hide() {
        _cursorState.value = _cursorState.value.copy(isVisible = false)
        Timber.d("Cursor hidden")
    }
}
