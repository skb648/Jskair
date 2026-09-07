package com.aircontrol.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import com.aircontrol.runtime.PerfTelemetry
import com.aircontrol.runtime.ResourceGovernor
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
        /** Analysis resolution; small on purpose, it is fed to a landmark model. */
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        const val CHANNEL_ID = "aircontrol_tracking"

        /** How often the watchdog checks the pipeline (ms). */
        private const val WATCHDOG_PERIOD_MS = 5_000L

        /** Fix A9: analysis FPS cap while eye tracking is active (dual models). */
        private const val EYE_MODE_FPS_CAP = 20

        /** After a tracker is found missing, retry every N ticks. */
        private const val TRACKER_RETRY_TICKS = 3

        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aircontrol.action.START_TRACKING"
        const val ACTION_STOP = "com.aircontrol.action.STOP_TRACKING"
        const val ACTION_PAUSE = "com.aircontrol.action.PAUSE_TRACKING"
        // Fix (audit #21): the screen-off pause must be distinguishable from the
        // notification's Pause button. A user pause is sticky (the watchdog may
        // never revive it); a system pause may be auto-recovered on wake.
        const val ACTION_SYSTEM_PAUSE = "com.aircontrol.action.SYSTEM_PAUSE_TRACKING"
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

        // Fix (audit #21): lets the accessibility service check "did the USER
        // pause this?" before auto-resuming after an unlock.
        private val _userPaused = MutableStateFlow(false)
        val isUserPaused: StateFlow<Boolean> = _userPaused.asStateFlow()

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
    /**
     * False whenever the camera device is released - while the screen is off or while
     * we are thermally paused. The session stays alive (same foreground service, same
     * trackers), only the sensor is given back, which is what turns the system's
     * "camera in use" indicator off and stops the HAL powering the sensor in a pocket.
     */
    @Volatile private var cameraBound = false

    @Volatile private var userPaused = false
    // Fix (audit #21): pause raised by the system (screen off), revivable.
    @Volatile private var systemPaused = false
    @Volatile private var thermalPaused = false
    @Volatile private var lastFrameTimestampMs: Long = 0L
    @Volatile private var lastProcessedFrameMs: Long = 0L
    @Volatile private var configuredFps = 24 // match UserPreferences default (fix #55)
    @Volatile private var eyeTrackingEnabled = false

    private var frameWatchdogJob: Job? = null

    /** Watchdog bookkeeping for a missing/uninitialized hand tracker (Fix A-1b). */
    private var deadTrackerTicks: Int = 0
    private var nextTrackerRetryTick: Int = TRACKER_RETRY_TICKS

    // Fix (audit #3): independent counters for the eye-only rebuild loop.
    private var eyeDeadTicks: Int = 0
    private var nextEyeRetryTick: Int = TRACKER_RETRY_TICKS
    private val pipelineJobs = mutableListOf<Job>()
    private val jobsLock = Any()

    /** Guards face-tracker init/close against a fast off→on settings toggle (Fix D7). */
    private val faceTrackerLock = Any()

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

    // Perf audit P5: hysteresis/debounce over raw thermal samples — FPS tiers
    // only change on a governor-confirmed transition.
    private val thermalGovernor = com.aircontrol.tracking.ThermalGovernor()

    // Perf audit P9: OS power-save mode caps the analysis FPS. Battery LEVEL
    // is deliberately not an input (see ResourceGovernor).
    private val resourceGovernor = ResourceGovernor()
    private var powerSaveReceiver: BroadcastReceiver? = null

    // Perf audit P6: set once if RGBA_8888 analysis output could not bind on
    // this device; later binds then use the YUV conversion path.
    @Volatile private var rgbaOutputFailed = false

    // Perf audit P9: the mode-configured FPS before the power-save cap.
    @Volatile private var baseConfiguredFps = 24

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
                        // The ONLY caller allowed to close the shared trackers:
                        // an explicit gesture disable means "really stop".
                        stopTrackingLocked(closeTrackers = true)
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
                    ACTION_PAUSE -> pauseTrackingLocked(userInitiated = true)
                    ACTION_SYSTEM_PAUSE -> pauseTrackingLocked(userInitiated = false)
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
        // Perf audit P2: onDestroy used to re-run the full stopTrackingLocked()
        // teardown here. After an ACTION_STOP that had already run it, this
        // re-ran the job cancels and tracker closes a second time — one of the
        // triple "HandTracker closed" lines in the 2026-09-07 logcat. The stop
        // path already did the work; only per-instance resources are released
        // here. Trackers survive by design (P2) and are reclaimed under
        // memory pressure (AirControlApp.onTrimMemory) or explicit disable.
        PerfTelemetry.recordCameraLifecycle("service-destroyed", SystemClock.elapsedRealtime())
        runCatching { unregisterPowerSaveReceiver() }
            .onFailure { Timber.e(it, "Power-save receiver unregister on destroy failed") }
        serviceScope.launch(Dispatchers.IO) {
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
        // Service is starting; report a live session only after CameraX binds.
        publishState(ServiceState(isRunning = false, isPaused = false))

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
                    // Fix A9: in eye mode two landmarkers run per frame on the CPU.
                    // Capping the analysis rate at 20 fps (only in eye mode) cuts
                    // the combined CPU load ~20–35% while the One Euro-smoothed
                    // gaze cursor stays visually smooth; battery saver still wins.
                    // Perf audit P9: this is the mode BASE rate; applyConfiguredFps
                    // layers the OS power-save cap on top.
                    baseConfiguredFps = when {
                        prefs.batterySaver -> minOf(15, prefs.analysisFps)
                        prefs.eyeTrackingEnabled -> minOf(EYE_MODE_FPS_CAP, prefs.analysisFps)
                        else -> prefs.analysisFps
                    }
                    applyConfiguredFps()
                    if (eyeTrackingEnabled != prefs.eyeTrackingEnabled) {
                        eyeTrackingEnabled = prefs.eyeTrackingEnabled
                        if (eyeTrackingEnabled && !faceTracker.isInitialized()) {
                            // Initializing the face landmarker can throw on devices where
                            // MediaPipe cannot allocate a GPU delegate. A throw here used to
                            // kill the collector (and the process). Now: log, keep hands
                            // working, and retry the next time the toggle changes.
                            runCatching {
                                withContext(Dispatchers.Default) {
                                    synchronized(faceTrackerLock) { faceTracker.initialize() }
                                }
                            }
                                .onFailure { Timber.e(it, "Face tracker init failed; eye mode stays off") }
                        } else if (!eyeTrackingEnabled && faceTracker.isInitialized()) {
                            // Fix D7: the face landmarker used to stay loaded (tens of MB of
                            // native model memory) after eye mode was switched off. Release it;
                            // the toggle-on path above re-initializes on demand.
                            serviceScope.launch(Dispatchers.Default) {
                                synchronized(faceTrackerLock) {
                                    runCatching { faceTracker.close() }
                                        .onFailure { Timber.e(it, "Face tracker close on disable failed") }
                                }
                            }
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
            // Fix A4: face presence also keeps the analysis at full FPS. In eye
            // mode the user's hands are usually down — with only the hand signal
            // wired, the controller dropped to 5 fps scan mode after 5 seconds
            // and the gaze cursor turned into a slideshow exactly in the mode
            // where the face is the active input.
            pipelineJobs.add(serviceScope.launchGuarded("face fps", restart = true) {
                faceTracker.gazeObservations.collectGuarded("face fps") { obs ->
                    if (obs.faceDetected) adaptiveFpsController.onHandDetected(obs.timestampMs)
                    else adaptiveFpsController.onHandLost(obs.timestampMs)
                }
            })
        }

        try {
            if (!bindAnalysisUseCase()) {
                // A bind failure here is a device/permission problem, not a transient one.
                // Tear the session down and let the accessibility-side retry bring it back:
                // a half-started service that reports "running" is what used to leave people
                // with gestures that never work until they reopened the app.
                Timber.e("Could not open the front camera; tearing the session down for a retry")
                stopTrackingLocked()
                return
            }
            // CameraX bind succeeded; now report the session as live.
            publishState(ServiceState(isRunning = true, isPaused = false))
            userPaused = false
            systemPaused = false
            _userPaused.value = false
            thermalPaused = false
            lastProcessedFrameMs = SystemClock.elapsedRealtime()
            Timber.i("Camera started")
            PerfTelemetry.recordCameraLifecycle("started", SystemClock.elapsedRealtime())
            startFrameWatchdog()
            startThermalMonitoring()
            registerPowerSaveReceiver()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start camera")
            stopTrackingLocked()
        }
    }

    /**
     * Binds (or re-binds) the front camera analysis use case to this service's lifecycle.
     *
     * One place for start, restart-after-stall and resume-from-pause, so a pause that released
     * the camera comes back exactly the way a cold start does - including the analyzer, which
     * is what a resumed session was missing when the binding code lived only in the start path.
     */
    private suspend fun bindAnalysisUseCase(): Boolean {
        val executor = analysisExecutor
        if (executor == null) {
            Timber.e("Analysis executor is gone; cannot bind the camera")
            return false
        }
        return try {
            val provider = cameraProvider
                ?: withContext(Dispatchers.Default) {
                    ProcessCameraProvider.getInstance(this@CameraService).get()
                }
            cameraProvider = provider
            withContext(Dispatchers.Main.immediate) {
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                // Perf audit P6: RGBA_8888 analysis output lets
                // ImageProxy.toBitmap() wrap the existing frame buffer instead
                // of allocating a fresh 640×480 ARGB bitmap (~1.2 MB large
                // object) per analyzed frame — the young-GC LOS churn proven
                // in the 2026-09-07 logcat. If this device cannot bind the
                // RGBA stream, fall back to YUV once and remember it.
                val analysis = buildAnalysisUseCase(executor, rgbaOutput = !rgbaOutputFailed)
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this@CameraService, cameraSelector, analysis)
                    imageAnalysis = analysis
                } catch (rgbaError: Exception) {
                    if (rgbaOutputFailed) throw rgbaError
                    Timber.w(rgbaError, "RGBA analysis output failed to bind; falling back to YUV")
                    rgbaOutputFailed = true
                    val fallback = buildAnalysisUseCase(executor, rgbaOutput = false)
                    provider.unbindAll()
                    provider.bindToLifecycle(this@CameraService, cameraSelector, fallback)
                    imageAnalysis = fallback
                }
                cameraBound = true
            }
            true
        } catch (e: Exception) {
            cameraBound = false
            Timber.e(e, "Failed to bind the analysis use case")
            false
        }
    }

    private fun buildAnalysisUseCase(
        executor: java.util.concurrent.ExecutorService,
        rgbaOutput: Boolean,
    ): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            ).build()
        return ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                if (rgbaOutput) setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            }
            .build()
            .also { img ->
                img.setAnalyzer(executor) { imageProxy -> processImageFrame(imageProxy) }
            }
    }

    private suspend fun stopTrackingLocked(closeTrackers: Boolean = false) {
        // Perf audit P2: idempotent. Two owners used to send ACTION_STOP for
        // one user action; the second re-ran the whole teardown (and re-closed
        // the shared trackers) for an already-dead session. A duplicate stop
        // intent that spawned a fresh service instance still stops that
        // instance — it just skips the (already done) pipeline teardown.
        if (!closeTrackers && !_state.value.isRunning && !cameraBound) {
            Timber.d("Stop requested for an already-stopped session; nothing to do")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return
        }
        restartJob?.cancel(); restartJob = null
        frameWatchdogJob?.cancel(); frameWatchdogJob = null
        thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
        synchronized(jobsLock) {
            pipelineJobs.forEach { it.cancel() }
            pipelineJobs.clear()
        }
        stopThermalMonitoring()
        unregisterPowerSaveReceiver()
        runCatching { withContext(Dispatchers.Main.immediate) { cameraProvider?.unbindAll() } }
            .onFailure { Timber.e(it, "unbindAll failed") }
        cameraProvider = null
        imageAnalysis = null
        cameraBound = false
        if (closeTrackers) {
            // Perf audit P2: only an explicit gesture disable closes the
            // @Singleton trackers. An ordinary session stop leaves the loaded
            // models in place so the next start skips the asset re-read and
            // native model creation entirely.
            withContext(Dispatchers.Default) {
                runCatching { handTracker.close() }
                runCatching { faceTracker.close() }
            }
        }
        adaptiveFpsController.reset()
        publishState(ServiceState(isRunning = false, isPaused = false))
        userPaused = false
        systemPaused = false
        _userPaused.value = false
        thermalPaused = false
        postRecoveryFps = 0
        PerfTelemetry.recordCameraLifecycle("stopped", SystemClock.elapsedRealtime())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        Timber.i("Tracking stopped")
    }

    private suspend fun pauseTrackingLocked(userInitiated: Boolean = true) {
        if (!_state.value.isRunning) return
        if (userInitiated) userPaused = true else systemPaused = true
        _userPaused.value = userInitiated
        publishState(_state.value.copy(isPaused = true))
        withContext(Dispatchers.Main.immediate) {
            imageAnalysis?.clearAnalyzer()
            // Hand the sensor back while paused. Keeping the capture session open with the
            // analyzer detached still lit the privacy indicator and kept the camera HAL
            // powered (visible as heat and a few percent an hour overnight in a pocket), and
            // on Android 12+ it is the indicator that makes a camera app look like a spy app.
            runCatching { cameraProvider?.unbindAll() }
                .onFailure { Timber.e(it, "unbindAll on pause failed") }
            cameraBound = false
        }
        updateNotification(isPaused = true)
        Timber.i(if (userInitiated) "Tracking paused by user (sticky)" else "Tracking paused by system (screen off)")
        PerfTelemetry.recordCameraLifecycle(
            if (userInitiated) "paused-user" else "paused-system",
            SystemClock.elapsedRealtime(),
        )
    }

    private suspend fun resumeTrackingLocked() {
        if (!_state.value.isRunning) { startTrackingLocked(); return }
        userPaused = false
        systemPaused = false
        _userPaused.value = false
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
        if (cameraBound && imageAnalysis != null) {
            val executor = analysisExecutor ?: return
            withContext(Dispatchers.Main.immediate) {
                imageAnalysis?.setAnalyzer(executor) { imageProxy -> processImageFrame(imageProxy) }
            }
        } else if (!bindAnalysisUseCase()) {
            // The camera refused to come back (another app took it, or the provider is in a
            // bad state after the screen was off for a long time). Tear the session down so
            // the retry logic starts it cleanly instead of leaving a paused service that
            // reports itself healthy forever.
            Timber.e("Camera did not come back after the pause; restarting the session")
            stopTrackingLocked()
            return
        }
        updateNotification(isPaused = false)
        Timber.i("Tracking resumed")
        PerfTelemetry.recordCameraLifecycle("resumed", SystemClock.elapsedRealtime())
    }

    // ------------------- Frame processing -------------------

    private fun processImageFrame(imageProxy: ImageProxy) {
        val startMs = SystemClock.elapsedRealtime()
        try {
            if (_state.value.isPaused) return
            val intervalMs = adaptiveFpsController.analysisIntervalMs
            if (startMs - lastFrameTimestampMs < intervalMs) {
                // Perf audit P18: throttled frames are counted, not processed.
                PerfTelemetry.recordFrameDroppedThrottle()
                return
            }
            lastFrameTimestampMs = startMs
            lastProcessedFrameMs = startMs

            val mpImage = imageProxyToMPImage(imageProxy)
            if (mpImage != null) {
                try {
                    if (!handTracker.isInitialized()) PerfTelemetry.recordFrameDroppedNoTracker()
                    handTracker.processFrame(mpImage, startMs)
                    if (eyeTrackingEnabled && faceTracker.isInitialized()) {
                        faceTracker.processFrame(mpImage, startMs)
                    }
                } finally {
                    // MPImage owns reference-counted native storage. Explicit close
                    // prevents native-memory growth during continuous tracking.
                    mpImage.close()
                }
                PerfTelemetry.recordFrameProcessed(startMs)
            } else {
                PerfTelemetry.recordFrameDroppedNoTracker()
            }
        } catch (e: Exception) {
            Timber.e(e, "processImageFrame error")
        } finally {
            imageProxy.close()
            PerfTelemetry.recordAnalyzerDuration(SystemClock.elapsedRealtime() - startMs)
        }
    }

    private fun imageProxyToMPImage(imageProxy: ImageProxy): MPImage? {
        return try {
            // Perf audit P6: with RGBA_8888 output (the default bind path)
            // toBitmap() WRAPS the frame's existing buffer — there is nothing
            // to allocate and, crucially, nothing to recycle: recycling a
            // wrapper would free storage CameraX still owns. On the YUV
            // fallback path toBitmap() allocates, but on API 26+ bitmap
            // pixels are GC-managed native allocations, so skipping recycle()
            // costs only promptness, not memory. One rule for both paths:
            // never recycle the source bitmap here.
            val sourceBitmap = imageProxy.toBitmap()
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
        }
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
                // Perf audit P18: one rate-limited summary per ≥30 s window,
                // emitted from this slow loop (never from the frame path) and
                // only while PerfTelemetry.enableLogging (debug builds).
                PerfTelemetry.maybeLogSummary(SystemClock.elapsedRealtime())
                val s = _state.value
                if (!s.isRunning || thermalPaused) continue
                if (s.isPaused || userPaused || systemPaused) {
                    // A pause releases the camera and normally ends via ACTION_RESUME
                    // (notification button, or the screen-on receiver for system pauses).
                    // If that broadcast is ever missed - doze, an OEM that reorders or
                    // swallows them - the session would sit paused with no camera
                    // forever: gestures dead and nothing left to restart it.
                    //
                    // Fix (audit #21): a USER pause is STICKY — the watchdog must
                    // never revive it. "I pressed Pause" has to mean "stay paused",
                    // or the camera coming back on by itself destroys trust. Only a
                    // system pause (screen off with a missed wake broadcast) may be
                    // recovered here, and only while plainly awake.
                    if (systemPaused && !userPaused && !cameraBound) revivePausedSessionIfNeeded()
                    continue
                }

                // Fix A-1b: a dead MediaPipe tracker is invisible to the stall
                // detector below, because frames keep reaching the analyzer (and keep
                // lastProcessedFrameMs fresh) even when there is no landmarker to hand
                // them to. On devices where HandLandmarker creation fails once - low
                // RAM, another camera user, a model file that could not be extracted
                // after an OTA - that used to mean "camera on, battery draining,
                // nothing works", forever. The watchdog now notices the missing
                // tracker and rebuilds the pipeline, with a widening backoff so a hard
                // failure cannot spin the CPU.
                if (!handTracker.isInitialized() &&
                    !(eyeTrackingEnabled && faceTracker.isInitialized())
                ) {
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
                // Fix (audit #3): eye-only death must not be invisible. The check
                // above restarts the CAMERA only when BOTH modalities are dead —
                // a live hand tracker with a dead face tracker used to leave
                // "Eye Tracking: ON" over a silent black box (hand cursor fine,
                // eye cursor frozen, no error anywhere). Rebuild the face tracker
                // on its own backoff; the camera and hand pipeline are untouched.
                if (eyeTrackingEnabled && !faceTracker.isInitialized()) {
                    eyeDeadTicks++
                    if (eyeDeadTicks >= nextEyeRetryTick) {
                        nextEyeRetryTick = eyeDeadTicks + TRACKER_RETRY_TICKS
                        Timber.w("Face tracker dead while eye tracking enabled (tick %d) — rebuilding it", eyeDeadTicks)
                        serviceScope.launch {
                            withContext(Dispatchers.Default) {
                                runCatching { faceTracker.close() }
                                runCatching { faceTracker.initialize() }
                            }
                        }
                    }
                } else {
                    eyeDeadTicks = 0
                    nextEyeRetryTick = TRACKER_RETRY_TICKS
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

    /** Resume a session that is paused with the camera released, but only when it is safe. */
    private suspend fun revivePausedSessionIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val awake = pm?.isInteractive == true && km?.isKeyguardLocked == false
        if (!awake) return
        Timber.w("Paused with no camera while the screen is awake; resuming the session")
        resumeTrackingLocked()
    }

    private suspend fun restartCamera() {
        PerfTelemetry.recordWatchdogAction("restart-camera", SystemClock.elapsedRealtime())
        runCatching { withContext(Dispatchers.Main.immediate) { cameraProvider?.unbindAll() } }
            .onFailure { Timber.e(it, "unbindAll on restart failed") }
        imageAnalysis = null
        cameraBound = false
        lastProcessedFrameMs = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) {
            runCatching { handTracker.close(); handTracker.initialize() }
            if (eyeTrackingEnabled) runCatching { faceTracker.close(); faceTracker.initialize() }
        }
        restartJob = serviceScope.launch {
            // Fix: a restart that failed silently used to leave a "running" session with no
            // analyzer attached - exactly the frozen-cursor state it was meant to clear. The
            // shared binder is used so a restart cannot drift from a cold start.
            //
            // Fix (audit #22): NEVER bind the camera when no tracker survived the
            // re-init. Binding anyway produced the zombie state — camera service
            // "running", privacy indicator on, but no pipeline to feed — which
            // users read as "everything says ON but nothing works". Skip the bind
            // and let the watchdog retry with its widening backoff instead.
            val handReady = handTracker.isInitialized()
            val eyeReady = !eyeTrackingEnabled || faceTracker.isInitialized()
            if (!handReady && !eyeReady) {
                Timber.e(
                    "Camera restart skipped — no working tracker (hand=%b, eye=%b); watchdog will retry",
                    handReady,
                    eyeReady,
                )
                return@launch
            }
            if (bindAnalysisUseCase()) Timber.i("Camera restarted")
            else Timber.e("Camera restart failed; the watchdog keeps trying")
        }
    }

    // ------------------- Thermal -------------------

    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring()
        // Fix B-2: guarded like the rest of the pipeline. applyThrottling touches
        // the camera use-case (resolution/fps), which can throw on some OEM HALs
        // while the camera is mid-reconfiguration; losing thermal throttling (or
        // the process) is worse than one skipped update, so retry.
        //
        // Perf audit P5: samples now flow through the ThermalGovernor — the
        // raw 5 s poll used to drive applyThermalThrottling directly, so a
        // single threshold-crossing sample flipped the FPS tier and cancelled
        // the 30 s recovery ramp (30↔10 oscillation). Throttling is applied
        // only on a governor-confirmed transition.
        val governor = thermalGovernor
        thermalMonitoringJob = serviceScope.launchGuarded("thermal", restart = true) {
            thermalMonitor.thermalSamples.collectGuarded("thermal") { raw ->
                val previous = governor.current()
                val effective = governor.onSample(raw, System.currentTimeMillis())
                if (effective != null) {
                    Timber.i("Effective thermal status applied: %s → %s", previous, effective)
                    PerfTelemetry.recordThermalTransition(
                        from = previous.name,
                        to = effective.name,
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                    applyThermalThrottling(effective)
                }
            }
        }
    }

    private fun stopThermalMonitoring() {
        thermalMonitoringJob?.cancel(); thermalMonitoringJob = null
        thermalMonitor.stopMonitoring(resetStatus = false) // fix #43
        // Perf audit P5: a stopped monitor must not leave pending confirmation
        // counts behind; the next session debounces from a clean NONE.
        thermalGovernor.reset()
    }

    // ------------------- Power save (perf audit P9) -------------------

    /**
     * OS power-save mode caps the analysis FPS while active. Registered per
     * session (unregistered on stop and destroy) so a stopped service never
     * keeps a receiver alive. Battery LEVEL and thermal status are handled
     * elsewhere by design — see ResourceGovernor.
     */
    private fun registerPowerSaveReceiver() {
        if (powerSaveReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                val active = pm?.isPowerSaveMode == true
                PerfTelemetry.recordPowerSaveChanged(active, SystemClock.elapsedRealtime())
                Timber.i("OS power-save mode %s — reapplying the FPS cap", if (active) "ON" else "OFF")
                applyConfiguredFps()
            }
        }
        powerSaveReceiver = receiver
        runCatching {
            registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }.onFailure { Timber.e(it, "Could not register the power-save receiver") }
    }

    private fun unregisterPowerSaveReceiver() {
        val receiver = powerSaveReceiver ?: return
        powerSaveReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    /** Effective configured FPS = mode base rate × OS power-save cap. */
    private fun applyConfiguredFps() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        val capped = minOf(
            baseConfiguredFps,
            resourceGovernor.fpsCapFor(isPowerSaveMode = pm?.isPowerSaveMode == true),
        )
        configuredFps = capped
        adaptiveFpsController.updateConfiguredFps(capped)
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
                    // Fix (audit #14): restore the normal notification text once
                    // thermal pressure is gone.
                    updateNotification(isPaused = false)
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
                    // Fix (audit #14): the user must be TOLD why the cursor
                    // suddenly feels slower, or thermal throttling reads as
                    // "tracking quality is random".
                    updateNotification(isPaused = false, isThermal = true)
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
