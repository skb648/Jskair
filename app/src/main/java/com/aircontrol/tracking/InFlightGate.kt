package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/**
 * One outstanding asynchronous submission per inference channel.
 *
 * A timeout is diagnostic only. Reclaiming a slot by time is unsafe because a
 * MediaPipe callback may arrive late and still be reading the old MPImage. The
 * owner must close/reinitialize the graph, which invokes the frame completion
 * callback, before the slot can be reused.
 */
class InFlightGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private val reservedAtMs = AtomicLong(IDLE)

    @Volatile private var submissions = 0L
    @Volatile private var refusals = 0L
    @Volatile private var stalledObservations = 0L

    fun tryReserve(nowMs: Long): Boolean {
        if (reservedAtMs.get() != IDLE) {
            refusals++
            if (isStalled(nowMs)) stalledObservations++
            return false
        }
        return if (reservedAtMs.compareAndSet(IDLE, nowMs)) {
            submissions++
            true
        } else {
            refusals++
            false
        }
    }

    fun release() {
        reservedAtMs.set(IDLE)
    }

    fun isBusy(nowMs: Long): Boolean = reservedAtMs.get() != IDLE

    /** True when an owner has been outstanding beyond the diagnostic window. */
    fun isStalled(nowMs: Long): Boolean {
        val reserved = reservedAtMs.get()
        return reserved != IDLE && nowMs - reserved >= timeoutMs
    }

    fun reset() {
        reservedAtMs.set(IDLE)
    }

    fun stats(): Stats = Stats(submissions, refusals, stalledObservations)

    data class Stats(val submitted: Long, val refused: Long, val stalled: Long) {
        /** Compatibility name for telemetry; this is an observation, never a reclaim. */
        val expired: Long get() = stalled
        val refusalRate: Float
            get() {
                val total = submitted + refused
                return if (total == 0L) 0f else refused.toFloat() / total.toFloat()
            }
    }

    companion object {
        private const val IDLE = Long.MIN_VALUE
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}
