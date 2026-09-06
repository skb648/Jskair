package com.aircontrol.accessibility.cursor

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.roundToInt

/**
 * Finds the deepest accessibility node containing a screen point and returns a
 * framework-free [CursorNodeSnapshot] of it.
 *
 * Cost model (spec §7): called at most ~8 Hz while the cursor is MOVING, never
 * per frame and never while stationary. The walk is bounded (max visits/depth)
 * and prunes by bounds. No AccessibilityNodeInfo is retained after the call —
 * the snapshot is plain data, so nothing can leak through this class.
 *
 * Runs on a background dispatcher (binder calls); single caller at a time
 * (enforced by CursorHoverMonitor).
 */
object CursorHitTester {

    private const val MAX_VISITS = 300
    private const val MAX_DEPTH = 28

    /**
     * @param windows service.windows — platform order (topmost first).
     * @param selfPackage this app's package — its ACCESSIBILITY overlays are
     * skipped by window type below anyway; the app's own activities are NOT
     * skipped (a real pointer changes over them too).
     */
    fun hitTest(
        windows: List<AccessibilityWindowInfo>,
        x: Float,
        y: Float,
        selfPackage: String,
    ): CursorNodeSnapshot? {
        if (!CursorGeometry.isUsable(x, y)) return null
        val px = x.roundToInt()
        val py = y.roundToInt()
        val bounds = Rect()
        var visits = 0
        for (window in windows) {
            if (window == null) continue
            val type = window.type
            // Skip our own overlay layer (cursor/status) and magnification:
            // they are NOT what a real pointer would hover over.
            if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
            ) {
                continue
            }
            val root = window.root ?: continue
            visits++
            val hit = descend(root, px, py, bounds, depth = 0, visits = intArrayOf(visits)) ?: continue
            return hit
        }
        return null
    }

    /** Depth-first descent; prefers the LAST child (topmost drawing order). */
    private fun descend(
        node: AccessibilityNodeInfo,
        px: Int,
        py: Int,
        bounds: Rect,
        depth: Int,
        visits: IntArray,
    ): CursorNodeSnapshot? {
        if (depth > MAX_DEPTH || visits[0] > MAX_VISITS) return null
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(px, py)) return null

        val children = node.childCount
        if (children > 0) {
            for (i in children - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                visits[0]++
                val deeper = descend(child, px, py, bounds, depth + 1, visits)
                if (deeper != null) return deeper
            }
        }
        return snapshotOf(node)
    }

    /** Reads ONLY scalar facts — no node object escapes this function. */
    private fun snapshotOf(node: AccessibilityNodeInfo): CursorNodeSnapshot = CursorNodeSnapshot(
        className = node.className?.toString() ?: "",
        isClickable = node.isClickable,
        isEnabled = node.isEnabled,
        isEditable = node.isEditable,
        hasClickAction = (node.actions and AccessibilityNodeInfo.ACTION_CLICK) != 0,
    )
}
