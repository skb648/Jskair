package com.aircontrol.accessibility.cursor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/**
 * Bridges cursor positions → resolved [CursorIcon]s.
 *
 * [onCursorPosition] is called from the overlay's applied-layout path (≤60 Hz,
 * main thread). The policy throttles it to at most one accessibility hit-test
 * per [HoverResolvePolicy.resolveIntervalMs] and only while the cursor is
 * actually moving; the hit-test itself ([snapshotProvider]) runs OFF the main
 * thread on Dispatchers.Default, single-flight (skips while one is running —
 * never queues). The icon callback hops back to the main thread and fires only
 * when the icon actually changes (no redraw/flicker spam).
 *
 * Lifecycle: owned by the accessibility service, created with its scope;
 * [cancel] on overlay teardown. Retains no Activity/View/node references —
 * only the provider lambda supplied by the service.
 */
class CursorHoverMonitor(
    scope: CoroutineScope,
    private val policy: HoverResolvePolicy,
    private val snapshotProvider: suspend (x: Float, y: Float) -> CursorNodeSnapshot?,
    private val onIcon: (CursorIcon) -> Unit,
) {
    private val monitorJob = SupervisorJob(scope.coroutineContext[Job])
    private val monitorScope = scope + monitorJob

    /** Single-flight guard: true while a hit-test is running. */
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var lastIcon: CursorIcon = CursorIcon.ARROW

    /** Feed a applied cursor position (screen px, main thread, ≤60 Hz). */
    fun onCursorPosition(x: Float, y: Float, nowMs: Long) {
        if (!policy.shouldResolve(x, y, nowMs)) return
        if (!resolving.compareAndSet(false, true)) return
        monitorScope.launch(Dispatchers.Default) {
            try {
                val snapshot = runCatching { snapshotProvider(x, y) }.getOrNull()
                policy.markResolved(x, y, nowMs)
                val icon = snapshot?.let(CursorContextResolver::resolve) ?: CursorIcon.ARROW
                if (icon != lastIcon) {
                    lastIcon = icon
                    withContext(Dispatchers.Main) { onIcon(icon) }
                }
            } finally {
                resolving.set(false)
            }
        }
    }

    /** Forces the next position event to re-resolve (overlay re-shown, etc.). */
    fun refresh() = policy.reset()

    /** Emits the current icon again (e.g. after the overlay view was recreated). */
    fun reemit() {
        val icon = lastIcon
        monitorScope.launch(Dispatchers.Main) { onIcon(icon) }
    }

    fun cancel() {
        monitorJob.cancel()
    }
}
