package com.aircontrol.tracking

/**
 * A fixed-capacity pool of reusable buffers with an explicit "no slot available" answer.
 *
 * It exists to make one property structural instead of a convention: the number of live analysis
 * buffers can never exceed [capacity], so a stalled or slower-than-expected consumer cannot grow
 * memory, and the producer is *forced* to decide what to do when the pool is empty (drop the new
 * frame — see [acquire]). Before this, the app held exactly one reusable bitmap that every frame
 * overwrote regardless of whether the previous frame's inference had finished reading it, which is
 * both the race and, in spirit, an unbounded reuse of owned data.
 *
 * Three deliberate properties:
 *  - **reuse in steady state**: an item handed back is handed out again, so a tracking session
 *    allocates at most [capacity] buffers in total. [created] is how you verify that on a device.
 *  - **no waiting**: there is no blocking acquire. A real-time producer must never park waiting for
 *    a buffer; it drops and counts.
 *  - **the owner is the key**: [release] accepts the item, not an id, and an item the pool has never
 *    seen (or already handed out again) is refused and counted in [Stats.foreignReleases] rather
 *    than silently corrupting the free set.
 *
 * [destroy] runs when an item leaves the pool for good ([discard], or [drain] on a resolution
 * change), which is where the caller closes native handles or recycles bitmaps.
 *
 * Thread safety: every method is `synchronized` on the instance and the critical sections contain no
 * calls back into the caller except [create] (deliberately outside the lock so a slow allocation
 * cannot block a releasing consumer thread) and [destroy].
 */
class SlotPool<T : Any>(
    val capacity: Int,
    private val create: () -> T?,
    private val destroy: (T) -> Unit = {},
) {
    private val lock = Any()

    /** Held items plus the free ones, always of length <= [capacity]. */
    private val live = ArrayList<T>(capacity)
    private val free = ArrayList<T>(capacity)

    private var acquiredCount = 0L
    private var refusedCount = 0L
    private var foreignReleases = 0L

    /**
     * Take a slot, or null if all [capacity] slots are still held. Returning null is the *expected*
     * behaviour under load and callers must treat it as "skip this frame", never as an error worth
     * retrying or queueing.
     */
    fun acquire(): T? {
        synchronized(lock) {
            if (free.isNotEmpty()) {
                val item = free.removeAt(free.size - 1)
                live += item
                acquiredCount++
                return item
            }
            if (live.size >= capacity) {
                refusedCount++
                return null
            }
            // else: fall through and create outside the lock — a buffer allocation must not
            // hold the pool while a consumer thread is trying to hand one back.
        }
        val created = create() ?: run {
            synchronized(lock) { refusedCount++ }
            return null
        }
        return synchronized(lock) {
            // Another thread may have filled the pool while this allocation was in flight; if so the
            // newly created buffer is surplus and is destroyed rather than kept as a hidden extra.
            if (live.size >= capacity) {
                refusedCount++
                destroy(created)
                return null
            }
            live += created
            acquiredCount++
            created
        }
    }

    /** Return [item] to the free set. Unknown items are refused and counted, never adopted. */
    fun release(item: T): Boolean = synchronized(lock) {
        if (!live.remove(item)) {
            foreignReleases++
            false
        } else {
            free += item
            true
        }
    }

    /** Remove [item] from the pool permanently (bad size, dead surface) and let [create] replace it. */
    fun discard(item: T): Boolean = synchronized(lock) {
        val known = live.remove(item) || free.remove(item)
        if (known) destroy(item)
        known
    }

    /** Empty the pool, destroying every buffer. Used when the analysis size changes. */
    fun drain() {
        val snapshot = synchronized(lock) {
            val all = ArrayList(live)
            all += free
            live.clear()
            free.clear()
            all
        }
        snapshot.forEach(destroy)
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(capacity, live.size, free.size, acquiredCount, refusedCount, foreignReleases)
    }

    data class Stats(
        val capacity: Int,
        val busy: Int,
        val freeCount: Int,
        val acquired: Long,
        val refused: Long,
        val foreignReleases: Long,
    ) {
        /** Buffers this pool has ever allocated. Bounded by [capacity] unless items are discarded. */
        val created: Int get() = busy + freeCount
    }
}
