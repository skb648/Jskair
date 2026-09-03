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
import com.aircontrol.tracking.Landmark3D
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

    // Gaze EMA filter for "eye is mouse" mode.
    private val gazeEmaFilter = com.aircontrol.tracking.EmaFilter(alpha = GAZE_EMA_ALPHA)

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

    // F12 gesture hint toast debounce.
    @Volatile private var lastHandDetectedMs: Long = 0L
    @Volatile private var lastHintShownMs: Long = 0L

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
                    runCatching { cameraServiceManager?.pauseTracking() }
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
        if (currentPreferences.gesturesEnabled) startCameraService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: we don't use accessibility events (fix #47).
    }

    override fun onInterrupt() {
        Timber.w("Accessibility service interrupted")
    }

    override fun onDestroy() {
        // Tear down BEFORE calling super.onDestroy() (fix #64).
        Timber.i("GestureControlAccessibilityService destroyed")
        CrashGuard.onFatalLoop = null
        publishConnectionState(false)
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
                    cameraServiceManager?.isTracking() != true) startCameraService()
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
                    if (!currentPreferences.gesturesEnabled || !currentPreferences.eyeTrackingEnabled ||
                        !currentPreferences.cursorEnabled) return@gaze
                    if (!gaze.isDetected) {
                        withContext(Dispatchers.Main) { cursorOverlay?.hide() }
                        cursorController?.hide()
                        gazeEmaFilter.reset()
                        blinkDetector.reset()
                        return@gaze
                    }

                    if (currentPreferences.blinkClickEnabled) {
                        val blinkResult = blinkDetector.update(gaze.ear, System.currentTimeMillis())
                        if (blinkResult == com.aircontrol.tracking.BlinkResult.CLICK) {
                            val cursor = cursorController?.cursorState?.value
                            val cx = cursor?.x ?: gaze.x
                            val cy = cursor?.y ?: gaze.y
                            actionDispatcher?.dispatchBlinkTap(cx, cy, screenWidth, screenHeight)
                            resetDwellState()
                            return@gaze
                        } else if (blinkResult != com.aircontrol.tracking.BlinkResult.NONE) {
                            resetDwellState()
                            return@gaze
                        }
                    }
                    if (currentPreferences.blinkClickEnabled && blinkDetector.isClosed())
                        return@gaze

                    val (nx, ny) = mapGazeToDisplay(gaze.x, gaze.y)
                    val (smoothX, smoothY) = gazeEmaFilter.filter(nx, ny)

                    withContext(Dispatchers.Main) {
                        cursorOverlay?.show()
                        cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight)
                    }
                    // Fix A-15: dwell-to-click was only ever driven from the hand
                    // "cursor moved" path, so an eye-tracking user turning on
                    // "look and hold to click" got nothing at all — the single most
                    // useful accessibility feature for that mode. Gaze now feeds the
                    // same stillness/dwell machine (using the monotonic clock the
                    // hand path uses).
                    handleCursorStillness(smoothX, smoothY, SystemClock.elapsedRealtime())
                    cursorController?.updatePosition(
                        HandFrame(
                            landmarks = listOf(Landmark3D(smoothX, smoothY, 0f)),
                            handedness = Handedness.UNKNOWN,
                            timestampMs = System.currentTimeMillis(),
                            confidence = 1f,
                        ),
                    )
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
                if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE) {
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
                    com.aircontrol.gesture.model.PinchPhase.START ->
                        freezeCursorBriefly(CURSOR_FREEZE_MS_PINCH)
                    com.aircontrol.gesture.model.PinchPhase.MOVE ->
                        unfreezeCursor()
                    com.aircontrol.gesture.model.PinchPhase.END -> {
                        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.releaseClick()
                        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.clearPinClick()
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
                    cursorController?.updatePosition(
                        HandFrame(
                            landmarks = listOf(Landmark3D(smoothX, smoothY, 0f)),
                            handedness = Handedness.UNKNOWN, // fix #60
                            timestampMs = event.timestampMs,
                            confidence = 1f,
                        ),
                    )
                }
                return
            }
        }

        // Fix B-3: while the user is inside our own calibration screens, the
        // service must not dispatch real taps onto those very screens (buttons
        // were being pressed by the user's own gestures mid-calibration, and the
        // gaze dwell added to this made it much worse). Cursor feedback keeps
        // running so calibration still "sees" the hand; only the actions stop.
        if (com.aircontrol.ui.Suppression.isSuppressed()) {
            if (event is GestureEvent.Pinch &&
                event.phase == com.aircontrol.gesture.model.PinchPhase.END
            ) {
                (cursorController as? com.aircontrol.control.CursorControllerImpl)
                    ?.releaseClick()
            }
            return
        }

        val engineState = gestureDetector?.engineState?.value ?: return

        // Dispatch action. Path construction/geometry is done on Default; the
        // actual service.dispatchGesture() call runs on Main inside the dispatcher.
        val dispatcher = actionDispatcher ?: return
        val dispatched = withContext(Dispatchers.Main) {
            dispatcher.dispatch(event, engineState, cursorX, cursorY, screenWidth, screenHeight)
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
            if (currentPreferences.gesturesEnabled && cameraServiceManager?.isTracking() != true) {
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
                    wantsTracking && !isTracking -> {
                        Timber.w("Watchdog: gestures enabled but the camera session is dead — reviving")
                        startCameraService()
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
        // Throttle main-thread hop.
        serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
    }

    /**
     * Fix #40: use the passed timestampMs (monotonic elapsedRealtime from the
     * camera pipeline) rather than System.currentTimeMillis().
     */
    private fun handleCursorStillness(x: Float, y: Float, timestampMs: Long) {
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
            dwellFired = false
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
            return
        }

        if (stationarySinceMs == 0L) stationarySinceMs = timestampMs
        val stillMs = timestampMs - stationarySinceMs

        if (!hoverActive && stillMs >= HOVER_AFTER_MS) {
            hoverActive = true
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.notifyHover() }
        }

        if (currentPreferences.gesturesEnabled && currentPreferences.dwellEnabled && !dwellFired &&
            stillMs >= currentPreferences.dwellDurationMs
        ) {
            dwellFired = true
            actionDispatcher?.dispatchDwellTap(x, y, screenWidth, screenHeight)
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
        } else if (currentPreferences.gesturesEnabled && currentPreferences.dwellEnabled && !dwellFired) {
            val progress = (stillMs.toFloat() / currentPreferences.dwellDurationMs)
                .coerceIn(0f, 1f)
            // Throttle dwell ring updates to ~30fps to avoid main-thread spam (fix #62).
            serviceScope.launch(Dispatchers.Main) {
                cursorOverlay?.setDwellProgress(progress)
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
        private const val GAZE_EMA_ALPHA = 0.2f
    }
}
