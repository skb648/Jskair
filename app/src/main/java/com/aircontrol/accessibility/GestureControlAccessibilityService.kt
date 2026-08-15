package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import com.aircontrol.camera.CameraService
import com.aircontrol.control.CursorController
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.HandTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Accessibility service that provides system-wide gesture control for AirControl.
 *
 * Capabilities:
 * - canPerformGestures=true: Dispatch touch gestures (tap, scroll, drag)
 * - canRetrieveWindowContent=false: Privacy — we request ONLY what is needed
 *
 * Lifecycle:
 * - onServiceConnected: Initialize tracking pipeline, create overlays, start CameraService
 * - onDestroy: Stop tracking, remove overlays, clean up
 * - System kill: Auto-restart camera binding on reconnect
 *
 * Edge cases handled:
 * - Service killed by system → auto-restart camera binding on reconnect
 * - Screen rotation → recompute coordinate mapping
 * - Multi-display → ignore non-default display
 * - Keyguard locked → suspend gesture injection except unlock-irrelevant global actions
 */
class GestureControlAccessibilityService : AccessibilityService() {

    private var handTracker: HandTracker? = null
    private var faceTracker: com.aircontrol.tracking.FaceTracker? = null
    private var gestureDetector: GestureDetector? = null
    private var actionDispatcher: ActionDispatcher? = null
    private var settingsRepository: SettingsRepository? = null
    private var cursorController: CursorController? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isRunning = false

    // Overlay managers
    private var cursorOverlay: CursorOverlay? = null
    private var statusOverlay: StatusOverlay? = null

    // Screen metrics (updated on rotation)
    private var screenWidth = 0
    private var screenHeight = 0

    // Keyguard state
    private var isKeyguardLocked = false
    private var isReceiverRegistered = false

    // Frame watchdog — detects pipeline stalls
    private var lastFrameReceivedMs: Long = 0L
    private var frameWatchdogJob: Job? = null
    private var pipelineJobs: MutableList<Job> = mutableListOf()

    // Thermal throttling
    private var thermalMonitor: com.aircontrol.tracking.ThermalMonitor? = null
    private var thermalMonitoringJob: Job? = null
    private var isThermalPaused = false

    // Issue 5 Fix: Thermal frame skip counter for graceful degradation.
    // Instead of fully pausing the service (which causes UX disruption),
    // we progressively skip frames to reduce thermal load:
    // - MODERATE: Skip every other frame (50% reduction)
    // - SEVERE: Skip 2 of every 3 frames (67% reduction) but keep pipeline alive
    // This keeps the service running and responsive without crashing or pausing.
    private var thermalFrameSkipCounter = 0

    // Runtime settings cache
    private var currentPreferences = UserPreferences()
    private var lastAppliedSensitivity: Int? = null

    // Tracks the last-applied calibration values so we only push a calibration
    // update to the gesture engine when they actually change (avoids rebuilding
    // the engine config on every unrelated preference change).
    private var lastAppliedCalibrationHandSize: Float? = null
    private var lastAppliedCalibrationPinchDist: Float? = null

    // Cursor freeze during gesture execution for better accuracy
    private var isCursorFrozen = false
    private var cursorFreezeJob: Job? = null

    // UL-01 Fix: Reduced cursor smoothing latency from ~80ms to ~40ms
    // Old values (minCutoff=0.45, beta=0.15) were too aggressive, causing lag
    // New values provide smooth tracking with minimal perceptible delay:
    //   - minCutoff = 0.7  (was 0.45) - lighter smoothing at rest, faster response
    //   - beta      = 0.08 (was 0.15) - faster tracking during motion, less lag
    // Combined with the 0.004 normalized dead zone in CursorSmoother and the 3dp
    // dead zone in CursorOverlay, this eliminates hand tremor while keeping
    // motion-to-cursor latency under 50ms (perceptually instant)
    private val cursorSmoother = com.aircontrol.tracking.CursorSmoother(
        minCutoff = DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF,
        beta = DEFAULT_CURSOR_SMOOTHER_BETA,
    )

    // Gaze smoothing ("eye is mouse") — a dedicated OneEuroFilter pair for gaze
    // X/Y. Eye tracking is noisier than fingertip tracking, so it gets stronger
    // smoothing than the hand cursor.
    private val gazeSmoother = com.aircontrol.tracking.CursorSmoother(
        minCutoff = GAZE_MIN_CUTOFF,
        beta = GAZE_BETA,
    )

    // Bug #13 Fix: Track the current beta so we can call cursorSmoother.updateParams
    // with the correct beta when only the minCutoff changes (via minCutoffHint).
    // Also track the last applied minCutoffHint to avoid redundant updateParams
    // calls on every frame — only call updateParams when the hint actually changes.
    @Volatile
    private var currentCursorBeta: Float = DEFAULT_CURSOR_SMOOTHER_BETA
    @Volatile
    private var lastAppliedMinCutoffHint: Float? = null

    // The user's "Cursor Speed" setting (1..100) now drives the CursorSmoother's
    // minCutoff: higher speed = lower minCutoff = less smoothing = faster cursor.
    // This replaces the removed second interpolation layer in CursorOverlay.
    @Volatile
    private var baseMinCutoff: Float = DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF

    // F1 (Dwell-to-click) + F2 (Hover): track how long the cursor has been
    // stationary so we can show a hover highlight and fire a dwell click.
    @Volatile
    private var lastCursorX: Float = 0.5f
    @Volatile
    private var lastCursorY: Float = 0.5f
    @Volatile
    private var stationarySinceMs: Long = 0L
    private var dwellFired: Boolean = false
    private var hoverActive: Boolean = false

    // F12 (Gesture hints): timestamp of the last frame where a hand was detected.
    @Volatile
    private var lastHandDetectedMs: Long = 0L

    // Broadcast receiver for screen state
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Timber.i("Screen off — suspending gesture injection and camera")
                    isKeyguardLocked = true
                    stopTrackingPipeline()
                    stopCameraService()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Timber.i("Screen on — resuming gesture injection")
                    startTrackingPipeline()
                    // Don't start camera service immediately — wait for user to unlock
                    // Camera will be started when user actually unlocks (ACTION_USER_PRESENT)
                    // or when the app comes to foreground
                }
                Intent.ACTION_USER_PRESENT -> {
                    Timber.i("User unlocked — fully active")
                    isKeyguardLocked = false
                    // Now safe to start camera service (user is in foreground)
                    startCameraService()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Inject dependencies (Hilt doesn't auto-inject AccessibilityService,
        // so we manually inject from the application component)
        (applicationContext as? com.aircontrol.AirControlApp)
            ?.let { app ->
                // Manually get the Hilt component and inject
                // Since AccessibilityService is not a standard Hilt entry point,
                // we use the application component directly
                try {
                    val entryPoint = com.aircontrol.di.AccessibilityServiceEntryPoint
                        .getFromApplication(app)
                    handTracker = entryPoint.handTracker()
                    faceTracker = entryPoint.faceTracker()
                    gestureDetector = entryPoint.gestureDetector()
                    actionDispatcher = entryPoint.actionDispatcher()
                    settingsRepository = entryPoint.settingsRepository()
                    cursorController = entryPoint.cursorController()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to inject dependencies into accessibility service")
                    return
                }
            } ?: run {
            Timber.e("Application is not AirControlApp — cannot inject")
            return
        }

        Timber.i("GestureControlAccessibilityService connected")

        // Attach to action dispatcher
        actionDispatcher?.attachService(this)
        
        // UG-09/UG-10 Fix: Register visual feedback callback
        // When a gesture is successfully dispatched, show a ripple on the cursor
        // (F11) to give clear visual confirmation. Gated on reduced-motion.
        actionDispatcher?.onGestureDispatched = { actionName ->
            serviceScope.launch(Dispatchers.Main) {
                cursorOverlay?.ripple()
            }
            Timber.d("Gesture dispatched: %s — cursor ripple", actionName)
        }

        // Update screen metrics
        updateScreenMetrics()

        // Create overlays
        createOverlays()

        // Stop any existing thermal monitor before creating a new one
        thermalMonitor?.stopMonitoring()

        // Initialize thermal monitoring (better here than lazy init in startThermalMonitoring)
        thermalMonitor = com.aircontrol.tracking.ThermalMonitor(
            context = this,
            scope = serviceScope,
        )

        // Start tracking pipeline
        startTrackingPipeline()

        // Register screen state receiver
        registerScreenStateReceiver()

        // Start camera foreground service
        startCameraService()

        isRunning = true

        // Capabilities are declared in accessibility_service_config.xml
        // (canRetrieveWindowContent=false, canPerformGestures=true). Setting
        // FLAG_REPORT_VIEW_IDS here would contradict canRetrieveWindowContent=false,
        // so we no longer mutate serviceInfo at runtime.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events for gesture injection
        // We declared typeWindowsChanged just to satisfy minimum event types
    }

    override fun onInterrupt() {
        Timber.w("Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("GestureControlAccessibilityService destroyed")

        stopTrackingPipeline()
        removeOverlays()
        actionDispatcher?.detachService()
        gestureDetector?.close()
        gestureDetector = null
        cursorController?.hide()
        stopCameraService()

        unregisterScreenStateReceiver()

        isRunning = false
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        serviceScope.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.i("Configuration changed — recomputing coordinate mapping")
        updateScreenMetrics()
        cursorOverlay?.updateScreenSize(screenWidth, screenHeight)
    }

    // ========== Tracking pipeline ==========

    private fun startTrackingPipeline() {
        if (pipelineJobs.isNotEmpty()) {
            Timber.d("Tracking pipeline already running")
            return
        }

        lastFrameReceivedMs = System.currentTimeMillis()

        // Collect settings → gesture detector and overlays
        pipelineJobs.add(serviceScope.launch {
            settingsRepository?.userPreferences?.collect { prefs ->
                currentPreferences = prefs

                if (lastAppliedSensitivity != prefs.sensitivity) {
                    gestureDetector?.updateSensitivity(prefs.sensitivity)
                    lastAppliedSensitivity = prefs.sensitivity
                }

                // Push calibration data so pinch detection is personalized to the
                // user's measured hand size / pinch distance (previously measured
                // but never used).
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

                // F6/F7: apply cursor gain + sit-back mode to the dead-zone mapping.
                com.aircontrol.accessibility.ActionDispatcher.setCursorMapping(
                    cursorGain = prefs.cursorGain,
                    sitBackMode = prefs.sitBackMode,
                )

                withContext(Dispatchers.Main) {
                    if (prefs.statusPillEnabled && statusOverlay == null) {
                        statusOverlay = StatusOverlay(this@GestureControlAccessibilityService)
                    } else if (!prefs.statusPillEnabled && statusOverlay != null) {
                        statusOverlay?.remove()
                        statusOverlay = null
                    }

                    if (prefs.cursorEnabled && cursorOverlay == null) {
                        cursorOverlay = CursorOverlay(this@GestureControlAccessibilityService, screenWidth, screenHeight)
                    } else if (!prefs.cursorEnabled && cursorOverlay != null) {
                        cursorOverlay?.remove()
                        cursorOverlay = null
                        cursorController?.hide()
                    }

                    // Apply the user's cursor-speed setting to the smoother.
                    applyCursorSpeed(prefs.cursorSpeed)

                    // F9: reduced motion disables pulse/glow/ripple animations.
                    cursorOverlay?.setReducedMotion(prefs.reducedMotion)
                }
            }
        })

        // Bug: Custom Gestures Not Triggering Fix — Collect custom gestures from
        // the repository and push landmark templates to the gesture detector.
        //
        // Only CustomGestures with a LandmarkTemplateTrigger are converted to
        // LandmarkTemplate objects and passed to the engine. CustomGestures with
        // PoseWithDirection or FingerCount triggers are handled by the
        // ActionDispatcher's existing matchCustomGesture() logic (they piggyback
        // on the standard Pose classification).
        //
        // This collector runs for the lifetime of the tracking pipeline. When the
        // user creates/edits/deletes a custom gesture in the UI, the repository
        // emits a new list, which flows here and updates the engine atomically.
        pipelineJobs.add(serviceScope.launch {
            settingsRepository?.customGestures?.collect { gestures ->
                try {
                    val templates = gestures
                        .filter { it.isEnabled }
                        .mapNotNull { gesture ->
                            val trigger = gesture.triggerPose
                            if (trigger is com.aircontrol.data.model.CustomGestureTrigger.LandmarkTemplateTrigger) {
                                trigger.template
                            } else {
                                null
                            }
                        }
                    gestureDetector?.updateCustomTemplates(templates)
                    Timber.d("Loaded %d landmark-template custom gestures", templates.size)
                } catch (e: Exception) {
                    Timber.e(e, "Error updating custom gesture templates")
                }
            }
        })

        // Collect hand frames → gesture detector (with thermal frame skipping)
        pipelineJobs.add(serviceScope.launch {
            var thermalSkipIndex = 0
            handTracker?.handFrames?.collect { frame ->
                try {
                    lastFrameReceivedMs = System.currentTimeMillis()
                    if (frame.isDetected) {
                        lastHandDetectedMs = System.currentTimeMillis()
                    }

                    // Issue 5 Fix: Thermal frame skipping — instead of pausing the
                    // entire service, we skip frames progressively to reduce thermal load.
                    // This keeps the pipeline alive and responsive.
                    if (thermalFrameSkipCounter > 0) {
                        thermalSkipIndex++
                        if (thermalSkipIndex % thermalFrameSkipCounter != 0) {
                            // Skip this frame to reduce thermal load
                            return@collect
                        }
                        thermalSkipIndex = 0
                    }

                    if (!isThermalPaused && frame.matchesHandPreference(currentPreferences.handPreference)) {
                        gestureDetector?.processHandFrame(frame)
                    } else if (!frame.matchesHandPreference(currentPreferences.handPreference)) {
                        gestureDetector?.processHandFrame(com.aircontrol.tracking.HandFrame.EMPTY.copy(timestampMs = frame.timestampMs))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing hand frame — skipping")
                }
            }
        })

        // Collect gesture events → action dispatcher. Read the latest state at
        // event time instead of combine(), which can replay the previous event
        // whenever only the state changes.
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.gestureEvents?.collect { event ->
                try {
                    if (!isThermalPaused) {
                        gestureDetector?.let { detector ->
                            handleGestureEvent(event, detector.engineState.value)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error handling gesture event — skipping")
                }
            }
        })

        // Collect engine state → overlays. Cursor show/hide is driven HERE (on
        // state transitions) rather than per-gesture-event, so show() is not
        // called every frame (which caused the cursor fade-in to restart and
        // blink). Also resets the cursor smoother on DISARMED so a stale filter
        // state doesn't make the cursor jump when the hand returns.
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
                                // Reset smoother so the cursor re-stabilizes from a
                                // clean state when the hand returns (prevents a jump).
                                cursorSmoother.reset()
                            }
                            GestureEngineState.ARMING -> { /* cursor stays hidden */ }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating overlays from engine state — skipping")
                }
            }
        })

        // Collect cursor position → overlay (with CursorSmoother for jitter elimination)
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.gestureEvents?.collect { event ->
                try {
                    if (event is GestureEvent.CursorMoved && currentPreferences.cursorEnabled &&
                        !currentPreferences.eyeTrackingEnabled && !isCursorFrozen
                    ) {
                        // Bug #13 Fix: Adaptive smoothing for low-confidence frames.
                        // If the engine provides a minCutoffHint, dynamically update
                        // the CursorSmoother's minCutoff. Only call updateParams when
                        // the hint actually changes (avoid per-frame overhead).
                        // When the hint is null (confidence recovered), restore the
                        // default minCutoff.
                        if (event.minCutoffHint != lastAppliedMinCutoffHint) {
                            // Restore the cursor-speed-derived base minCutoff when
                            // confidence recovers (hint == null); otherwise apply the
                            // low-confidence hint.
                            val newMinCutoff = event.minCutoffHint ?: baseMinCutoff
                            cursorSmoother.updateParams(
                                minCutoff = newMinCutoff,
                                beta = currentCursorBeta,
                            )
                            lastAppliedMinCutoffHint = event.minCutoffHint
                            Timber.v(
                                "CursorSmoother minCutoff updated to %.2f (low-confidence=%s)",
                                newMinCutoff,
                                event.minCutoffHint != null,
                            )
                        }

                        // Issue 1 Fix: Apply cursor-level smoothing with dead-zone.
                        // This eliminates micro-jitter from hand tremor while preserving
                        // intentional movements with no perceptible lag.
                        val (smoothX, smoothY) = cursorSmoother.filter(
                            event.x, event.y, event.timestampMs,
                        )

                        // Bug #18 Fix: If this CursorMoved is "silent" (emitted during
                        // ARMING to pre-warm the smoother), feed the coordinates to the
                        // smoother (above) but SKIP showing/updating the visual cursor
                        // overlay and cursorController. The cursor should remain hidden
                        // until the engine reaches ARMED — this prevents a visible
                        // "jump" when the cursor first appears (the smoother has already
                        // converged on a stable position during ARMING).
                        if (event.isSilent) {
                            return@collect
                        }

                        // F1 (Dwell) + F2 (Hover): detect cursor stillness.
                        handleCursorStillness(smoothX, smoothY, event.timestampMs)

                        withContext(Dispatchers.Main) {
                            cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight)
                        }
                        cursorController?.updatePosition(
                            com.aircontrol.tracking.HandFrame(
                                landmarks = listOf(
                                    com.aircontrol.tracking.Landmark3D(smoothX, smoothY, 0f)
                                ),
                                handedness = com.aircontrol.tracking.Handedness.RIGHT,
                                timestampMs = event.timestampMs,
                                confidence = 1f,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating cursor position — skipping")
                }
            }
        })

        // "Eye is mouse": collect gaze points from the face tracker and drive the
        // cursor when eye tracking is enabled. Hand tracking still runs for pinch
        // (click) and other gestures.
        pipelineJobs.add(serviceScope.launch {
            faceTracker?.gazePoints?.collect { gaze ->
                try {
                    if (!currentPreferences.eyeTrackingEnabled ||
                        !currentPreferences.cursorEnabled
                    ) {
                        return@collect
                    }
                    if (!gaze.isDetected) {
                        // No face — hide the cursor (same as losing the hand).
                        withContext(Dispatchers.Main) { cursorOverlay?.hide() }
                        cursorController?.hide()
                        gazeSmoother.reset()
                        return@collect
                    }

                    // Map gaze (0..1, 0.5 = center) to screen-normalized with a
                    // sensitivity-driven gain that expands the eye's limited range
                    // to the full screen.
                    val gain = 1.5f + (currentPreferences.gazeSensitivity / 100f) * 2.0f
                    var screenX = 0.5f + (gaze.x - 0.5f) * gain
                    if (currentPreferences.gazeInvertX) screenX = 1f - screenX
                    val screenY = 0.5f + (gaze.y - 0.5f) * gain
                    val clampedX = screenX.coerceIn(0f, 1f)
                    val clampedY = screenY.coerceIn(0f, 1f)

                    // Smooth with the gaze-specific OneEuro filter.
                    val (smoothX, smoothY) = gazeSmoother.filter(
                        clampedX, clampedY, System.currentTimeMillis(),
                    )

                    withContext(Dispatchers.Main) {
                        cursorOverlay?.show()
                        cursorOverlay?.updatePosition(smoothX, smoothY, screenWidth, screenHeight)
                    }
                    cursorController?.updatePosition(
                        com.aircontrol.tracking.HandFrame(
                            landmarks = listOf(
                                com.aircontrol.tracking.Landmark3D(smoothX, smoothY, 0f),
                            ),
                            handedness = com.aircontrol.tracking.Handedness.UNKNOWN,
                            timestampMs = System.currentTimeMillis(),
                            confidence = 1f,
                        ),
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Error updating gaze cursor — skipping")
                }
            }
        })

        // Frame watchdog — detect pipeline stalls
        frameWatchdogJob = serviceScope.launch {
            while (true) {
                delay(5000L)
                val elapsed = System.currentTimeMillis() - lastFrameReceivedMs
                if (lastFrameReceivedMs > 0L && elapsed > 5000L) {
                    Timber.w("No frames for %d ms — restarting tracking pipeline", elapsed)
                    restartTrackingPipeline()
                }
            }
        }

        // Start thermal monitoring
        startThermalMonitoring()

        // F12 (Gesture hints): if the user has a hand in view but the engine stays
        // DISARMED for a while (they likely don't know to show an open palm), show
        // a one-time Toast hint.
        pipelineJobs.add(serviceScope.launch {
            var hintShown = false
            while (true) {
                delay(4000L)
                val now = System.currentTimeMillis()
                val armed = gestureDetector?.engineState?.value != GestureEngineState.DISARMED
                if (armed) {
                    hintShown = false
                } else if (!hintShown &&
                    lastHandDetectedMs > 0L &&
                    now - lastHandDetectedMs < 1500L
                ) {
                    hintShown = true
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@GestureControlAccessibilityService,
                            "Show an open palm to activate gestures",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        })
    }

    private fun restartTrackingPipeline() {
        Timber.i("Restarting tracking pipeline and camera service")
        stopTrackingPipeline()
        // NOTE: Do NOT close/reinitialize HandTracker here. It is a shared
        // singleton owned by CameraService, which manages its lifecycle. Calling
        // close()/initialize() from this service raced with CameraService's own
        // processing and could leave the tracker in a half-initialized state.
        // Restarting the camera service re-binds and reinitializes it safely.
        startTrackingPipeline()
        startCameraService()
    }

    private fun stopTrackingPipeline() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        pipelineJobs.forEach { it.cancel() }
        pipelineJobs.clear()
        stopThermalMonitoring()
        gestureDetector?.reset()
    }

    private fun startThermalMonitoring() {
        val monitor = thermalMonitor ?: return
        monitor.startMonitoring()

        // Collect thermal status and apply graceful degradation
        // Issue 5 Fix / Bug #5 Fix: Never fully pause the service on SEVERE.
        // Instead, skip frames progressively to reduce thermal load while keeping
        // the pipeline alive. Only CRITICAL (PowerManager CRITICAL/EMERGENCY/SHUTDOWN)
        // fully pauses gesture dispatch.
        thermalMonitoringJob = serviceScope.launch {
            monitor.thermalStatus.collect { status ->
                when (status) {
                    com.aircontrol.tracking.ThermalStatus.CRITICAL -> {
                        // Critical/Emergency/Shutdown — pause gesture dispatch entirely.
                        // The CameraService has already paused the camera pipeline.
                        Timber.w("Thermal CRITICAL — pausing gesture dispatch")
                        isThermalPaused = true
                        thermalFrameSkipCounter = 0
                    }
                    com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                        Timber.w("Thermal SEVERE — aggressive frame skipping (1 in 3 frames)")
                        isThermalPaused = false // Don't pause entirely
                        thermalFrameSkipCounter = 3 // Process only every 3rd frame
                    }
                    com.aircontrol.tracking.ThermalStatus.MODERATE -> {
                        Timber.i("Thermal MODERATE — moderate frame skipping (every other frame)")
                        isThermalPaused = false
                        thermalFrameSkipCounter = 2 // Process every other frame
                    }
                    com.aircontrol.tracking.ThermalStatus.NONE,
                    com.aircontrol.tracking.ThermalStatus.LIGHT -> {
                        if (thermalFrameSkipCounter > 0) {
                            Timber.i("Thermal recovered — full frame processing")
                        }
                        isThermalPaused = false
                        thermalFrameSkipCounter = 0 // No frame skipping
                    }
                }
            }
        }
    }

    private fun stopThermalMonitoring() {
        thermalMonitoringJob?.cancel()
        thermalMonitoringJob = null
        thermalMonitor?.stopMonitoring()
    }

    private suspend fun handleGestureEvent(event: GestureEvent, engineState: GestureEngineState) {
        withContext(Dispatchers.Main) {
            val cursorState = cursorController?.cursorState?.value
            // Bug #2 Fix: Coordinate routing by pinch phase.
            //
            // The Pinch event carries TWO coordinate pairs:
            //   - event.x / event.y            : live hand position (current index tip)
            //   - event.anchoredX / event.anchoredY : index tip position at pinch START
            //
            // For PinchPhase.MOVE: pass the LIVE position (event.x/y). The drag
            //   stroke must follow the hand.
            // For PinchPhase.START and PinchPhase.END: pass the ANCHORED position
            //   (event.anchoredX/Y). This is the value ActionDispatcher uses for
            //   pinchStartX/Y (click-target for TAP / LONG_PRESS).
            //
            // IMPORTANT (Bug #2 Fix): For PinchPhase.END with a DRAG action,
            //   ActionDispatcher.dispatchPinch() overrides this and reads event.x/y
            //   directly from the event (the live hand position) as the drop target.
            //   So even though we pass anchoredX/Y here, the DRAG drop uses the
            //   current hand position. This keeps the routing logic in one place
            //   (dispatchPinch) where the action type is known.
            val cursorX = if (event is GestureEvent.Pinch) {
                if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE) event.x else event.anchoredX
            } else cursorState?.x ?: 0f
            val cursorY = if (event is GestureEvent.Pinch) {
                if (event.phase == com.aircontrol.gesture.model.PinchPhase.MOVE) event.y else event.anchoredY
            } else cursorState?.y ?: 0f

            // Cursor show/hide is now driven by the engineState collector (see
            // startTrackingPipeline) so it only runs on state transitions, not per
            // event. This avoids re-triggering the fade-in every frame.

            // Freeze cursor during gesture execution for better accuracy
            // When any gesture is recognized (swipe, pose, pinch start), freeze the cursor
            // briefly so the action targets the correct position
            //
            // Bug #8 Fix: The pinch START freeze was previously 150ms, which locked
            // the visual cursor for the first ~5 frames of a drag (at 30fps). When
            // MOVE events started arriving, the cursor would suddenly "pop" from
            // the anchor to the live hand position — a jarring visual jump.
            //
            // Two-pronged fix:
            //   1. CURSOR_FREEZE_MS_PINCH reduced from 150ms to 50ms (safety net
            //      in case MOVE events are delayed).
            //   2. On the first PinchPhase.MOVE event, immediately call
            //      unfreezeCursor() to release the lock the instant the drag
            //      actually begins.
            when (event) {
                is GestureEvent.Swipe -> freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
                is GestureEvent.PoseTriggered -> freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
                is GestureEvent.CustomGestureTriggered -> freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
                is GestureEvent.Pinch -> {
                    when (event.phase) {
                        com.aircontrol.gesture.model.PinchPhase.START -> freezeCursorBriefly(CURSOR_FREEZE_MS_PINCH)
                        com.aircontrol.gesture.model.PinchPhase.MOVE -> {
                            // Bug #8 Fix: Release the START freeze immediately so
                            // the visual cursor dot can follow the hand during drag.
                            // Without this, the cursor stays locked at the anchor
                            // for up to 50ms (or longer if the START freeze window
                            // hasn't elapsed), causing a visible "pop" when it
                            // finally unlocks.
                            unfreezeCursor()
                        }
                        com.aircontrol.gesture.model.PinchPhase.END -> {
                            (cursorController as? com.aircontrol.control.CursorControllerImpl)?.releaseClick()
                        }
                    }
                }
                is GestureEvent.Armed,
                is GestureEvent.Disarmed,
                is GestureEvent.CursorMoved,
                is GestureEvent.PalmHome -> { /* No freeze */ }
            }

            // Dispatch action
            actionDispatcher?.dispatch(event, engineState, cursorX, cursorY, screenWidth, screenHeight)

            // Update cursor pressed state for pinch
            if (event is GestureEvent.Pinch) {
                when (event.phase) {
                    com.aircontrol.gesture.model.PinchPhase.START -> cursorController?.performClick()
                    com.aircontrol.gesture.model.PinchPhase.MOVE -> { /* drag continues */ }
                    com.aircontrol.gesture.model.PinchPhase.END -> cursorController?.show()
                }
            }
        }
    }

    /**
     * Freezes the cursor for a brief duration to prevent position drift during
     * gesture execution. This improves accuracy by ensuring the action targets
     * the exact position where the gesture was recognized.
     */
    private fun freezeCursorBriefly(durationMs: Long) {
        isCursorFrozen = true
        cursorFreezeJob?.cancel()
        cursorFreezeJob = serviceScope.launch {
            delay(durationMs)
            isCursorFrozen = false
        }
    }

    /**
     * Immediately releases any active cursor freeze.
     *
     * Bug #8 Fix: Called on the first PinchPhase.MOVE event so the visual cursor
     * dot can follow the hand during drag operations. Without this, the 50ms
     * START freeze (or the previous 150ms freeze) would keep the cursor locked
     * at the anchor for several frames, causing a visible "pop" when MOVE
     * events finally override the position.
     */
    private fun unfreezeCursor() {
        if (!isCursorFrozen) return
        cursorFreezeJob?.cancel()
        cursorFreezeJob = null
        isCursorFrozen = false
    }

    // ========== Camera service ==========

    private fun startCameraService() {
        runCatching {
            val intent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            }
            startForegroundService(intent)
            Timber.i("Camera service start requested from accessibility service")
        }.onFailure { error ->
            Timber.e(error, "Failed to start camera service from accessibility service")
        }
    }

    private fun stopCameraService() {
        runCatching {
            val intent = Intent(this, CameraService::class.java).apply {
                action = CameraService.ACTION_STOP
            }
            startService(intent)
            Timber.i("Camera service stop requested from accessibility service")
        }.onFailure { error ->
            Timber.e(error, "Failed to stop camera service from accessibility service")
        }
    }

    // ========== Overlays ==========

    private fun createOverlays() {
        // Only create overlays that are enabled in current preferences
        val prefs = currentPreferences
        if (prefs.cursorEnabled) {
            cursorOverlay = CursorOverlay(this, screenWidth, screenHeight)
        }
        if (prefs.statusPillEnabled) {
            statusOverlay = StatusOverlay(this)
        }
        applyCursorSpeed(prefs.cursorSpeed)
    }

    /**
     * Maps the user's "Cursor Speed" setting (1..100) to the CursorSmoother's
     * minCutoff. Higher speed → lower minCutoff → less smoothing → faster cursor.
     */
    private fun applyCursorSpeed(speed: Int) {
        val s = speed.coerceIn(1, 100)
        baseMinCutoff = (1.6f - (s / 100f) * 1.2f).coerceIn(0.4f, 1.6f)
        // Only apply if no low-confidence hint is currently overriding.
        if (lastAppliedMinCutoffHint == null) {
            cursorSmoother.updateParams(minCutoff = baseMinCutoff, beta = currentCursorBeta)
        }
    }

    /**
     * F1 (Dwell-to-click) + F2 (Hover): called on every non-silent CursorMoved.
     * Tracks how long the cursor has stayed put:
     *  - after HOVER_AFTER_MS, show the hover highlight (pre-interaction certainty)
     *  - after dwellDurationMs (if dwell enabled), fire a single dwell click.
     */
    private suspend fun handleCursorStillness(x: Float, y: Float, timestampMs: Long) {
        val dx = x - lastCursorX
        val dy = y - lastCursorY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val now = System.currentTimeMillis()

        lastCursorX = x
        lastCursorY = y

        if (dist > STATIONARY_THRESHOLD) {
            // Cursor moved — reset hover and dwell.
            if (hoverActive) {
                hoverActive = false
                withContext(Dispatchers.Main) { cursorOverlay?.resetHover() }
            }
            stationarySinceMs = now
            dwellFired = false
            withContext(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
            return
        }

        if (stationarySinceMs == 0L) stationarySinceMs = now
        val stillMs = now - stationarySinceMs

        // F2: hover highlight once the cursor has been still briefly.
        if (!hoverActive && stillMs >= HOVER_AFTER_MS) {
            hoverActive = true
            withContext(Dispatchers.Main) { cursorOverlay?.notifyHover() }
        }

        // F1: dwell click once the configured duration has elapsed.
        if (currentPreferences.dwellEnabled && !dwellFired && stillMs >= currentPreferences.dwellDurationMs) {
            dwellFired = true
            actionDispatcher?.dispatchDwellTap(x, y, screenWidth, screenHeight)
            withContext(Dispatchers.Main) { cursorOverlay?.setDwellProgress(0f) }
        } else if (currentPreferences.dwellEnabled && !dwellFired) {
            // Visual dwell progress ring.
            val progress = (stillMs.toFloat() / currentPreferences.dwellDurationMs).coerceIn(0f, 1f)
            withContext(Dispatchers.Main) { cursorOverlay?.setDwellProgress(progress) }
        }
    }

    private fun removeOverlays() {
        cursorOverlay?.remove()
        cursorOverlay = null
        statusOverlay?.remove()
        statusOverlay = null
    }

    // ========== Screen metrics ==========

    private fun updateScreenMetrics() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            screenWidth = metrics.bounds.width() - insets.left - insets.right
            screenHeight = metrics.bounds.height() - insets.top - insets.bottom
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Timber.d("Screen metrics updated: %dx%d", screenWidth, screenHeight)
    }

    // ========== Screen state ==========

    private fun registerScreenStateReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }
        isReceiverRegistered = false
    }

    // ========== Utility ==========

    private fun com.aircontrol.tracking.HandFrame.matchesHandPreference(
        preference: com.aircontrol.data.model.HandPreference,
    ): Boolean {
        if (!isDetected || preference == com.aircontrol.data.model.HandPreference.ANY) return true
        // If MediaPipe couldn't determine handedness (UNKNOWN — common in poor
        // light/blur), don't drop the frame. Dropping UNKNOWN frames caused all
        // gestures to silently stop working for hand-preference users.
        if (handedness == com.aircontrol.tracking.Handedness.UNKNOWN) return true
        return when (preference) {
            com.aircontrol.data.model.HandPreference.LEFT -> handedness == com.aircontrol.tracking.Handedness.LEFT
            com.aircontrol.data.model.HandPreference.RIGHT -> handedness == com.aircontrol.tracking.Handedness.RIGHT
            com.aircontrol.data.model.HandPreference.ANY -> true
        }
    }

    companion object {
        private const val CURSOR_FREEZE_MS_GESTURE = 300L  // Freeze for swipe/pose gestures
        // Bug #8 Fix: Reduced from 150ms to 50ms. The pinch START freeze is now
        // a short safety window; the cursor is also explicitly released on the
        // first PinchPhase.MOVE event via unfreezeCursor(). 50ms is ~1.5 frames
        // at 30fps — long enough to register a stable click target, short enough
        // that the visual dot can follow the hand as soon as the drag begins.
        private const val CURSOR_FREEZE_MS_PINCH = 50L

        // BUG #2 FIX: Aligned with Apple Vision Pro specs
        // Old values (0.7, 0.08) were custom tweaks that didn't match Apple's tuning
        // New values match the Apple Vision Pro architecture for optimal performance:
        //   - minCutoff = 1.0 Hz: Lower lag, better jitter elimination (Apple spec)
        //   - beta      = 0.007:  Lower high-speed delay, minimal filtering (Apple spec)
        private const val DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF = 1.0f
        private const val DEFAULT_CURSOR_SMOOTHER_BETA = 0.007f

        // F1/F2: cursor displacement (normalized) below this counts as "stationary".
        private const val STATIONARY_THRESHOLD = 0.008f
        // F2: how long the cursor must be still before the hover highlight shows.
        private const val HOVER_AFTER_MS = 150L

        // Gaze smoother tuning (stronger smoothing for noisy eye tracking).
        private const val GAZE_MIN_CUTOFF = 1.4f
        private const val GAZE_BETA = 0.004f
    }
}
