package com.aircontrol.gestures

import com.aircontrol.gesture.GestureEngine
import com.aircontrol.gesture.detection.DynamicGestureDetector
import com.aircontrol.gesture.config.GestureEngineConfig
import com.aircontrol.gesture.model.GestureEngineState
import com.aircontrol.gesture.model.GestureEvent
import com.aircontrol.gesture.model.HandGestureSnapshot
import com.aircontrol.gesture.model.HandInput
import com.aircontrol.gesture.model.LandmarkTemplate
import com.aircontrol.gesture.model.MultiHandInput
import com.aircontrol.gesture.model.Pose
import com.aircontrol.tracking.HandFrame
import com.aircontrol.tracking.TrackedHand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/**
 * Bridges the Android tracking layer (HandFrame from MediaPipe)
 * with the pure-Kotlin gesture engine (HandInput / MultiHandInput).
 *
 * Converts [HandFrame] → [MultiHandInput] and exposes the engine's
 * [GestureEvent] flow and [GestureEngineState] for UI consumption.
 */
interface GestureDetector : AutoCloseable {
    /** Lossless ordered semantic events. */
    val gestureEvents: SharedFlow<GestureEvent>
    /** Latest-wins continuous cursor positions. */
    val cursorEvents: SharedFlow<GestureEvent.CursorMoved>
    val engineState: StateFlow<GestureEngineState>
    val currentPose: StateFlow<Pose>
    val armingProgress: StateFlow<Float>
    /** True while hand tracking confidence is too low to trust poses/pinches. */
    val lowConfidence: StateFlow<Boolean>
    /** Normalized pinch proximity (0.0 = open, 1.0 = fingertips touching). */
    val pinchProximity: StateFlow<Float>
    /** Independent per-hand gesture state snapshots keyed by trackId. */
    val perHandStates: StateFlow<Map<Int, HandGestureSnapshot>>
        get() = MutableStateFlow<Map<Int, HandGestureSnapshot>>(emptyMap())
    fun processHandFrame(frame: HandFrame)
    /** Suspending variant used by the realtime service so semantic backpressure never blocks a thread. */
    suspend fun processHandFrameSuspending(frame: HandFrame)
    fun updateSensitivity(sensitivity: Int)

    /**
     * Fix A-11: whether a swipe requires the open-palm pose. Forwarded to the
     * engine so that moving the cursor can never scroll the page.
     */
    fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean)

    /**
     * Bug: Custom Gestures Not Triggering Fix — Updates the dynamic list of
     * user-defined landmark templates that the engine matches against live
     * hand frames.
     */
    fun updateCustomTemplates(templates: List<LandmarkTemplate>)

    /**
     * Personalizes pinch detection using user-measured calibration data.
     * Pass 0f (or handSizeMm <= 0) to clear calibration.
     */
    fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float)

    val swipeDebug: StateFlow<DynamicGestureDetector.SwipeDebugInfo?>
    val swipeRejectionCounts: StateFlow<Map<String, Int>>
    val swipeDebugLog: StateFlow<List<String>>

    fun reset()

    override fun close() {
        // Default no-op; implementations should cancel their coroutine scope
    }
}

@Singleton
class GestureDetectorImpl @Inject constructor() : GestureDetector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val engineRef = AtomicReference(GestureEngine(GestureEngineConfig()))
    private val engine: GestureEngine get() = engineRef.get()

    private val frameSerial = ReentrantLock()
    private var engineEventsJob: Job? = null
    @Volatile
    private var currentSensitivity: Int = 70

    /** Semantic events are already lossless/bounded in the pure gesture engine. */
    override val gestureEvents: SharedFlow<GestureEvent>
        get() = engine.semanticEvents

    /** Continuous cursor updates use latest-wins transport. */
    override val cursorEvents: SharedFlow<GestureEvent.CursorMoved>
        get() = engine.cursorEvents

    private val _engineState = MutableStateFlow(GestureEngineState.DISARMED)
    override val engineState: StateFlow<GestureEngineState> = _engineState.asStateFlow()

    private val _currentPose = MutableStateFlow(Pose.NONE)
    override val currentPose: StateFlow<Pose> = _currentPose.asStateFlow()

    private val _armingProgress = MutableStateFlow(0f)
    override val armingProgress: StateFlow<Float> = _armingProgress.asStateFlow()

    private val _lowConfidence = MutableStateFlow(false)
    override val lowConfidence: StateFlow<Boolean> = _lowConfidence.asStateFlow()

    private val _pinchProximity = MutableStateFlow(0f)
    override val pinchProximity: StateFlow<Float> = _pinchProximity.asStateFlow()

    private val _perHandStates = MutableStateFlow<Map<Int, HandGestureSnapshot>>(emptyMap())
    override val perHandStates: StateFlow<Map<Int, HandGestureSnapshot>> = _perHandStates.asStateFlow()

    private val debugInstrumentation = com.aircontrol.BuildConfig.DEBUG

    private val _swipeDebug = MutableStateFlow<DynamicGestureDetector.SwipeDebugInfo?>(null)
    override val swipeDebug: StateFlow<DynamicGestureDetector.SwipeDebugInfo?> = _swipeDebug.asStateFlow()

    private val _swipeRejections = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val swipeRejectionCounts: StateFlow<Map<String, Int>> = _swipeRejections.asStateFlow()

    private val _swipeLog = MutableStateFlow<List<String>>(emptyList())
    override val swipeDebugLog: StateFlow<List<String>> = _swipeLog.asStateFlow()

    private val swipeCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val swipeLog = ArrayDeque<String>()
    private var lastRecordedHoldReason: String? = null

    init {
        collectEngineEvents()
        if (debugInstrumentation) attachSwipeDecisionHook()
    }

    private fun attachSwipeDecisionHook() {
        engine.onSwipeDecision = { detected, direction, confidence, reason, _, _, timestampMs ->
            val outcome = if (detected) "COMMITTED ${direction?.name}" else "REJECTED ${reason ?: "UNCLAIMED"}"
            recordSwipeVerdict(
                "$outcome score=${(confidence * 100f).toInt() / 100f} at=$timestampMs",
                countKey = if (detected) null else "ENGINE:$outcome",
            )
        }
    }

    private fun publishSwipeDebug() {
        val info = engine.swipeDebugInfo()
        _swipeDebug.value = info
        val key = info.holdReason?.name
        if (key == lastRecordedHoldReason) return
        lastRecordedHoldReason = key
        if (key != null) recordSwipeVerdict(info.format(), countKey = key)
    }

    private fun recordSwipeVerdict(line: String, countKey: String?) {
        if (countKey != null) {
            val total = swipeCounters.computeIfAbsent(countKey) { AtomicInteger() }.incrementAndGet()
            _swipeRejections.value = swipeCounters.entries
                .associate { it.key to it.value.get() }
                .toSortedMap()
            recordSwipeLog("$line  x$total")
        } else {
            recordSwipeLog(line)
        }
    }

    /** Fixed-capacity, newest first: a session of any length cannot grow this. */
    private fun recordSwipeLog(line: String) {
        synchronized(swipeLog) {
            if (swipeLog.size == SWIPE_LOG_CAPACITY) swipeLog.removeLast()
            swipeLog.addFirst(line)
            _swipeLog.value = swipeLog.toList()
        }
        Timber.tag("SwipeIntent").d(line)
    }

    override fun processHandFrame(frame: HandFrame) {
        runBlocking { processHandFrameSuspending(frame) }
    }

    override suspend fun processHandFrameSuspending(frame: HandFrame) {
        frameSerial.lock()
        try {
            if (frame.hands.size <= 1 && frame.primaryTrackId != null) {
                val input = frame.toHandInput()
                engine.processFrameSuspending(input)
            } else {
                val multiInput = frame.toMultiHandInput()
                engine.processMultiHandFrameSuspending(multiInput)
            }

            // Forward state from engine while still owning the same serial turn.
            _engineState.value = engine.engineState.value
            _currentPose.value = engine.currentPose.value
            _armingProgress.value = engine.armingProgress.value
            _lowConfidence.value = engine.lowConfidence.value
            _pinchProximity.value = engine.pinchProximity.value
            _perHandStates.value = engine.perHandStates.value
            if (debugInstrumentation) publishSwipeDebug()
        } finally {
            frameSerial.unlock()
        }
    }

    override fun updateSensitivity(sensitivity: Int) {
        frameSerial.lock()
        try {
            val clamped = sensitivity.coerceIn(0, 100)
            if (clamped == currentSensitivity) return
            Timber.i("Updating gesture engine sensitivity to %d (without engine recreation)", clamped)
            currentSensitivity = clamped
            engine.updateSensitivity(clamped)
        } finally {
            frameSerial.unlock()
        }
    }

    override fun updateCustomTemplates(templates: List<LandmarkTemplate>) {
        frameSerial.lock()
        try { engine.updateCustomTemplates(templates.toList()) }
        finally { frameSerial.unlock() }
    }

    override fun updateSwipeRequiresOpenHand(requiresOpenHand: Boolean) {
        frameSerial.lock()
        try {
            Timber.i("Updating swipe pose gate: requiresOpenHand=%s", requiresOpenHand)
            engine.updateSwipeRequiresOpenHand(requiresOpenHand)
        } finally { frameSerial.unlock() }
    }

    override fun updateCalibration(handSizeMm: Float, pinchDistanceMm: Float) {
        frameSerial.lock()
        try { engine.updateCalibration(handSizeMm, pinchDistanceMm) }
        finally { frameSerial.unlock() }
    }

    override fun reset() {
        frameSerial.lock()
        try {
            engine.reset()
            resetStateFlows()
        } finally { frameSerial.unlock() }
        if (debugInstrumentation) clearSwipeDebug()
        Timber.d("Gesture detector reset")
    }

    private fun collectEngineEvents() {
        engineEventsJob?.cancel()
        engineEventsJob = null
    }

    private fun clearSwipeDebug() {
        lastRecordedHoldReason = null
        swipeCounters.clear()
        _swipeRejections.value = emptyMap()
        synchronized(swipeLog) {
            swipeLog.clear()
            _swipeLog.value = emptyList()
        }
    }

    private fun resetStateFlows() {
        _engineState.value = GestureEngineState.DISARMED
        _currentPose.value = Pose.NONE
        _lowConfidence.value = false
        _armingProgress.value = 0f
        _pinchProximity.value = 0f
        _perHandStates.value = emptyMap()
    }

    override fun close() {
        frameSerial.lock()
        try { engine.stop() } finally { frameSerial.unlock() }
        scope.cancel()
        Timber.d("GestureDetector closed and scope cancelled")
    }

    private fun HandFrame.toHandInput(): HandInput {
        val effectiveConfidence = if (isDetected) confidence else 0f
        return HandInput(
            landmarks = if (isDetected) {
                landmarks.map { lm ->
                    com.aircontrol.gesture.model.Landmark3D(
                        x = lm.x,
                        y = lm.y,
                        z = lm.z,
                    )
                }
            } else {
                emptyList()
            },
            handedness = when (handedness) {
                com.aircontrol.tracking.Handedness.LEFT -> com.aircontrol.gesture.model.Handedness.LEFT
                com.aircontrol.tracking.Handedness.RIGHT -> com.aircontrol.gesture.model.Handedness.RIGHT
                com.aircontrol.tracking.Handedness.UNKNOWN -> com.aircontrol.gesture.model.Handedness.UNKNOWN
            },
            timestampMs = timestampMs,
            confidence = effectiveConfidence,
            frameAspectRatio = frameAspectRatio,
            trackId = primaryTrackId ?: 0,
            isAmbiguous = hands.firstOrNull { it.trackId == primaryTrackId }?.isAmbiguous == true,
        )
    }

    private fun HandFrame.toMultiHandInput(): MultiHandInput {
        val mappedHands = hands.map { it.toHandInput() }
        return MultiHandInput(
            hands = mappedHands,
            primaryTrackId = primaryTrackId,
            timestampMs = timestampMs,
        )
    }

    private fun TrackedHand.toHandInput(): HandInput {
        val effectiveConfidence = if (isValid) confidence else 0f
        return HandInput(
            landmarks = if (isValid) {
                landmarks.map { lm ->
                    com.aircontrol.gesture.model.Landmark3D(
                        x = lm.x,
                        y = lm.y,
                        z = lm.z,
                    )
                }
            } else {
                emptyList()
            },
            handedness = when (handedness) {
                com.aircontrol.tracking.Handedness.LEFT -> com.aircontrol.gesture.model.Handedness.LEFT
                com.aircontrol.tracking.Handedness.RIGHT -> com.aircontrol.gesture.model.Handedness.RIGHT
                com.aircontrol.tracking.Handedness.UNKNOWN -> com.aircontrol.gesture.model.Handedness.UNKNOWN
            },
            timestampMs = timestampMs,
            confidence = effectiveConfidence,
            frameAspectRatio = frameAspectRatio,
            trackId = trackId,
            isAmbiguous = isAmbiguous,
        )
    }

    private companion object {
        /** Debug verdict history: 12 lines is what fits above the overlay, and it bounds memory. */
        const val SWIPE_LOG_CAPACITY = 12
    }
}
