package com.aircontrol.accessibility

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Compatibility bridge for the accessibility service's camera-FGS start gate.
 *
 * The accessibility service lives in a different package and must not keep an
 * Activity reference. Android's process lifecycle is the correct source for the
 * question this service actually needs: is an AirControl activity currently in
 * the foreground and therefore able to initiate a while-in-use camera FGS?
 */
internal class MainActivity private constructor() {
    companion object {
        val isVisible: Boolean
            get() = ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
    }
}
