package com.aircontrol.accessibility.cursor

/**
 * Pure rate-limit policy for hover-context resolution.
 *
 * Requirements (spec §7/§8): no accessibility-tree scan every frame; no scan
 * while the cursor is stationary; refresh only when the cursor has moved a
 * meaningful distance AND a minimum interval has elapsed.
 */
class HoverResolvePolicy(
    private val moveThresholdPx: Float,
    private val resolveIntervalMs: Long,
) {
    @Volatile private var lastResolveX: Float = Float.NaN
    @Volatile private var lastResolveY: Float = Float.NaN
    @Volatile private var lastResolveMs: Long = Long.MIN_VALUE

    /**
     * True when a resolve SHOULD run for a cursor at (x, y) at time nowMs.
     * First call after construction/[reset] always resolves.
     */
    fun shouldResolve(x: Float, y: Float, nowMs: Long): Boolean {
        if (!CursorGeometry.isUsable(x, y)) return false
        if (lastResolveX.isNaN() || lastResolveY.isNaN()) return true
        val dx = x - lastResolveX
        val dy = y - lastResolveY
        if (dx * dx + dy * dy < moveThresholdPx * moveThresholdPx) return false
        if (nowMs - lastResolveMs < resolveIntervalMs) return false
        return true
    }

    /** Records that a resolve ran for (x, y) at nowMs. */
    fun markResolved(x: Float, y: Float, nowMs: Long) {
        lastResolveX = x
        lastResolveY = y
        lastResolveMs = nowMs
    }

    /** Forces the next position event to resolve (window change, re-show, …). */
    fun reset() {
        lastResolveX = Float.NaN
        lastResolveY = Float.NaN
        lastResolveMs = Long.MIN_VALUE
    }
}
