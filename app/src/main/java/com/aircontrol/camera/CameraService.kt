package com.aircontrol.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.isActive
import com.aircontrol.util.collectGuarded
import com.aircontrol.util.launchGuarded
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that manages the camera and feeds frames to HandTracker.
 *
 * Key fixes in this revision:
 * - returns START_NOT_STICKY so Android does not silently re-arm the camera
 *   after a process kill (fix #14)
 * - checks camera permission before binding (fix #15)
 * - defers start while the keyguard is locked (fix #7)
 * - frame watchdog respects user pause and keyguard (fix #7, #8)
 * - face tracker only runs when eye tracking is enabled (fix #11)
 * - tracker init is off the main thread (fix #12)
 * - pipelineJobs access is thread-safe (fix #20)
 * - executor shutdown runs off the main thread (fix #22)
 * - single source of truth for isRunning/isPaused in companion state so
 *   external consumers (CameraServiceManager, ViewModels) can read it
 * - only FOREGROUND_SERVICE_TYPE_CAMERA is declared (fix #88)
 */
class CameraService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "aircontrol_tracking"

        /** How often the watchdog checks the pipeline (ms). */
        private const val WATCHDOG_PERIOD_MS = 5_000L

        /** After a tracker is found missing, retry every N ticks. */
        private const val TRACKER_RETRY_TICKS = 3

        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aircontrol.action.START_TRACKING"
        const val ACTION_STOP = "com.aircontrol.action.STOP_TRACKING"
        const val ACTION_PAUSE = "com.aircontrol.action.PAUSE_TRACKING"
        const val ACTION_RESUME = "com.aircontrol.action.RESUME_TRACKING"

        const val COMMAND_START = 1
        // Fix B-4: the notification's Stop button. Used to be a bare ACTION_STOP,
        // which left the master gesture switch ON; the accessibility service
        // watchdog then revived the camera a few seconds later and the "Stop"
        // button appeared to do nothing. This variant stops the session *and*
        // turns the switch off, so the app state and the notification agree.
        const val ACTION_STOP_AND_DISABLE = "com.aircontrol.action.STOP_AND_DISABLE"
        const val COMMAND_STOP = 2
        const val COMMAND_PAUSE = 3
        const val COMMAND_RESUME = 4

        data class ServiceState(
            val isRunning: Boolean = false,
            val isPaused: Boolean = false,
        )

        // Single companion-held MutableStateFlow so external consumers can read
        // running/paused state without a service binding.
        private val _state = MutableStateFlow(ServiceState())
        val serviceState: StateFlow<ServiceState> = _state.asStateFlow()
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private fun publishState(state: ServiceState) {
            _state.value = state
            _isRunning.value = state.isRunning
            _isPaused.value = state.isPaused
        }

        fun resetState() = publishState(ServiceState())
    }

    // Dependencies
    private lateinit var handTracker: HandTracker
    private lateinit var faceTracker: com.aircontrol.tracking.FaceTracker
    private lateinit var settingsRepository: com.aircontrol.data.repository.SettingsRepository

    // Fix B-1: the handler is essential. This scope runs the camera pipeline's
    // collectors for the whole life of the service; an exception that escaped one
    // of them reached the default uncaught handler and killed the process, which
    // is what the user sees as "AirControl closes by itself".
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + com.aircontrol.util.CrashGuard.handler,
    )
    private val lifecycleMutex = Mutex()

    private var analysisExecutor: java.util.concurrent.ExecutorService? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile private var userPaused = false
    @Volatile private var thermalPaused = false
    @Volatile private var lastFrameTimestampMs: Long = 0L
    @Volatile private var lastProcessedFrameMs: Long = 0L
    @Volatile private var configuredFps = 24 // match UserPreferences default (fix #55)
    @Volatile private var eyeTrackingEnabled = false

    private var frameWatchdogJob: Job? = null

    /** Watchdog bookkeeping for a missing/uninitialized hand tracker (Fix A-1b). */
    private var deadTrackerTicks: Int = 0
    private var nextTrackerRetryTick: Int = TRACKER_RETRY_TICKS
    private val pipelineJobs = mutableListOf<Job>()
    private val jobsLock = Any()

    private lateinit var thermalMonitor: com.aircontrol.tracking.ThermalMonitor
    private var thermalMonitoringJob: Job? = null
    private var thermalRecoveryJob: Job? = null
    private var postRecoveryFps: Int = 0

    private var reusableTransformBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0

    private var cachedRotationDegrees = -1
    private var cachedMatrix: android.graphics.Matrix? = null

    private var restartJob: Job? = null

    private lateinit var adaptiveFpsController: AdaptiveFpsController

    override fun onCreate() {
        super.onCreate()

        val app = applicationContext as? com.aircontrol.AirControlApp
        if (app == null) {
            Timber.e("AirControlApp not found — stopping service")
            stopSelf()
            return
        }

        try {
            val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint.getFromApplication(app)
            handTracker = entryPoint.handTracker()
            faceTracker = entryPoint.faceTracker()
            settingsRepository = entryPoint.settingsRepository()
        } catch (e: Exception) {
            Timber.e(e, "DI failure — stopping service")
            stopSelf()
            return
        }

        adaptiveFpsController = AdaptiveFpsController(scope = serviceScope, configuredFps = configuredFps)
        thermalMonitor = com.aircontrol.tracking.ThermalMonitor(context = app, scope = serviceScope)
        createNotificationChannel()
        analysisExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "aircontrol-analysis").apply { isDaemon = true }
        }
        Timber.i("CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        serviceScope.launch {
            lifecycleMutex.withLock {
                when (intent?.action) {
                    ACTION_STOP_AND_DISABLE -> {
                        stopTrackingLocked()
                        // Persist the master switch off (idempotent for every other
                        // caller, which already turned it off before stopping).
                        runCatching {
                            if (::settingsRepository.isInitialized) {
                                settingsRepository.updateGesturesEnabled(false)
                            }
                        }.onFailure { Timber.e(it, "Could not persist gesturesEnabled=false on stop") }
                        return@launch
                    }
                    ACTION_STOP -> { stopTrackingLocked(); return@launch }
                    ACTION_PAUSE -> pauseTrackingLocked()
                    ACTION_RESUME -> resumeTrackingLocked()
                    ACTION_START -> startTrackingLocked()
                    else -> {
                        Timber.d("Null/unknown intent; not auto-starting camera (fix #14).")
                    }
                }
            }
        }
        // Fix #14: START_NOT_STICKY so a killed service does not restart with null intent.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        serviceScope.launch(Dispatchers.IO) {
            runCatching { stopTrackingLocked() }
            val executor = analysisExecutor
            analysisExecutor = null
            executor?.shutdown()
            try {
                if (executor != null && !executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                executor?.shutdownNow()
            }
            reusableTransformBitmap?.recycle()
            reusableTransformBitmap = null
            cachedMatrix = null
        }
        super.onDestroy()
        serviceScope.launch { delay(1500); serviceScope.cancel() }
        Timber.i("CameraService destroyed")
    }

    // ------------------- start/stop/pause/resume -------------------

    private suspend fun startTrackingLocked() {
        if (_state.value.isRunning) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.e("Camera permission missing; aborting start")
            stopSelf()
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            Timber.i("Keyguard locked — deferring camera start until unlock")
            return
        }
        if (!::handTracker.isInitialized || !::settingsRepository.isInitialized) {
            Timber.e("Dependencies not initialized")
            stopSelf()
            return
        }

        try {
            val notification = buildNotification(isPaused = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.e(e, "startForeground failed")
            stopSelf()
            return
        }
        publishState(ServiceState(isRunning = true, isPaused = false))

        withContext(Dispatchers.Default) { handTracker.initialize() }
        // Fix A-1b: never pretend the session is live if the model could not be
        // created - the watchdog below rebuilds it, and the log says so plainly.
        if (!handTracker.isInitialized()) {
            Timber.e("Hand tracker failed to initialize; the watchdog will keep retrying")
        }
        deadTrackerTicks = 0
        nextTrackerRetryTick = TRACKER_RETRY_TICKS

        synchronized(jobsLock) {
            pipelineJobs.add(serviceScope.launchGuarded("camera settings", restart = true) {
                settingsRepository.userPreferences.collectGuarded("camera settings") { prefs ->
                    configuredFps = if (prefs.batterySaver) minOf(15, prefs.analysisFps) else prefs.analysisFps
                    adaptiveFpsController.updateConfiguredFps(configuredFps)
                    if (eyeTrackingEnabled != prefs.eyeTrackingEnabled) {
                        eyeTrackingEnabled = prefs.eyeTrackingEnabled
                        if (eyeTrackingEnabled && !faceTracker.isInitialized()) {
                            // Initializing the face landmarker can throw on devices where
                            // MediaPipe cannot allocate a GPU delegate. A throw here used to
                            // kill the collector (and the process). Now: log, keep hands
                            // working, and retry the next time the toggle changes.
                            runCatching { withContext(Dispatchers.Default) { faceTracker.initialize() } }
                                .onFailure { Timber.e(it, "Face tracker init failed; eye mode stays off") }
                        }
                    }
                }
            })
            pipelineJobs.add(serviceScope.launchGuarded("hand fps", restart = true) {
                handTracker.handFrames.collectGuarded("hand fps") { frame ->
                    if (frame.isDetected) adaptiveFpsController.onHandDetected(frame.timestampMs)
                    else adaptiveFpsController.onHandLost(frame.timestampMs)
                }
            })
        }

        try {
            val provider = withContext(Dispatchers.Default) {
                ProcessCameraProvider.getInstance(this@CameraService).get()
            }
            withContext(Dispatchers.Main.immediate) {
                cameraProvider = provider
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    ).build()
                val executor = analysisExecutor ?: return@withContext
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { img ->
                        img.setAnalyzer(executor) { imageProxy -> processImageFrame(imageProxy) }
                    }
                imageAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(this@CameraService, cameraSelector, analysis)
            }
            publishState(ServiceState(isRunning = true, isPaused = false))
            userPaused = false
            thermalPaused = false
            lastProcessedFrameMs = SystemClock.elapsedRealtime()
            Timber.i("Camera started")
            startFrameWatchdog()
            startThermalMonitoring()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start camera")
            stopTrackingLocked()
        }
    }

    private suspend fun stopTrackingLocked() {
        restartJob?.cancel(); restartJob = null
        frameWatchdogJob?.cancel(); frameWatchdogJob = null
        thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
        synchronized(jobsLock) {
            pipelineJobs.forEach { it.cancel() }
            pipelineJobs.clear()
        }
        stopThermalMonitoring()
        runCatching { withContext(Dispatchers.Main.immediate) { cameraProvider?.unbindAll() } }
            .onFailure { Timber.e(it, "unbindAll failed") }
        cameraProvider = null
        imageAnalysis = null
        withContext(Dispatchers.Default) {
            runCatching { handTracker.close() }
            runCatching { faceTracker.close() }
        }
        adaptiveFpsController.reset()
        publishState(ServiceState(isRunning = false, isPaused = false))
        userPaused = false
        thermalPaused = false
        postRecoveryFps = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        Timber.i("Tracking stopped")
    }

    private suspend fun pauseTrackingLocked() {
        if (!_state.value.isRunning) return
        userPaused = true
        publishState(_state.value.copy(isPaused = true))
        withContext(Dispatchers.Main.immediate) { imageAnalysis?.clearAnalyzer() }
        updateNotification(isPaused = true)
        Timber.i("Tracking paused by user")
    }

    private suspend fun resumeTrackingLocked() {
        if (!_state.value.isRunning) { startTrackingLocked(); return }
        userPaused = false
        if (thermalPaused) {
            Timber.i("Resume requested but thermal pause active; waiting for recovery")
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            Timber.i("Resume requested but keyguard locked; staying paused")
            return
        }
        publishState(_state.value.copy(isPaused = false))
        lastFrameTimestampMs = 0L
        lastProcessedFrameMs = SystemClock.elapsedRealtime()
        val executor = analysisExecutor ?: return
        withContext(Dispatchers.Main.immediate) {
            imageAnalysis?.setAnalyzer(executor) { imageProxy -> processImageFrame(imageProxy) }
        }
        updateNotification(isPaused = false)
        Timber.i("Tracking resumed")
    }

    // ------------------- Frame processing -------------------

    private fun processImageFrame(imageProxy: ImageProxy) {
        try {
            if (_state.value.isPaused) return
            val now = SystemClock.elapsedRealtime()
            val intervalMs = adaptiveFpsController.analysisIntervalMs
            if (now - lastFrameTimestampMs < intervalMs) return
            lastFrameTimestampMs = now
            lastProcessedFrameMs = now

            val mpImage = imageProxyToMPImage(imageProxy)
            if (mpImage != null) {
                try {
                    handTracker.processFrame(mpImage, now)
                    if (eyeTrackingEnabled && faceTracker.isInitialized()) {
                        faceTracker.processFrame(mpImage, now)
                    }
                } finally {
                    // MPImage owns reference-counted native storage. Explicit close
                    // prevents native-memory growth during continuous tracking.
                    mpImage.close()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "processImageFrame error")
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToMPImage(imageProxy: ImageProxy): MPImage? {
        var rawBitmap: Bitmap? = null
        return try {
            val sourceBitmap = imageProxy.toBitmap()
            rawBitmap = sourceBitmap
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val targetW: Int
            val targetH: Int
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                targetW = sourceBitmap.height; targetH = sourceBitmap.width
            } else { targetW = sourceBitmap.width; targetH = sourceBitmap.height }

            if (reusableTransformBitmap == null || reusableBitmapWidth != targetW ||
                reusableBitmapHeight != targetH || reusableTransformBitmap?.isRecycled == true) {
                reusableTransformBitmap?.recycle()
                reusableTransformBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                reusableBitmapWidth = targetW; reusableBitmapHeight = targetH
            }
            val targetBitmap = checkNotNull(reusableTransformBitmap)
            if (cachedRotationDegrees != rotationDegrees || cachedMatrix == null) {
                val m = android.graphics.Matrix()
                when (rotationDegrees) {
                    90 -> { m.postRotate(90f); m.postTranslate(sourceBitmap.height.toFloat(), 0f) }
                    180 -> { m.postRotate(180f); m.postTranslate(sourceBitmap.width.toFloat(), sourceBitmap.height.toFloat()) }
                    270 -> { m.postRotate(270f); m.postTranslate(0f, sourceBitmap.width.toFloat()) }
                }
                m.postScale(-1f, 1f, targetW / 2f, targetH / 2f)
                cachedMatrix = m; cachedRotationDegrees = rotationDegrees
            }
            val canvas = android.graphics.Canvas(targetBitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(sourceBitmap, checkNotNull(cachedMatrix), null)
            BitmapImageBuilder(targetBitmap).build()
        } catch (e: Exception) {
            Timber.e(e, "imageProxyToMPImage failed")
            null
        } finally { rawBitmap?.recycle() }
    }

    // ------------------- Notifications -------------------

    private fun createNotificationChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false); enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }.onFailure { Timber.e(it, "Notification channel creation failed") }
    }

    private fun buildNotification(isPaused: Boolean, isThermal: Boolean = false): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseResumeAction = if (isPaused && !isThermal) {
            NotificationCompat.Action(null, getString(R.string.notification_action_resume),
                createCommandPendingIntent(COMMAND_RESUME))
        } else if (!isPaused) {
            NotificationCompat.Action(null, getString(R.string.notification_action_pause),
                createCommandPendingIntent(COMMAND_PAUSE))
        } else null

        val stopAction = NotificationCompat.Action(null, getString(R.string.notification_action_stop),
            createCommandPendingIntent(COMMAND_STOP))

        val contentText = when {
            isThermal && isPaused -> getString(R.string.notification_text_thermal_critical)
            isThermal -> getString(R.string.notification_text_thermal)
            isPaused -> getString(R.string.notification_text_paused)
            else -> getString(R.string.notification_text_active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true).setSilent(true)
            .apply { pauseResumeAction?.let { addAction(it) } }
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification(isPaused: Boolean, isThermal: Boolean = false) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(isPaused, isThermal))
        }
    }

    private fun createCommandPendingIntent(command: Int): PendingIntent {
        val intent = Intent(this, CameraService::class.java).apply {
            action = when (command) {
                COMMAND_PAUSE -> ACTION_PAUSE
                COMMAND_RESUME -> ACTION_RESUME
                COMMAND_STOP -> ACTION_STOP_AND_DISABLE
                else -> ACTION_START
            }
        }
        return PendingIntent.getService(this, command, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ------------------- Watchdog -------------------

    private fun startFrameWatchdog() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = serviceScope.launchGuarded("frame watchdog", restart = true) {
            while (isActive) {
                delay(WATCHDOG_PERIOD_MS)
                val s = _state.value
                if (!s.isRunning || s.isPaused || userPaused || thermalPaused) continue

                // Fix A-1b: a dead MediaPipe tracker is invisible to the stall
                // detector below, because frames keep reaching the analyzer (and keep
                // lastProcessedFrameMs fresh) even when there is no landmarker to hand
                // them to. On devices where HandLandmarker creation fails once - low
                // RAM, another camera user, a model file that could not be extracted
                // after an OTA - that used to mean "camera on, battery draining,
                // nothing works", forever. The watchdog now notices the missing
                // tracker and rebuilds the pipeline, with a widening backoff so a hard
                // failure cannot spin the CPU.
                if (!handTracker.isInitialized()) {
                    deadTrackerTicks++
                    if (deadTrackerTicks >= nextTrackerRetryTick) {
                        nextTrackerRetryTick = deadTrackerTicks + TRACKER_RETRY_TICKS
                        Timber.w(
                            "Hand tracker is not running (tick %d) - rebuilding the pipeline",
                            deadTrackerTicks,
                        )
                        restartCamera()
                    }
                    continue
                }
                if (deadTrackerTicks != 0) {
                    Timber.i("Hand tracker recovered after %d watchdog tick(s)", deadTrackerTicks)
                    deadTrackerTicks = 0
                    nextTrackerRetryTick = TRACKER_RETRY_TICKS
                }
                val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
                if (km?.isKeyguardLocked == true) continue
                val elapsed = SystemClock.elapsedRealtime() - lastProcessedFrameMs
                if (lastProcessedFrameMs > 0L && elapsed > 5000L) {
                    Timber.w("Frame stall %dms — restarting camera", elapsed)
                    restartCamera()
                }
            }
        }
    }

    private suspend fun restartCamera() {
        runCatching { withContext(Dispatchers.Main.immediate) { cameraProvider?.unbindAll() } }
        imageAnalysis = null
        lastProcessedFrameMs = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) {
            runCatching { handTracker.close(); handTracker.initialize() }
            if (eyeTrackingEnabled) runCatching { faceTracker.close(); faceTracker.initialize() }
        }
        restartJob = serviceScope.launch {
            try {
                val executor = analysisExecutor ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    val provider = cameraProvider ?: withContext(Dispatchers.Default) {
                        ProcessCameraProvider.getInstance(this@CameraService).get()
                    }
                    cameraProvider = provider
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { it.setAnalyzer(executor) { ip -> processImageFrame(ip) } }
                    imageAnalysis = analysis
                    provider.unbindAll()
                    provider.bindToLifecycle(this@CameraService, cameraSelector, analysis)
                }
                Timber.i("Camera restarted")
            } catch (e: Exception) { Timber.e(e, "Restart failed") }
        }
    }

    // ------------------- Thermal -------------------

    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring()
        // Fix B-2: guarded like the rest of the pipeline. applyThrottling touches
        // the camera use-case (resolution/fps), which can throw on some OEM HALs
        // while the camera is mid-reconfiguration; losing thermal throttling (or
        // the process) is worse than one skipped update, so retry.
        thermalMonitoringJob = serviceScope.launchGuarded("thermal", restart = true) {
            thermalMonitor.thermalStatus.collectGuarded("thermal") { applyThermalThrottling(it) }
        }
    }

    private fun stopThermalMonitoring() {
        thermalMonitoringJob?.cancel(); thermalMonitoringJob = null
        thermalMonitor.stopMonitoring(resetStatus = false) // fix #43
    }

    private fun applyThermalThrottling(status: com.aircontrol.tracking.ThermalStatus) {
        when (status) {
            com.aircontrol.tracking.ThermalStatus.NONE -> {
                if (thermalPaused) {
                    thermalPaused = false
                    if (!userPaused) serviceScope.launch { resumeTrackingLocked() }
                    postRecoveryFps = (configuredFps / 2).coerceAtLeast(5)
                    adaptiveFpsController.updateConfiguredFps(postRecoveryFps)
                    thermalRecoveryJob?.cancel()
                    thermalRecoveryJob = serviceScope.launch {
                        delay(30_000); adaptiveFpsController.updateConfiguredFps(configuredFps)
                        postRecoveryFps = 0
                    }
                } else if (postRecoveryFps > 0 || isThermalThrottled()) {
                    thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
                    postRecoveryFps = 0
                    adaptiveFpsController.updateConfiguredFps(configuredFps)
                }
            }
            com.aircontrol.tracking.ThermalStatus.LIGHT -> {
                if (thermalPaused) {
                    thermalPaused = false
                    if (!userPaused) serviceScope.launch { resumeTrackingLocked() }
                    postRecoveryFps = (configuredFps / 2).coerceAtLeast(5)
                    adaptiveFpsController.updateConfiguredFps(postRecoveryFps)
                    thermalRecoveryJob?.cancel()
                    thermalRecoveryJob = serviceScope.launch {
                        delay(30_000); adaptiveFpsController.updateConfiguredFps(configuredFps); postRecoveryFps = 0
                    }
                } else if (postRecoveryFps <= 0 && !isSevereThrottled()) {
                    val throttledFps = (configuredFps * 2 / 3).coerceIn(8, 20)
                    adaptiveFpsController.updateConfiguredFps(throttledFps)
                }
            }
            com.aircontrol.tracking.ThermalStatus.MODERATE -> {
                if (thermalPaused) return
                val throttledFps = (configuredFps / 2).coerceIn(5, 15)
                thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
                postRecoveryFps = 0
                adaptiveFpsController.updateConfiguredFps(throttledFps)
                updateNotification(isPaused = false)
            }
            com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                if (thermalPaused) {
                    thermalPaused = false
                    if (!userPaused) serviceScope.launch { resumeTrackingLocked() }
                }
                thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
                postRecoveryFps = 0
                adaptiveFpsController.updateConfiguredFps(8)
                updateNotification(isPaused = false, isThermal = true)
            }
            com.aircontrol.tracking.ThermalStatus.CRITICAL -> {
                thermalPaused = true
                thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
                postRecoveryFps = 0
                if (!_state.value.isPaused) {
                    serviceScope.launch { pauseTrackingLocked() }
                    updateNotification(isPaused = true, isThermal = true)
                }
            }
        }
    }

    private fun isSevereThrottled(): Boolean =
        !thermalPaused && adaptiveFpsController.currentFps.value <= 8 &&
            adaptiveFpsController.currentFps.value < configuredFps

    private fun isThermalThrottled(): Boolean =
        !thermalPaused && adaptiveFpsController.currentFps.value < configuredFps
}
