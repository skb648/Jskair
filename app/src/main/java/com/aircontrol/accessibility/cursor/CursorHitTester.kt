package com.aircontrol.accessibility.cursor

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Finds the deepest accessibility node containing a screen point and returns a
 * framework-free [CursorNodeSnapshot] of it.
 *
 * Cost model (spec §7/§8/§9): called at most ~8 Hz while the cursor is MOVING,
 * never per frame and never while stationary. The walk is bounded (max
 * visits/depth, enforced by [BoundedCursorTreeWalk]) and prunes by bounds. No
 * [AccessibilityNodeInfo] is retained after the call — the snapshot is plain
 * data, so nothing can leak through this class.
 *
 * The actual traversal lives in the pure [BoundedCursorTreeWalk] operating on
 * [CursorHitNodeSource]/[CursorHitWindowSource]; this class only adapts the
 * Android framework objects to those views. Runs on a background dispatcher
 * (binder calls); single caller at a time (enforced by CursorHoverMonitor).
 */
object CursorHitTester {

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
        // Platform window lists can contain null entries on some OEM builds;
        // the original loop guarded them explicitly, so the adapter list does too.
        val adapted = ArrayList<CursorHitWindowSource>(windows.size)
        for (window in windows) {
            if (window == null) continue
            adapted.add(WindowAdapter(window))
        }
        return BoundedCursorTreeWalk.hitTest(windows = adapted, x = x, y = y)
    }

    /** Reads ONLY scalar facts — no node object escapes this function. */
    private fun snapshotOf(node: AccessibilityNodeInfo): CursorNodeSnapshot = CursorNodeSnapshot(
        className = node.className?.toString() ?: "",
        isClickable = node.isClickable,
        isEnabled = node.isEnabled,
        isEditable = node.isEditable,
        hasClickAction = (node.actions and AccessibilityNodeInfo.ACTION_CLICK) != 0,
    )

    /** Adapts a platform window to the pure walk's view. */
    private class WindowAdapter(window: AccessibilityWindowInfo) : CursorHitWindowSource {
        override val isOverlayLike: Boolean =
            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                window.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY

        override val root: CursorHitNodeSource? = window.root?.let(::NodeAdapter)
    }

    /** Adapts a platform node to the pure walk's view (never retained). */
    private class NodeAdapter(node: AccessibilityNodeInfo) : CursorHitNodeSource {

        private val boundsRect = Rect()

        override val bounds: CursorRect? by lazy {
            node.getBoundsInScreen(boundsRect)
            CursorRect(boundsRect.left, boundsRect.top, boundsRect.right, boundsRect.bottom)
        }

        override val childCount: Int
            get() = node.childCount

        override fun childAt(index: Int): CursorHitNodeSource? =
            node.getChild(index)?.let(::NodeAdapter)

        override fun snapshot(): CursorNodeSnapshot = snapshotOf(node)
    }
}
