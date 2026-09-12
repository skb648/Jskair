package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-visible ownership record for one camera frame. In release builds it is
 * still small (two atomics and one callback); it never owns the bitmap itself.
 */
class FrameOwnership(
    val frameId: Long,
    val generation: Long,
    val bitmapId: Int,
    val createdAtMs: Long,
    consumerCount: Int,
    private val onReleased: () -> Unit,
    private val onViolation: (String) -> Unit = {},
) {
    private val remaining = AtomicInteger(consumerCount)
    private val released = AtomicBoolean(false)
    private val handSubmitted = AtomicBoolean(false)
    private val faceSubmitted = AtomicBoolean(false)
    private val handCompleted = AtomicBoolean(false)
    private val faceCompleted = AtomicBoolean(false)

    fun markSubmitted(consumer: Consumer) {
        val target = if (consumer == Consumer.HAND) handSubmitted else faceSubmitted
        if (!target.compareAndSet(false, true)) onViolation("double-submit:${consumer.name}")
    }

    fun markCompleted(consumer: Consumer) {
        val submitted = if (consumer == Consumer.HAND) handSubmitted else faceSubmitted
        val completed = if (consumer == Consumer.HAND) handCompleted else faceCompleted
        if (!submitted.get()) onViolation("complete-before-submit:${consumer.name}")
        if (!completed.compareAndSet(false, true)) {
            onViolation("double-complete:${consumer.name}")
            return
        }
        if (remaining.decrementAndGet() == 0 && released.compareAndSet(false, true)) {
            onReleased()
        } else if (remaining.get() < 0) {
            onViolation("release-before-consumers-finished")
        }
    }

    fun abort(consumer: Consumer) {
        // A refused submission still owns no asynchronous reference, but it does
        // own its share of the frame lease and must complete it exactly once.
        markSubmitted(consumer)
        markCompleted(consumer)
    }

    enum class Consumer { HAND, FACE }
}
