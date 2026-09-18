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
    private val reservedAtMs = AtomicLong(IDLE_TIME)
    private val activeToken = AtomicLong(IDLE_TOKEN)
    private val nextToken = AtomicLong(0L)

    @Volatile private var submissions = 0L
    @Volatile private var refusals = 0L
    @Volatile private var stalledObservations = 0L

    /** Reserves the single in-flight slot and returns a unique token for this submission. */
    fun tryReserve(nowMs: Long): Long? {
        if (activeToken.get() != IDLE_TOKEN) {
            refusals++
            if (isStalled(nowMs)) stalledObservations++
            return null
        }
        val token = nextToken.incrementAndGet()
        return if (activeToken.compareAndSet(IDLE_TOKEN, token)) {
            reservedAtMs.set(nowMs)
            submissions++
            token
        } else {
            refusals++
            null
        }
    }

    /** Releases the slot only when [token] still owns it; stale callbacks are ignored. */
    fun release(token: Long): Boolean {
        // Clear the timestamp before releasing the token. A new reservation may
        // start immediately after the token becomes idle; setting the timestamp
        // afterwards could erase the new reservation's start time.
        reservedAtMs.set(IDLE_TIME)
        return activeToken.compareAndSet(token, IDLE_TOKEN)
    }

    fun isBusy(nowMs: Long): Boolean = activeToken.get() != IDLE_TOKEN

    /** True when an owner has been outstanding beyond the diagnostic window. */
    fun isStalled(nowMs: Long): Boolean {
        val reserved = reservedAtMs.get()
        return activeToken.get() != IDLE_TOKEN && reserved != IDLE_TIME && nowMs - reserved >= timeoutMs
    }

    /** Clears the current owner during graph teardown and returns its token, if any. */
    fun reset(): Long? {
        // Block new reservations from observing a stale timestamp before the
        // active token is cleared; callers use this during graph teardown.
        reservedAtMs.set(IDLE_TIME)
        val token = activeToken.getAndSet(IDLE_TOKEN)
        return token.takeIf { it != IDLE_TOKEN }
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
        private const val IDLE_TOKEN = Long.MIN_VALUE
        private const val IDLE_TIME = Long.MIN_VALUE
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}
