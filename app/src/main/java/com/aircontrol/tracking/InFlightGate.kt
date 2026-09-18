package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile

/**
 * One outstanding asynchronous submission per inference channel.
 *
 * A timeout is diagnostic only. Reclaiming a slot by time is unsafe because a
 * MediaPipe callback may arrive late and still be reading the old MPImage. The
 * owner must close/reinitialize the graph, which invokes the frame completion
 * callback, before the slot can be reused.
 *
 * The token and reservation timestamp live in one atomic object so a stale
 * callback can never clear or overwrite a newer reservation's timing state.
 */
class InFlightGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private data class Reservation(
        val token: Long,
        val reservedAtMs: Long,
    )

    private val reservation = AtomicReference<Reservation?>(null)
    private val nextToken = AtomicLong(0L)

    @Volatile private var submissions = 0L
    @Volatile private var refusals = 0L
    @Volatile private var stalledObservations = 0L

    /** Reserves the single in-flight slot and returns a unique token for this submission. */
    fun tryReserve(nowMs: Long): Long? {
        if (reservation.get() != null) {
            refusals++
            if (isStalled(nowMs)) stalledObservations++
            return null
        }
        val candidate = Reservation(nextToken.incrementAndGet(), nowMs)
        return if (reservation.compareAndSet(null, candidate)) {
            submissions++
            candidate.token
        } else {
            refusals++
            null
        }
    }

    /** Releases the slot only when [token] still owns the exact reservation. */
    fun release(token: Long): Boolean {
        val current = reservation.get() ?: return false
        if (current.token != token) return false
        return reservation.compareAndSet(current, null)
    }

    fun isBusy(nowMs: Long): Boolean = reservation.get() != null

    /** True when an owner has been outstanding beyond the diagnostic window. */
    fun isStalled(nowMs: Long): Boolean {
        val current = reservation.get() ?: return false
        return nowMs - current.reservedAtMs >= timeoutMs
    }

    /** Clears the current owner during graph teardown and returns its token, if any. */
    fun reset(): Long? = reservation.getAndSet(null)?.token

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
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}
