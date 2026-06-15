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
import androidx.core.content.ContextCompat
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

        handTracker.initialize()
        _isServiceRunning.value = true

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
                    is GestureEvent.Armed -> "ARMED"
                    is GestureEvent.Disarmed -> "DISARMED"
                    is GestureEvent.CursorMoved -> "" // Don't update label
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

        val context = previewView.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.STRATEGY_NEAREST_HIGHER))
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                    preview.surfaceProvider = previewView.surfaceProvider

                    val analysisResolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.STRATEGY_NEAREST_HIGHER))
                        .build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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

                    isPreviewBound.set(true)
                    Timber.i("Debug camera bound successfully")
                } catch (e: Exception) {
                    isPreviewBound.set(false)
                    Timber.e(e, "Failed to bind debug camera")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun processDebugFrame(imageProxy: ImageProxy) {
        try {
            val mpImage = synchronized(this) {
                imageProxyToMPImage(imageProxy)
            }
            if (mpImage != null) {
                val timestampMs = System.currentTimeMillis()
                handTracker.processFrame(mpImage, timestampMs)
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
                reusableDebugBitmap!!.isRecycled
            ) {
                reusableDebugBitmap?.recycle()
                reusableDebugBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                debugBitmapWidth = targetWidth
                debugBitmapHeight = targetHeight
            }

            val targetBitmap = reusableDebugBitmap!!

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

            // Recycle raw bitmap immediately — no longer needed
            rawBitmap.recycle()

            // BitmapImageBuilder copies data internally, safe to reuse targetBitmap next frame
            BitmapImageBuilder(targetBitmap).build()
        } catch (e: Exception) {
            Timber.e(e, "Error converting debug ImageProxy to MPImage")
            null
        }
    }

    fun stopTracking(context: Context) {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.e(e, "Error unbinding debug camera")
        }
        cameraProvider = null
        isPreviewBound.set(false)

        trackingJobs.forEach { it.cancel() }
        trackingJobs.clear()
        handTracker.close()
        gestureDetector.reset()
        _isServiceRunning.value = false

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
        analysisExecutor.shutdown()
        try {
            if (!analysisExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            analysisExecutor.shutdownNow()
        }
    }
}
