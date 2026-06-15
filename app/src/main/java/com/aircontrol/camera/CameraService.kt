package com.aircontrol.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.aircontrol.MainActivity
import com.aircontrol.R
import com.aircontrol.tracking.AdaptiveFpsController
import com.aircontrol.tracking.HandTracker
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that manages the camera and feeds frames to HandTracker.
 *
 * Features:
 * - foregroundServiceType="camera" for proper service type declaration
 * - CameraX ImageAnalysis with front camera, 640x480, STRATEGY_KEEP_ONLY_LATEST
 * - Dedicated single thread executor for frame processing
 * - Adaptive FPS: full speed when hand detected, 5fps scan mode after 3s idle
 * - Persistent notification with Pause/Resume and Stop actions via PendingIntents
 * - Efficient ImageProxy → MPImage: reusable transform bitmap avoids per-frame allocation
 * - Front camera: rotate then mirror (correct selfie orientation for MediaPipe)
 * - Survives app swipe-away (START_STICKY, stopWithTask=false)
 */
class CameraService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "aircontrol_tracking"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aircontrol.action.START_TRACKING"
        const val ACTION_STOP = "com.aircontrol.action.STOP_TRACKING"
        const val ACTION_PAUSE = "com.aircontrol.action.PAUSE_TRACKING"
        const val ACTION_RESUME = "com.aircontrol.action.RESUME_TRACKING"

        const val EXTRA_COMMAND = "com.aircontrol.extra.COMMAND"

        const val COMMAND_START = 1
        const val COMMAND_STOP = 2
        const val COMMAND_PAUSE = 3
        const val COMMAND_RESUME = 4

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused
    }

    private lateinit var handTracker: HandTracker
    private lateinit var settingsRepository: com.aircontrol.data.repository.SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aircontrol-analysis").apply { isDaemon = true }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val isPaused = AtomicBoolean(false)
    private var lastFrameTimestampMs = 0L
    private var lastProcessedFrameMs: Long = 0L
    private var configuredFps = 24

    // Frame watchdog — detects camera pipeline stalls
    private var frameWatchdogJob: Job? = null
    private val trackingJobs: MutableList<Job> = mutableListOf()

    // Thermal monitoring
    private lateinit var thermalMonitor: com.aircontrol.tracking.ThermalMonitor
    private var thermalMonitoringJob: Job? = null
    private var thermalPaused = false

    // Reusable transform bitmap — avoids allocation per frame for the rotation+mirror step.
    // Since the analysis executor is single-threaded, no synchronization needed.
    private var reusableTransformBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0
    
    // Frame counter for periodic bitmap health check
    private var frameCount: Long = 0L
    private val BITMAP_HEALTH_CHECK_INTERVAL = 1000L

    private lateinit var adaptiveFpsController: AdaptiveFpsController

    override fun onCreate() {
        super.onCreate()

        // Manual Hilt injection (LifecycleService is not supported by @AndroidEntryPoint)
        (applicationContext as? com.aircontrol.AirControlApp)?.let { app ->
            val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint.getFromApplication(app)
            handTracker = entryPoint.handTracker()
            settingsRepository = entryPoint.settingsRepository()
        } ?: run {
            Timber.e("Application is not AirControlApp — cannot inject HandTracker")
        }

        adaptiveFpsController = AdaptiveFpsController(
            scope = serviceScope,
            configuredFps = configuredFps,
        )
        thermalMonitor = com.aircontrol.tracking.ThermalMonitor(
            context = this,
            scope = serviceScope,
        )
        createNotificationChannel()
        Timber.i("CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseTracking()
            }
            ACTION_RESUME -> {
                resumeTracking()
            }
            ACTION_START -> {
                startTracking()
            }
            else -> {
                // Launch from notification or system - ensure we're running
                if (!_isRunning.value) {
                    startTracking()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopTracking()
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        analysisExecutor.shutdownNow()
        serviceScope.cancel()
        reusableTransformBitmap?.recycle()
        reusableTransformBitmap = null
        super.onDestroy()
        Timber.i("CameraService destroyed")
    }

    private fun startTracking() {
        if (_isRunning.value) return

        if (!::handTracker.isInitialized || !::settingsRepository.isInitialized) {
            Timber.e("CameraService dependencies not initialized; cannot start tracking")
            stopSelf()
            return
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification(isPaused = false))
        } catch (e: Exception) {
            Timber.e(e, "Failed to enter foreground; cannot start camera tracking")
            stopSelf()
            return
        }

        _isRunning.value = true
        _isPaused.value = false

        handTracker.initialize()

        // Subscribe to settings updates that affect the camera pipeline.
        trackingJobs.add(serviceScope.launch {
            settingsRepository.userPreferences.collect { prefs ->
                configuredFps = if (prefs.batterySaver) minOf(15, prefs.analysisFps) else prefs.analysisFps
                adaptiveFpsController.updateConfiguredFps(configuredFps)
            }
        })

        // Subscribe to hand detection events for adaptive FPS.
        trackingJobs.add(serviceScope.launch {
            handTracker.handFrames.collect { frame ->
                if (frame.isDetected) {
                    adaptiveFpsController.onHandDetected(frame.timestampMs)
                } else {
                    adaptiveFpsController.onHandLost(frame.timestampMs)
                }
            }
        })

        trackingJobs.add(serviceScope.launch {
            try {
                val provider = withContext(Dispatchers.Default) {
                    ProcessCameraProvider.getInstance(this@CameraService).get()
                }
                withContext(Dispatchers.Main.immediate) {
                    cameraProvider = provider

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                    // ImageAnalysis for hand tracking
                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imgAnalysis ->
                            imgAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                processImageFrame(imageProxy)
                            }
                        }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this@CameraService,
                        cameraSelector,
                        analysis,
                    )
                }

                Timber.i("Camera started successfully")

                // Start frame watchdog after camera binding succeeds
                startFrameWatchdog()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start camera")
                withContext(Dispatchers.Main.immediate) { stopTracking() }
            }
        })

        // Start thermal monitoring
        startThermalMonitoring()
    }

    private fun stopTracking() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        trackingJobs.forEach { it.cancel() }
        trackingJobs.clear()
        stopThermalMonitoring()

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.e(e, "Error unbinding camera")
        }
        cameraProvider = null
        imageAnalysis = null

        handTracker.close()
        adaptiveFpsController.reset()
        _isRunning.value = false
        _isPaused.value = false
        thermalPaused = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Timber.i("Tracking stopped")
    }

    private fun pauseTracking() {
        isPaused.set(true)
        _isPaused.value = true
        imageAnalysis?.clearAnalyzer()
        updateNotification(isPaused = true)
        Timber.i("Tracking paused")
    }

    private fun resumeTracking() {
        isPaused.set(false)
        _isPaused.value = false
        lastFrameTimestampMs = 0L // Reset to allow immediate frame processing
        imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy ->
            processImageFrame(imageProxy)
        }
        updateNotification(isPaused = false)
        Timber.i("Tracking resumed")
    }

    private fun processImageFrame(imageProxy: ImageProxy) {
        try {
            if (isPaused.get()) {
                return
            }

            val currentTimestampMs = System.currentTimeMillis()

            // Adaptive FPS check - skip frame if too soon
            val intervalMs = adaptiveFpsController.analysisIntervalMs
            if (currentTimestampMs - lastFrameTimestampMs < intervalMs) {
                return
            }
            lastFrameTimestampMs = currentTimestampMs
            lastProcessedFrameMs = currentTimestampMs
            
            // Increment frame counter for bitmap health monitoring
            frameCount++

            // Convert ImageProxy to MPImage efficiently
            val mpImage = imageProxyToMPImage(imageProxy)
            if (mpImage != null) {
                handTracker.processFrame(mpImage, currentTimestampMs)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing image frame")
        } finally {
            // ALWAYS close ImageProxy to prevent memory leaks
            imageProxy.close()
        }
    }

    /**
     * Converts ImageProxy to MPImage for MediaPipe processing.
     *
     * Optimization: Reuses a transform bitmap across frames to avoid per-frame
     * allocation for the rotation+mirror step. The BitmapImageBuilder copies
     * the pixel data internally, so reusing the source bitmap is safe.
     *
     * Memory leak prevention (critical for long sessions):
     * - Periodic health check every 1000 frames to detect and recycle stale bitmaps
     * - Proper recycling on dimension changes or corruption
     * - Defensive checks against recycled bitmap access
     *
     * Front camera transform order (critical for correct landmark mapping):
     * 1. Rotate by [ImageProxy.imageInfo.rotationDegrees] to correct sensor orientation
     * 2. Mirror horizontally for selfie-view (so the user's right hand appears
     *    on the right side of the image)
     *
     * This order ensures MediaPipe landmarks correspond correctly to the
     * mirrored view that users expect from a front-facing camera.
     */
    private fun imageProxyToMPImage(imageProxy: ImageProxy): MPImage? {
        var rawBitmap: Bitmap? = null
        return try {
            val sourceBitmap = imageProxy.toBitmap()
            rawBitmap = sourceBitmap
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Calculate target dimensions after rotation
            val targetWidth: Int
            val targetHeight: Int
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                targetWidth = sourceBitmap.height
                targetHeight = sourceBitmap.width
            } else {
                targetWidth = sourceBitmap.width
                targetHeight = sourceBitmap.height
            }

            // Periodic health check to prevent memory leaks in long sessions
            if (frameCount % BITMAP_HEALTH_CHECK_INTERVAL == 0L) {
                if (reusableTransformBitmap != null && !reusableTransformBitmap!!.isRecycled) {
                    // Force recycle and reallocate to prevent gradual corruption
                    reusableTransformBitmap?.recycle()
                    reusableTransformBitmap = null
                    Timber.d("Bitmap health check: forced recycle at frame %d", frameCount)
                }
            }

            // Reuse or allocate transform bitmap
            if (reusableTransformBitmap == null ||
                reusableBitmapWidth != targetWidth ||
                reusableBitmapHeight != targetHeight ||
                reusableTransformBitmap!!.isRecycled
            ) {
                reusableTransformBitmap?.recycle()
                reusableTransformBitmap = Bitmap.createBitmap(
                    targetWidth, targetHeight, Bitmap.Config.ARGB_8888,
                )
                reusableBitmapWidth = targetWidth
                reusableBitmapHeight = targetHeight
                Timber.d("Allocated transform bitmap: %dx%d", targetWidth, targetHeight)
            }

            val targetBitmap = reusableTransformBitmap!!

            // Build combined transform: rotate then mirror
            val matrix = android.graphics.Matrix()
            when (rotationDegrees) {
                90 -> {
                    matrix.postRotate(90f)
                    matrix.postTranslate(sourceBitmap.height.toFloat(), 0f)
                }
                180 -> {
                    matrix.postRotate(180f)
                    matrix.postTranslate(sourceBitmap.width.toFloat(), sourceBitmap.height.toFloat())
                }
                270 -> {
                    matrix.postRotate(270f)
                    matrix.postTranslate(0f, sourceBitmap.width.toFloat())
                }
                // 0 degrees: no rotation needed
            }
            // Mirror horizontally for front camera (selfie view)
            matrix.postScale(-1f, 1f, targetWidth / 2f, targetHeight / 2f)

            // Draw into reusable target bitmap
            val canvas = android.graphics.Canvas(targetBitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(sourceBitmap, matrix, null)

            // BitmapImageBuilder copies data internally, safe to reuse targetBitmap next frame
            BitmapImageBuilder(targetBitmap).build()
        } catch (e: Exception) {
            Timber.e(e, "Error converting ImageProxy to MPImage")
            null
        } finally {
            rawBitmap?.recycle()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(isPaused: Boolean, isThermal: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseResumeAction = if (isPaused && !isThermal) {
            NotificationCompat.Action.Builder(
                null,
                getString(R.string.notification_action_resume),
                createCommandPendingIntent(COMMAND_RESUME),
            ).build()
        } else if (!isPaused) {
            NotificationCompat.Action.Builder(
                null,
                getString(R.string.notification_action_pause),
                createCommandPendingIntent(COMMAND_PAUSE),
            ).build()
        } else {
            null // No resume action for thermal pause — it auto-resumes
        }

        val stopAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.notification_action_stop),
            createCommandPendingIntent(COMMAND_STOP),
        ).build()

        val contentText = when {
            isThermal -> getString(R.string.notification_text_thermal)
            isPaused -> getString(R.string.notification_text_paused)
            else -> getString(R.string.notification_text_active)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)

        pauseResumeAction?.let { builder.addAction(it) }
        builder.addAction(stopAction)

        return builder.build()
    }

    private fun updateNotification(isPaused: Boolean, isThermal: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(isPaused, isThermal))
    }

    // ========== Frame watchdog ==========

    private fun startFrameWatchdog() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = serviceScope.launch {
            while (true) {
                delay(5000L)
                if (_isRunning.value && !isPaused.get()) {
                    val elapsed = System.currentTimeMillis() - lastProcessedFrameMs
                    if (lastProcessedFrameMs > 0L && elapsed > 5000L) {
                        Timber.w("No frames for %d ms — restarting camera", elapsed)
                        restartCamera()
                    }
                }
            }
        }
    }

    private fun restartCamera() {
        Timber.i("Restarting camera binding")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.e(e, "Error unbinding camera during restart")
        }
        imageAnalysis = null
        lastProcessedFrameMs = 0L

        serviceScope.launch {
            try {
                withContext(Dispatchers.Main.immediate) {
                    val provider = cameraProvider ?: withContext(Dispatchers.Default) {
                        ProcessCameraProvider.getInstance(this@CameraService).get()
                    }
                    cameraProvider = provider

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imgAnalysis ->
                            imgAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                processImageFrame(imageProxy)
                            }
                        }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this@CameraService,
                        cameraSelector,
                        analysis,
                    )
                }

                Timber.i("Camera restarted successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to restart camera")
            }
        }
    }

    // ========== Thermal monitoring ==========

    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring()

        thermalMonitoringJob = serviceScope.launch {
            thermalMonitor.thermalStatus.collect { status ->
                applyThermalThrottling(status)
            }
        }
    }

    private fun stopThermalMonitoring() {
        thermalMonitoringJob?.cancel()
        thermalMonitoringJob = null
        thermalMonitor.stopMonitoring()
    }

    private fun applyThermalThrottling(status: com.aircontrol.tracking.ThermalStatus) {
        when (status) {
            com.aircontrol.tracking.ThermalStatus.NONE,
            com.aircontrol.tracking.ThermalStatus.LIGHT -> {
                if (thermalPaused) {
                    Timber.i("Thermal recovered — resuming tracking")
                    thermalPaused = false
                    if (_isPaused.value) {
                        resumeTracking()
                    }
                    adaptiveFpsController.updateConfiguredFps(configuredFps)
                    updateNotification(isPaused = false)
                }
            }
            com.aircontrol.tracking.ThermalStatus.MODERATE -> {
                val throttledFps = (configuredFps / 2).coerceIn(5, 15)
                Timber.i("Thermal MODERATE — reducing FPS to %d", throttledFps)
                adaptiveFpsController.updateConfiguredFps(throttledFps)
            }
            com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                Timber.w("Thermal SEVERE — pausing tracking")
                thermalPaused = true
                if (!isPaused.get()) {
                    pauseTracking()
                    updateNotification(isPaused = true, isThermal = true)
                }
            }
        }
    }

    private fun createCommandPendingIntent(command: Int): PendingIntent {
        val intent = Intent(this@CameraService, CameraService::class.java).apply {
            action = when (command) {
                COMMAND_PAUSE -> ACTION_PAUSE
                COMMAND_RESUME -> ACTION_RESUME
                COMMAND_STOP -> ACTION_STOP
                else -> ACTION_START
            }
            putExtra(EXTRA_COMMAND, command)
        }
        return PendingIntent.getService(
            this,
            command,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
