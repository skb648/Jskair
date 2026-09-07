package com.aircontrol.ui.debug

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aircontrol.camera.CameraService
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.Pose
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.HandTracker
import com.aircontrol.util.CrashGuard
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * ViewModel for the Debug screen.
 *
 * For the debug screen, we manage the camera directly (Preview + ImageAnalysis)
 * bound to the Activity lifecycle, rather than using the CameraService.
 * This gives us full control over the preview surface and avoids conflicts
 * between the service's lifecycle and the UI lifecycle.
 *
 * When the debug screen is active, the CameraService is stopped if running,
 * and restarted when the debug screen is disposed.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val handTracker: HandTracker,
    private val gestureDetector: GestureDetector,
    private val cameraServiceManager: com.aircontrol.service.CameraServiceManager,
) : ViewModel() {

    private val _handFrame = MutableStateFlow(HandFrame.EMPTY)
    val handFrame: StateFlow<HandFrame> = _handFrame

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

    private val _currentFps = MutableStateFlow(24)
    val currentFps: StateFlow<Int> = _currentFps

    private val _isHandDetected = MutableStateFlow(false)
    val isHandDetected: StateFlow<Boolean> = _isHandDetected

    private val _measuredFps = MutableStateFlow(0)
    val measuredFps: StateFlow<Int> = _measuredFps

    // Gesture recognition state for debug overlay
    private val _gestureLabel = MutableStateFlow("")
    val gestureLabel: StateFlow<String> = _gestureLabel

    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    val engineState: StateFlow<GestureEngineState> = _engineState

    private val _currentPose = MutableStateFlow(Pose.NONE)
    val currentPose: StateFlow<Pose> = _currentPose

    private val _armingProgress = MutableStateFlow(0f)
    val armingProgress: StateFlow<Float> = _armingProgress

    // Crash-guard accounting: how often a collector died and was restarted, and what it was
    private val _guardFailures = MutableStateFlow(0)
    val guardFailures: StateFlow<Int> = _guardFailures

    private val _guardLog = MutableStateFlow<List<String>>(emptyList())
    val guardLog: StateFlow<List<String>> = _guardLog

    // FPS measurement
    private var frameCount = 0
    private var lastFpsMeasureTimeMs = 0L

    // Reusable transform bitmap for debug camera — avoids per-frame allocation
    @Volatile
    private var reusableDebugBitmap: Bitmap? = null
    private var debugBitmapWidth: Int = 0
    private var debugBitmapHeight: Int = 0

    // Camera management
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "debug-analysis").apply { isDaemon = true }
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var wasServiceRunning = false
    private val trackingJobs: MutableList<Job> = mutableListOf()
    private val isPreviewBound = java.util.concurrent.atomic.AtomicBoolean(false)

    // Perf audit P4: the bind coroutine, cancellable from stopTracking.
    private var bindJob: Job? = null

    /**
     * Starts tracking: stops CameraService if running, then initializes HandTracker
     * and prepares for camera binding via [bindPreview].
     */
    fun startTracking(context: Context) {
        if (_isServiceRunning.value) return

        // Check if CameraService is running and stop it to take over camera
        wasServiceRunning = CameraService.isRunning.value
        if (wasServiceRunning) {
            val stopIntent = Intent(context, CameraService::class.java).apply {
                action = CameraService.ACTION_STOP
            }
            context.startService(stopIntent)
            Timber.d("Stopped CameraService for debug screen")
        }

        // Perf audit P3: the tracker init (asset load + native model creation)
        // used to run here on the MAIN thread — proven by the logcat line
        // "7847:7847 W/HandTrackerImpl HandTracker already initialized".
        viewModelScope.launch(Dispatchers.Default) { handTracker.initialize() }
        _isServiceRunning.value = true

        // This screen binds the camera itself. Without this the accessibility
        // watchdog - which exists to revive a dead session - would start
        // CameraService again a few seconds later and steal the camera away from
        // the very screen used to diagnose tracking problems.
        cameraServiceManager.autoReviveEnabled = false

        // Guard counters on their own ticker: a pipeline that stopped producing frames is
        // exactly the case where "how many collectors died?" matters, so this cannot hang off
        // the frame window below.
        trackingJobs.add(viewModelScope.launch {
            while (isActive) {
                _guardFailures.value = CrashGuard.failures
                _guardLog.value = CrashGuard.recentFailures
                delay(1000L)
            }
        })

        // Collect hand frames for skeleton overlay and FPS measurement
        trackingJobs.add(viewModelScope.launch {
            handTracker.handFrames.collect { frame ->
                _handFrame.value = frame
                _isHandDetected.value = frame.isDetected

                // Feed to gesture detector
                gestureDetector.processHandFrame(frame)

                // Measure FPS using 1-second windows
                frameCount++
                val now = System.currentTimeMillis()
                if (lastFpsMeasureTimeMs == 0L) {
                    lastFpsMeasureTimeMs = now
                } else if (now - lastFpsMeasureTimeMs >= 1000L) {
                    val elapsed = now - lastFpsMeasureTimeMs
                    val fps = ((frameCount * 1000L) / elapsed).toInt()
                    _measuredFps.value = fps
                    frameCount = 0
                    lastFpsMeasureTimeMs = now
                }
            }
        })

        // Collect gesture events for debug label
        trackingJobs.add(viewModelScope.launch {
            gestureDetector.gestureEvents.collect { event ->
                val label = when (event) {
                    is GestureEvent.Swipe -> "Swipe ${event.direction}"
                    is GestureEvent.Pinch -> "Pinch ${event.phase}"
                    is GestureEvent.PoseTriggered -> event.pose.name
                    is GestureEvent.CustomGestureTriggered -> "Custom: ${event.gestureName}"
                    is GestureEvent.Armed -> "ARMED"
                    is GestureEvent.Disarmed -> "DISARMED"
                    is GestureEvent.CursorMoved -> "" // Don't update label
                    is GestureEvent.PalmHome -> "Palm Home"
                }
                if (label.isNotEmpty()) {
                    _gestureLabel.value = label
                }
            }
        })

        // Collect engine state
        trackingJobs.add(viewModelScope.launch {
            gestureDetector.engineState.collect { state ->
                _engineState.value = state
            }
        })

        // Collect current pose and arming progress
        trackingJobs.add(viewModelScope.launch {
            gestureDetector.currentPose.collect { pose ->
                _currentPose.value = pose
            }
        })

        trackingJobs.add(viewModelScope.launch {
            gestureDetector.armingProgress.collect { progress ->
                _armingProgress.value = progress
            }
        })
    }

    /**
     * Binds the camera (Preview + ImageAnalysis) to the given PreviewView
     * using the provided LifecycleOwner (the Activity).
     */
    fun bindPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (!isPreviewBound.compareAndSet(false, true)) return

        // Perf audit P4: wait for CameraService to finish stopping BEFORE
        // binding. Both share the process-wide ProcessCameraProvider; the
        // service's async ACTION_STOP teardown runs unbindAll() on the main
        // thread, which used to land *after* this screen had bound — killing
        // the debug camera and leaving the screen blind (logcat 15:10:09).
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            awaitCameraServiceStopped(SERVICE_STOP_WAIT_TIMEOUT_MS)
            if (!isPreviewBound.get() || !_isServiceRunning.value) return@launch

            val context = previewView.context
            val provider = withContext(Dispatchers.Default) {
                runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
            }
            if (provider == null) {
                isPreviewBound.set(false)
                Timber.e("Could not obtain the camera provider for the debug screen")
                return@launch
            }
            if (!isPreviewBound.get() || !_isServiceRunning.value) return@launch
            cameraProvider = provider

            withContext(Dispatchers.Main.immediate) {
                try {
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                    preview.surfaceProvider = previewView.surfaceProvider

                    val analysisResolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
                        .build()

                    // Perf audit P6: same RGBA_8888 zero-copy path as the
                    // service — toBitmap() wraps the frame buffer instead of
                    // allocating a 640×480 ARGB bitmap per frame.
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processDebugFrame(imageProxy)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                    )

                    Timber.i("Debug camera bound successfully")
                } catch (e: Exception) {
                    isPreviewBound.set(false)
                    Timber.e(e, "Failed to bind debug camera")
                }
            }
        }
    }

    /**
     * Suspends until [CameraService] reports itself stopped (or the timeout
     * expires). Polls cheap companion state — no binder calls.
     */
    private suspend fun awaitCameraServiceStopped(timeoutMs: Long) {
        if (!CameraService.isRunning.value) return
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!CameraService.isRunning.value) return
            delay(50L)
        }
        Timber.w("CameraService did not report stopped within %dms; binding anyway", timeoutMs)
    }

    private fun processDebugFrame(imageProxy: ImageProxy) {
        try {
            val mpImage = synchronized(this) {
                imageProxyToMPImage(imageProxy)
            }
            if (mpImage != null) {
                try {
                    val timestampMs = System.currentTimeMillis()
                    handTracker.processFrame(mpImage, timestampMs)
                } finally {
                    // Perf audit P15: the debug path never closed the MPImage —
                    // its reference-counted native storage leaked once per
                    // frame (the service path has always closed it). Close
                    // after submission; detectAsync consumes the image
                    // synchronously, same contract as CameraService.
                    mpImage.close()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing debug frame")
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToMPImage(imageProxy: ImageProxy): MPImage? {
        return try {
            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Calculate target dimensions after rotation
            val targetWidth: Int
            val targetHeight: Int
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                targetWidth = rawBitmap.height
                targetHeight = rawBitmap.width
            } else {
                targetWidth = rawBitmap.width
                targetHeight = rawBitmap.height
            }

            // Reuse or allocate transform bitmap
            if (reusableDebugBitmap == null ||
                debugBitmapWidth != targetWidth ||
                debugBitmapHeight != targetHeight ||
                reusableDebugBitmap?.isRecycled == true
            ) {
                reusableDebugBitmap?.recycle()
                reusableDebugBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                debugBitmapWidth = targetWidth
                debugBitmapHeight = targetHeight
            }

            val targetBitmap = checkNotNull(reusableDebugBitmap)

            val matrix = Matrix()
            when (rotationDegrees) {
                90 -> {
                    matrix.postRotate(90f)
                    matrix.postTranslate(rawBitmap.height.toFloat(), 0f)
                }
                180 -> {
                    matrix.postRotate(180f)
                    matrix.postTranslate(rawBitmap.width.toFloat(), rawBitmap.height.toFloat())
                }
                270 -> {
                    matrix.postRotate(270f)
                    matrix.postTranslate(0f, rawBitmap.width.toFloat())
                }
            }
            // Mirror horizontally for front camera (selfie view)
            matrix.postScale(-1f, 1f, targetWidth / 2f, targetHeight / 2f)

            // Draw into reusable target bitmap
            val canvas = android.graphics.Canvas(targetBitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(rawBitmap, matrix, null)

            // Perf audit P6: with RGBA_8888 output, toBitmap() WRAPS the frame
            // buffer CameraX still owns — recycling it would free live
            // storage. On API 26+ even the YUV-converted path is GC-managed,
            // so the source bitmap is simply left to the GC on both paths.

            // BitmapImageBuilder copies data internally, safe to reuse targetBitmap next frame
            BitmapImageBuilder(targetBitmap).build()
        } catch (e: Exception) {
            Timber.e(e, "Error converting debug ImageProxy to MPImage")
            null
        }
    }

    fun stopTracking(context: Context) {
        // Perf audit P4: cancel a pending bind so it cannot land after dispose.
        bindJob?.cancel()
        bindJob = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.e(e, "Error unbinding debug camera")
        }
        cameraProvider = null
        isPreviewBound.set(false)

        trackingJobs.forEach { it.cancel() }
        trackingJobs.clear()
        // Perf audit P2: the HandTracker is a process-wide @Singleton and no
        // longer dies with this screen. Leaving it initialized means the
        // CameraService restarted below (or later, by the watchdog) reuses the
        // loaded model instead of re-reading it from assets — the churn the
        // 2026-09-07 logcat showed on every debug-screen round trip. An idle
        // tracker is released under memory pressure (AirControlApp.onTrimMemory)
        // or on an explicit gesture disable.
        gestureDetector.reset()
        _isServiceRunning.value = false

        cameraServiceManager.autoReviveEnabled = true

        if (wasServiceRunning) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isForeground = activityManager?.runningAppProcesses?.any {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: false

            if (isForeground) {
                val startIntent = Intent(context, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
                try {
                    context.startForegroundService(startIntent)
                    Timber.d("Restarted CameraService after debug screen")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start camera service")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(this) {
            reusableDebugBitmap?.recycle()
            reusableDebugBitmap = null
        }
        // Perf audit P3: the old code blocked the MAIN thread here for up to
        // 2 s in awaitTermination while navigating away from the screen.
        // shutdown() is non-blocking; the bounded wait + shutdownNow run on a
        // short-lived cleanup thread instead.
        val executor = analysisExecutor
        executor.shutdown()
        Thread {
            try {
                if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
            }
        }.apply {
            name = "debug-executor-cleanup"
            isDaemon = true
        }.start()
    }

    private companion object {
        /** Perf audit P4: how long to wait for CameraService to report stopped. */
        const val SERVICE_STOP_WAIT_TIMEOUT_MS = 2_500L
    }
}
