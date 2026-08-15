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

    // Cursor smoothing uses the centralized CursorSmoother defaults below.
    // Keeping the description next to the actual constants prevents tuning comments
    // from drifting away from the runtime configuration.
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
