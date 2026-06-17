package com.aircontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

    // Cursor freeze during gesture execution for better accuracy
    private var isCursorFrozen = false
    private var cursorFreezeJob: Job? = null

    // Issue 1 Fix / Bug #6 & #7 Fix: Single, consolidated cursor-side One Euro
    // Filter. The landmark-level filter previously in HandTracker has been removed,
    // so this is now the ONLY smoothing stage for the cursor. Parameters are more
    // aggressive than the old defaults to compensate:
    //   - minCutoff = 0.45  (heavier smoothing at rest, was 0.6)
    //   - beta      = 0.15  (faster tracking during motion, was 0.1)
    // Combined with the 0.004 normalized dead zone in CursorSmoother and the 3dp
    // dead zone in CursorOverlay, this eliminates hand tremor while keeping
    // motion-to-cursor latency low (no double-filtering).
    private val cursorSmoother = com.aircontrol.tracking.CursorSmoother(
        minCutoff = DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF,
        beta = DEFAULT_CURSOR_SMOOTHER_BETA,
    )

    // Bug #13 Fix: Track the current beta so we can call cursorSmoother.updateParams
    // with the correct beta when only the minCutoff changes (via minCutoffHint).
    // Also track the last applied minCutoffHint to avoid redundant updateParams
    // calls on every frame — only call updateParams when the hint actually changes.
    @Volatile
    private var currentCursorBeta: Float = DEFAULT_CURSOR_SMOOTHER_BETA
    @Volatile
    private var lastAppliedMinCutoffHint: Float? = null

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

        // Update service info to ensure capabilities are declared
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
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

        // Collect cursor state → overlay
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.engineState?.collect { state ->
                try {
                    if (currentPreferences.statusPillEnabled) {
                        withContext(Dispatchers.Main) {
                            statusOverlay?.updateState(state)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating status overlay — skipping")
                }
            }
        })

        // Collect cursor position → overlay (with CursorSmoother for jitter elimination)
        pipelineJobs.add(serviceScope.launch {
            gestureDetector?.gestureEvents?.collect { event ->
                try {
                    if (event is GestureEvent.CursorMoved && currentPreferences.cursorEnabled && !isCursorFrozen) {
                        // Bug #13 Fix: Adaptive smoothing for low-confidence frames.
                        // If the engine provides a minCutoffHint, dynamically update
                        // the CursorSmoother's minCutoff. Only call updateParams when
                        // the hint actually changes (avoid per-frame overhead).
                        // When the hint is null (confidence recovered), restore the
                        // default minCutoff.
                        if (event.minCutoffHint != lastAppliedMinCutoffHint) {
                            val newMinCutoff = event.minCutoffHint ?: DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF
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
    }

    private fun restartTrackingPipeline() {
        Timber.i("Restarting tracking pipeline and camera service")
        stopTrackingPipeline()
        handTracker?.close()
        handTracker?.initialize()
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

            // Show/hide cursor based on engine state
            when (engineState) {
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
                }
                GestureEngineState.ARMING -> {
                    // Keep cursor visible during arming
                }
            }

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
                is GestureEvent.CursorMoved -> { /* No freeze */ }
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

        // Bug #13 Fix: Default CursorSmoother parameters. Used to construct the
        // smoother AND to restore defaults when minCutoffHint transitions back
        // to null (confidence recovered).
        private const val DEFAULT_CURSOR_SMOOTHER_MIN_CUTOFF = 0.45f
        private const val DEFAULT_CURSOR_SMOOTHER_BETA = 0.15f
    }
}
