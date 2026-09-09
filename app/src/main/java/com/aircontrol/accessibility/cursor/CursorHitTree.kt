package com.aircontrol.accessibility.cursor

import kotlin.math.roundToInt

/**
 * Pure, framework-free bounded traversal for hover hit-testing (Issue 9).
 *
 * [CursorHitTester] adapts Android [android.view.accessibility.AccessibilityWindowInfo] /
 * [android.view.accessibility.AccessibilityNodeInfo] to the [CursorHitWindowSource] /
 * [CursorHitNodeSource] views below, and [BoundedCursorTreeWalk] performs the actual
 * bounded depth-first descent — so the traversal invariants (budget, overlay
 * exclusion, snapshot-only retention) are testable on the JVM with synthetic
 * pathological trees (huge breadth, extreme depth, overlay windows, broken
 * roots, invalid bounds) that cannot be fabricated through the real framework.
 *
 * Hard guarantees:
 *  - The walk visits at most [BoundedCursorTreeWalk.MAX_VISITS] nodes and descends
 *    at most [BoundedCursorTreeWalk.MAX_DEPTH] levels.
 *  - Overlay-like windows (accessibility overlays and the magnification overlay)
 *    are never descended into — the cursor must not hover "itself".
 *  - Only immutable [CursorNodeSnapshot]s can be returned; node sources never
 *    escape the walk, so nothing can be retained past the call.
 */
internal data class CursorRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    companion object {
        val EMPTY = CursorRect(0, 0, 0, 0)
    }
}

/** A node the walker can descend into. */
internal interface CursorHitNodeSource {
    /** Screen bounds; [null] means "no usable bounds" (pruned immediately). */
    val bounds: CursorRect?
    val childCount: Int
    fun childAt(index: Int): CursorHitNodeSource?

    /** Reads ONLY scalar facts into an immutable snapshot. */
    fun snapshot(): CursorNodeSnapshot
}

/** A top-level accessibility window. */
internal interface CursorHitWindowSource {
    /** True when the window is an accessibility/magnification overlay (skip it). */
    val isOverlayLike: Boolean
    val root: CursorHitNodeSource?
}

internal object BoundedCursorTreeWalk {

    /** Absolute cap on nodes visited per hit-test (perf §7/§9). */
    const val MAX_VISITS = 300

    /** Absolute cap on tree depth per hit-test (malformed/degenerate trees). */
    const val MAX_DEPTH = 28

    /** Result of descending into one branch of the tree. */
    private sealed class Walk {
        /** A node at/below this point contains the point. */
        class Hit(val snapshot: CursorNodeSnapshot) : Walk()

        /** This branch did not contain the point (or was beyond the depth cap). */
        object Miss : Walk()

        /**
         * The GLOBAL visit budget was exhausted while exploring this branch.
         * Propagates all the way up so a pathological sibling list can never
         * force O(children) work after the budget is gone.
         */
        object Exhausted : Walk()
    }

    /**
     * Walks [windows] in platform order (topmost first) and returns the deepest
     * node whose bounds contain the point, or null when nothing is hit.
     */
    fun hitTest(
        windows: List<CursorHitWindowSource>,
        x: Float,
        y: Float,
    ): CursorNodeSnapshot? {
        if (!CursorGeometry.isUsable(x, y)) return null
        val px = x.roundToInt()
        val py = y.roundToInt()
        var visits = 0
        for (window in windows) {
            if (window.isOverlayLike) continue
            val root = window.root ?: continue
            visits++
            if (visits > MAX_VISITS) return null
            when (val walk = descend(root, px, py, depth = 0, visits = intArrayOf(visits))) {
                is Walk.Hit -> return walk.snapshot
                // Depth-cap misses: try the next window.
                Walk.Miss -> Unit
                // Budget exhausted: stop everything.
                Walk.Exhausted -> return null
            }
        }
        return null
    }

    /** Depth-first descent; prefers the LAST child (topmost drawing order). */
    private fun descend(
        node: CursorHitNodeSource,
        px: Int,
        py: Int,
        depth: Int,
        visits: IntArray,
    ): Walk {
        if (visits[0] > MAX_VISITS) return Walk.Exhausted
        // A node beyond the depth cap is skipped silently — its whole subtree is
        // unreachable within the cap, but siblings at shallower depth may still
        // contain a valid deepest hit.
        if (depth > MAX_DEPTH) return Walk.Miss
        val bounds = node.bounds ?: return Walk.Miss
        if (!bounds.contains(px, py)) return Walk.Miss

        val children = node.childCount
        if (children > 0) {
            for (i in children - 1 downTo 0) {
                val child = node.childAt(i) ?: continue
                visits[0]++
                if (visits[0] > MAX_VISITS) return Walk.Exhausted
                when (val deeper = descend(child, px, py, depth + 1, visits)) {
                    is Walk.Hit -> return deeper
                    Walk.Exhausted -> return Walk.Exhausted
                    Walk.Miss -> Unit // try the next sibling
                }
            }
        }
        return Walk.Hit(node.snapshot())
    }
}
