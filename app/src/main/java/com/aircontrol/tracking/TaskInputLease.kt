package com.aircontrol.tracking

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference-counted producer/consumer lease for one immutable analysis input.
 * The payload may only be accessed while the lease is live.
 */
class TaskInputLease<T : Any>(
    private val payload: T,
    private val onZeroReferences: () -> Unit = {},
) : AutoCloseable {
    private val references = AtomicInteger(1)
    private val released = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)

    fun retain(): TaskInputLease<T> {
        while (true) {
            val current = references.get()
            check(current > 0 && !released.get()) { "cannot retain a released lease" }
            if (references.compareAndSet(current, current + 1)) return this
        }
    }

    fun get(): T {
        check(references.get() > 0 && !released.get()) { "use after release" }
        return payload
    }

    fun release() {
        while (true) {
            val current = references.get()
            check(current > 0) { "double release" }
            val next = current - 1
            if (references.compareAndSet(current, next)) {
                if (next == 0) {
                    released.set(true)
                    if (cleanupStarted.compareAndSet(false, true)) onZeroReferences()
                }
                return
            }
        }
    }

    override fun close() = release()

    val referenceCount: Int get() = references.get()
    val isReleased: Boolean get() = released.get()
}
