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
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
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
import com.aircontrol.util.CrashGuard
import com.aircontrol.tracking.AdaptiveFpsController
import com.aircontrol.tracking.HandTracker
import com.aircontrol.tracking.SlotPool
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
        /**
         * P0-2: how many frames may be in flight at once. Two per channel would be the theoretical
         * minimum; three keeps the camera thread unblocked while a result is being delivered.
         */
        private const val FRAME_BUFFER_SLOTS = 3

        /** Analysis resolution; small on purpose, it is fed to a landmark model. */
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        const val CHANNEL_ID = "aircontrol_tracking"

        /** How often the watchdog checks the pipeline (ms). */
        private const val WATCHDOG_PERIOD_MS = 5_000L

        /** Fluid 30 FPS analysis rate while eye tracking is active for smooth, responsive gaze. */
        private const val EYE_MODE_FPS_CAP = 30

        /** After a tracker is found missing, retry every N ticks. */
        private const val TRACKER_RETRY_TICKS = 3
        private const val CAMERA_RETRY_BASE_MS = 2_000L

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
            /** True only after CameraX is bound and the analyzer is accepting frames. */
            val isRunning: Boolean = false,
            val isPaused: Boolean = false,
            val actualState: TrackingState = TrackingState.STOPPED,
            val desiredTrackingEnabled: Boolean = false,
            val generation: Long = 0L,
            val reason: String? = null,
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
    /** CameraX callbacks can race a stop/rebind; this gate makes old analyzer callbacks no-ops. */
    @Volatile private var acceptingFrames = false
    @Volatile private var bindInProgress = false
    @Volatile private var desiredTrackingEnabled = false
    @Volatile private var lifecycleGeneration = 0L
    private var cameraRetryJob: Job? = null
    private val nextFrameId = java.util.concurrent.atomic.AtomicLong(0L)

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

    /**
     * P0-2: the pixel buffers the analysers draw into — one reusable bitmap per *in-flight* frame,
     * leased until every channel that was handed the frame reports back, never recycled at the end
     * of the camera callback while MediaPipe is still reading the same pixels on its own thread.
     * Exhausting the pool drops a frame and counts it; it never blocks the camera thread and never
     * queues work behind inference.
     */
    private val frameBitmaps = SlotPool<Bitmap>(
        capacity = FRAME_BUFFER_SLOTS,
        create = {
            Bitmap.createBitmap(
                conversionTargetWidth.coerceAtLeast(1),
                conversionTargetHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        },
        destroy = { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
    )

    @Volatile
    private var conversionTargetWidth: Int = 0

    @Volatile
    private var conversionTargetHeight: Int = 0

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

    private var orientationEventListener: OrientationEventListener? = null
    private var cameraManager: CameraManager? = null
    @Volatile private var isCameraConflictPaused = false

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            // CameraManager reports the camera as unavailable to *other clients* when
            // this service owns it. Treating that observation as a pause request was
            // the first bad transition in the reported loop: our own unbind made the
            // camera available again, which immediately requested a resume and a new
            // bind. Availability is an input to the lifecycle owner, never a direct
            // start/stop command.
            if (bindInProgress || cameraBound) {
                Timber.d("Camera %s unavailable while owned by this session; ignoring availability edge", cameraId)
                return
            }
            if (desiredTrackingEnabled && _state.value.actualState in setOf(
                    TrackingState.STARTING,
                    TrackingState.WAITING_FOR_CAMERA,
                    TrackingState.RUNNING,
                    TrackingState.CAMERA_LOST,
                )
            ) {
                Timber.w("Camera %s temporarily unavailable; waiting without tearing down the service", cameraId)
                isCameraConflictPaused = true
                serviceScope.launch {
                    lifecycleMutex.withLock {
                        if (!desiredTrackingEnabled || lifecycleGeneration != _state.value.generation) return@withLock
                        publishState(_state.value.copy(
                            isRunning = false,
                            isPaused = false,
                            actualState = if (_state.value.actualState == TrackingState.RUNNING) {
                                TrackingState.CAMERA_LOST
                            } else TrackingState.WAITING_FOR_CAMERA,
                            reason = "camera-unavailable",
                        ))
                        PerfTelemetry.recordCameraUnavailable(SystemClock.elapsedRealtime())
                    }
                }
            }
        }

        override fun onCameraAvailable(cameraId: String) {
            if (!desiredTrackingEnabled || !isCameraConflictPaused) return
            isCameraConflictPaused = false
            serviceScope.launch {
                lifecycleMutex.withLock {
                    if (!desiredTrackingEnabled) return@withLock
                    Timber.i("Camera %s available; lifecycle owner will attempt recovery", cameraId)
                    publishState(_state.value.copy(
                        actualState = TrackingState.STARTING,
                        generation = lifecycleGeneration,
                        reason = null,
                    ))
                    val generation = lifecycleGeneration
                    if (attemptCameraBindLocked(generation)) {
                        isCameraConflictPaused = false
                        acceptingFrames = true
                        publishState(_state.value.copy(
                            isRunning = true,
                            isPaused = false,
                            actualState = TrackingState.RUNNING,
                            generation = generation,
                            desiredTrackingEnabled = true,
                            reason = null,
                        ))
                    } else {
                        publishState(_state.value.copy(
                            actualState = TrackingState.WAITING_FOR_CAMERA,
                            reason = "camera-unavailable",
                        ))
                        scheduleCameraRetryLocked(generation)
                    }
                }
            }
        }
    }

    private fun startOrientationListener() {
        if (orientationEventListener != null) return
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageAnalysis?.targetRotation = rotation
            }
        }.also {
            if (it.canDetectOrientation()) it.enable()
        }
    }

    private fun stopOrientationListener() {
        orientationEventListener?.disable()
        orientationEventListener = null
    }

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
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm != null) {
            cameraManager = cm
            runCatching { cm.registerAvailabilityCallback(cameraAvailabilityCallback, null) }
        }
        Timber.i("CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        serviceScope.launch {
            lifecycleMutex.withLock {
                when (intent?.action) {
                    ACTION_STOP_AND_DISABLE -> {
                        desiredTrackingEnabled = false
                        lifecycleGeneration++
                        stopTrackingLocked(clearDesired = true)
                        runCatching {
                            if (::settingsRepository.isInitialized) settingsRepository.updateGesturesEnabled(false)
                        }.onFailure { Timber.e(it, "Could not persist gesturesEnabled=false on stop") }
                        return@launch
                    }
                    ACTION_STOP -> {
                        desiredTrackingEnabled = false
                        lifecycleGeneration++
                        stopTrackingLocked(clearDesired = true)
                        return@launch
                    }
                    ACTION_PAUSE -> {
                        desiredTrackingEnabled = true
                        pauseTrackingLocked(userInitiated = true)
                    }
                    ACTION_SYSTEM_PAUSE -> {
                        desiredTrackingEnabled = true
                        pauseTrackingLocked(userInitiated = false)
                    }
                    ACTION_RESUME -> {
                        desiredTrackingEnabled = true
                        resumeTrackingLocked()
                    }
                    ACTION_START -> {
                        if (!desiredTrackingEnabled || _state.value.actualState == TrackingState.STOPPED) {
                            desiredTrackingEnabled = true
                            lifecycleGeneration++
                        }
                        startTrackingLocked()
                    }
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
        // Invalidate the generation before any platform teardown. Queued analyzer
        // callbacks and late MediaPipe results can then only close their own
        // resources; they cannot publish into the next session.
        acceptingFrames = false
        desiredTrackingEnabled = false
        lifecycleGeneration++
        publishState(ServiceState(
            isRunning = false,
            isPaused = false,
            actualState = TrackingState.STOPPING,
            desiredTrackingEnabled = false,
            generation = lifecycleGeneration,
            reason = "service-destroyed",
        ))
        synchronized(jobsLock) {
            pipelineJobs.forEach { it.cancel() }
            pipelineJobs.clear()
        }
        frameWatchdogJob?.cancel(); frameWatchdogJob = null
        cameraRetryJob?.cancel(); cameraRetryJob = null
        thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
        stopThermalMonitoring()
        runCatching { unregisterPowerSaveReceiver() }
        stopOrientationListener()
        runCatching {
            // onDestroy is on the main thread for LifecycleService, so this
            // closes the CameraX ownership edge synchronously before the service
            // reports STOPPED to a debug handoff.
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        }.onFailure { Timber.e(it, "CameraService destroy unbind failed") }
        cameraBound = false
        imageAnalysis = null
        cameraProvider = null
        PerfTelemetry.recordCameraLifecycle("service-destroyed", SystemClock.elapsedRealtime())

        val executor = analysisExecutor
        analysisExecutor = null
        serviceScope.launch(Dispatchers.IO) {
            runCatching { handTracker.close() }.onFailure { Timber.e(it, "hand tracker close on destroy failed") }
            runCatching { faceTracker.close() }.onFailure { Timber.e(it, "face tracker close on destroy failed") }
            executor?.shutdown()
            try {
                if (executor != null && !executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
            } catch (_: InterruptedException) {
                executor?.shutdownNow()
            }
            // SlotPool drains only free buffers and retires busy ones; the graph
            // closes above release any outstanding lease first.
            frameBitmaps.drain()
            cachedMatrix = null
            publishState(ServiceState())
            serviceScope.cancel()
        }
        Timber.i("CameraService destroyed")
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Timber.w("CameraService onTrimMemory (level=%d) — draining intermediate frame pool", level)
            serviceScope.launch(Dispatchers.IO) {
                frameBitmaps.drain()
            }
        }
    }

    // ------------------- start/stop/pause/resume -------------------

    private suspend fun startTrackingLocked() {
        if (!desiredTrackingEnabled) return
        val current = _state.value.actualState
        if (current == TrackingState.RUNNING || current == TrackingState.STARTING ||
            current == TrackingState.WAITING_FOR_CAMERA || current == TrackingState.CAMERA_LOST
        ) return

        val generation = lifecycleGeneration
        publishState(_state.value.copy(
            isRunning = false,
            isPaused = false,
            actualState = TrackingState.STARTING,
            desiredTrackingEnabled = true,
            generation = generation,
            reason = null,
        ))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            publishState(_state.value.copy(
                actualState = TrackingState.FAILED,
                reason = "camera-permission-missing",
            ))
            Timber.e("Camera permission missing; tracking remains requested but is not active")
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            publishState(_state.value.copy(
                actualState = TrackingState.WAITING_FOR_CAMERA,
                reason = "device-locked",
            ))
            Timber.i("Keyguard locked — waiting without starting the camera")
            return
        }
        if (!::handTracker.isInitialized || !::settingsRepository.isInitialized) {
            publishState(_state.value.copy(actualState = TrackingState.FAILED, reason = "dependencies-not-ready"))
            Timber.e("Tracking dependencies are not initialized")
            return
        }

        try {
            val notification = buildNotification(isPaused = false, isWaiting = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            publishState(_state.value.copy(actualState = TrackingState.FAILED, reason = "foreground-start-failed"))
            Timber.e(e, "startForeground failed")
            stopSelf()
            return
        }

        acceptingFrames = false
        withContext(Dispatchers.Default) { handTracker.initialize() }
        if (!handTracker.isInitialized()) {
            publishState(_state.value.copy(actualState = TrackingState.FAILED, reason = "hand-tracker-not-ready"))
            scheduleCameraRetryLocked(generation)
            updateNotification(isPaused = false, isWaiting = true)
            ensureWatchdogAndMonitoring()
            return
        }

        // These collectors are session-owned and are installed exactly once. A
        // duplicate START therefore cannot create duplicate consumers/watchdogs.
        synchronized(jobsLock) {
            if (pipelineJobs.isEmpty()) {
                installPipelineCollectors()
            }
        }

        if (!attemptCameraBindLocked(generation)) {
            publishState(_state.value.copy(
                isRunning = false,
                isPaused = false,
                actualState = TrackingState.WAITING_FOR_CAMERA,
                desiredTrackingEnabled = true,
                generation = generation,
                reason = "camera-unavailable",
            ))
            scheduleCameraRetryLocked(generation)
            updateNotification(isPaused = false, isWaiting = true)
            ensureWatchdogAndMonitoring()
            return
        }

        if (!desiredTrackingEnabled || generation != lifecycleGeneration) return
        isCameraConflictPaused = false
        acceptingFrames = true
        publishState(_state.value.copy(
            isRunning = true,
            isPaused = false,
            actualState = TrackingState.RUNNING,
            desiredTrackingEnabled = true,
            generation = generation,
            reason = null,
        ))
        userPaused = false
        systemPaused = false
        _userPaused.value = false
        thermalPaused = false
        lastProcessedFrameMs = SystemClock.elapsedRealtime()
        Timber.i("Tracking reached RUNNING (generation=%d)", generation)
        PerfTelemetry.recordCameraLifecycle("started", SystemClock.elapsedRealtime())
        ensureWatchdogAndMonitoring()
        registerPowerSaveReceiver()
    }

    /** Installs all long-lived service collectors. */
    private fun installPipelineCollectors() {
        pipelineJobs.add(serviceScope.launchGuarded("camera settings", restart = true) {
            settingsRepository.userPreferences.collectGuarded("camera settings") { prefs ->
                baseConfiguredFps = when {
                    prefs.batterySaver -> minOf(15, prefs.analysisFps)
                    prefs.eyeTrackingEnabled -> minOf(EYE_MODE_FPS_CAP, prefs.analysisFps)
                    else -> prefs.analysisFps
                }
                applyConfiguredFps()
                if (eyeTrackingEnabled != prefs.eyeTrackingEnabled) {
                    eyeTrackingEnabled = prefs.eyeTrackingEnabled
                    if (eyeTrackingEnabled && !faceTracker.isInitialized()) {
                        runCatching { withContext(Dispatchers.Default) { synchronized(faceTrackerLock) { faceTracker.initialize() } } }
                            .onFailure { Timber.e(it, "Face tracker init failed; eye mode stays off") }
                    } else if (!eyeTrackingEnabled && faceTracker.isInitialized()) {
                        serviceScope.launch(Dispatchers.Default) {
                            synchronized(faceTrackerLock) { runCatching { faceTracker.close() }.onFailure { Timber.e(it, "Face tracker close failed") } }
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
        pipelineJobs.add(serviceScope.launchGuarded("face fps", restart = true) {
            faceTracker.gazeObservations.collectGuarded("face fps") { obs ->
                if (obs.faceDetected) adaptiveFpsController.onFaceDetected(obs.timestampMs)
                else adaptiveFpsController.onFaceLost(obs.timestampMs)
            }
        })
    }

    private fun ensureWatchdogAndMonitoring() {
        if (frameWatchdogJob?.isActive != true) startFrameWatchdog()
        if (!::thermalMonitor.isInitialized || thermalMonitoringJob?.isActive != true) startThermalMonitoring()
    }

    private suspend fun attemptCameraBindLocked(generation: Long): Boolean {
        if (!desiredTrackingEnabled || generation != lifecycleGeneration) return false
        bindInProgress = true
        return try {
            val ok = bindAnalysisUseCase()
            if (ok) PerfTelemetry.recordCameraLifecycle("bound", SystemClock.elapsedRealtime())
            ok
        } finally {
            bindInProgress = false
        }
    }

    private fun scheduleCameraRetryLocked(generation: Long) {
        if (!desiredTrackingEnabled || generation != lifecycleGeneration) return
        if (cameraRetryJob?.isActive == true) return
        cameraRetryJob = serviceScope.launch {
            delay(CAMERA_RETRY_BASE_MS)
            lifecycleMutex.withLock {
                if (desiredTrackingEnabled && generation == lifecycleGeneration &&
                    _state.value.actualState in setOf(TrackingState.WAITING_FOR_CAMERA, TrackingState.CAMERA_LOST, TrackingState.STARTING)
                ) {
                    attemptCameraBindLocked(generation)
                    if (cameraBound) {
                        isCameraConflictPaused = false
                        acceptingFrames = true
                        publishState(_state.value.copy(
                            isRunning = true,
                            isPaused = false,
                            actualState = TrackingState.RUNNING,
                            desiredTrackingEnabled = true,
                            generation = generation,
                            reason = null,
                        ))
                    } else {
                        publishState(_state.value.copy(actualState = TrackingState.WAITING_FOR_CAMERA, reason = "camera-unavailable"))
                        cameraRetryJob = null
                        scheduleCameraRetryLocked(generation)
                    }
                }
            }
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
                startOrientationListener()
            }
            true
        } catch (e: Exception) {
            cameraBound = false
            acceptingFrames = false
            PerfTelemetry.recordCameraLifecycle("bind-failed", SystemClock.elapsedRealtime())
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

    private suspend fun stopTrackingLocked(clearDesired: Boolean = false) {
        val wasStopped = _state.value.actualState == TrackingState.STOPPED &&
            !cameraBound && pipelineJobs.isEmpty()
        if (wasStopped) {
            publishState(_state.value.copy(
                isRunning = false,
                isPaused = false,
                actualState = TrackingState.STOPPED,
                desiredTrackingEnabled = if (clearDesired) false else desiredTrackingEnabled,
                generation = lifecycleGeneration,
            ))
            stopForegroundCompat()
            stopSelf()
            return
        }

        acceptingFrames = false
        cameraRetryJob?.cancel(); cameraRetryJob = null
        restartJob?.cancel(); restartJob = null
        frameWatchdogJob?.cancel(); frameWatchdogJob = null
        thermalRecoveryJob?.cancel(); thermalRecoveryJob = null
        publishState(_state.value.copy(
            isRunning = false,
            isPaused = false,
            actualState = TrackingState.STOPPING,
            desiredTrackingEnabled = if (clearDesired) false else desiredTrackingEnabled,
            generation = lifecycleGeneration,
        ))

        // Stop accepting callbacks before touching CameraX. The analyzer can
        // already have a queued ImageProxy; its first line is the generation/state
        // gate and it will close that proxy without touching a bitmap.
        synchronized(jobsLock) {
            pipelineJobs.forEach { it.cancel() }
            pipelineJobs.clear()
        }
        stopThermalMonitoring()
        unregisterPowerSaveReceiver()
        stopOrientationListener()
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
            }
        }.onFailure { Timber.e(it, "camera close failed") }
        cameraProvider = null
        imageAnalysis = null
        cameraBound = false

        // A stop is a resource boundary. Close both async graphs before
        // retiring pixel leases; otherwise a debug handoff or a rapid
        // start→stop→start can leave the old callback holding a bitmap.
        withContext(Dispatchers.Default) {
            runCatching { handTracker.close() }.onFailure { Timber.e(it, "hand tracker close failed") }
            runCatching { faceTracker.close() }.onFailure { Timber.e(it, "face tracker close failed") }
        }
        // Busy buffers are retired, not recycled. Tracker.close() above releases
        // any outstanding leases; a late callback can only release a retired slot.
        frameBitmaps.drain()
        adaptiveFpsController.reset()
        desiredTrackingEnabled = if (clearDesired) false else desiredTrackingEnabled
        publishState(ServiceState(
            isRunning = false,
            isPaused = false,
            actualState = TrackingState.STOPPED,
            desiredTrackingEnabled = desiredTrackingEnabled,
            generation = lifecycleGeneration,
        ))
        userPaused = false
        systemPaused = false
        _userPaused.value = false
        thermalPaused = false
        postRecoveryFps = 0
        PerfTelemetry.recordCameraLifecycle("stopped", SystemClock.elapsedRealtime())
        stopForegroundCompat()
        stopSelf()
        Timber.i("Tracking stopped (desired=%s)", desiredTrackingEnabled)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private suspend fun pauseTrackingLocked(userInitiated: Boolean = true) {
        if (_state.value.actualState != TrackingState.RUNNING) return
        if (userInitiated) userPaused = true else systemPaused = true
        _userPaused.value = userInitiated
        acceptingFrames = false
        publishState(_state.value.copy(
            isRunning = false,
            isPaused = true,
            actualState = TrackingState.PAUSING,
            desiredTrackingEnabled = true,
            reason = if (userInitiated) "user-paused" else "screen-off",
        ))
        withContext(Dispatchers.Main.immediate) {
            imageAnalysis?.clearAnalyzer()
            runCatching { cameraProvider?.unbindAll() }
                .onFailure { Timber.e(it, "unbindAll on pause failed") }
            cameraBound = false
        }
        publishState(_state.value.copy(
            isRunning = false,
            isPaused = true,
            actualState = TrackingState.PAUSED,
            desiredTrackingEnabled = true,
        ))
        updateNotification(isPaused = true)
        Timber.i(if (userInitiated) "Tracking paused by user (sticky)" else "Tracking paused by system (screen off)")
        PerfTelemetry.recordCameraLifecycle(
            if (userInitiated) "paused-user" else "paused-system",
            SystemClock.elapsedRealtime(),
        )
    }

    private suspend fun resumeTrackingLocked() {
        if (!desiredTrackingEnabled) return
        if (_state.value.actualState == TrackingState.RUNNING) return
        if (_state.value.actualState == TrackingState.STOPPED || _state.value.actualState == TrackingState.FAILED) {
            lifecycleGeneration++
            startTrackingLocked()
            return
        }
        if (_state.value.actualState !in setOf(TrackingState.PAUSED, TrackingState.CAMERA_LOST, TrackingState.WAITING_FOR_CAMERA)) return
        userPaused = false
        systemPaused = false
        _userPaused.value = false
        if (thermalPaused) {
            Timber.i("Resume requested but thermal pause active; waiting for recovery")
            return
        }
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            publishState(_state.value.copy(actualState = TrackingState.PAUSED, reason = "device-locked"))
            return
        }
        val generation = ++lifecycleGeneration
        publishState(_state.value.copy(
            isRunning = false,
            isPaused = false,
            actualState = TrackingState.STARTING,
            generation = generation,
            desiredTrackingEnabled = true,
            reason = null,
        ))
        lastFrameTimestampMs = 0L
        lastProcessedFrameMs = SystemClock.elapsedRealtime()
        if (!attemptCameraBindLocked(generation)) {
            publishState(_state.value.copy(
                isRunning = false,
                isPaused = false,
                actualState = TrackingState.WAITING_FOR_CAMERA,
                generation = generation,
                reason = "camera-unavailable",
            ))
            scheduleCameraRetryLocked(generation)
            updateNotification(isPaused = false, isWaiting = true)
            return
        }
        isCameraConflictPaused = false
        acceptingFrames = true
        publishState(_state.value.copy(
            isRunning = true,
            isPaused = false,
            actualState = TrackingState.RUNNING,
            generation = generation,
            desiredTrackingEnabled = true,
            reason = null,
        ))
        updateNotification(isPaused = false)
        Timber.i("Tracking resumed (generation=%d)", generation)
        PerfTelemetry.recordCameraLifecycle("resumed", SystemClock.elapsedRealtime())
    }

    // ------------------- Frame processing -------------------

    private fun processImageFrame(imageProxy: ImageProxy) {
        val startMs = SystemClock.elapsedRealtime()
        var leased: Bitmap? = null
        var handedOff = false
        try {
            val state = _state.value
            if (!acceptingFrames || state.actualState != TrackingState.RUNNING ||
                state.generation != lifecycleGeneration || state.isPaused
            ) return
            val intervalMs = adaptiveFpsController.analysisIntervalMs
            if (startMs - lastFrameTimestampMs < intervalMs) {
                PerfTelemetry.recordFrameDroppedThrottle()
                return
            }
            lastFrameTimestampMs = startMs
            lastProcessedFrameMs = startMs

            val rotation = imageProxy.imageInfo.rotationDegrees
            val swapped = rotation == 90 || rotation == 270
            val targetW = if (swapped) imageProxy.height else imageProxy.width
            val targetH = if (swapped) imageProxy.width else imageProxy.height
            if (targetW != conversionTargetWidth || targetH != conversionTargetHeight) {
                conversionTargetWidth = targetW
                conversionTargetHeight = targetH
                // SlotPool.drain retires busy slots; it never recycles a bitmap
                // still visible to MediaPipe.
                frameBitmaps.drain()
            }
            val bitmap = frameBitmaps.acquire()
            if (bitmap == null) {
                PerfTelemetry.recordFrameDroppedBackpressure()
                return
            }
            leased = bitmap

            val conversionStart = SystemClock.elapsedRealtime()
            val mpImage = convertIntoLeasedBitmap(bitmap, imageProxy)
            PerfTelemetry.recordImageConversion(SystemClock.elapsedRealtime() - conversionStart)
            if (mpImage == null) {
                PerfTelemetry.recordFrameDroppedConversion()
                return
            }

            val frameId = nextFrameId.incrementAndGet()
            val generation = lifecycleGeneration
            val eyeChannelWanted = eyeTrackingEnabled && faceTracker.isInitialized()
            val ownership = com.aircontrol.tracking.FrameOwnership(
                frameId = frameId,
                generation = generation,
                bitmapId = System.identityHashCode(bitmap),
                createdAtMs = startMs,
                consumerCount = if (eyeChannelWanted) 2 else 1,
                onReleased = {
                    runCatching { mpImage.close() }
                    frameBitmaps.release(bitmap)
                },
                onViolation = { reason ->
                    Timber.e("Frame ownership violation frame=%d generation=%d bitmap=%d: %s", frameId, generation, System.identityHashCode(bitmap), reason)
                    PerfTelemetry.recordFrameOwnershipViolation()
                },
            )
            handedOff = true

            ownership.markSubmitted(com.aircontrol.tracking.FrameOwnership.Consumer.HAND)
            try {
                handTracker.processFrame(mpImage, startMs) {
                    ownership.markCompleted(com.aircontrol.tracking.FrameOwnership.Consumer.HAND)
                    PerfTelemetry.recordHandFrameCompleted(frameId, SystemClock.elapsedRealtime() - startMs)
                }
            } catch (e: Throwable) {
                ownership.markCompleted(com.aircontrol.tracking.FrameOwnership.Consumer.HAND)
                CrashGuard.report("hand frame $frameId", e)
            }

            if (eyeChannelWanted) {
                ownership.markSubmitted(com.aircontrol.tracking.FrameOwnership.Consumer.FACE)
                try {
                    faceTracker.processFrame(mpImage, startMs) {
                        ownership.markCompleted(com.aircontrol.tracking.FrameOwnership.Consumer.FACE)
                        PerfTelemetry.recordFaceFrameCompleted(frameId, SystemClock.elapsedRealtime() - startMs)
                    }
                } catch (e: Throwable) {
                    ownership.markCompleted(com.aircontrol.tracking.FrameOwnership.Consumer.FACE)
                    CrashGuard.report("face frame $frameId", e)
                }
            }
            PerfTelemetry.recordFrameProcessed(startMs)
        } catch (e: Throwable) {
            Timber.e(e, "processImageFrame error")
            PerfTelemetry.recordPipelineException("frame-analyzer", e::class.java.simpleName)
        } finally {
            leased?.let { if (!handedOff) frameBitmaps.discard(it) }
            runCatching { imageProxy.close() }
            PerfTelemetry.recordAnalyzerDuration(SystemClock.elapsedRealtime() - startMs)
        }
    }

    /**
     * Draws the proxy's pixels, rotated and (once, deliberately) mirrored, into the leased
     * [targetBitmap], and wraps it as a MediaPipe image that shares — does not own — those pixels.
     */
    private fun convertIntoLeasedBitmap(targetBitmap: Bitmap, imageProxy: ImageProxy): MPImage? {
        return try {
            // Perf audit P6: with RGBA_8888 output (the default bind path) toBitmap() WRAPS the
            // frame's existing buffer, so there is nothing to allocate here and nothing to
            // recycle: recycling a wrapper would free storage CameraX still owns.
            val sourceBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val targetW = conversionTargetWidth
            val targetH = conversionTargetHeight
            if (targetBitmap.width != targetW || targetBitmap.height != targetH) {
                // The stream changed between acquire() and here; drop the frame rather than draw
                // a mis-sized image into a buffer the trackers would read out of bounds.
                return null
            }

            if (cachedRotationDegrees != rotationDegrees || cachedMatrix == null) {
                val m = android.graphics.Matrix()
                when (rotationDegrees) {
                    90 -> { m.postRotate(90f); m.postTranslate(sourceBitmap.height.toFloat(), 0f) }
                    180 -> { m.postRotate(180f); m.postTranslate(sourceBitmap.width.toFloat(), sourceBitmap.height.toFloat()) }
                    270 -> { m.postRotate(270f); m.postTranslate(0f, sourceBitmap.width.toFloat()) }
                }
                // The ONE mirror decision for the analysis stream; shared with the
                // gaze pipeline through GazeCoordinateContract so the flag the
                // tracker reports can never drift from the flip actually applied.
                if (com.aircontrol.tracking.GazeCoordinateContract.ANALYSIS_MIRRORED_HORIZONTALLY) {
                    m.postScale(-1f, 1f, targetW / 2f, targetH / 2f)
                }
                cachedMatrix = m; cachedRotationDegrees = rotationDegrees
            }
            val canvas = android.graphics.Canvas(targetBitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(sourceBitmap, checkNotNull(cachedMatrix), null)
            BitmapImageBuilder(targetBitmap).build()
        } catch (e: Exception) {
            Timber.e(e, "Frame conversion failed")
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

    private fun buildNotification(
        isPaused: Boolean,
        isThermal: Boolean = false,
        isWaiting: Boolean = false,
    ): Notification {
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
            isWaiting -> getString(R.string.notification_text_waiting)
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

    private fun updateNotification(
        isPaused: Boolean,
        isThermal: Boolean = false,
        isWaiting: Boolean = false,
    ) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(isPaused, isThermal, isWaiting))
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
                // P0-1: sample both in-flight gates once per watchdog tick. Saturation only means
                // anything as a rate, and reading it here (every 5 s) keeps the cost off the frame
                // path entirely. `faceTracker` is lateinit and injection races a very early
                // watchdog tick, hence runCatching.
                var inferenceStalled = false
                runCatching {
                    val gateNow = SystemClock.elapsedRealtime()
                    val handStats = handTracker.inFlightStats(gateNow)
                    val faceStats =
                        if (faceTracker.isInitialized()) faceTracker.inFlightStats(gateNow) else null
                    inferenceStalled = handStats.stalled || faceStats?.stalled == true
                    PerfTelemetry.recordGateStats(
                        handBusy = handStats.busy,
                        faceBusy = faceStats?.busy ?: false,
                        handRefused = handStats.refused,
                        faceRefused = faceStats?.refused ?: 0L,
                        expiredReservations = handStats.expired + (faceStats?.expired ?: 0L),
                    )
                }
                val s = _state.value
                // The watchdog observes RUNNING; it never turns STARTING,
                // WAITING_FOR_CAMERA, PAUSING, PAUSED, CAMERA_LOST or STOPPING
                // into a restart request. Those states are owned by the lifecycle
                // coordinator and recover through their explicit event.
                if (s.actualState != TrackingState.RUNNING || !s.isRunning || thermalPaused ||
                    !desiredTrackingEnabled
                ) continue
                if (inferenceStalled) {
                    Timber.w("Inference callback exceeded its diagnostic window; rebuilding the graph")
                    restartCamera()
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

    private suspend fun restartCamera() {
        lifecycleMutex.withLock {
            if (!desiredTrackingEnabled || _state.value.actualState != TrackingState.RUNNING || bindInProgress) return@withLock
            val generation = ++lifecycleGeneration
            Timber.w("Watchdog detected a genuine RUNNING stall; rebuilding generation=%d", generation)
            PerfTelemetry.recordWatchdogAction("rebuild-camera", SystemClock.elapsedRealtime())
            acceptingFrames = false
            publishState(_state.value.copy(
                isRunning = false,
                isPaused = false,
                actualState = TrackingState.STARTING,
                generation = generation,
                reason = "pipeline-stall",
            ))
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    imageAnalysis?.clearAnalyzer()
                    cameraProvider?.unbindAll()
                }
            }.onFailure { Timber.e(it, "unbindAll on watchdog rebuild failed") }
            cameraBound = false
            imageAnalysis = null
            // Closing the graph, rather than reclaiming a timed-out gate, is what
            // proves that no old callback can still read the old frame.
            withContext(Dispatchers.Default) {
                runCatching { handTracker.close() }
                if (eyeTrackingEnabled) runCatching { faceTracker.close() }
                runCatching { handTracker.initialize() }
                if (eyeTrackingEnabled) runCatching { faceTracker.initialize() }
            }
            frameBitmaps.drain()
            if (handTracker.isInitialized() && attemptCameraBindLocked(generation)) {
                isCameraConflictPaused = false
                acceptingFrames = true
                publishState(_state.value.copy(
                    isRunning = true,
                    isPaused = false,
                    actualState = TrackingState.RUNNING,
                    generation = generation,
                    desiredTrackingEnabled = true,
                    reason = null,
                ))
                PerfTelemetry.recordCameraLifecycle("recovered", SystemClock.elapsedRealtime())
            } else {
                publishState(_state.value.copy(
                    isRunning = false,
                    actualState = TrackingState.WAITING_FOR_CAMERA,
                    generation = generation,
                    desiredTrackingEnabled = true,
                    reason = "camera-unavailable-after-stall",
                ))
                scheduleCameraRetryLocked(generation)
            }
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
        if (!::thermalMonitor.isInitialized) return
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
        PerfTelemetry.recordConfiguredFps(capped)
        adaptiveFpsController.updateConfiguredFps(capped)
    }

    private fun applyThermalThrottling(status: com.aircontrol.tracking.ThermalStatus) {
        when (status) {
            com.aircontrol.tracking.ThermalStatus.NONE -> {
                if (thermalPaused) {
                    thermalPaused = false
                    if (!userPaused) serviceScope.launch { lifecycleMutex.withLock { resumeTrackingLocked() } }
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
                    if (!userPaused) serviceScope.launch { lifecycleMutex.withLock { resumeTrackingLocked() } }
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
                updateNotification(isPaused = false, isThermal = true)
            }
            com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                if (thermalPaused) {
                    thermalPaused = false
                    if (!userPaused) serviceScope.launch { lifecycleMutex.withLock { resumeTrackingLocked() } }
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
                    serviceScope.launch { lifecycleMutex.withLock { pauseTrackingLocked() } }
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
