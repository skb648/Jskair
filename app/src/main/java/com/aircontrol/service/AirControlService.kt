package com.aircontrol.service

import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.HandTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

interface AirControlService {
    val isRunning: Boolean
    val currentGestureLabel: StateFlow<String>
    suspend fun start()
    suspend fun stop()
}

/**
 * Lightweight coordinator used by UI/tests. The production camera ownership is
 * handled by CameraService; callers should not run this at the same time as the
 * foreground camera service.
 */
@Singleton
class AirControlServiceImpl @Inject constructor(
    private val handTracker: HandTracker,
    private val gestureDetector: GestureDetector,
) : AirControlService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Collections.synchronizedList(mutableListOf<Job>())

    private val _isRunning = AtomicBoolean(false)
    override val isRunning: Boolean get() = _isRunning.get()

    private val _currentGestureLabel = MutableStateFlow("")
    override val currentGestureLabel: StateFlow<String> = _currentGestureLabel

    override suspend fun start() {
        if (_isRunning.get()) {
            Timber.w("AirControl service already running")
            return
        }
        Timber.i("Starting AirControl service")
        try {
            handTracker.initialize()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize hand tracker")
            _isRunning.set(false)
            return
        }
        _isRunning.set(true)

        // Collect hand frames and feed to gesture detector
        jobs.add(scope.launch {
            handTracker.handFrames.collect { frame ->
                try {
                    gestureDetector.processHandFrame(frame)
                } catch (e: Exception) {
                    Timber.e(e, "Error processing hand frame — skipping")
                }
            }
        })

        // Collect gesture events for label updates
        jobs.add(scope.launch {
            gestureDetector.gestureEvents.collect { event ->
                try {
                    val label = when (event) {
                        is com.aircontrol.gesture.model.GestureEvent.Swipe ->
                            "Swipe ${event.direction}"
                        is com.aircontrol.gesture.model.GestureEvent.Pinch ->
                            "Pinch ${event.phase}"
                        is com.aircontrol.gesture.model.GestureEvent.PoseTriggered ->
                            event.pose.name
                        is com.aircontrol.gesture.model.GestureEvent.CustomGestureTriggered ->
                            "Custom: ${event.gestureName}"
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
        })

        Timber.i("AirControl service started")
    }

    override suspend fun stop() {
        if (!_isRunning.get()) {
            Timber.w("AirControl service not running")
            return
        }
        Timber.i("Stopping AirControl service")
        jobs.forEach { it.cancel() }
        jobs.clear()
        handTracker.close()
        gestureDetector.reset()
        _isRunning.set(false)
        _currentGestureLabel.value = ""
        Timber.i("AirControl service stopped")
    }

    /**
     * Resets internal state without cancelling the scope.
     * Useful when the singleton is reused after a stop.
     */
    fun reset() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        _isRunning.set(false)
        _currentGestureLabel.value = ""
        Timber.i("AirControl service reset")
    }
}
