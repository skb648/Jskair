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
        // Landmark 8 is the index-fingertip for real MediaPipe frames. Synthetic
        // cursor frames (built by the accessibility service) carry a single
        // landmark at index 0, so fall back to the first landmark when index 8
        // is absent — previously getOrNull(8) always returned null for those
        // frames and cursorState never updated.
        val indexTip = handFrame.landmarks.getOrNull(8) ?: handFrame.landmarks.firstOrNull()
        // Fallback to first landmark only for synthetic cursor frames (size 1); for real frames with <9 landmarks, ignore (wrist jitter)
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
        clearPinClick()
        Timber.d("Cursor hidden")
    }

    /**
     * Fix A-9: the position the click target is locked to. The dot the user aims
     * with is smoothed, so a pinch that begins while the hand is moving must use
     * the dot's position at that instant — not the live (ahead-of-the-dot) hand
     * position, and not a re-smoothed value that has already drifted onward.
     */
    @Volatile
    private var pinnedX: Float? = null

    @Volatile
    private var pinnedY: Float? = null

    fun pinClickPosition(x: Float, y: Float) {
        pinnedX = x
        pinnedY = y
    }

    fun pinnedClickPosition(): Pair<Float, Float>? {
        val x = pinnedX ?: return null
        val y = pinnedY ?: return null
        return x to y
    }

    fun clearPinClick() {
        pinnedX = null
        pinnedY = null
    }
}
