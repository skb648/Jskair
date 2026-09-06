package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aircontrol.R
import com.aircontrol.control.CursorController
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.CursorSmoother
import com.aircontrol.util.CrashGuard
import com.aircontrol.util.collectGuarded
import com.aircontrol.util.launchGuarded
import com.aircontrol.tracking.Handedness
import com.aircontrol.tracking.HandFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.Collections

/**
 * Accessibility service that provides system-wide gesture control for AirControl.
 */
class GestureControlAccessibilityService : AccessibilityService() {

    private var handTracker: com.aircontrol.tracking.HandTracker? = null
    private var faceTracker: com.aircontrol.tracking.FaceTracker? = null
    private var gestureDetector: GestureDetector? = null
    private var actionDispatcher: ActionDispatcher? = null
    private var settingsRepository: SettingsRepository? = null
    private var cursorController: CursorController? = null
    private var cameraServiceManager: com.aircontrol.service.CameraServiceManager? = null

    // CrashGuard.handler keeps a throwing collector from taking the process down
    // (see util/CrashGuard.kt) — the previous scope had no handler at all, so any
    // OEM hiccup inside a collector killed the whole app.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CrashGuard.handler,
    )

    /** Camera restart backoff, so a rejected foreground-service start self-heals. */
    @Volatile private var cameraRetryAttempt: Int = 0
    private var cameraWatchdogJob: Job? = null
    private var cameraRetryJob: Job? = null

    // Overlays (must only be touched on the main thread).
    private var cursorOverlay: CursorOverlay? = null
    private var statusOverlay: StatusOverlay? = null

    // Screen metrics (raw display, no inset subtraction — fix #1).
    @Volatile private var screenWidth: Int = 0
    @Volatile private var screenHeight: Int = 0

    // Keyguard state cache — backed by KeyguardManager, updated on SCREEN_ON/OFF/USER_PRESENT.
    @Volatile private var cachedKeyguardLocked: Boolean = false
    private var isReceiverRegistered = false

    // Fix #20: thread-safe list for pipeline jobs.
    private val pipelineJobs: MutableList<Job> =
        Collections.synchronizedList(mutableListOf())

    // Runtime settings cache.
    @Volatile private var currentPreferences = UserPreferences()
    private var lastAppliedSensitivity: Int? = null
    private var lastAppliedCalibrationHandSize: Float? = null
    private var lastAppliedCalibrationPinchDist: Float? = null

    // Cursor freeze during gestures.
    @Volatile private var isCursorFrozen = false
    private var cursorFreezeJob: Job? = null

    // Cursor smoother (fix #101: one set of constants — Apple Vision Pro tuned).
    private val cursorSmoother = CursorSmoother(
        minCutoff = DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF,
        beta = DEFAULT_CURSOR_SMOOTHER_BETA,
    )

    // Gaze smoothing for "eye is mouse" mode.
    // Fix E1: the gaze cursor used a FIXED-alpha EMA. Fixed smoothing cannot
    // have both "still = steady" and "moving = instant": at alpha 0.2 the dot
    // trailed the eyes by ~100ms (laggy) and between iris-landmark quantization
    // steps it snapped in visible jumps ("teleport"). One Euro is
    // velocity-adaptive — heavy filtering when the gaze is still, near-zero
    // latency on saccades — plus its micro dead-zone kills the quantization
    // steps. Slightly hotter constants than the hand cursor: gaze needs snappy.
    private val gazeCursorSmoother = CursorSmoother(
        minCutoff = GAZE_SMOOTHER_MIN_CUTOFF,
        beta = GAZE_SMOOTHER_BETA,
    )

    // Blink detector for blink-to-click.
    private val blinkDetector = com.aircontrol.tracking.BlinkDetector()

    @Volatile private var gazeCalibration = com.aircontrol.tracking.GazeCalibration.UNAVAILABLE

    @Volatile private var currentCursorBeta: Float = DEFAULT_CURSOR_SMOOTHER_BETA
    @Volatile private var lastAppliedMinCutoffHint: Float? = null
    @Volatile private var baseMinCutoff: Float = DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF

    // Dwell/hover state.
    @Volatile private var lastCursorX: Float = 0.5f
    @Volatile private var lastCursorY: Float = 0.5f
    @Volatile private var stationarySinceMs: Long = 0L
    @Volatile private var dwellFired: Boolean = false
    @Volatile private var hoverActive: Boolean = false

    // Fix A8 (full): dwell intent detection. A dwell click now requires a
    // *deliberate arrival*: the travel accumulated while the cursor was moving
    // must exceed a saccade-sized minimum before the fixation may accumulate a
    // dwell (reading drifts word-to-word in tiny steps and never qualifies),
    // and after a dwell fires the cursor must leave a re-arm radius before
    // another dwell can even start (no machine-gun taps while staring).
    @Volatile private var dwellMovingTravel: Float = 0f
    @Volatile private var fixationDwellAllowed: Boolean = false
    @Volatile private var dwellFireX: Float = 0.5f
    @Volatile private var dwellFireY: Float = 0.5f

    /** Fix (audit #10): last main-thread dwell-ring paint (real ~30fps gate). */
    @Volatile private var lastDwellUiUpdateMs: Long = 0L

    // Fix (audit #5): re-acquisition priming frames (overlay hidden while the
    // smoother re-converges, so the cursor reappears IN PLACE, not mid-jump).
    @Volatile private var gazeReacquireFrames: Int = 0

    // Fix (audit #6): last time the smoothed gaze moved meaningfully — the
    // blink-intent gate reads this (natural blinks happen mid-movement).
    @Volatile private var lastGazeMoveMs: Long = 0L
    @Volatile private var prevGazeX: Float = 0.5f
    @Volatile private var prevGazeY: Float = 0.5f

    // Gaze ("eye is mouse") cursor state (Fixes A1/A3/A6/A8).
    /** Latest smoothed gaze cursor position in screen-normalized coordinates. */
    @Volatile private var gazeCursorX: Float = 0.5f
    @Volatile private var gazeCursorY: Float = 0.5f
    /** Consecutive undetected gaze frames (hysteresis before hiding the dot). */
    @Volatile private var gazeMissCount: Int = 0
    /** When the face became continuously visible again (dwell grace, Fix A8). */
    @Volatile private var faceStableSinceMs: Long = 0L
    /** Monotonic timestamp of the last detected face frame (palm hint, Fix A10). */
    @Volatile private var lastFaceDetectedMs: Long = 0L
    /** Last serialized personalized model pushed to the tracker (avoids re-parsing). */
    @Volatile private var lastAppliedPersonalizedCalibration: String? = null

    // F12 gesture hint toast debounce.
    @Volatile private var lastHandDetectedMs: Long = 0L
    @Volatile private var lastHintShownMs: Long = 0L

    // HID POC (experimental, isolated): Bluetooth HID mouse path. Disabled by
    // default; the adapter no-ops unless the pref is on AND a host is connected.
    private var nativeHidMouseController: com.aircontrol.nativeinput.NativeHidMouseController? = null
    private var nativeHidAdapter: com.aircontrol.nativeinput.NativeHidInputAdapter? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Timber.i("Screen off — pausing the camera, keeping the session alive")
                    cachedKeyguardLocked = true
                    stopTrackingPipeline()
                    // Fix A-1: pause the *analysis*, not the service. The foreground
                    // service stays running, so coming back after an unlock only has to
                    // resume the analyzer — it does NOT have to start a camera foreground
                    // service from the background, which Android 14+ and Samsung/MIUI
                    // reject. Previously the camera was stopped here and the restart was
                    // gated on "the app is on screen", so every single unlock killed
                    // gestures until the user opened AirControl again.
                    //
                    // Fix (audit #21): this is a SYSTEM pause — revivable on wake. The
                    // notification's Pause button sends a sticky user pause instead.
                    runCatching { cameraServiceManager?.pauseTracking(systemInitiated = true) }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Re-query keyguard state on SCREEN_ON (fix #25).
                    val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    cachedKeyguardLocked = km?.isKeyguardLocked ?: true
                    Timber.i("Screen on — keyguardLocked=%s", cachedKeyguardLocked)
                    // Some devices never send USER_PRESENT (no secure lock screen).
                    if (!cachedKeyguardLocked) wakeTracking()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Timber.i("User unlocked — fully active")
                    cachedKeyguardLocked = false
                    wakeTracking()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val app = applicationContext as? com.aircontrol.AirControlApp
        if (app == null) {
            Timber.e("Application is not AirControlApp — cannot inject")
            return
        }

        try {
            val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint.getFromApplication(app)
            handTracker = entryPoint.handTracker()
            faceTracker = entryPoint.faceTracker()
            gestureDetector = entryPoint.gestureDetector()
            actionDispatcher = entryPoint.actionDispatcher()
            settingsRepository = entryPoint.settingsRepository()
            cursorController = entryPoint.cursorController()
            cameraServiceManager = entryPoint.cameraServiceManager()
            nativeHidMouseController = entryPoint.nativeHidMouseController()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to inject dependencies into accessibility service")
            // Fix C-1 (crash on enabling accessibility): never call disableSelf()
            // here. Hilt's singleton graph is only briefly unavailable while the
            // process is cold-starting by the system; disabling the service made the
            // accessibility toggle snap back off and the user was bounced out of
            // Settings with nothing working. Retry instead.
            publishConnectionState(false)
            scheduleInjectionRetry()
            return
        }

        try {
            // Init keyguard cache.
            val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
            cachedKeyguardLocked = km?.isKeyguardLocked ?: false

            // Attach dispatcher and register visual-feedback callback.
            actionDispatcher?.attachService(this)
            actionDispatcher?.onGestureDispatched = { actionName ->
                serviceScope.launch(Dispatchers.Main) { cursorOverlay?.ripple() }
                Timber.d("Gesture dispatched: %s — cursor ripple", actionName)
            }

            updateScreenMetrics()
            createOverlays()
            startTrackingPipeline()
            registerScreenStateReceiver()

            isInitializedOk = true
            startCameraWatchdog()

            // A collector that gave up after all of its retries means part of the
            // pipeline is dead. Say so out loud instead of silently doing nothing -
            // that silence is exactly what gets reported as "the app stopped
            // working" - and clear the hook in onDestroy so it can never outlive
            // this service instance (it captures `this`).
            CrashGuard.onFatalLoop = { collector, _ ->
                serviceScope.launch(Dispatchers.Main) {
                    runCatching {
                        Toast.makeText(
                            this@GestureControlAccessibilityService,
                            getString(R.string.error_collector_gave_up, collector),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }

            // Publish readiness only after every initialization step succeeds.
            publishConnectionState(true)
            Timber.i("GestureControlAccessibilityService connected")
        } catch (failure: Throwable) {
            // A malformed OEM WindowManager/receiver implementation must not crash
            // MainActivity or bounce the user out of onboarding.
            Timber.e(failure, "Accessibility service initialization failed")
            CrashGuard.report("accessibility init", failure)
            publishConnectionState(false)
            runCatching { unregisterScreenStateReceiver() }
            runCatching { stopTrackingPipeline() }
            runCatching { removeOverlays() }
            runCatching { actionDispatcher?.detachService() }
            Toast.makeText(this, R.string.accessibility_start_failed_toast, Toast.LENGTH_LONG).show()
            // Keep the *overlays* optional but bring the pipeline back: a failure
            // while adding a cursor view must not leave the service "on" in
            // Settings yet inert.
            serviceScope.launch {
                delay(2_000L)
                if (!isInitializedOk) startTrackingPipeline()
            }
        }
    }

    @Volatile
    private var isInitializedOk: Boolean = false

    private var injectionRetryCount: Int = 0

    /**
     * Hilt's application graph can be momentarily unavailable when the system
     * starts the accessibility service right after install/enable. Retrying beats
     * disabling the service (which read as "the app crashes and turns itself off").
     */
    private fun scheduleInjectionRetry() {
        if (injectionRetryCount >= INJECTION_MAX_RETRIES) {
            Toast.makeText(this, R.string.injection_failed_toast, Toast.LENGTH_LONG).show()
            return
        }
        injectionRetryCount++
        serviceScope.launchGuarded("a11y injection retry") {
            delay(INJECTION_RETRY_MS * injectionRetryCount)
            if (!isInitializedOk) onServiceConnected()
        }
    }

    /** Called on unlock: rebuild the pipeline and bring the camera back. */
    private fun wakeTracking() {
        startTrackingPipeline()
        cameraRetryAttempt = 0
        if (!currentPreferences.gesturesEnabled) return

        // Unlocking is a background event. If the existing camera foreground
        // service is still alive but paused, resume its already-created FGS here;
        // this does not create a new camera FGS and is safe after unlock. If the
        // service is gone entirely, defer creation of a new camera FGS until the
        // visible Activity owns the foreground-start opportunity.
        //
        // Fix (audit #21): never resume over a USER pause. Waking the screen after
        // the user tapped Pause on the notification used to restart tracking —
        // camera indicator back on, trust gone.
        if (com.aircontrol.camera.CameraService.isUserPaused.value) {
            Timber.i("Unlock: session is user-paused — staying paused")
        } else if (cameraServiceManager?.isTracking() == true) {
            runCatching { cameraServiceManager?.resumeTracking() }
                .onFailure { Timber.e(it, "Failed to resume existing camera service after unlock") }
        } else if (MainActivity.isVisible || cameraPermissionGranted()) {
            // Fix (audit #23): with camera permission granted we attempt the start
            // from here too — the accessibility service keeps the process elevated
            // on most OEMs, and a failed FGS start is caught and retried by the
            // watchdog. The Activity-visible fast path remains the first choice.
            startCameraService()
        } else {
            Timber.v("Unlock: camera service absent; new camera FGS deferred until AirControl is visible")
        }
    }

    private fun cameraPermissionGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: we don't use accessibility events (fix #47).
    }

    override fun onDestroy() {
        // Tear down BEFORE calling super.onDestroy() (fix #64).
        Timber.i("GestureControlAccessibilityService destroyed")
        CrashGuard.onFatalLoop = null
        publishConnectionState(false)
        setGazeTracking(false)
        unregisterScreenStateReceiver()
        stopTrackingPipeline()
        removeOverlays()
        actionDispatcher?.onGestureDispatched = null // fix #6: clear callback
        actionDispatcher?.detachService()
        // GestureDetector is application-scoped. Closing it here permanently cancels
        // its internal scope, so toggling accessibility Off/On in the same process
        // reconnects to a dead detector. Reset transient state instead.
        gestureDetector?.reset()
        gestureDetector = null
        cursorController?.hide()
        stopCameraService()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * The system took the screen away mid-gesture (incoming call, user touch,
     * shade swipe). Drop the synthetic-gesture state so the next pinch starts a
     * clean stroke chain instead of throwing on a dead continued stroke (Fix B-6),
     * and release the frozen cursor so the dot is not left stuck.
     */
    override fun onInterrupt() {
        // AccessibilityService.onInterrupt() is abstract, so there is no super
        // implementation to call.
        Timber.w("Accessibility service interrupted - clearing in-flight gesture state")
        runCatching { actionDispatcher?.resetTransientGestureState() }
            .onFailure { CrashGuard.report("interrupt reset", it) }
        cursorFreezeJob?.cancel()
        cursorFreezeJob = null
        isCursorFrozen = false
        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.let {
            it.releaseClick()
            it.clearPinClick()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.i("Configuration changed — recomputing coordinate mapping")
        updateScreenMetrics()
        // Reset smoother so post-rotation landmarks don't cause a jump (fix #63).
        cursorSmoother.reset()
        cursorOverlay?.updateScreenSize(screenWidth, screenHeight)
        statusOverlay?.reposition()
    }

    // ---------- Tracking pipeline ----------

    private fun startTrackingPipeline() {
        if (pipelineJobs.isNotEmpty()) {
            Timber.d("Tracking pipeline already running")
            return
        }

        // Collect settings → detector + overlays.
        pipelineJobs.add(serviceScope.launchGuarded("settings collector", restart = true) {
            settingsRepository?.userPreferences?.collectGuarded("settings") { prefs ->
                currentPreferences = prefs
                if (!cachedKeyguardLocked && prefs.gesturesEnabled &&
                    MainActivity.isVisible && cameraServiceManager?.isTracking() != true) startCameraService()
                if (!prefs.gesturesEnabled && cameraServiceManager?.isTracking() == true) stopCameraService()

                if (lastAppliedSensitivity != prefs.sensitivity) {
                    gestureDetector?.updateSensitivity(prefs.sensitivity)
                    lastAppliedSensitivity = prefs.sensitivity
                }
                if (lastAppliedCalibrationHandSize != prefs.calibratedHandSizeMm ||
                    lastAppliedCalibrationPinchDist != prefs.calibratedPinchDistanceMm
                ) {
                    gestureDetector?.updateCalibration(
                        prefs.calibratedHandSizeMm,
                        prefs.calibratedPinchDistanceMm,
                    )
                    lastAppliedCalibrationHandSize = prefs.calibratedHandSizeMm
                    lastAppliedCalibrationPinchDist = prefs.calibratedPinchDistanceMm
                }

                // Fix A-16: the two cursor knobs are now separate and honestly
                // labelled: cursorGain is the pointer speed (this mapping), while the
                // slider stored under the legacy "cursorSpeed" key only drives filter
                // smoothing via applyCursorSmoothing() below. Previously the slider
                // labelled "Cursor speed" changed smoothing and nothing else, so
                // "speed does nothing" was literally true.
                ActionDispatcher.setCursorMapping(prefs.effectiveCursorGain, prefs.sitBackMode)
                gestureDetector?.updateSwipeRequiresOpenHand(prefs.swipeRequiresOpenHand)
                gazeCalibration = com.aircontrol.tracking.GazeCalibration.fromString(prefs.gazeCalibration)

                // Fix A5: push the persisted personalized gaze model into the
                // tracker. Parsed once per change (not per emission); a stale or
                // signature-mismatched model silently falls back to ratios.
                if (prefs.personalizedGazeCalibration != lastAppliedPersonalizedCalibration) {
                    lastAppliedPersonalizedCalibration = prefs.personalizedGazeCalibration
                    val model = if (prefs.personalizedGazeCalibration.isBlank()) null
                    else when (
                        val loaded = com.aircontrol.tracking.PersonalizedGazeCalibrationSerializer.deserialize(
                            prefs.personalizedGazeCalibration,
                            expectedTransformSignature = com.aircontrol.tracking.GazeCalibrationFeatureSchema.TRANSFORM_SIGNATURE,
                        )
                    ) {
                        is com.aircontrol.tracking.CalibrationLoadResult.Loaded -> loaded.model
                        is com.aircontrol.tracking.CalibrationLoadResult.Invalid -> {
                            Timber.w("Personalized gaze model rejected: %s — using fallback", loaded.reason)
                            null
                        }
                    }
                    faceTracker?.updatePersonalizedModel(model)
                }

                // Fix A10: push the user's blink-duration window into the detector.
                blinkDetector.updateConfig(
                    minBlinkMs = prefs.blinkWindowMs.toLong(),
                    maxBlinkMs = prefs.blinkWindowMs.toLong() + 500L,
                )

                withContext(Dispatchers.Main) {
                    // Fix C-1: overlay creation is wrapped. On some OEM ROMs
                    // addView(TYPE_ACCESSIBILITY_OVERLAY) throws while the service is
                    // still being bound; losing the dot must never mean losing
                    // gestures (or killing the process).
                    runCatching {
                        if (prefs.statusPillEnabled && statusOverlay == null) {
                            statusOverlay = StatusOverlay(this@GestureControlAccessibilityService)
                        } else if (!prefs.statusPillEnabled && statusOverlay != null) {
                            statusOverlay?.remove()
                            statusOverlay = null
                        }
                    }.onFailure { CrashGuard.report("status overlay", it) }
                    runCatching {
                        if (prefs.cursorEnabled && cursorOverlay == null) {
                            cursorOverlay = CursorOverlay(
                                this@GestureControlAccessibilityService,
                                screenWidth, screenHeight,
                            )
                        } else if (!prefs.cursorEnabled && cursorOverlay != null) {
                            cursorOverlay?.remove()
                            cursorOverlay = null
                            cursorController?.hide()
                        }
                    }.onFailure { CrashGuard.report("cursor overlay", it) }
                    applyCursorSmoothing(prefs.cursorSmoothing)
                    cursorOverlay?.setReducedMotion(prefs.reducedMotion)
                    // Fix B-1: the armed ring on the cursor dot was never driven,
                    // so there was no way to tell whether the app was listening.
                    val armedNow = gestureDetector?.engineState?.value.let {
                        it == GestureEngineState.ARMED || it == GestureEngineState.EXECUTING ||
                            it == GestureEngineState.COOLDOWN
                    }
                    cursorOverlay?.setArmed(armedNow)
                }
            }
        })

        // Feed hand frames → gesture detector (with hand preference filtering).
        // Fix #19: when the detected hand doesn't match preference, feed EMPTY
        // rather than a frame with the wrong hand, but don't thrash on every
        // frame — use hysteresis via a "last hand seen" tracker.
        pipelineJobs.add(serviceScope.launchGuarded("hand frames", restart = true) {
            handTracker?.handFrames?.collectGuarded("hand frames") { frame ->
                // Fix A-20: record hand presence from every detected frame, on the
                // same monotonic clock the rest of the pipeline uses. Previously the
                // hint timestamp was only written from ARMED "cursor moved" events
                // and was then compared against System.currentTimeMillis(), so the
                // "show your open palm" guidance could never appear for a new user.
                if (frame.isDetected) lastHandDetectedMs = SystemClock.elapsedRealtime()

                val preference = currentPreferences.handPreference
                val shouldProcess = when {
                    !frame.isDetected -> false
                    preference == com.aircontrol.data.model.HandPreference.ANY -> true
                    frame.handedness == Handedness.UNKNOWN -> true // don't drop unknown
                    preference == com.aircontrol.data.model.HandPreference.LEFT ->
                        frame.handedness == Handedness.LEFT
                    preference == com.aircontrol.data.model.HandPreference.RIGHT ->
                        frame.handedness == Handedness.RIGHT
                    else -> true
                }
                if (shouldProcess) {
                    gestureDetector?.processHandFrame(frame)
                } else {
                    // Feed EMPTY so the engine can disarm cleanly, but only if
                    // we were previously tracking (avoid resetting on every frame
                    // of the wrong hand, which caused arming thrash).
                    gestureDetector?.processHandFrame(
                        HandFrame.EMPTY.copy(timestampMs = frame.timestampMs),
                    )
                }
            }
        })

        // Custom gesture templates.
        pipelineJobs.add(serviceScope.launchGuarded("custom gestures", restart = true) {
            settingsRepository?.customGestures?.collectGuarded("custom gestures") { gestures ->
                try {
                    val templates = gestures
                        .filter { it.isEnabled }
                        .mapNotNull { g ->
                            val trigger = g.triggerPose
                            if (trigger is com.aircontrol.data.model.CustomGestureTrigger.LandmarkTemplateTrigger)
                                trigger.template else null
                        }
                    gestureDetector?.updateCustomTemplates(templates)
                } catch (e: Exception) {
                    Timber.e(e, "Error updating custom gesture templates")
                }
            }
        })

        // HID POC (Phase 1, isolated): experimental Native HID Mouse. When the
        // pref is OFF this block does nothing at all (hand frames are ignored,
        // controller stays down) — the existing accessibility path is untouched.
        pipelineJobs.add(serviceScope.launchGuarded("native hid mouse", restart = true) {
            val controller = nativeHidMouseController ?: return@launchGuarded
            nativeHidAdapter = com.aircontrol.nativeinput.NativeHidInputAdapter(
                controller,
                debug = { Timber.d("NativeHidAdapter: %s", it) },
            )
            launch {
                settingsRepository?.userPreferences?.collectGuarded("native hid prefs") { prefs ->
                    if (prefs.nativeHidMouseEnabled) controller.start() else controller.stop()
                }
            }
            handTracker?.handFrames?.collectGuarded("native hid hand") { frame ->
                if (currentPreferences.nativeHidMouseEnabled) nativeHidAdapter?.onHandFrame(frame)
            }
        })

        // SINGLE collector on gestureEvents fans out to both dispatch and cursor
        // updates. This preserves ordering between "cursor moved" and "action
        // dispatched" (fix #61).
        pipelineJobs.add(serviceScope.launchGuarded("gesture events", restart = true) {
            gestureDetector?.gestureEvents?.collectGuarded("gesture events") { event ->
                handleGestureEvent(event)
            }
        })

        // Engine state → overlays.
        pipelineJobs.add(serviceScope.launchGuarded("engine state", restart = true) {
            gestureDetector?.engineState?.collectGuarded("engine state") { state ->
                try {
                    withContext(Dispatchers.Main) {
                        if (currentPreferences.statusPillEnabled) {
                            statusOverlay?.updateState(state)
                        }
                        when (state) {
                            GestureEngineState.ARMED,
                            GestureEngineState.EXECUTING,
                            GestureEngineState.COOLDOWN -> {
                                if (currentPreferences.cursorEnabled) {
                                    cursorOverlay?.show()
                                    // Fix B-1: the ring that says "I am listening".
                                    cursorOverlay?.setArmed(true)
                                    cursorController?.show()
                                }
                            }
                            GestureEngineState.DISARMED -> {
                                cursorOverlay?.hide()
                                cursorOverlay?.setArmed(false)
                                cursorController?.hide()
                                cursorSmoother.reset()
                                resetDwellState()
                            }
                            GestureEngineState.ARMING -> {
                                // Cursor stays hidden while arming, but the ring is
                                // primed so it appears already armed (no flicker).
                                cursorOverlay?.setArmed(true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CrashGuard.report("overlay state", e)
                }
            }
        })

        // Gaze "eye is mouse" collector.
        pipelineJobs.add(serviceScope.launchGuarded("gaze", restart = true) {
            faceTracker?.gazePoints?.collectGuarded("gaze") gaze@{ gaze ->
                try {
                    // Fix (audit #11): the gaze pipeline must ALSO run when Blink Click
                    // is enabled with the cursor overlay OFF — blink taps then land at
                    // the (invisible) gaze point. Only cursor *visuals* below are gated
                    // on cursorEnabled.
                    if (!currentPreferences.gesturesEnabled || !currentPreferences.eyeTrackingEnabled ||
                        (!currentPreferences.cursorEnabled && !currentPreferences.blinkClickEnabled)
                    ) {
                        setGazeTracking(false)
                        return@gaze
                    }
                    if (!gaze.isDetected) {
                        // Fix A6: hysteresis. A single borderline frame (face
                        // slightly small, one eye occluded) used to hide the dot
                        // outright, and the 200 ms fade-in/out then fought the
                        // next detection — visible as constant flicker at normal
                        // holding distances. Hide only after a few consecutive
                        // misses; a single re-detection restores immediately.
                        gazeMissCount++
                        if (gazeMissCount >= GAZE_HIDE_MISS_FRAMES) {
                            gazeMissCount = 0
                            faceStableSinceMs = 0L
                            setGazeTracking(false)
                            withContext(Dispatchers.Main) { cursorOverlay?.hide() }
                            cursorController?.hide()
                            gazeCursorSmoother.reset()
                            blinkDetector.reset()
                            // Fix (audit #5): on re-acquisition the cursor must
                            // REAPPEAR where the face is now — not teleport there.
                            // Prime the (reset) smoother for a couple of frames
                            // while the overlay stays hidden, then show in place.
                            gazeReacquireFrames = GAZE_REACQUIRE_PRIME_FRAMES
                        }
                        return@gaze
                    }
                    gazeMissCount = 0
                    lastFaceDetectedMs = SystemClock.elapsedRealtime()
                    setGazeTracking(true)

                    if (currentPreferences.blinkClickEnabled) {
                        // Fix A7: the blink window must be measured on the
                        // monotonic clock like the rest of the pipeline; wall
                        // time broke (phantom/missed blinks) whenever the user
                        // changed system time or NTP synced.
                        val blinkResult = blinkDetector.update(gaze.ear, SystemClock.elapsedRealtime())
                        if (blinkResult == com.aircontrol.tracking.BlinkResult.CLICK) {
                            // Fix (audit #6/#7): a NATURAL blink fires mid-saccade
                            // all the time — that is how eyes work — and clicking
                            // then both fires unintentionally AND lands on the
                            // previous location. An intentional blink happens
                            // after the user has settled their gaze ON the target:
                            // require a brief still window before the closure
                            // counts as a click. Moving-eyes blinks are dropped.
                            if (SystemClock.elapsedRealtime() - lastGazeMoveMs < BLINK_INTENT_STILLNESS_MS) {
                                Timber.d("Blink during eye movement ignored (not intentional)")
                                resetDwellState()
                                return@gaze
                            }
                            // Fix A1: click where the eye cursor actually is.
                            // The old code read cursorState, which was never
                            // updated by the gaze path (its synthetic hand frame
                            // failed isDetected), so every blink tapped (0.5,
                            // 0.5) — dead centre of the screen.
                            actionDispatcher?.dispatchBlinkTap(gazeCursorX, gazeCursorY, screenWidth, screenHeight)
                            resetDwellState()
                            return@gaze
                        } else if (blinkResult != com.aircontrol.tracking.BlinkResult.NONE) {
                            resetDwellState()
                            return@gaze
                        }
                    }
                    if (currentPreferences.blinkClickEnabled && blinkDetector.isClosed())
                        return@gaze

                    // Fix A5: personalized predictions are already screen-space;
                    // only the legacy ratio path needs gain/invert/affine.
                    val (nx, ny) = if (gaze.personalized) {
                        gaze.x.coerceIn(0f, 1f) to gaze.y.coerceIn(0f, 1f)
                    } else {
                        mapGazeToDisplay(gaze.x, gaze.y)
                    }
                    // Fix E1: velocity-adaptive One Euro smoothing — no fixed-alpha
                    // lag on saccades, no quantization "teleports" when still.
                    val (smoothX, smoothY) = gazeCursorSmoother.filter(nx, ny, gaze.timestampMs)
                    gazeCursorX = smoothX
                    gazeCursorY = smoothY

                    // Fix (audit #6): track when the gaze was last seen MOVING —
                    // the blink-click intent gate above reads this.
                    val gazeMoveDist = kotlin.math.sqrt(
                        (smoothX - prevGazeX) * (smoothX - prevGazeX) +
                            (smoothY - prevGazeY) * (smoothY - prevGazeY),
                    )
                    if (gazeMoveDist > GAZE_MOVE_EPSILON) {
                        lastGazeMoveMs = SystemClock.elapsedRealtime()
                    }
                    prevGazeX = smoothX
                    prevGazeY = smoothY

                    // Fix (audit #5): first frames after re-acquisition update the
                    // position quietly (overlay hidden) — the cursor then appears
                    // already at the face, no visible teleport.
                    if (gazeReacquireFrames > 0) {
                        gazeReacquireFrames--
                    } else {
                        withContext(Dispatchers.Main) {
                            if (currentPreferences.cursorEnabled) {
                                cursorOverlay?.show()
                                // Fix A2: direct mapping — gaze coords are screen-space
                                // and must not pass through the hand dead-zone mapping.
                                cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight, directMapping = true)
                            }
                        }
                    }
                    // Fix A1: keep the shared cursor state truthful for this path.
                    cursorController?.updatePosition(smoothX, smoothY)

                    // Fix A8: after (re)acquiring the face, saccade settling and
                    // re-detection jumps used to count as "still" gaze — a dwell
                    // click could fire the instant the face came back. Require a
                    // short stable-presence grace before dwell accumulates.
                    val nowMs = SystemClock.elapsedRealtime()
                    if (faceStableSinceMs == 0L) faceStableSinceMs = nowMs
                    // Fix (audit #11): dwell needs a VISIBLE cursor — invisible
                    // taps out of nowhere are worse than no tap.
                    if (currentPreferences.cursorEnabled &&
                        nowMs - faceStableSinceMs >= GAZE_DWELL_STABLE_MS
                    ) {
                        // Fix A-15: gaze feeds the same stillness/dwell machine as
                        // the hand path (monotonic clock), with fromGaze so the
                        // eventual tap maps directly to pixels (Fix A2).
                        handleCursorStillness(smoothX, smoothY, nowMs, isGaze = true)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating gaze cursor — skipping")
                }
            }
        })

        // F12: one-time toast hint when hand is visible but engine stays DISARMED (fix #71).
        pipelineJobs.add(serviceScope.launchGuarded("palm hint", restart = true) {
            while (isActive) {
                delay(4000L)
                val now = SystemClock.elapsedRealtime()
                val armed = gestureDetector?.engineState?.value.let {
                    it == GestureEngineState.ARMED || it == GestureEngineState.EXECUTING ||
                        it == GestureEngineState.COOLDOWN
                }
                if (armed) { /* no hint needed */ }
                else if (now - lastHandDetectedMs in 0L..6000L && now - lastHintShownMs > 30_000L) {
                    lastHintShownMs = now
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GestureControlAccessibilityService,
                            R.string.show_open_palm_hint,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                // Fix A10: in eye mode, pinch clicks still need the hand engine
                // to be ARMED (show an open palm first). With blink and dwell
                // off, a user whose face is tracked but who never shows a palm
                // got zero clicks and zero explanation — surface the same hint.
                else if (
                    currentPreferences.eyeTrackingEnabled &&
                    !currentPreferences.blinkClickEnabled &&
                    !currentPreferences.dwellEnabled &&
                    now - lastFaceDetectedMs in 0L..6000L &&
                    now - lastHintShownMs > 30_000L
                ) {
                    lastHintShownMs = now
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GestureControlAccessibilityService,
                            R.string.show_open_palm_hint,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        })
    }

    private fun stopTrackingPipeline() {
        synchronized(pipelineJobs) {
            pipelineJobs.forEach { it.cancel() }
            pipelineJobs.clear()
        }
        gestureDetector?.reset()
    }

    /**
     * Single pipeline entry for gesture events. Runs on Dispatchers.Default so
     * we don't choke the main thread, then hops to Main only for the actual
     * dispatchGesture call (which Android requires to be on main).
     */
    private suspend fun handleGestureEvent(event: GestureEvent) {
        // Dispatch on Default, except the actual dispatchGesture call which is
        // on Main (Android requirement).
        val cursorState = cursorController?.cursorState?.value
        val cursorX: Float
        val cursorY: Float

        when (event) {
            is GestureEvent.Pinch -> {
                // Fix A-9: the tap used to use the RAW anchored index tip from the
                // engine while the user aims at the *smoothed* dot. Whenever the
                // hand was moving, the dot lagged behind the hand, so the click
                // landed ahead of the dot: "I clicked the button under the dot and
                // it hit the thing next to it". The click now uses the exact
                // position the dot is drawn at (captured at pinch START so it does
                // not drift while the fingers close).
                //
                // Fix A3: in eye mode the *gaze* dot is what the user aims with.
                // Pinch START pins the current gaze position, MOVE drags along the
                // live gaze cursor, and END taps the pinned position — so the click
                // lands where the user is looking instead of where the hand hangs
                // in the camera frame.
                val eyeMode = currentPreferences.eyeTrackingEnabled && currentPreferences.cursorEnabled
                if (eyeMode) {
                    when (event.phase) {
                        com.aircontrol.gesture.model.PinchPhase.START -> {
                            (cursorController as? com.aircontrol.control.CursorControllerImpl)
                                ?.pinClickPosition(gazeCursorX, gazeCursorY)
                            cursorX = gazeCursorX
                            cursorY = gazeCursorY
                        }
                        com.aircontrol.gesture.model.PinchPhase.MOVE -> {
                            cursorX = gazeCursorX
                            cursorY = gazeCursorY
                        }
                        else -> {
                            val pinned = (cursorController as? com.aircontrol.control.CursorControllerImpl)
                                ?.pinnedClickPosition()
                            cursorX = pinned?.first ?: gazeCursorX
                            cursorY = pinned?.second ?: gazeCursorY
                        }
                    }
                } else if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE) {
                    cursorX = event.x
                    cursorY = event.y
                } else {
                    val pinned = (cursorController as? com.aircontrol.control.CursorControllerImpl)
                        ?.pinnedClickPosition()
                    if (pinned != null) {
                        cursorX = pinned.first
                        cursorY = pinned.second
                    } else {
                        val smoothed = cursorSmoother.lastPosition
                        cursorX = smoothed?.first ?: event.anchoredX
                        cursorY = smoothed?.second ?: event.anchoredY
                        if (event.phase == com.aircontrol.gesture.model.PinchPhase.START) {
                            (cursorController as? com.aircontrol.control.CursorControllerImpl)
                                ?.pinClickPosition(cursorX, cursorY)
                        }
                    }
                }

                when (event.phase) {
                    com.aircontrol.gesture.model.PinchPhase.START -> {
                        freezeCursorBriefly(CURSOR_FREEZE_MS_PINCH)
                        serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDragging(true) }
                    }
                    com.aircontrol.gesture.model.PinchPhase.MOVE -> {
                        unfreezeCursor()
                        serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDragging(true) }
                    }
                    com.aircontrol.gesture.model.PinchPhase.END -> {
                        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.releaseClick()
                        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.clearPinClick()
                        serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDragging(false) }
                    }
                }
            }
            is GestureEvent.Swipe,
            is GestureEvent.PoseTriggered,
            is GestureEvent.CustomGestureTriggered,
            is GestureEvent.PalmHome -> {
                // Reduced freeze for discrete gestures (fix #30: 300ms → 80ms).
                freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
                cursorX = cursorState?.x ?: 0f
                cursorY = cursorState?.y ?: 0f
            }
            is GestureEvent.Armed,
            is GestureEvent.Disarmed -> { cursorX = 0f; cursorY = 0f }
            is GestureEvent.CursorMoved -> {
                if (currentPreferences.cursorEnabled &&
                    !currentPreferences.eyeTrackingEnabled && !isCursorFrozen
                ) {
                    if (event.minCutoffHint != lastAppliedMinCutoffHint) {
                        val newMinCutoff = event.minCutoffHint ?: baseMinCutoff
                        cursorSmoother.updateParams(minCutoff = newMinCutoff, beta = currentCursorBeta)
                        lastAppliedMinCutoffHint = event.minCutoffHint
                    }

                    val (smoothX, smoothY) = cursorSmoother.filter(
                        event.x, event.y, event.timestampMs,
                    )

                    if (event.isSilent) return

                    handleCursorStillness(smoothX, smoothY, event.timestampMs)

                    // Main-thread hop is only for the overlay setPosition (UI draw).
                    withContext(Dispatchers.Main) {
                        cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight)
                    }
                    // Fix A1 (hand path): the synthetic 1-landmark HandFrame below
                    // failed HandFrame.isDetected, so CursorController.hide() ran
                    // instead of ever updating the position. Use the direct
                    // normalized update so the shared cursor state is truthful.
                    cursorController?.updatePosition(smoothX, smoothY)
                }
                return
            }
        }

        // Fix B-3: actions taken while one of our own setup flows is on screen are
        // filtered by ActionDispatcher.actionAllowed() - pinch/dwell/blink taps
        // still work (the user needs them to drive the calibration UI), while
        // Home/Back/Recents/scroll/volume are refused so the screen cannot be pulled
        // away mid-calibration. Nothing is skipped here: cursor bookkeeping, the
        // click pin and the dwell ring all have to keep running.

        val engineState = gestureDetector?.engineState?.value ?: return

        // Dispatch action. Path construction/geometry is done on Default; the
        // actual service.dispatchGesture() call runs on Main inside the dispatcher.
        val dispatcher = actionDispatcher ?: return
        // Fix A3: gaze-originated pinches map straight to screen pixels.
        val dispatchedWithGaze = currentPreferences.eyeTrackingEnabled && event is GestureEvent.Pinch
        val dispatched = withContext(Dispatchers.Main) {
            dispatcher.dispatch(
                event, engineState, cursorX, cursorY, screenWidth, screenHeight,
                fromGaze = dispatchedWithGaze,
            )
        }

        // Update controller pressed state for pinch.
        if (dispatched && event is GestureEvent.Pinch) {
            when (event.phase) {
                com.aircontrol.gesture.model.PinchPhase.START -> cursorController?.performClick()
                com.aircontrol.gesture.model.PinchPhase.MOVE -> { /* drag continues */ }
                com.aircontrol.gesture.model.PinchPhase.END -> cursorController?.show()
            }
        }
    }

    private fun freezeCursorBriefly(durationMs: Long) {
        isCursorFrozen = true
        cursorFreezeJob?.cancel()
        cursorFreezeJob = serviceScope.launch {
            delay(durationMs)
            isCursorFrozen = false
        }
    }

    private fun unfreezeCursor() {
        if (!isCursorFrozen) return
        cursorFreezeJob?.cancel()
        cursorFreezeJob = null
        isCursorFrozen = false
    }

    // ---------- Camera service helpers ----------

    /**
     * Ensures the tracking session is alive.
     *
     * Fix A-1 / A-2: this used to bail out whenever the AirControl activity was
     * not on screen ("camera start deferred until visible"), which meant that
     * after a lock/unlock — or after the system killed the camera service under
     * memory pressure — gestures stayed dead until the user manually opened the
     * app again. The camera service is now kept *running* and merely paused around
     * the screen state, so this path normally only has to resume the analyzer
     * (always allowed). A real background start is still attempted — needed after
     * a process kill — and retried with backoff if a strict OEM rejects it;
     * MainActivity.onResume remains the fast path while the app is visible.
     */
    private fun startCameraService() {
        if (!currentPreferences.gesturesEnabled) return
        if (!MainActivity.isVisible) {
            Timber.v("Camera start deferred: AirControl Activity is not visible")
            return
        }
        if (cameraServiceManager?.isTracking() == true) {
            runCatching { cameraServiceManager?.resumeTracking() }
            cameraRetryAttempt = 0
            return
        }
        runCatching {
            cameraServiceManager?.startTracking()
            cameraRetryAttempt = 0
            Timber.i("Camera service start requested from accessibility service")
        }.onFailure { error ->
            CrashGuard.report("camera start", error)
            scheduleCameraRetry()
        }
    }

    /** Retries a rejected camera start with backoff, a bounded number of times. */
    private fun scheduleCameraRetry() {
        if (cameraRetryAttempt >= CAMERA_MAX_RETRIES) return
        cameraRetryAttempt++
        val shift = (cameraRetryAttempt - 1).coerceAtMost(4)
        val retryDelayMs = CAMERA_RETRY_BASE_MS shl shift
        cameraRetryJob?.cancel()
        cameraRetryJob = serviceScope.launchGuarded("camera retry") {
            delay(retryDelayMs)
            // Fix (audit #23): retry whenever the user actually granted the
            // camera permission — not only while the Activity is on screen.
            // ("Nothing works until I open AirControl" after an OEM event.)
            // A start that the platform still rejects is caught and rescheduled.
            if (currentPreferences.gesturesEnabled && cameraPermissionGranted() &&
                cameraServiceManager?.isTracking() != true) {
                startCameraService()
            }
        }
    }

    /**
     * Self-healing watchdog (Fix A-2). When Android stops the camera service — low
     * memory, OEM battery optimization, a native MediaPipe failure — nothing used
     * to bring it back, so the app looked "on" in Settings while doing nothing.
     * Every few seconds we compare the user's master switch with reality and
     * re-arm the session when they disagree.
     */
    private fun startCameraWatchdog() {
        if (cameraWatchdogJob != null) return
        cameraWatchdogJob = serviceScope.launchGuarded("camera watchdog", restart = true) {
            while (isActive) {
                delay(CAMERA_WATCHDOG_MS)
                val wantsTracking = currentPreferences.gesturesEnabled
                val isTracking = cameraServiceManager?.isTracking() == true
                val keyguard = keyguardLocked()
                when {
                    // Screen locked: the session stays down until the unlock
                    // broadcast, which calls wakeTracking(). Reviving behind a
                    // lock screen would burn the camera (and battery) for nobody,
                    // and a camera FGS start while locked is exactly what some
                    // OEM policies reject.
                    wantsTracking && !isTracking && keyguard -> Unit
                    wantsTracking && !isTracking &&
                        cameraServiceManager?.autoReviveEnabled == false -> {
                        // Someone else owns the camera on purpose (debug screen).
                        Timber.v("Watchdog: camera revive suppressed (exclusive camera user)")
                    }
                    wantsTracking && !isTracking && cameraPermissionGranted() -> {
                        // Fix (audit #23): attempt revival whenever the permission is
                        // there, not only while AirControl is foreground. Failures are
                        // caught and retried on the next tick.
                        Timber.w("Watchdog: gestures enabled — reviving camera")
                        startCameraService()
                    }
                    wantsTracking && !isTracking -> {
                        Timber.v("Watchdog: camera restart deferred until permission is granted")
                    }
                    !wantsTracking && isTracking -> stopCameraService()
                }
            }
        }
    }

    private fun keyguardLocked(): Boolean =
        cachedKeyguardLocked ||
            (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true

    private fun stopCameraService() {
        runCatching {
            cameraServiceManager?.stopTracking()
            Timber.i("Camera service stop requested from accessibility service")
        }.onFailure { Timber.e(it, "Failed to stop camera service") }
    }

    // ---------- Overlays ----------

    private fun createOverlays() {
        val prefs = currentPreferences
        if (prefs.cursorEnabled) {
            cursorOverlay = CursorOverlay(this, screenWidth, screenHeight)
        }
        // Status pill defaults to OFF (fix #32). The overlay is only created when
        // the user enables it in settings.
        applyCursorSmoothing(prefs.cursorSmoothing)
    }

    private fun removeOverlays() {
        cursorOverlay?.remove()
        cursorOverlay = null
        statusOverlay?.remove()
        statusOverlay = null
    }

    /**
     * The "Cursor smoothing" slider (stored in the legacy `cursorSpeed` key).
     * It deliberately only controls smoothing now — actual pointer speed is
     * `cursorGain`, which reaches the coordinate mapping directly. Fix A-16:
     * previously a slider labelled "speed" only touched the filter, so dragging
     * it to maximum did not make the pointer any faster.
     */
    private fun applyCursorSmoothing(smoothing: Int) {
        val s = smoothing.coerceIn(1, 100)
        // 1 = nearly raw (high cutoff), 100 = heavy filtering (low cutoff).
        baseMinCutoff = MAX_SMOOTHING_CUTOFF - (s / 100f) * (MAX_SMOOTHING_CUTOFF - MIN_SMOOTHING_CUTOFF)
        if (lastAppliedMinCutoffHint == null) {
            cursorSmoother.updateParams(minCutoff = baseMinCutoff, beta = currentCursorBeta)
        }
    }

    private fun mapGazeToDisplay(gx: Float, gy: Float): Pair<Float, Float> {
        if (gazeCalibration.isCalibrated) return gazeCalibration.map(gx, gy)
        val gain = 1.2f + (currentPreferences.gazeSensitivity / 100f) * 1.3f
        var screenX = 0.5f + (gx - 0.5f) * gain
        if (currentPreferences.gazeInvertX) screenX = 1f - screenX
        val screenY = 0.5f + (gy - 0.5f) * gain
        return screenX.coerceIn(0f, 1f) to screenY.coerceIn(0f, 1f)
    }

    /**
     * Fix #59: resetDwellState must set dwellFired to FALSE so future dwells can
     * fire; the previous code set it to TRUE which blocked dwell indefinitely
     * after a blink/move.
     */
    private fun resetDwellState() {
        dwellFired = false
        stationarySinceMs = 0L
        dwellMovingTravel = 0f
        fixationDwellAllowed = false
        // Throttle main-thread hop.
        serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
    }

    /**
     * Fix #40: use the passed timestampMs (monotonic elapsedRealtime from the
     * camera pipeline) rather than System.currentTimeMillis().
     *
     * Fix A2: [isGaze] selects the pixel mapping for the dwell tap — gaze
     * coordinates map straight to pixels, hand coordinates keep the hand
     * dead-zone mapping.
     */
    private fun handleCursorStillness(x: Float, y: Float, timestampMs: Long, isGaze: Boolean = false) {
        val dx = x - lastCursorX
        val dy = y - lastCursorY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)

        lastCursorX = x
        lastCursorY = y

        if (dist > STATIONARY_THRESHOLD) {
            if (hoverActive) {
                hoverActive = false
                serviceScope.launch(Dispatchers.Main) { cursorOverlay?.resetHover() }
            }
            stationarySinceMs = timestampMs
            dwellMovingTravel += dist
            // Fix A8: after a dwell fires it stays latched until the cursor
            // leaves the re-arm radius — micro-drift around the clicked spot
            // must not re-start the dwell machine.
            if (dwellFired) {
                val fireDx = x - dwellFireX
                val fireDy = y - dwellFireY
                if (kotlin.math.sqrt(fireDx * fireDx + fireDy * fireDy) >= DWELL_REARM_RADIUS) {
                    dwellFired = false
                }
            }
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
            return
        }

        if (stationarySinceMs == 0L) stationarySinceMs = timestampMs

        // Fix A8: the first still frame after a movement decides whether this
        // fixation may ever dwell. Gaze mode requires a deliberate, saccade-
        // sized arrival (reading steps are ~0.01–0.04 and never qualify; a
        // deliberate look across the screen is ≥0.05). Hand dwells are always
        // deliberate by nature (a hand does not "read").
        if (dwellMovingTravel > 0f) {
            // Fix (audit #9): 0.05 was too coarse for dense UIs — moving to the
            // button right next to the previous one (~0.02–0.04) never counted as a
            // deliberate arrival, so staring at it did nothing. 0.025 still filters
            // reading drift (~0.01) but admits adjacent-control movements.
            fixationDwellAllowed = !isGaze || dwellMovingTravel >= GAZE_DELIBERATE_SACCADE_MIN
            dwellMovingTravel = 0f
        }

        val stillMs = timestampMs - stationarySinceMs

        if (!hoverActive && stillMs >= HOVER_AFTER_MS) {
            hoverActive = true
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.notifyHover() }
        }

        if (currentPreferences.gesturesEnabled && currentPreferences.dwellEnabled && !dwellFired &&
            fixationDwellAllowed && stillMs >= currentPreferences.dwellDurationMs
        ) {
            dwellFired = true
            dwellFireX = x
            dwellFireY = y
            actionDispatcher?.dispatchDwellTap(x, y, screenWidth, screenHeight, fromGaze = isGaze)
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
        } else if (currentPreferences.gesturesEnabled && currentPreferences.dwellEnabled && !dwellFired &&
            fixationDwellAllowed
        ) {
            val progress = (stillMs.toFloat() / currentPreferences.dwellDurationMs)
                .coerceIn(0f, 1f)
            // Throttle dwell ring updates to ~30fps to avoid main-thread spam (fix #62).
            // Fix (audit #10): the throttle was only a comment — every qualifying
            // frame still hopped to the main thread. Time-gate it for real.
            val uiNow = SystemClock.elapsedRealtime()
            if (uiNow - lastDwellUiUpdateMs >= 33L) {
                lastDwellUiUpdateMs = uiNow
                serviceScope.launch(Dispatchers.Main) {
                    cursorOverlay?.setDwellProgress(progress)
                }
            }
        }
    }

    // ---------- Screen metrics ----------

    private fun updateScreenMetrics() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // AccessibilityService.dispatchGesture uses raw display coordinates.
            // Do NOT subtract system-bar insets (fix #1).
            val metrics = windowManager.currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Timber.d("Screen metrics updated: %dx%d", screenWidth, screenHeight)
    }

    // ---------- Screen state receiver ----------

    private fun registerScreenStateReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        isReceiverRegistered = false
    }

    companion object {
        // Real runtime connection state. OEM settings can report a service as enabled
        // even after its process failed to bind; onboarding observes this signal too.
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        // Fix (audit #23): "Enabled" vs "Ready". The Home screen can now show
        // whether the gaze pipeline is actually seeing a face right now,
        // instead of the user guessing from a still cursor.
        private val _gazeTracking = MutableStateFlow(false)
        val gazeTracking: StateFlow<Boolean> = _gazeTracking.asStateFlow()

        fun setGazeTracking(detected: Boolean) {
            if (_gazeTracking.value != detected) _gazeTracking.value = detected
        }

        private fun publishConnectionState(connected: Boolean) {
            _isConnected.value = connected
        }

        // Fix #30: reduced from 300ms to 80ms so the cursor doesn't visibly stall.
        private const val CURSOR_FREEZE_MS_GESTURE = 80L
        private const val CURSOR_FREEZE_MS_PINCH = 50L

        // Fix B-5 (cursor feels floaty/slow): the old pair was minCutoff 1.0 Hz
        // with beta 0.007. Velocity here is in *normalized units per second*
        // (a fast sweep is ~1-3), so beta*velocity ≈ 0.02 — the adaptive half of
        // the One Euro filter was effectively disabled and the cursor was a fixed
        // ~1 Hz low-pass: ~400ms to settle at 24fps. Beta is now in the units the
        // filter actually sees, so fast motion passes through and only tremor is
        // damped.
        private const val DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF = 1.1f
        private const val DEFAULT_CURSOR_SMOOTHER_BETA = 0.9f

        // Smoothing slider range (minCutoff in Hz). Higher cutoff = less lag.
        private const val MIN_SMOOTHING_CUTOFF = 0.9f
        private const val MAX_SMOOTHING_CUTOFF = 4.0f

        // Camera watchdog / retry policy (Fix A-1, A-2).
        private const val CAMERA_WATCHDOG_MS = 5_000L
        private const val CAMERA_RETRY_BASE_MS = 2_000L
        private const val CAMERA_MAX_RETRIES = 6

        private const val INJECTION_RETRY_MS = 1_500L
        private const val INJECTION_MAX_RETRIES = 4

        private const val STATIONARY_THRESHOLD = 0.008f
        private const val HOVER_AFTER_MS = 150L

        // Fix E1: gaze One Euro constants — a little hotter than the hand
        // cursor's (1.1 / 0.9) because gaze targets are small and saccades are
        // the fastest human movement; the dead-zone inside CursorSmoother
        // handles the sub-threshold jitter.
        private const val GAZE_SMOOTHER_MIN_CUTOFF = 1.6f
        private const val GAZE_SMOOTHER_BETA = 1.1f

        // Fix A6: consecutive missed gaze frames tolerated before the dot hides.
        private const val GAZE_HIDE_MISS_FRAMES = 4

        // Fix (audit #5/#6): re-acquisition priming + blink-intent stillness window.
        private const val GAZE_REACQUIRE_PRIME_FRAMES = 2
        private const val BLINK_INTENT_STILLNESS_MS = 200L
        private const val GAZE_MOVE_EPSILON = 0.008f

        // Fix A8: the face must be continuously visible this long before gaze
        // dwell can accumulate (post-(re)acquisition settling grace).
        private const val GAZE_DWELL_STABLE_MS = 500L
        // Fix A8 (full): gaze dwell requires an arrival of at least this much
        // travel (normalized) — reading saccades (0.01–0.04) never qualify, a
        // deliberate look at a target does (≥0.05).
        private const val GAZE_DELIBERATE_SACCADE_MIN = 0.02f

        // Fix A8 (full): after a dwell click, the cursor must move this far from
        // the click point before another dwell can fire.
        private const val DWELL_REARM_RADIUS = 0.03f
    }
}
