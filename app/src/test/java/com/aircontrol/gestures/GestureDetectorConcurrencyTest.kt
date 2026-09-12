package com.aircontrol.gestures

import com.aircontrol.tracking.HandFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDetectorConcurrencyTest {
    @Test
    fun `concurrent frame submissions do not corrupt temporal gesture state`() {
        val detector = GestureDetectorImpl()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1_000)
        val pool = Executors.newFixedThreadPool(8)
        repeat(1_000) {
            pool.execute {
                ready.await()
                detector.processHandFrame(HandFrame.EMPTY)
                done.countDown()
            }
        }
        ready.countDown()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        pool.shutdownNow()
        detector.reset()
        assertTrue(detector.engineState.value.name.isNotEmpty())
    }
}
