package com.aircontrol.accessibility

/**
 * Throttled, change-only hub for interaction block reasons (Issue 10).
 *
 * Raw "this action was blocked" events can arrive many times per second (every
 * too-short blink, every suppressed dwell frame, …). Emitting each one to the UI
 * would be spam. This hub:
 *
 *  1. keeps a single [currentReason] that only changes when the block state
 *     meaningfully changes (a different reason, or the same reason re-surfacing
 *     after a quiet gap ≥ [minRepeatMs]);
 *  2. records occurrence counters per reason for debug diagnostics;
 *  3. lets the owner clear the current reason when the block clears (or after a
 *     transient timeout) with [clearIfCurrent], so an old reason never sticks.
 *
 * [record] returns `true` exactly when the *effective* displayed reason changed,
 * so callers only repaint/relay on real transitions. Pure Kotlin — JVM-testable.
 */
class BlockReasonHub(
    /** Minimum gap before the SAME reason may surface again. */
    private val minRepeatMs: Long = 1_500L,
) {

    private var current: BlockReason? = null
    private var lastSurfacedAtMs: Long = Long.MIN_VALUE
    private val counts = mutableMapOf<BlockReason, Int>()

    /**
     * Invoked (on the recording thread) whenever the EFFECTIVE surfaced reason
     * changes — that is, exactly when [record]/[clearIfCurrent]/[clearAll]
     * return true. UI owners relay this to the status pill / debug surface.
     */
    var onChange: ((BlockReason?) -> Unit)? = null

    /** The reason currently surfaced, or null when nothing is blocked. */
    fun currentReason(): BlockReason? = current

    /**
     * Reports that [reason] blocked an interaction at [atMs]. Returns true only
     * when the effective surfaced reason changed (callers update UI/status then).
     */
    fun record(reason: BlockReason, atMs: Long): Boolean {
        counts[reason] = (counts[reason] ?: 0) + 1
        if (current == reason) {
            // Same reason still current. Re-surface only after a quiet gap so a
            // sustained block (e.g. face lost) does not repaint every frame.
            if (atMs - lastSurfacedAtMs < minRepeatMs) return false
            lastSurfacedAtMs = atMs
            onChange?.invoke(reason)
            return true
        }
        current = reason
        lastSurfacedAtMs = atMs
        onChange?.invoke(reason)
        return true
    }

    /**
     * Clears the current reason if it is still [reason] (used by the owner's
     * transient timeout, so an older record cannot erase a newer reason).
     * Returns true when the surfaced reason actually cleared.
     */
    fun clearIfCurrent(reason: BlockReason, atMs: Long): Boolean {
        if (current != reason) return false
        current = null
        lastSurfacedAtMs = atMs
        onChange?.invoke(null)
        return true
    }

    /** Clears every reason. Returns true when something was actually cleared. */
    fun clearAll(atMs: Long): Boolean {
        if (current == null) return false
        current = null
        lastSurfacedAtMs = atMs
        onChange?.invoke(null)
        return true
    }

    /** Occurrence count per reason since construction (debug diagnostics). */
    fun occurrences(reason: BlockReason): Int = counts[reason] ?: 0

    /** Snapshot of all recorded counts. */
    fun countSnapshot(): Map<BlockReason, Int> = counts.toMap()
}
