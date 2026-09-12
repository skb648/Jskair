package com.aircontrol.gesture.detection

import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.Pose
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test

class StaticPoseClassifierConcurrencyTest {
    @Test
    fun `1000 simultaneous debounce updates are serialized and deterministic`() {
        val classifier = StaticPoseClassifier(GestureEngineConfig(poseDebounceFrames = 3))
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1_000)
        repeat(1_000) {
            pool.execute {
                ready.await()
                classifier.applyDebounce(Pose.OPEN_PALM)
                done.countDown()
            }
        }
        ready.countDown()
        done.await()
        pool.shutdown()
        assertEquals(Pose.OPEN_PALM, classifier.confirmedPose)
    }
}
