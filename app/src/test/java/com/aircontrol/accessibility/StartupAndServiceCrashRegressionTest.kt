package com.aircontrol.accessibility

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import com.aircontrol.service.CameraServiceManager
import com.aircontrol.tracking.FaceTrackerImpl
import com.aircontrol.tracking.HandTrackerImpl
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartupAndServiceCrashRegressionTest {

    private class FakeTrackingContext(
        base: Context,
        private val cameraPermissionResult: Int = PackageManager.PERMISSION_DENIED,
        private val assetThrowable: Throwable? = null,
    ) : ContextWrapper(base) {
        var startForegroundServiceCalls = 0
        var startServiceCalls = 0

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            return if (permission == Manifest.permission.CAMERA) {
                cameraPermissionResult
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            startForegroundServiceCalls++
            return null
        }

        override fun startService(service: Intent?): ComponentName? {
            startServiceCalls++
            return null
        }

        override fun getAssets(): AssetManager {
            assetThrowable?.let { throw it }
            return super.getAssets()
        }
    }

    @Before
    fun setUp() {
        MainActivity.processInForeground = false
    }

    @After
    fun tearDown() {
        MainActivity.processInForeground = false
    }

    @Test
    fun `accessibility MainActivity isVisible is safe to read on Dispatchers Default background thread`() = runBlocking {
        MainActivity.processInForeground = false
        val backgroundFalse = withContext(Dispatchers.Default) {
            MainActivity.isVisible
        }
        assertFalse(backgroundFalse)

        MainActivity.processInForeground = true
        val backgroundTrue = withContext(Dispatchers.Default) {
            MainActivity.isVisible
        }
        assertTrue(backgroundTrue)
    }

    @Test
    fun `CameraServiceManager skips startForegroundService when CAMERA permission is not granted`() {
        val base = RuntimeEnvironment.getApplication()
        val context = FakeTrackingContext(base, cameraPermissionResult = PackageManager.PERMISSION_DENIED)
        val manager = CameraServiceManager(context)

        manager.startTracking()

        assertEquals(0, context.startForegroundServiceCalls)
        assertEquals(0, context.startServiceCalls)
    }

    @Test
    fun `HandTrackerImpl and FaceTrackerImpl catch Throwable and Error during delegate initialization and runtime fallback`() {
        val base = RuntimeEnvironment.getApplication()
        val context = FakeTrackingContext(
            base = base,
            cameraPermissionResult = PackageManager.PERMISSION_GRANTED,
            assetThrowable = UnsatisfiedLinkError("Simulated JNI GPU link failure"),
        )

        val handTracker = HandTrackerImpl(context)
        // Must not throw UnsatisfiedLinkError
        handTracker.initialize()
        assertFalse(handTracker.isInitialized())
        assertEquals("ERROR", handTracker.activeDelegate)
        assertNotNull(handTracker.lastInitError)
        assertTrue(handTracker.lastInitError is UnsatisfiedLinkError)

        // Direct delegate init failure with Throwable must return null, never throw
        assertNull(handTracker.tryInitializeWithDelegate(Delegate.GPU))
        handTracker.handleRuntimeDelegateError(Delegate.GPU, RuntimeException("Simulated GPU runtime failure"))

        val faceTracker = FaceTrackerImpl(context)
        faceTracker.initialize()
        assertFalse(faceTracker.isInitialized())
        assertEquals("ERROR", faceTracker.activeDelegate)
        assertNotNull(faceTracker.lastInitError)
        assertTrue(faceTracker.lastInitError is UnsatisfiedLinkError)

        assertNull(faceTracker.tryInitializeWithDelegate(Delegate.GPU))
        faceTracker.handleRuntimeDelegateError(Delegate.GPU, RuntimeException("Simulated GPU runtime failure"))
    }
}
