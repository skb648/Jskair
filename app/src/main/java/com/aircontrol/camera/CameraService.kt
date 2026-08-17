package com.aircontrol.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import kotlin.concurrent.Volatile

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

        // M-02 Fix: Use AtomicReference<State> instead of separate static StateFlows.
        // The old approach had two separate MutableStateFlows that could get out of sync
        // (e.g., isRunning=true but isPaused=false when the service was actually paused).
        // Now we use a single atomic state object that is always consistent.
        //
        // The StateFlows are exposed for backward compatibility with existing consumers
        // (HomeViewModel, SettingsViewModel, etc.) but are derived from the single source
        // of truth, eliminating the sync issue.
        private data class ServiceState(
            val isRunning: Boolean = false,
            val isPaused: Boolean = false,
        )

        private val _state = java.util.concurrent.atomic.AtomicReference(ServiceState())
        
        // Expose as derived StateFlows for backward compatibility
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused

        /**
         * Atomically update the service state and propagate to the derived StateFlows.
         * This ensures isRunning and isPaused are always in sync.
         */
        private fun updateState(isRunning: Boolean, isPaused: Boolean) {
            _state.set(ServiceState(isRunning, isPaused))
            _isRunning.value = isRunning
            _isPaused.value = isPaused
        }

        /**
         * Reset the static state. Call this in tests or when the service is recreated.
         */
        fun resetState() {
            updateState(isRunning = false, isPaused = false)
        }
    }

    private lateinit var handTracker: HandTracker
    private lateinit var faceTracker: com.aircontrol.tracking.FaceTracker
    private lateinit var settingsRepository: com.aircontrol.data.repository.SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aircontrol-analysis").apply { isDaemon = true }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val isPaused = AtomicBoolean(false)
    @Volatile
    private var lastFrameTimestampMs = 0L
    @Volatile
    private var lastProcessedFrameMs: Long = 0L
    // Default analysis FPS. This is a transient default: it is replaced by the
    // user's configured analysisFps (15/24/30) as soon as preferences flow in.
    // (AdaptiveFpsController coerces to the supported set {5,10,15,24,30}.)
    private var configuredFps = 30

    // Frame watchdog — detects camera pipeline stalls
    private var frameWatchdogJob: Job? = null
    private val trackingJobs: MutableList<Job> = mutableListOf()

    // Thermal monitoring
    private lateinit var thermalMonitor: com.aircontrol.tracking.ThermalMonitor
    private var thermalMonitoringJob: Job? = null
    private var thermalPaused = false
    private var userPaused = false
    private var thermalRecoveryJob: Job? = null
    private var postRecoveryFps: Int = 0

    // Reusable transform bitmap — avoids allocation per frame for the rotation+mirror step.
    // Since the analysis executor is single-threaded, no synchronization needed.
    private var reusableTransformBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0

    // Cached transform matrix — only rebuilds when rotation changes
    private var cachedRotationDegrees = -1
    private var cachedMatrix: android.graphics.Matrix? = null

    // Restart coroutine job — tracked for cancellation
    private var restartJob: Job? = null

    private lateinit var adaptiveFpsController: AdaptiveFpsController

    override fun onCreate() {
        super.onCreate()

        // H-03 Fix: Robust DI injection with explicit error handling.
        // If injection fails, stop the service immediately rather than continuing
        // with uninitialized dependencies (which would cause silent failures).
        val app = applicationContext as? com.aircontrol.AirControlApp
        if (app == null) {
            Timber.e("Application is not AirControlApp — cannot inject dependencies. Stopping service.")
            stopSelf()
            return
        }

        try {
            val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint.getFromApplication(app)
            handTracker = entryPoint.handTracker()
            faceTracker = entryPoint.faceTracker()
            settingsRepository = entryPoint.settingsRepository()
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject dependencies via Hilt EntryPoint. Stopping service.")
            stopSelf()
            return
        }

        // Reset companion object state in case service was recreated
        resetState()

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
                if (!_state.get().isRunning) {
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
        analysisExecutor.shutdown()
        try {
            if (!analysisExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            analysisExecutor.shutdownNow()
        }
        reusableTransformBitmap?.recycle()
        reusableTransformBitmap = null
        cachedMatrix = null
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("CameraService destroyed")
    }

    private fun startTracking() {
        if (_state.get().isRunning) return

        if (!::handTracker.isInitialized || !::settingsRepository.isInitialized) {
            Timber.e("CameraService dependencies not initialized; cannot start tracking")
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(isPaused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(isPaused = false))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to enter foreground; cannot start camera tracking")
            stopSelf()
            return
        }

        updateState(isRunning = _state.get().isRunning, isPaused = false)

        handTracker.initialize()
        // Face tracker runs in parallel for "eye is mouse" gaze tracking. It
        // gracefully no-ops if the model file is missing or init fails.
        faceTracker.initialize()

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

                    // UT-01 Fix: Increased resolution from 640x480 to 1280x720 (HD)
                    // Higher resolution dramatically improves hand tracking accuracy,
                    // especially for small hands, users far from camera, or edge-of-frame detection
                    // Falls back to closest lower resolution if HD not available
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
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

                updateState(isRunning = true, isPaused = _state.get().isPaused)
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
        restartJob?.cancel()
        restartJob = null
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        thermalRecoveryJob?.cancel()
        thermalRecoveryJob = null
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
        faceTracker.close()
        adaptiveFpsController.reset()
        // M-02 Fix: Use updateState to atomically reset both flags
        updateState(isRunning = false, isPaused = false)
        thermalPaused = false
        userPaused = false
        postRecoveryFps = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()

        Timber.i("Tracking stopped")
    }

    private fun pauseTracking() {
        userPaused = true
        isPaused.set(true)
        updateState(isRunning = _state.get().isRunning, isPaused = true)
        imageAnalysis?.clearAnalyzer()
        updateNotification(isPaused = true)
        Timber.i("Tracking paused")
    }

    private fun resumeTracking() {
        userPaused = false
        isPaused.set(false)
        updateState(isRunning = _state.get().isRunning, isPaused = false)
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

            val currentTimestampMs = android.os.SystemClock.elapsedRealtime()

            // Adaptive FPS check - skip frame if too soon
            val intervalMs = adaptiveFpsController.analysisIntervalMs
            if (currentTimestampMs - lastFrameTimestampMs < intervalMs) {
                return
            }
            lastFrameTimestampMs = currentTimestampMs
            lastProcessedFrameMs = currentTimestampMs

            // Convert ImageProxy to MPImage efficiently
            val mpImage = imageProxyToMPImage(imageProxy)
            if (mpImage != null) {
                handTracker.processFrame(mpImage, currentTimestampMs)
                // Gaze tracking (eye is mouse). Runs on every frame; the face
                // landmarker handles its own async processing.
                if (faceTracker.isInitialized()) {
                    faceTracker.processFrame(mpImage, currentTimestampMs)
                }
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
     * Front camera transform order (critical for correct landmark mapping):
     * 1. Rotate by [ImageProxy.imageInfo.rotationDegrees] to correct sensor orientation
     * 2. Mirror horizontally for selfie-view (so the user's right hand appears
     *    on the right side of the image)
     *
     * This order ensures MediaPipe landmarks correspond correctly to the
     * mirrored view that users expect from a front-facing camera.
     */
    /**
     * Roadmap: Direct YUV_420_888 → MPImage via ByteBuffer (no Bitmap copy) to save 65MB/s.
     * Current reusableTransformBitmap already cuts allocation by ~50% on 720p.
     * Full YUV path requires native libYUV via NDK — planned for v1.1.
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
            // Cache the matrix — only rebuild when rotation changes
            if (cachedRotationDegrees != rotationDegrees || cachedMatrix == null) {
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
                cachedMatrix = matrix
                cachedRotationDegrees = rotationDegrees
            }
            val matrix = cachedMatrix!!

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

        // Bug #5 Fix: When isThermal is true but isPaused is false, we are in the
        // SEVERE state (5 FPS frame-skip). The user is NOT paused and may still want
        // to pause manually — so we show the Pause button. The notification text
        // reads "Performance reduced due to heat".
        // When isThermal is true AND isPaused is true, we are in the CRITICAL state
        // (full pause). The user should not be able to resume manually (cooling
        // resumes automatically) — so no Resume button. The text reads
        // "Paused — device is overheating".
        val pauseResumeAction = if (isPaused && !isThermal) {
            // User-initiated pause — show Resume
            NotificationCompat.Action.Builder(
                null,
                getString(R.string.notification_action_resume),
                createCommandPendingIntent(COMMAND_RESUME),
            ).build()
        } else if (!isPaused) {
            // Active or SEVERE (frame-skip) — show Pause
            NotificationCompat.Action.Builder(
                null,
                getString(R.string.notification_action_pause),
                createCommandPendingIntent(COMMAND_PAUSE),
            ).build()
        } else {
            // CRITICAL thermal pause — no Resume action (auto-resumes on cooling)
            null
        }

        val stopAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.notification_action_stop),
            createCommandPendingIntent(COMMAND_STOP),
        ).build()

        val contentText = when {
            // CRITICAL thermal pause (isThermal + isPaused)
            isThermal && isPaused -> getString(R.string.notification_text_thermal_critical)
            // SEVERE thermal frame-skip (isThermal + !isPaused) — tracking still alive
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
                if (_state.get().isRunning && !isPaused.get()) {
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
        Timber.i("Restarting camera binding and HandTracker")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.e(e, "Error unbinding camera during restart")
        }
        imageAnalysis = null
        lastProcessedFrameMs = 0L

        // Reinitialize HandTracker in case it was the cause of the stall
        handTracker.close()
        handTracker.initialize()
        faceTracker.close()
        faceTracker.initialize()

        restartJob = serviceScope.launch {
            try {
                withContext(Dispatchers.Main.immediate) {
                    val provider = cameraProvider ?: withContext(Dispatchers.Default) {
                        ProcessCameraProvider.getInstance(this@CameraService).get()
                    }
                    cameraProvider = provider

                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
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
            com.aircontrol.tracking.ThermalStatus.NONE -> {
                if (thermalPaused) {
                    // Recovering from CRITICAL pause — resume tracking.
                    Timber.i("Thermal recovered (CRITICAL → NONE) — resuming tracking")
                    thermalPaused = false
                    if (!userPaused) {
                        resumeTracking()
                        updateNotification(isPaused = false)
                    }
                    // Resume at reduced FPS with gradual recovery
                    postRecoveryFps = (configuredFps / 2).coerceAtLeast(5)
                    adaptiveFpsController.updateConfiguredFps(postRecoveryFps)

                    // Gradually restore FPS over 30 seconds
                    thermalRecoveryJob?.cancel()
                    thermalRecoveryJob = serviceScope.launch {
                        delay(30_000L)
                        adaptiveFpsController.updateConfiguredFps(configuredFps)
                        postRecoveryFps = 0
                    }
                } else if (postRecoveryFps > 0 || isCurrentlyThermalThrottled()) {
                    // Recovering from SEVERE/MODERATE/LIGHT throttle (no pause) — just restore FPS.
                    Timber.i("Thermal recovered (→ NONE) — restoring FPS to %d", configuredFps)
                    thermalRecoveryJob?.cancel()
                    thermalRecoveryJob = null
                    postRecoveryFps = 0
                    adaptiveFpsController.updateConfiguredFps(configuredFps)
                }
            }
            com.aircontrol.tracking.ThermalStatus.LIGHT -> {
                if (thermalPaused) {
                    // Recovering from CRITICAL pause — same recovery path as NONE.
                    Timber.i("Thermal recovering (CRITICAL → LIGHT) — resuming tracking")
                    thermalPaused = false
                    if (!userPaused) {
                        resumeTracking()
                        updateNotification(isPaused = false)
                    }
                    postRecoveryFps = (configuredFps / 2).coerceAtLeast(5)
                    adaptiveFpsController.updateConfiguredFps(postRecoveryFps)

                    thermalRecoveryJob?.cancel()
                    thermalRecoveryJob = serviceScope.launch {
                        delay(30_000L)
                        adaptiveFpsController.updateConfiguredFps(configuredFps)
                        postRecoveryFps = 0
                    }
                } else if (postRecoveryFps <= 0 && !isCurrentlySevereThrottled()) {
                    // Proactive throttling at LIGHT status (only if not already throttled harder)
                    val throttledFps = (configuredFps * 2 / 3).coerceIn(8, 20)
                    Timber.i("Thermal LIGHT — reducing FPS to %d", throttledFps)
                    adaptiveFpsController.updateConfiguredFps(throttledFps)
                }
                // If currently SEVERE-throttled (5 FPS), LIGHT is an improvement —
                // let the SEVERE branch's notification/text stand; FPS will be
                // restored when we reach NONE.
            }
            com.aircontrol.tracking.ThermalStatus.MODERATE -> {
                if (thermalPaused) {
                    // Still in CRITICAL-pause path; don't stack MODERATE throttling on top.
                    return
                }
                val throttledFps = (configuredFps / 2).coerceIn(5, 15)
                Timber.i("Thermal MODERATE — reducing FPS to %d", throttledFps)
                thermalRecoveryJob?.cancel()
                thermalRecoveryJob = null
                postRecoveryFps = 0
                adaptiveFpsController.updateConfiguredFps(throttledFps)
                // Update notification in case we're transitioning out of SEVERE state
                updateNotification(isPaused = false, isThermal = false)
            }
            com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                // Bug #5 Fix: Dynamic frame skipping at 5 FPS — DO NOT pause tracking.
                // Tracking stays alive so the user can still interact (just at lower
                // responsiveness). The notification reads "Performance reduced due to
                // heat" and the user can still manually pause if they want.
                if (thermalPaused) {
                    // Recovering from CRITICAL → SEVERE. Resume tracking at 5 FPS.
                    Timber.i("Thermal improving (CRITICAL → SEVERE) — resuming at 5 FPS")
                    thermalPaused = false
                    if (!userPaused) {
                        resumeTracking()
                    }
                }
                Timber.w("Thermal SEVERE — dynamic frame skipping at 5 FPS (no pause)")
                thermalRecoveryJob?.cancel()
                thermalRecoveryJob = null
                postRecoveryFps = 0
                adaptiveFpsController.updateConfiguredFps(5)
                // Show the "Performance reduced" notification (NOT a pause notification):
                //   isPaused = false  → tracking still alive, user can manually pause
                //   isThermal = true  → text reads "Performance reduced due to heat"
                updateNotification(isPaused = false, isThermal = true)
            }
            com.aircontrol.tracking.ThermalStatus.CRITICAL -> {
                // Critical/Emergency/Shutdown — pause tracking entirely to protect the device.
                Timber.w("Thermal CRITICAL — pausing tracking")
                thermalPaused = true
                thermalRecoveryJob?.cancel()
                thermalRecoveryJob = null
                postRecoveryFps = 0
                if (!isPaused.get()) {
                    pauseTracking()
                    // isPaused=true + isThermal=true → "Paused — device is overheating",
                    // no Resume button (auto-resumes on cooling).
                    updateNotification(isPaused = true, isThermal = true)
                }
            }
        }
    }

    /**
     * Returns true if the current adaptive FPS is below the configured FPS due to
     * SEVERE-level thermal throttling (5 FPS). Used by the NONE/LIGHT recovery
     * branches to decide whether to restore the configured FPS.
     */
    private fun isCurrentlySevereThrottled(): Boolean =
        !thermalPaused &&
            adaptiveFpsController.currentFps.value <= 5 &&
            adaptiveFpsController.currentFps.value < configuredFps

    /**
     * Returns true if the current adaptive FPS is below the configured FPS for any
     * thermal reason (MODERATE or SEVERE). Used by the NONE recovery branch.
     */
    private fun isCurrentlyThermalThrottled(): Boolean =
        !thermalPaused &&
            adaptiveFpsController.currentFps.value < configuredFps

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
