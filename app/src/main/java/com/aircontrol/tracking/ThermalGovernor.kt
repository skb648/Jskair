package com.aircontrol.tracking

/**
 * Hysteresis/debounce layer between raw [ThermalMonitor] samples and the FPS
 * throttling decision (perf audit P5).
 *
 * Root cause this fixes: the raw status was mapped 1:1 to an FPS tier, so a
 * device hovering at a thermal threshold flipped the configured FPS every
 * 5 s poll — and every flip cancelled the 30 s recovery ramp — producing the
 * 30↔10 FPS oscillation seen in the 2026-09-07 logcat.
 *
 * Rules (all deterministic, no wall-clock reads — time is a parameter):
 * - Worsening by one tier applies only after [worsenConfirmSamples]
 *   consecutive confirming samples.
 * - [ThermalStatus.CRITICAL] applies IMMEDIATELY (safety first).
 * - Relaxing applies only after [relaxConfirmSamples] consecutive lower
 *   samples AND at least [relaxCooldownMs] since the last worsening — the
 *   hysteresis band that prevents oscillation.
 * - [onSample] returns the new effective status ONLY on a transition, so
 *   callers can apply throttling exactly when something actually changed.
 *
 * Pure Kotlin (no Android imports) — testable on the JVM.
 */
class ThermalGovernor(
    private val worsenConfirmSamples: Int = DEFAULT_WORSEN_CONFIRM,
    private val relaxConfirmSamples: Int = DEFAULT_RELAX_CONFIRM,
    private val relaxCooldownMs: Long = DEFAULT_RELAX_COOLDOWN_MS,
) {

    private var applied = ThermalStatus.NONE
    private var pending: ThermalStatus? = null
    private var pendingCount = 0
    private var lastWorsenAtMs = 0L

    /** Current effective (debounced) status. */
    fun current(): ThermalStatus = applied

    /**
     * Feeds one raw poll sample. Returns the new effective status when the
     * debounced state transitioned, null otherwise (including "no change").
     */
    fun onSample(raw: ThermalStatus, nowMs: Long): ThermalStatus? {
        // CRITICAL is a safety tier: never debounce it.
        if (raw == ThermalStatus.CRITICAL && applied != ThermalStatus.CRITICAL) {
            return apply(raw, worsening = true, nowMs = nowMs)
        }
        if (raw == applied) {
            pending = null
            pendingCount = 0
            return null
        }
        // Count consecutive confirming samples for the candidate status.
        if (pending != raw) {
            pending = raw
            pendingCount = 1
        } else {
            pendingCount++
        }
        val worsening = raw.ordinal > applied.ordinal
        val required = if (worsening) worsenConfirmSamples else relaxConfirmSamples
        if (pendingCount < required) return null
        // Relax hysteresis: hold the throttled tier for at least the cooldown
        // after the last worsening before allowing a drop. A run that was
        // confirmed only *during* the cooldown does not carry over — the
        // relaxed status must re-confirm afterwards.
        if (!worsening && nowMs - lastWorsenAtMs < relaxCooldownMs) {
            pending = null
            pendingCount = 0
            return null
        }
        return apply(raw, worsening, nowMs)
    }

    /** Clears all state back to [ThermalStatus.NONE] (e.g. monitor stop). */
    fun reset() {
        applied = ThermalStatus.NONE
        pending = null
        pendingCount = 0
        lastWorsenAtMs = 0L
    }

    private fun apply(status: ThermalStatus, worsening: Boolean, nowMs: Long): ThermalStatus {
        applied = status
        pending = null
        pendingCount = 0
        if (worsening) lastWorsenAtMs = nowMs
        return status
    }

    private companion object {
        const val DEFAULT_WORSEN_CONFIRM = 2
        const val DEFAULT_RELAX_CONFIRM = 3
        const val DEFAULT_RELAX_COOLDOWN_MS = 30_000L
    }
}
