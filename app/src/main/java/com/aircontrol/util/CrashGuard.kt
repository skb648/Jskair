package com.aircontrol.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crash isolation for the always-on parts of the app.
 *
 * The accessibility service, the camera service and the action dispatcher all
 * run long-lived collectors on scopes that had no [CoroutineExceptionHandler].
 * A single exception raised inside one of those collectors — an OEM WindowManager
 * quirk, a corrupt preference file, an unexpected null on a foldable — reaches
 * the thread's default uncaught handler and **kills the whole process**. That is
 * exactly the reported failure: "I turn on accessibility and the app crashes and
 * bounces me back; with it off the app opens but nothing works", because the
 * service never stays alive long enough to do anything.
 *
 * Every runtime collector now goes through [launchGuarded] (optionally
 * self-restarting) and consumes flows through [collectGuarded], so a failure is
 * logged, counted and surfaced instead of terminating the process.
 */
object CrashGuard {

    private val failureCount = AtomicInteger(0)

    /** Number of pipeline failures swallowed since process start (debug screen). */
    val failures: Int get() = failureCount.get()

    /** Invoked when a collector dies for good, for user-visible surfacing. */
    @Volatile
    var onFatalLoop: ((String, Throwable) -> Unit)? = null

    fun report(context: String, error: Throwable) {
        failureCount.incrementAndGet()
        Timber.e(error, "Pipeline failure in %s (total=%d)", context, failureCount.get())
    }

    internal fun noteGiveUp(context: String, error: Throwable, attempts: Int) {
        onFatalLoop?.invoke(context, error)
        Timber.e(error, "Collector '%s' gave up after %d attempts", context, attempts)
    }

    internal val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        report("uncaught", error)
    }
}

/**
 * Launch [block] on this scope with crash isolation.
 *
 * @param restart when true, an exception thrown by the block re-runs it with
 *   exponential backoff (1s, 2s, 4s … capped at 30s) instead of leaving the
 *   pipeline permanently dead. After [MAX_RESTARTS] consecutive failures the
 *   collector reports and stops, so a hard failure cannot burn the CPU.
 */
fun CoroutineScope.launchGuarded(
    context: String,
    restart: Boolean = false,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(CrashGuard.handler) {
    var attempt = 0
    while (isActive) {
        try {
            block()
            return@launch
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            CrashGuard.report(context, e)
            if (!restart) return@launch
            attempt++
            if (attempt > MAX_RESTARTS) {
                CrashGuard.noteGiveUp(context, e, attempt)
                return@launch
            }
            delay(RESTART_BASE_MS shl (attempt - 1))
        }
    }
}

/**
 * [Flow.collect] whose failures cannot escape: neither a throwing upstream nor a
 * throwing action reaches the uncaught handler. Cancellation propagates so
 * structured concurrency keeps working.
 */
suspend fun <T> Flow<T>.collectGuarded(
    context: String,
    action: suspend (T) -> Unit,
) {
    try {
        collect { value ->
            try {
                action(value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashGuard.report("$context (emission)", e)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        CrashGuard.report("$context (flow)", e)
    }
}

private const val MAX_RESTARTS = 6
private const val RESTART_BASE_MS = 1_000L
