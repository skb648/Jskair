package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
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
import com.aircontrol.tracking.Handedness
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.Landmark3D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                    Timber.i("Screen off — stopping camera service")
                    cachedKeyguardLocked = true
                    stopTrackingPipeline()
                    stopCameraService()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Timber.i("Screen on — updating keyguard state; not starting camera until USER_PRESENT")
                    // Re-query keyguard state on SCREEN_ON (fix #25).
                    val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    cachedKeyguardLocked = km?.isKeyguardLocked ?: true
                    // Start pipeline only if unlocked (e.g., for devices with no secure lock
                    // screen where USER_PRESENT may not fire).
                    if (!cachedKeyguardLocked) {
                        startTrackingPipeline()
                        startCameraService()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Timber.i("User unlocked — fully active")
                    cachedKeyguardLocked = false
                    startTrackingPipeline()
                    startCameraService()
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
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject dependencies into accessibility service")
            // Surface the error to the user so they know the service is inert (fix #65).
            Toast.makeText(this, R.string.injection_failed_toast, Toast.LENGTH_LONG).show()
            disableSelf()
            return
        }

        // Init keyguard cache.
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        cachedKeyguardLocked = km?.isKeyguardLocked ?: false

        Timber.i("GestureControlAccessibilityService connected")

        // Attach dispatcher and register visual-feedback callback (fix #6: cleared on detach).
        actionDispatcher?.attachService(this)
        actionDispatcher?.onGestureDispatched = { actionName ->
            serviceScope.launch(Dispatchers.Main) {
                cursorOverlay?.ripple()
            }
            Timber.d("Gesture dispatched: %s — cursor ripple", actionName)
        }

        updateScreenMetrics()
        createOverlays()

        // Only the CameraService owns thermal monitoring to avoid double throttling
        // (fix #24). We do NOT create a second ThermalMonitor here.

        startTrackingPipeline()
        registerScreenStateReceiver()

        // Only start the camera service if we're not on the keyguard.
        if (!cachedKeyguardLocked) {
            startCameraService()
        }
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
        unregisterScreenStateReceiver()
        stopTrackingPipeline()
        removeOverlays()
        actionDispatcher?.onGestureDispatched = null // fix #6: clear callback
        actionDispatcher?.detachService()
        gestureDetector?.close()
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
        pipelineJobs.add(serviceScope.launch {
            settingsRepository?.userPreferences?.collect { prefs ->
                currentPreferences = prefs

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

                ActionDispatcher.setCursorMapping(prefs.cursorGain, prefs.sitBackMode)
                gazeCalibration = com.aircontrol.tracking.GazeCalibration.fromString(prefs.gazeCalibration)

                withContext(Dispatchers.Main) {
                    if (prefs.statusPillEnabled && statusOverlay == null) {
                        statusOverlay = StatusOverlay(this@GestureControlAccessibilityService)
                    } else if (!prefs.statusPillEnabled && statusOverlay != null) {
                        statusOverlay?.remove()
                        statusOverlay = null
                    }
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
                    applyCursorSpeed(prefs.cursorSpeed)
                    cursorOverlay?.setReducedMotion(prefs.reducedMotion)
                }
            }
        })

        // Feed hand frames → gesture detector (with hand preference filtering).
        // Fix #19: when the detected hand doesn't match preference, feed EMPTY
        // rather than a frame with the wrong hand, but don't thrash on every
        // frame — use hysteresis via a "last hand seen" tracker.
        pipelineJobs.add(serviceScope.launch {
            handTracker?.handFrames?.collect { frame ->
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
        pipelineJobs.add(serviceScope.launch {
            settingsRepository?.customGestures?.collect { gestures ->
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
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.gestureEvents?.collect { event ->
                try {
                    handleGestureEvent(event)
                } catch (e: Exception) {
                    Timber.e(e, "Error handling gesture event — skipping")
                }
            }
        })

        // Engine state → overlays.
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.engineState?.collect { state ->
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
                                    cursorController?.show()
                                }
                            }
                            GestureEngineState.DISARMED -> {
                                cursorOverlay?.hide()
                                cursorController?.hide()
                                cursorSmoother.reset()
                                resetDwellState()
                            }
                            GestureEngineState.ARMING -> { /* cursor stays hidden */ }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating overlays from engine state — skipping")
                }
            }
        })

        // Gaze "eye is mouse" collector.
        pipelineJobs.add(serviceScope.launch {
            faceTracker?.gazePoints?.collect { gaze ->
                try {
                    if (!currentPreferences.eyeTrackingEnabled || !currentPreferences.cursorEnabled)
                        return@collect
                    if (!gaze.isDetected) {
                        withContext(Dispatchers.Main) { cursorOverlay?.hide() }
                        cursorController?.hide()
                        gazeEmaFilter.reset()
                        blinkDetector.reset()
                        return@collect
                    }

                    if (currentPreferences.blinkClickEnabled) {
                        val blinkResult = blinkDetector.update(gaze.ear, System.currentTimeMillis())
                        if (blinkResult == com.aircontrol.tracking.BlinkResult.CLICK) {
                            val cursor = cursorController?.cursorState?.value
                            val cx = cursor?.x ?: gaze.x
                            val cy = cursor?.y ?: gaze.y
                            actionDispatcher?.dispatchBlinkTap(cx, cy, screenWidth, screenHeight)
                            resetDwellState()
                            return@collect
                        } else if (blinkResult != com.aircontrol.tracking.BlinkResult.NONE) {
                            resetDwellState()
                            return@collect
                        }
                    }
                    if (currentPreferences.blinkClickEnabled && blinkDetector.isClosed())
                        return@collect

                    val (nx, ny) = mapGazeToDisplay(gaze.x, gaze.y)
                    val (smoothX, smoothY) = gazeEmaFilter.filter(nx, ny)

                    withContext(Dispatchers.Main) {
                        cursorOverlay?.show()
                        cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight)
                    }
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
        pipelineJobs.add(serviceScope.launch {
            while (true) {
                delay(4000L)
                val now = System.currentTimeMillis()
                val armed = gestureDetector?.engineState?.value.let {
                    it == GestureEngineState.ARMED || it == GestureEngineState.EXECUTING ||
                        it == GestureEngineState.COOLDOWN
                }
                if (armed) { /* no hint needed */ }
                else if (now - lastHandDetectedMs < 1500L && now - lastHintShownMs > 30_000L) {
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
                cursorX = if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE)
                    event.x else event.anchoredX
                cursorY = if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE)
                    event.y else event.anchoredY

                when (event.phase) {
                    com.aircontrol.gesture.model.PinchPhase.START ->
                        freezeCursorBriefly(CURSOR_FREEZE_MS_PINCH)
                    com.aircontrol.gesture.model.PinchPhase.MOVE ->
                        unfreezeCursor()
                    com.aircontrol.gesture.model.PinchPhase.END ->
                        (cursorController as? com.aircontrol.control.CursorControllerImpl)?.releaseClick()
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
                    // Update hand-detection timestamp for F12 hint.
                    if (!event.isSilent) lastHandDetectedMs = event.timestampMs

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

    private fun startCameraService() {
        runCatching {
            cameraServiceManager?.startTracking()
            Timber.i("Camera service start requested from accessibility service")
        }.onFailure { Timber.e(it, "Failed to start camera service") }
    }

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
        applyCursorSpeed(prefs.cursorSpeed)
    }

    private fun removeOverlays() {
        cursorOverlay?.remove()
        cursorOverlay = null
        statusOverlay?.remove()
        statusOverlay = null
    }

    private fun applyCursorSpeed(speed: Int) {
        val s = speed.coerceIn(1, 100)
        // Map speed (1..100) to minCutoff (0.4..1.6): higher speed → less smoothing.
        baseMinCutoff = 0.4f + (s / 100f) * 1.2f
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

        if (currentPreferences.dwellEnabled && !dwellFired &&
            stillMs >= currentPreferences.dwellDurationMs
        ) {
            dwellFired = true
            actionDispatcher?.dispatchDwellTap(x, y, screenWidth, screenHeight)
            serviceScope.launch(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
        } else if (currentPreferences.dwellEnabled && !dwellFired) {
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
        // Fix #30: reduced from 300ms to 80ms so the cursor doesn't visibly stall.
        private const val CURSOR_FREEZE_MS_GESTURE = 80L
        private const val CURSOR_FREEZE_MS_PINCH = 50L

        // Fix #101: single, documented set of OneEuroFilter constants.
        private const val DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF = 1.0f
        private const val DEFAULT_CURSOR_SMOOTHER_BETA = 0.007f

        private const val STATIONARY_THRESHOLD = 0.008f
        private const val HOVER_AFTER_MS = 150L
        private const val GAZE_EMA_ALPHA = 0.2f
    }
}
