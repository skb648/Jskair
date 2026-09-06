package com.aircontrol.accessibility.cursor

import kotlin.math.roundToInt

/**
 * Pure geometry for the Native-like Cursor overlay window.
 *
 * EXPLICIT HOTSPOT MODEL (the core correctness requirement):
 *
 *   logical cursor position (x, y)  ← what clicks/hover/node-lookup use
 *   hotspot (hx, hy)                ← the pixel inside the overlay window that
 *                                     must sit exactly on (x, y)
 *   window position                 ← (x − hx, y − hy)
 *
 * Every icon is drawn in the view with its semantic point (arrow tip, index
 * fingertip, beam centre, resize-arrow centre) exactly at [HOTSPOT_DP,
 * HOTSPOT_DP] — so switching icons never shifts the logical position, and the
 * visible arrow tip always covers the point that receives the click.
 *
 * All functions are pure Float math (JVM-testable); density is passed in.
 */
object CursorGeometry {

    /** Overlay window side, dp. Must fit: largest icon glyph + dwell arc + ripple. */
    const val VIEW_SIZE_DP = 44

    /**
     * Hotspot offset from the window's top-left, dp. Chosen so every icon's
     * glyph (which extends at most ~−1dp left/up and ~+22dp right/down from
     * its hotspot) plus the dwell arc (r = 15dp + stroke) stay inside the view.
     */
    const val HOTSPOT_DP = 20

    fun viewSizePx(density: Float): Int = dp(VIEW_SIZE_DP, density)

    fun hotspotPx(density: Float): Float = HOTSPOT_DP * density

    /**
     * Window left so the hotspot column covers [logicalX].
     * Round-half-to-int of a fresh subtraction each frame — never
     * float→int→float accumulation.
     */
    fun windowLeft(logicalX: Float, density: Float): Int =
        (logicalX - hotspotPx(density)).roundToInt()

    /** Window top so the hotspot row covers [logicalY]. */
    fun windowTop(logicalY: Float, density: Float): Int =
        (logicalY - hotspotPx(density)).roundToInt()

    /** Clamp a logical coordinate into [0, max]; NaN collapses to 0 (no jumps). */
    fun clampToScreen(value: Float, max: Int): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > max -> max.toFloat()
        else -> value
    }

    /** True when both coordinates are finite — guards every mapping step. */
    fun isUsable(x: Float, y: Float): Boolean = x.isFinite() && y.isFinite()

    private fun dp(value: Int, density: Float): Int = (value * density).toInt()
}
