package com.aircontrol.control

import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
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
    fun updatePosition(x: Float, y: Float)
    fun performClick()
    fun releaseClick()
    fun show()
    fun hide()
}

@Singleton
class CursorControllerImpl @Inject constructor() : CursorController {
    private val _cursorState = MutableStateFlow(CursorState(0.5f, 0.5f, false, false))
    override val cursorState: StateFlow<CursorState> = _cursorState

    override fun updatePosition(handFrame: HandFrame) {
        if (!handFrame.isDetected) {
            hide()
            return
        }
        val lm = handFrame.landmarks
        if (lm.size < HandFrame.LANDMARK_COUNT) return

        // Allocation-free palm center. This runs on every analyzed hand frame;
        // avoid listOf(), iteration lambdas, sumOf(), and boxing on the hot path.
        val mcpX = (lm[5].x + lm[9].x + lm[13].x + lm[17].x) * 0.25f
        val mcpY = (lm[5].y + lm[9].y + lm[13].y + lm[17].y) * 0.25f
        val wrist = lm[0]
        val palmX = mcpX * 0.70f + wrist.x * 0.30f
        val palmY = mcpY * 0.70f + wrist.y * 0.30f

        // Stable palm anchor with a modest index-tip contribution for natural
        // pointing. All arithmetic stays primitive and allocation-free.
        val indexTip = lm[8]
        val anchorX = (palmX * 0.80f + indexTip.x * INDEX_TIP_BLEND).takeIf(Float::isFinite)?.coerceIn(0f, 1f)
            ?: return
        val anchorY = (palmY * 0.80f + indexTip.y * INDEX_TIP_BLEND).takeIf(Float::isFinite)?.coerceIn(0f, 1f)
            ?: return

        val pinned = pinnedPacked.get()
        val finalX: Float
        val finalY: Float
        if (pinned != UNPINNED) {
            finalX = Float.fromBits((pinned ushr 32).toInt())
            finalY = Float.fromBits(pinned.toInt())
        } else {
            finalX = anchorX
            finalY = anchorY
        }
        _cursorState.update { it.copy(x = finalX, y = finalY, isVisible = true) }
    }

    override fun updatePosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) {
            hide()
            return
        }
        _cursorState.update {
            it.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f), isVisible = true)
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
        _cursorState.update { it.copy(isVisible = false, isPressed = false) }
        clearPinClick()
    }

    /** Packed x/y state avoids a Pair allocation on the high-frequency cursor path. */
    private val pinnedPacked = AtomicLong(UNPINNED)

    fun pinClickPosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) {
            clearPinClick()
            return
        }
        val packed = (Float.floatToRawIntBits(x.coerceIn(0f, 1f)).toLong() shl 32) or
            (Float.floatToRawIntBits(y.coerceIn(0f, 1f)).toLong() and 0xffffffffL)
        pinnedPacked.set(packed)
    }

    fun pinnedClickPosition(): Pair<Float, Float>? {
        val packed = pinnedPacked.get()
        if (packed == UNPINNED) return null
        return Float.fromBits((packed ushr 32).toInt()) to Float.fromBits(packed.toInt())
    }

    fun clearPinClick() {
        pinnedPacked.set(UNPINNED)
    }

    companion object {
        private const val INDEX_TIP_BLEND = 0.20f
        private const val UNPINNED = Long.MIN_VALUE
    }
}
