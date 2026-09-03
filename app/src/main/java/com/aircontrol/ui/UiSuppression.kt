package com.aircontrol.ui

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts how many of AirControl's own full-screen setup flows are in front of the
 * user (hand calibration, gaze calibration, custom-gesture capture).
 *
 * Fix B-3: while calibrating, the accessibility service kept dispatching the very
 * gestures the user was performing — a pinch during hand calibration pressed
 * buttons on the calibration screen itself, so the screens jumped around and
 * taps were "stolen". Cursor feedback stays live (the user still needs to see the
 * dot while they hold a pose), but *actions* are suppressed for as long as one
 * of these flows is on screen.
 *
 * A counter (not a boolean) is used because Compose screens can overlap during
 * navigation transitions: a naive boolean cleared by the outgoing screen would
 * switch dispatch back on while the calibration screen is still visible.
 */
object Suppression {

    private val active = AtomicInteger(0)

    val isSuppressedFlow: Boolean get() = active.get() > 0

    fun isSuppressed(): Boolean = isSuppressedFlow

    /** Marks a flow as foreground. Returns the token to pass to [release]. */
    fun acquire(): Int {
        active.incrementAndGet()
        return active.get()
    }

    fun release() {
        val next = active.updateAndGet { if (it > 0) it - 1 else 0 }
        if (next == 0) {
            onUnsuppressed?.invoke()
        }
    }

    /** Hook for the service to flush/prime UI state once actions resume. */
    @Volatile
    var onUnsuppressed: (() -> Unit)? = null

    /** Test helper. */
    fun resetForTest() = active.set(0)
}
