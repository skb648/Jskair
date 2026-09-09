package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/**
 * Capacity bound for one asynchronous ML channel: "at most one submission whose result has not come
 * back yet", which is the missing condition in front of every `detectAsync` call site.
 *
 * Why this exists and what it replaces. The landmarkers run in `RunningMode.LIVE_STREAM`, so
 * submitting a frame returns immediately and the graph keeps working through its own queue. Before
 * this class, the only thing between the camera and the graph was a *time* gate
 * (`AdaptiveFpsController.analysisIntervalMs`): if sustained inference took longer than the
 * submission interval — normal on a low-end device — every frame still got submitted, the graph's
 * queue grew, and the number the cursor was driven by became progressively older. That is why lag on
 * cheap hardware is not a fixed extra delay but a delay that *builds up* while you use the app: the
 * backlog is the mechanism, and no FPS number or threshold change can fix it because the invariant
 * "one outstanding job" was never enforced.
 *
 * A reservation must be released by the owner (the tracker's result path, or its failure path). A
 * reservation whose result never arrives is reclaimed after [timeoutMs] so a wedged graph cannot
 * wedge the pipeline: with [DEFAULT_TIMEOUT_MS] at 1 000 ms, the window is five intervals of the
 * slowest tier the app ever selects (5 fps = 200 ms), so a legitimate result cannot be evicted while
 * the counter still detects the wedged-graph case.
 *
 * Time is a parameter, never read from a clock here, so tests and callers drive it explicitly.
 */
class InFlightGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    /** Reserved at the given timestamp, or [IDLE]. */
    private val reservedAtMs = AtomicLong(IDLE)

    @Volatile
    private var submissions = 0L

    @Volatile
    private var refusals = 0L

    @Volatile
    private var expiredReservations = 0L

    /**
     * Take the slot for the frame arriving at [nowMs]. False means "the previous job is still
     * running" and the caller must then **drop this frame** — that is the whole point: when the
     * channel is saturated the correct response is less work in flight, not a longer queue.
     */
    fun tryReserve(nowMs: Long): Boolean {
        while (true) {
            val current = reservedAtMs.get()
            if (current != IDLE && nowMs - current < timeoutMs) {
                refusals++
                return false
            }
            if (reservedAtMs.compareAndSet(current, nowMs)) {
                if (current != IDLE) expiredReservations++
                submissions++
                return true
            }
        }
    }

    /** Give the slot back: the result arrived (or the submission failed and nothing is pending). */
    fun release() {
        reservedAtMs.set(IDLE)
    }

    /** Read-only "is a job outstanding", for telemetry. Does not reclaim. */
    fun isBusy(nowMs: Long): Boolean {
        val current = reservedAtMs.get()
        return current != IDLE && nowMs - current < timeoutMs
    }

    /** Drop the reservation without counting a submission (used by reset paths). */
    fun reset() {
        reservedAtMs.set(IDLE)
    }

    /** Counters for the debug overlay: what saturation actually looked like this session. */
    fun stats(): Stats = Stats(submissions, refusals, expiredReservations)

    data class Stats(val submitted: Long, val refused: Long, val expired: Long) {
        /** Fraction of frames the channel could not absorb. 0 means the bound never bit. */
        val refusalRate: Float
            get() {
                val total = submitted + refused
                return if (total == 0L) 0f else (refused.toFloat() / total.toFloat())
            }
    }

    companion object {
        private const val IDLE = Long.MIN_VALUE

        /** Five intervals of the slowest tier the app selects (5 fps), see class KDoc. */
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}
