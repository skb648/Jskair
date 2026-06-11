package com.aircontrol.service

import com.aircontrol.camera.CameraService
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.HandTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface AirControlService {
    val isRunning: Boolean
    val currentGestureLabel: StateFlow<String>
    suspend fun start()
    suspend fun stop()
}

@Singleton
class AirControlServiceImpl @Inject constructor(
    private val handTracker: HandTracker,
    private val gestureDetector: GestureDetector,
) : AirControlService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var _isRunning = false
    override val isRunning: Boolean get() = _isRunning

    private val _currentGestureLabel = MutableStateFlow("")
    override val currentGestureLabel: StateFlow<String> = _currentGestureLabel

    override suspend fun start() {
        if (_isRunning) {
            Timber.w("AirControl service already running")
            return
        }
        Timber.i("Starting AirControl service")
        handTracker.initialize()
        _isRunning = true

        // Collect hand frames and feed to gesture detector
        scope.launch {
            handTracker.handFrames.collect { frame ->
                try {
                    gestureDetector.processHandFrame(frame)
                } catch (e: Exception) {
                    Timber.e(e, "Error processing hand frame — skipping")
                }
            }
        }

        // Collect gesture events for label updates
        scope.launch {
            gestureDetector.gestureEvents.collect { event ->
                try {
                    val label = when (event) {
                        is com.aircontrol.gesture.model.GestureEvent.Swipe ->
                            "Swipe ${event.direction}"
                        is com.aircontrol.gesture.model.GestureEvent.Pinch ->
                            "Pinch ${event.phase}"
                        is com.aircontrol.gesture.model.GestureEvent.PoseTriggered ->
                            event.pose.name
                        is com.aircontrol.gesture.model.GestureEvent.Armed ->
                            "Armed"
                        is com.aircontrol.gesture.model.GestureEvent.Disarmed ->
                            "Disarmed"
                        is com.aircontrol.gesture.model.GestureEvent.CursorMoved ->
                            "" // Don't update label for cursor moves
                    }
                    if (label.isNotEmpty()) {
                        _currentGestureLabel.value = label
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing gesture event label — skipping")
                }
            }
        }

        Timber.i("AirControl service started")
    }

    override suspend fun stop() {
        if (!_isRunning) {
            Timber.w("AirControl service not running")
            return
        }
        Timber.i("Stopping AirControl service")
        handTracker.close()
        gestureDetector.reset()
        _isRunning = false
        _currentGestureLabel.value = ""
        Timber.i("AirControl service stopped")
    }
}
