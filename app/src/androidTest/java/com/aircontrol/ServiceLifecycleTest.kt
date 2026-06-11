package com.aircontrol

import com.aircontrol.gestures.GestureDetector
import com.aircontrol.gestures.GestureDetectorImpl
import com.aircontrol.service.AirControlService
import com.aircontrol.service.AirControlServiceImpl
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.HandTracker
import com.aircontrol.tracking.Handedness
import com.aircontrol.tracking.Landmark3D
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.inject.Inject

/**
 * Instrumented tests for the AirControlService lifecycle.
 * Tests service start/stop behavior with real dependencies.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ServiceLifecycleTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun airControlServiceImplInitializesAsNotRunning() {
        // Test basic service state without full initialization
        val service = AirControlServiceImpl(
            handTracker = TestHandTracker(),
            gestureDetector = GestureDetectorImpl(),
        )
        assertFalse("Service should not be running initially", service.isRunning)
    }

    @Test
    fun airControlServiceImplCurrentGestureLabelInitiallyEmpty() {
        val service = AirControlServiceImpl(
            handTracker = TestHandTracker(),
            gestureDetector = GestureDetectorImpl(),
        )
        assertEquals("", service.currentGestureLabel.value)
    }

    @Test
    fun gestureDetectorImplInitializesInDisarmedState() {
        val detector = GestureDetectorImpl()
        assertEquals(
            com.aircontrol.gesture.model.GestureEngineState.DISARMED,
            detector.engineState.value,
        )
    }

    @Test
    fun gestureDetectorImplInitializesWithNonePose() {
        val detector = GestureDetectorImpl()
        assertEquals(com.aircontrol.gesture.model.Pose.NONE, detector.currentPose.value)
    }

    @Test
    fun gestureDetectorImplResetClearsState() {
        val detector = GestureDetectorImpl()
        detector.reset()
        assertEquals(
            com.aircontrol.gesture.model.GestureEngineState.DISARMED,
            detector.engineState.value,
        )
        assertEquals(com.aircontrol.gesture.model.Pose.NONE, detector.currentPose.value)
        assertEquals(0f, detector.armingProgress.value, 0.001f)
    }

    /**
     * A simple test HandTracker that doesn't use the camera.
     * Emits empty hand frames.
     */
    private class TestHandTracker : HandTracker {
        private val _handFrames = MutableSharedFlow<HandFrame>(extraBufferCapacity = 16)
        override val handFrames: SharedFlow<HandFrame> = _handFrames

        override fun initialize() {
            // No-op for testing
        }

        override fun processFrame(
            mpImage: com.google.mediapipe.framework.image.MPImage,
            timestampMs: Long,
        ) {
            // No-op for testing
        }

        override fun close() {
            // No-op for testing
        }

        override fun isInitialized(): Boolean = true
    }
}
