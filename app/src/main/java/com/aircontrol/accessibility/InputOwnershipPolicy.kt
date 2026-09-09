package com.aircontrol.accessibility

/**
 * Deterministic cross-modality tap ownership (Issue 15).
 *
 * With eye + hand + blink + dwell all enabled, one physical intent could be
 * observed by several modalities at once and produce several taps (blink while
 * pinching, dwell while blinking, …). This policy serializes tap-like actions:
 *
 * - Every tap-like request carries a modality [source] (e.g. "hand_pinch",
 *   "blink", "dwell").
 * - If a request arrives within [serializationWindowMs] of a tap fired by a
 *   DIFFERENT modality, it is refused (fail closed: one physical intent →
 *   one action). The first modality to actually arrive owns the slot —
 *   priority is deterministic, no modality is silently preferred.
 * - A request from the SAME modality that just fired is allowed: fast repeated
 *   deliberate clicks by one input (e.g. two quick pinches) stay possible,
 *   while each modality's own state machine (dwell re-arm radius, blink
 *   duration window, pinch re-start) still prevents machine-gun fire.
 *
 * Deliberate multi-tap sequences that belong to ONE gesture (the second tap of
 * a double-tap, which the dispatcher emits internally through its own window)
 * do not pass through this policy.
 *
 * Pure Kotlin — JVM-testable (caller passes a monotonic clock).
 */
class InputOwnershipPolicy(
    /** Cross-modality serialization window. */
    private val serializationWindowMs: Long = 350L,
) {
    private var lastSource: String? = null
    private var lastAcquiredAtMs: Long = Long.MIN_VALUE / 2
    private var refusedCount = 0L

    /**
     * Attempts to acquire the tap slot for [source] at [nowMs] (monotonic).
     * Returns true when the tap may fire, false when another modality fired
     * within the serialization window.
     */
    fun tryAcquire(source: String, nowMs: Long): Boolean {
        val previous = lastSource
        if (previous != null && previous != source && nowMs - lastAcquiredAtMs < serializationWindowMs) {
            refusedCount++
            return false
        }
        lastSource = source
        lastAcquiredAtMs = nowMs
        return true
    }

    /** Monotonic timestamp of the last acquired tap. */
    fun lastAcquiredAt(): Long = lastAcquiredAtMs

    /** Modality that owns the most recent tap slot (null until the first acquire). */
    fun lastSource(): String? = lastSource

    /** How many cross-modality taps were refused since construction. */
    fun refusedCount(): Long = refusedCount

    /** Force-resets ownership (service interrupt / teardown). */
    fun reset() {
        lastSource = null
        lastAcquiredAtMs = Long.MIN_VALUE / 2
    }
}
