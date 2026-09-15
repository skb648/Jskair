package com.aircontrol.accessibility

import com.aircontrol.accessibility.ServiceReadinessPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: service readiness must not be coupled to camera / overlay success.
 * Before the fix, a deferred camera FGS start (Activity not visible) left
 * `isConnected=false` on a fully bound accessibility service.
 */
class ServiceReadinessPolicyTest {

    @Test
    fun `camera deferred does not make the service not-ready`() {
        val verdict = ServiceReadinessPolicy.decide(
            dependenciesInjected = true, dispatcherAttached = true, overlayOk = true, cameraOk = false,
        )
        assertEquals(Verdict.READY_DEGRADED, verdict)
        assertTrue(ServiceReadinessPolicy.publishesConnected(verdict))
    }

    @Test
    fun `overlay failure does not make the service not-ready`() {
        val verdict = ServiceReadinessPolicy.decide(
            dependenciesInjected = true, dispatcherAttached = true, overlayOk = false, cameraOk = false,
        )
        assertEquals(Verdict.READY_DEGRADED, verdict)
        assertTrue(ServiceReadinessPolicy.publishesConnected(verdict))
    }

    @Test
    fun `everything up is READY`() {
        val verdict = ServiceReadinessPolicy.decide(true, true, true, true)
        assertEquals(Verdict.READY, verdict)
        assertTrue(ServiceReadinessPolicy.publishesConnected(verdict))
    }

    @Test
    fun `DI failure is NOT_READY regardless of the rest`() {
        val verdict = ServiceReadinessPolicy.decide(false, true, true, true)
        assertEquals(Verdict.NOT_READY, verdict)
        assertFalse(ServiceReadinessPolicy.publishesConnected(verdict))
    }

    @Test
    fun `dispatcher attach failure is NOT_READY`() {
        val verdict = ServiceReadinessPolicy.decide(true, false, true, true)
        assertEquals(Verdict.NOT_READY, verdict)
        assertFalse(ServiceReadinessPolicy.publishesConnected(verdict))
    }
}
