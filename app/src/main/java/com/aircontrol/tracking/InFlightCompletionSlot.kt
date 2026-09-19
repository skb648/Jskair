package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicReference

/**
 * Correlates an asynchronous inference callback with the exact frame submission
 * that owns the callback. A late callback from a previous tracker session can
 * never consume the current session's completion hook.
 *
 * This is intentionally tiny and framework-free so the lifecycle race is
 * regression-testable without MediaPipe or an Android device.
 */
internal class InFlightCompletionSlot {

    data class Pending(
        val reservationToken: Long,
        val mediaPipeTimestampMs: Long,
        val onConsumed: (() -> Unit)?,
    )

    private val pending = AtomicReference<Pending?>(null)

    /** Installs the completion hook before detectAsync() is called. */
    fun replace(value: Pending): Pending? = pending.getAndSet(value)

    /** Takes the hook only when the result timestamp is the one it belongs to. */
    fun takeForTimestamp(mediaPipeTimestampMs: Long): Pending? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.mediaPipeTimestampMs != mediaPipeTimestampMs) return null
            if (pending.compareAndSet(current, null)) return current
        }
    }

    /** Cancels the hook for one reservation without touching a newer reservation. */
    fun cancelForToken(reservationToken: Long): Pending? {
        while (true) {
            val current = pending.get() ?: return null
            if (current.reservationToken != reservationToken) return null
            if (pending.compareAndSet(current, null)) return current
        }
    }

    /** Clears the hook during graph teardown. */
    fun clear(): Pending? = pending.getAndSet(null)
}
