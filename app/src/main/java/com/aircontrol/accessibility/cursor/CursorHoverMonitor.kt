package com.aircontrol.accessibility.cursor

import kotlinx.coroutines.CoroutineDispatcher
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
 * thread, single-flight.
 *
 * Issue 7/8 hardening — LATEST-WINS, never a backlog:
 *  - While a hit-test is running, new qualifying positions are NOT dropped and
 *    are NOT queued: the newest one replaces the remembered "pending" position
 *    (exactly one slot).
 *  - When an in-flight result returns it is applied ONLY if it still represents
 *    the newest relevant position. If a newer request arrived meanwhile, the
 *    old result is DISCARDED and the newer position is resolved instead — an
 *    old asynchronous result can never overwrite the current cursor state.
 *  - Every request carries an implicit generation (monotonic sequence); only
 *    the request whose sequence is still current may update the icon.
 *
 * Lifecycle: owned by the accessibility service, created with its scope;
 * [cancel] on overlay teardown. Retains no Activity/View/node references —
 * only the provider lambda supplied by the service. The dispatchers are
 * injectable for tests; production defaults are background work + main UI.
 */
class CursorHoverMonitor(
    scope: CoroutineScope,
    private val policy: HoverResolvePolicy,
    private val snapshotProvider: suspend (x: Float, y: Float) -> CursorNodeSnapshot?,
    private val onIcon: (CursorIcon) -> Unit,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private data class ResolveRequest(
        val x: Float,
        val y: Float,
        val nowMs: Long,
        val seq: Long,
    )

    private val monitorJob = SupervisorJob(scope.coroutineContext[Job])
    private val monitorScope = scope + monitorJob

    /** Guards [inFlight] + [pendingLatest] (position feeds can race). */
    private val requestLock = Any()

    /** The request currently being resolved (null when idle). */
    private var inFlight: ResolveRequest? = null

    /**
     * The NEWEST request that arrived while one was in flight. Replaced on
     * every arrival — never accumulates (Issue 8: fast movement = no backlog).
     */
    private var pendingLatest: ResolveRequest? = null

    private var nextSeq = 0L

    @Volatile private var lastIcon: CursorIcon = CursorIcon.ARROW

    /** Feed an applied cursor position (screen px, main thread, ≤60 Hz). */
    fun onCursorPosition(x: Float, y: Float, nowMs: Long) {
        if (!policy.shouldResolve(x, y, nowMs)) return
        var toLaunch: ResolveRequest? = null
        synchronized(requestLock) {
            val request = ResolveRequest(x, y, nowMs, ++nextSeq)
            if (inFlight == null) {
                inFlight = request
                toLaunch = request
            } else {
                pendingLatest = request
            }
        }
        toLaunch?.let { launchResolve(it) }
    }

    private fun launchResolve(request: ResolveRequest) {
        monitorScope.launch(workDispatcher) {
            val snapshot = runCatching { snapshotProvider(request.x, request.y) }.getOrNull()
            // Throttle bookkeeping from the position this scan was started for.
            policy.markResolved(request.x, request.y, request.nowMs)

            var next: ResolveRequest? = null
            var mayApply = true
            synchronized(requestLock) {
                // Only the current in-flight request may finalize. If it was
                // somehow superseded already, never apply it.
                if (inFlight?.seq == request.seq) {
                    inFlight = null
                    val pending = pendingLatest
                    if (pending != null) {
                        pendingLatest = null
                        if (policy.shouldResolve(pending.x, pending.y, pending.nowMs)) {
                            // A newer position matters more: DISCARD this result
                            // and resolve the newest one instead.
                            inFlight = pending
                            next = pending
                            mayApply = false
                        }
                        // Else: pending is within the resolve threshold/interval
                        // of the position we just resolved — its icon is the
                        // current one; nothing more to scan.
                    }
                } else {
                    mayApply = false
                }
            }

            if (mayApply) {
                val icon = snapshot?.let(CursorContextResolver::resolve) ?: CursorIcon.ARROW
                if (icon != lastIcon) {
                    lastIcon = icon
                    withContext(uiDispatcher) { onIcon(icon) }
                }
            }
            next?.let { launchResolve(it) }
        }
    }

    /** Forces the next position event to re-resolve (overlay re-shown, etc.). */
    fun refresh() = policy.reset()

    /** Emits the current icon again (e.g. after the overlay view was recreated). */
    fun reemit() {
        val icon = lastIcon
        monitorScope.launch(uiDispatcher) { onIcon(icon) }
    }

    fun cancel() {
        monitorJob.cancel()
    }
}
