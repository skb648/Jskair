package com.aircontrol.accessibility

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Latest-state-wins transport between the tracking threads and the UI frame.
 *
 * Why this exists (audit P0-3, and the reason the cursor felt "floaty then suddenly far away"):
 * the gaze path used to `withContext(Dispatchers.Main)` **once per sample** and move the overlay
 * window inline. That has two failure modes, both of which get worse exactly when the device is
 * slow:
 *
 *  1. the inference coroutine pays a main-thread round trip per frame (scheduling latency on the
 *     critical path, and back-pressure from the UI into ML — the opposite of Rule 14);
 *  2. the UI thread ends up with a queue of *already obsolete* moves and replays them, so the
 *     pointer spends the next few frames walking through the recent past.
 *
 * The fix is not a filter or a dead zone, it is the shape of the hand-off: the producer stores the
 * newest value in a single slot and asks for **one** frame callback; the callback takes whatever is
 * newest at paint time and applies it. N intermediate samples collapse into one applied move,
 * memory is bounded by one slot, and a slow UI drops *staleness*, never work-in-progress.
 *
 * Concurrency contract:
 *  - [publish] is safe from any thread, never blocks, and never loses the newest value;
 *  - [consume] is called from the UI frame; the "was a frame already requested" flag is cleared
 *    before the slot is drained, so a publish racing with a frame can cause one redundant callback
 *    but can never cause a missed one (over-scheduling is harmless, under-scheduling is a frozen
 *    cursor);
 *  - [reset] drops whatever is pending (tracking lost, pause, overlay hidden) without touching the
 *    scheduler, so a stale target can never be painted after a state change.
 *
 * The counters are advisory under concurrent producers (a publish can be counted as coalesced by a
 * frame that drains it a moment later, so `published`, `applied` and `coalesced` are individually
 * exact but their sum is not); with the single producer the pipeline actually uses — one gaze
 * collector — the identity `published == applied + coalesced + pending` holds exactly and is tested.
 * The payload rules above do not depend on the counters.
 *
 * Pure Kotlin, no Android imports: the *policy* is unit-testable, while the vsync wiring stays in
 * the service that owns a Looper.
 */
class LatestWinsTransport<T : Any>(
    private val schedule: () -> Unit,
) {

    private val pending = AtomicReference<T?>(null)
    private val frameScheduled = AtomicBoolean(false)

    // Counters for diagnostics only — Rule 5 wants coalescing to be observable, and
    // "how many samples did the UI actually skip" is the number that distinguishes a
    // transport that is working from one that is dropping on the floor.
    private val publishedCount = AtomicLong()
    private val appliedCount = AtomicLong()
    private val coalescedCount = AtomicInteger()

    /** Stores [value] as the newest target and makes sure exactly one frame is pending. */
    fun publish(value: T) {
        publishedCount.incrementAndGet()
        pending.set(value)
        if (frameScheduled.compareAndSet(false, true)) {
            try {
                schedule()
            } catch (e: Exception) {
                // A failed schedule must not leave the flag set, or nothing is ever painted again.
                frameScheduled.set(false)
                throw e
            }
        } else {
            coalescedCount.incrementAndGet()
        }
    }

    /**
     * Takes the newest pending value from the UI frame. Returns null when nothing changed since
     * the last frame, which is the normal idle case: no `WindowManager` call, no invalidation.
     */
    fun consume(): T? {
        frameScheduled.set(false)
        val value = pending.getAndSet(null)
        if (value != null) appliedCount.incrementAndGet()
        return value
    }

    /** Drops the pending target (still-scheduled frame callback will find nothing to do). */
    fun reset() {
        pending.set(null)
    }

    /** Snapshot of the counters, for the Debug screen and the perf matrix. */
    fun stats(): Stats = Stats(
        published = publishedCount.get(),
        applied = appliedCount.get(),
        coalesced = coalescedCount.get(),
        awaitingFrame = frameScheduled.get(),
        hasPending = pending.get() != null,
    )

    data class Stats(
        val published: Long,
        val applied: Long,
        val coalesced: Int,
        val awaitingFrame: Boolean,
        val hasPending: Boolean,
    )
}
