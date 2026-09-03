package com.aircontrol.util

import com.aircontrol.ui.Suppression
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the crash isolation added while fixing "enabling accessibility makes
 * the app close itself".
 *
 * The production failure was never a thrown exception inside a gesture handler -
 * it was an exception escaping a *long-lived collector* on a scope with no
 * [kotlinx.coroutines.CoroutineExceptionHandler]. On Android that reaches the
 * thread's default uncaught handler and kills the process, so the accessibility
 * service dies the moment it starts and Settings shows the service as "not
 * working". These tests pin the contract that keeps that from happening again:
 * a failing collector is logged and (optionally) restarted, and never cancels
 * its siblings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashGuardTest {

    @Test
    fun `a throwing collector is contained and reported`() = runTest {
        val before = CrashGuard.failures
        var ran = 0

        backgroundScope.launchGuarded("test collector") {
            ran++
            throw IllegalStateException("simulated overlay failure")
        }
        runCurrent()

        assertEquals("the block ran once", 1, ran)
        assertTrue("the failure was counted", CrashGuard.failures > before)
        // If the exception had escaped, runTest would have failed the test already.
    }

    @Test
    fun `a guarded collector does not cancel its siblings`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        var survivor = 0
        try {
            scope.launchGuarded("dying") { throw IllegalStateException("boom") }
            scope.launch { survivor++ }
            runCurrent()
        } finally {
            scope.cancel()
        }
        assertEquals("the sibling collector still ran", 1, survivor)
    }

    @Test
    fun `restart retries with backoff and then gives up`() = runTest {
        var attempts = 0
        var gaveUp: String? = null
        CrashGuard.onFatalLoop = { context, _ -> gaveUp = context }
        try {
            backgroundScope.launchGuarded("flaky", restart = true) {
                attempts++
                throw IllegalStateException("still broken")
            }
            // Each retry waits on a virtual delay; advance the scheduler until the
            // collector decides to stop (MAX_RESTARTS + the initial attempt).
            repeat(20) {
                runCurrent()
                if (gaveUp != null) return@repeat
                testScheduler.advanceTimeBy(64_000L)
                testScheduler.runCurrent()
            }
        } finally {
            CrashGuard.onFatalLoop = null
        }
        assertEquals("gave up after repeated failures", "flaky", gaveUp)
        assertTrue("retried instead of dying silently: $attempts", attempts >= 6)
        assertTrue("stopped retrying eventually: $attempts", attempts <= 10)
    }

    @Test
    fun `cancellation is not counted as a failure`() = runTest {
        val before = CrashGuard.failures
        var started = 0
        val job = backgroundScope.launchGuarded("cancelled") {
            started++
            kotlinx.coroutines.delay(10_000)
            throw IllegalStateException("must never run")
        }
        runCurrent()
        job.cancel()
        runCurrent()

        assertEquals("started once", 1, started)
        assertEquals("cancellation is not a pipeline failure", before, CrashGuard.failures)
    }

    @Test
    fun `collectGuarded survives a throwing action`() = runTest {
        var seen = 0
        flowOfThree().collectGuarded("action") { value ->
            seen++
            if (value == 2) throw IllegalStateException("bad emission")
        }
        runCurrent()
        assertEquals("kept consuming after the failure", 3, seen)
    }

    @Test
    fun `collectGuarded swallows a failing upstream`() = runTest {
        var seen = 0
        val broken: Flow<Int> = flow {
            emit(1)
            throw IllegalStateException("data store exploded")
        }
        broken.collectGuarded("upstream") { seen++ }
        assertEquals(1, seen)
    }

    private fun flowOfThree(): Flow<Int> = flow { emit(1); emit(2); emit(3) }

    @Test
    fun `suppression counts overlapping flows`() {
        Suppression.resetForTest()
        assertFalse(Suppression.isSuppressed())

        Suppression.acquire()
        Suppression.acquire()
        assertTrue("actions are suppressed while any flow is open", Suppression.isSuppressed())

        Suppression.release()
        assertTrue("still suppressed while the second flow is open", Suppression.isSuppressed())

        Suppression.release()
        assertFalse(Suppression.isSuppressed())

        // Releasing more than acquired must never make the counter negative (a
        // stray onCleared would otherwise permanently disable dispatch).
        Suppression.release()
        assertFalse(Suppression.isSuppressed())
    }

    @Test
    fun `scope with the guard handler stays usable after an uncaught error`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + CrashGuard.handler + kotlinx.coroutines.Dispatchers.Unconfined)
        val before = CrashGuard.failures
        try {
            scope.launch { throw IllegalStateException("escaped a bare launch") }
            runCurrent()
            var ran = false
            scope.launch { ran = true }
            runCurrent()
            assertTrue("later work still runs", ran)
            assertTrue(scope.isActive)
            assertTrue(CrashGuard.failures > before)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recent failures are newest first and bounded`() {
        val before = CrashGuard.failures
        repeat(12) { CrashGuard.report("collector-$it", IllegalStateException("boom-$it")) }
        val recent = CrashGuard.recentFailures
        assertTrue("the debug log must stay bounded, was ${'$'}{recent.size}", recent.size <= 8)
        assertEquals("collector-11: IllegalStateException: boom-11", recent.first())
        assertTrue(CrashGuard.failures >= before + 12)
    }
}
