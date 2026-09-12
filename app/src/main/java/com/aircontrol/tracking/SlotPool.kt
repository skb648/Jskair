package com.aircontrol.tracking

/**
 * Fixed-capacity, non-blocking pool for resources that may be held by an
 * asynchronous consumer.
 *
 * A resize/teardown cannot destroy an item that is still leased. [drain]
 * therefore retires busy items and destroys only free items; a retired item is
 * destroyed at the exact release that proves no consumer can still reference it.
 * This is the important ownership rule for Bitmap/MPImage buffers.
 */
class SlotPool<T : Any>(
    val capacity: Int,
    private val create: () -> T?,
    private val destroy: (T) -> Unit = {},
) {
    private val lock = Any()
    private val live = ArrayList<T>(capacity)
    private val free = ArrayList<T>(capacity)
    private val retired = HashSet<T>()

    private var acquiredCount = 0L
    private var refusedCount = 0L
    private var foreignReleases = 0L

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
        }

        val created = create() ?: run {
            synchronized(lock) { refusedCount++ }
            return null
        }
        return synchronized(lock) {
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

    /**
     * Releases one lease. A retired lease is destroyed here, never while it is
     * still in [live].
     */
    fun release(item: T): Boolean {
        var destroyNow = false
        val accepted = synchronized(lock) {
            if (!live.remove(item)) {
                foreignReleases++
                false
            } else if (retired.remove(item)) {
                destroyNow = true
                true
            } else {
                free += item
                true
            }
        }
        if (destroyNow) destroy(item)
        return accepted
    }

    /** Discard is only valid before an item is handed to an async consumer. */
    fun discard(item: T): Boolean {
        val known = synchronized(lock) {
            val wasLive = live.remove(item)
            val wasFree = if (!wasLive) free.remove(item) else false
            if (wasLive) retired.remove(item)
            wasLive || wasFree
        }
        if (known) destroy(item)
        return known
    }

    /**
     * Retire busy items and destroy only free items. This method is safe during
     * resolution changes and shutdown while MediaPipe still has callbacks in flight.
     */
    fun drain() {
        val destroyNow = synchronized(lock) {
            retired += live
            val freeSnapshot = ArrayList(free)
            free.clear()
            freeSnapshot
        }
        destroyNow.forEach(destroy)
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(capacity, live.size, free.size, retired.size, acquiredCount, refusedCount, foreignReleases)
    }

    data class Stats(
        val capacity: Int,
        val busy: Int,
        val freeCount: Int,
        val retiredCount: Int = 0,
        val acquired: Long,
        val refused: Long,
        val foreignReleases: Long,
    ) {
        val created: Int get() = busy + freeCount
    }
}
