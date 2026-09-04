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
        CursorState(0.5f, 0.5f, false, false),
    )
    override val cursorState: StateFlow<CursorState> = _cursorState

    override fun updatePosition(handFrame: HandFrame) {
        if (!handFrame.isDetected) {
            hide()
            return
        }

        /*
         * Cursor anchor deliberately uses the PALM, not the index fingertip.
         *
         * The fingertip moves dramatically during pinch/swipe/drag. Using it as
         * the pointer anchor makes the cursor fight the gesture recognizer: a
         * click gesture moves the pointer while the user is trying to hold it
         * still. A palm anchor is much more stable and leaves finger motion free
         * for gesture intent.
         *
         * We use the four MCP joints for the main palm centre and blend in the
         * wrist. MCPs are less affected by finger articulation; the wrist keeps
         * the anchor natural when the hand is rotated.
         */
        val lm = handFrame.landmarks
        if (lm.size < HandFrame.LANDMARK_COUNT) return

        val mcp = listOf(lm[5], lm[9], lm[13], lm[17])
        val mcpX = mcp.sumOf { it.x.toDouble() }.toFloat() / mcp.size
        val mcpY = mcp.sumOf { it.y.toDouble() }.toFloat() / mcp.size
        val wrist = lm[0]

        // 70% MCP centre + 30% wrist = stable palm anchor without a floating feel.
        val palmX = (mcpX * 0.70f + wrist.x * 0.30f).coerceIn(0f, 1f)
        val palmY = (mcpY * 0.70f + wrist.y * 0.30f).coerceIn(0f, 1f)

        _cursorState.update {
            it.copy(x = palmX, y = palmY, isVisible = true)
        }
    }

    override fun performClick() {
        _cursorState.update { it.copy(isPressed = true) }
        Timber.d("Cursor click performed")
    }

    override fun releaseClick() {
        _cursorState.update { it.copy(isPressed = false) }
        Timber.d("Cursor click released")
    }

    override fun show() {
        _cursorState.update { it.copy(isVisible = true, isPressed = false) }
    }

    override fun hide() {
        _cursorState.update { it.copy(isVisible = false) }
        clearPinClick()
    }

    @Volatile private var pinnedX: Float? = null
    @Volatile private var pinnedY: Float? = null

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
