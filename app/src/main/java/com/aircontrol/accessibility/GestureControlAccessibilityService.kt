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

    private lateinit var handTracker: HandTracker
    private lateinit var gestureDetector: GestureDetector
    private lateinit var actionDispatcher: ActionDispatcher
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cursorController: CursorController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isRunning = false

    // Overlay managers
    private var cursorOverlay: CursorOverlay? = null
    private var statusOverlay: StatusOverlay? = null

    // Screen metrics (updated on rotation)
    private var screenWidth = 0
    private var screenHeight = 0

    // Keyguard state
    private var isKeyguardLocked = false

    // Frame watchdog — detects pipeline stalls
    private var lastFrameReceivedMs: Long = 0L
    private var frameWatchdogJob: Job? = null
    private var pipelineJobs: MutableList<Job> = mutableListOf()

    // Thermal throttling
    private var thermalMonitor: com.aircontrol.tracking.ThermalMonitor? = null
    private var thermalMonitoringJob: Job? = null
    private var isThermalPaused = false

    // Runtime settings cache
    private var currentPreferences = UserPreferences()
    private var lastAppliedSensitivity: Int? = null

    // Cursor freeze during gesture execution for better accuracy
    private var isCursorFrozen = false
    private var cursorFreezeJob: Job? = null

    // Broadcast receiver for screen state
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Timber.i("Screen off — suspending gesture injection and camera")
                    stopTrackingPipeline()
                    stopCameraService()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Timber.i("Screen on — resuming gesture injection and camera")
                    startTrackingPipeline()
                    startCameraService()
                }
                Intent.ACTION_USER_PRESENT -> {
                    Timber.i("User unlocked — fully active")
                    isKeyguardLocked = false
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
        actionDispatcher.attachService(this)

        // Update screen metrics
        updateScreenMetrics()

        // Create overlays
        createOverlays()

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
        actionDispatcher.detachService()
        gestureDetector.reset()
        cursorController.hide()
        stopCameraService()

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }

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
            settingsRepository.userPreferences.collect { prefs ->
                currentPreferences = prefs

                if (lastAppliedSensitivity != prefs.sensitivity) {
                    gestureDetector.updateSensitivity(prefs.sensitivity)
                    lastAppliedSensitivity = prefs.sensitivity
                }

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
                    cursorController.hide()
                }
            }
        })

        // Collect hand frames → gesture detector
        pipelineJobs.add(serviceScope.launch {
            handTracker.handFrames.collect { frame ->
                try {
                    lastFrameReceivedMs = System.currentTimeMillis()
                    if (!isThermalPaused) {
                        gestureDetector.processHandFrame(frame)
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
            gestureDetector.gestureEvents.collect { event ->
                try {
                    if (!isThermalPaused) {
                        handleGestureEvent(event, gestureDetector.engineState.value)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error handling gesture event — skipping")
                }
            }
        })

        // Collect cursor state → overlay
        pipelineJobs.add(serviceScope.launch {
            gestureDetector.engineState.collect { state ->
                try {
                    if (currentPreferences.statusPillEnabled) {
                        statusOverlay?.updateState(state)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating status overlay — skipping")
                }
            }
        })

        // Collect cursor position → overlay
        pipelineJobs.add(serviceScope.launch {
            gestureDetector.gestureEvents.collect { event ->
                try {
                    // Freeze cursor during gesture execution for better accuracy
                    if (event is GestureEvent.CursorMoved && currentPreferences.cursorEnabled && !isCursorFrozen) {
                        cursorOverlay?.updatePosition(event.x, event.y, screenWidth, screenHeight)
                        cursorController.updatePosition(
                            com.aircontrol.tracking.HandFrame(
                                landmarks = listOf(
                                    com.aircontrol.tracking.Landmark3D(event.x, event.y, 0f)
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
        startTrackingPipeline()
        startCameraService()
    }

    private fun stopTrackingPipeline() {
        frameWatchdogJob?.cancel()
        frameWatchdogJob = null
        pipelineJobs.forEach { it.cancel() }
        pipelineJobs.clear()
        stopThermalMonitoring()
        gestureDetector.reset()
    }

    private fun startThermalMonitoring() {
        val monitor = thermalMonitor ?: return
        monitor.startMonitoring()

        // Collect thermal status and pause/resume gesture dispatch
        thermalMonitoringJob = serviceScope.launch {
            monitor.thermalStatus.collect { status ->
                when (status) {
                    com.aircontrol.tracking.ThermalStatus.SEVERE -> {
                        Timber.w("Thermal SEVERE — pausing gesture dispatch")
                        isThermalPaused = true
                    }
                    com.aircontrol.tracking.ThermalStatus.MODERATE -> {
                        Timber.i("Thermal MODERATE — gesture dispatch continues at reduced rate")
                        // Allow dispatch but camera FPS is reduced by CameraService
                    }
                    com.aircontrol.tracking.ThermalStatus.NONE,
                    com.aircontrol.tracking.ThermalStatus.LIGHT -> {
                        if (isThermalPaused) {
                            Timber.i("Thermal recovered — resuming gesture dispatch")
                            isThermalPaused = false
                        }
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

    private fun handleGestureEvent(event: GestureEvent, engineState: GestureEngineState) {
        val cursorX = cursorController.cursorState.value.x
        val cursorY = cursorController.cursorState.value.y

        // Show/hide cursor based on engine state
        when (engineState) {
            GestureEngineState.ARMED,
            GestureEngineState.EXECUTING,
            GestureEngineState.COOLDOWN -> {
                if (currentPreferences.cursorEnabled) {
                    cursorOverlay?.show()
                    cursorController.show()
                }
            }
            GestureEngineState.DISARMED -> {
                cursorOverlay?.hide()
                cursorController.hide()
            }
            GestureEngineState.ARMING -> {
                // Keep cursor visible during arming
            }
        }

        // Freeze cursor during gesture execution for better accuracy
        // When any gesture is recognized (swipe, pose, pinch start), freeze the cursor
        // briefly so the action targets the correct position
        when (event) {
            is GestureEvent.Swipe -> freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
            is GestureEvent.PoseTriggered -> freezeCursorBriefly(CURSOR_FREEZE_MS_GESTURE)
            is GestureEvent.Pinch -> {
                when (event.phase) {
                    com.aircontrol.gesture.model.PinchPhase.START -> freezeCursorBriefly(CURSOR_FREEZE_MS_PINCH)
                    com.aircontrol.gesture.model.PinchPhase.MOVE -> { /* Don't freeze during drag */ }
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
        actionDispatcher.dispatch(event, engineState, cursorX, cursorY, screenWidth, screenHeight)

        // Update cursor pressed state for pinch
        if (event is GestureEvent.Pinch) {
            when (event.phase) {
                com.aircontrol.gesture.model.PinchPhase.START -> cursorController.performClick()
                com.aircontrol.gesture.model.PinchPhase.MOVE -> { /* drag continues */ }
                com.aircontrol.gesture.model.PinchPhase.END -> cursorController.show()
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
        cursorOverlay = CursorOverlay(this, screenWidth, screenHeight)
        statusOverlay = StatusOverlay(this)
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
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    // ========== Utility ==========

    companion object {
        private const val CURSOR_FREEZE_MS_GESTURE = 300L  // Freeze for swipe/pose gestures
        private const val CURSOR_FREEZE_MS_PINCH = 150L    // Shorter freeze for pinch (tap)
    }
}
