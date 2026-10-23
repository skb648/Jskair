package com.aircontrol.accessibility

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Compatibility bridge for the accessibility service's camera-FGS start gate.
 *
 * The accessibility service lives in a different package and must not keep an
 * Activity reference. Android's process lifecycle is the correct source for the
 * question this service actually needs: is an AirControl activity currently in
 * the foreground and therefore able to initiate a while-in-use camera FGS?
 *
 * IMPORTANT: `ProcessLifecycleOwner.get().lifecycle.currentState` enforces
 * main-thread access in Lifecycle 2.9+ (`enforceMainThreadIfNeeded("getCurrentState")`)
 * and throws `IllegalStateException` when called from `Dispatchers.Default`.
 * We therefore read volatile flags updated on the main thread and only touch
 * `ProcessLifecycleOwner` directly when already on the main looper.
 */
internal class MainActivity private constructor() {
    companion object {
        @Volatile
        internal var processInForeground: Boolean = false

        val isVisible: Boolean
            get() {
                if (com.aircontrol.MainActivity.isVisible || processInForeground) {
                    return true
                }
                val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
                if (mainLooper != null && Looper.myLooper() == mainLooper) {
                    return runCatching {
                        ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                    }.getOrDefault(false)
                }
                return false
            }
    }
}

