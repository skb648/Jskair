package com.aircontrol.camera

/**
 * Actual camera/tracking lifecycle.  This is deliberately different from the
 * persisted/user intent: a session may be desired while it is waiting for the
 * camera, and it is not ACTIVE until CameraX has been bound and the analyzer is
 * accepting frames.
 */
enum class TrackingState {
    STOPPED,
    STARTING,
    WAITING_FOR_CAMERA,
    RUNNING,
    PAUSING,
    PAUSED,
    CAMERA_LOST,
    STOPPING,
    FAILED,
}

/** Events accepted by the lifecycle owner. Callbacks report events; they do not
 * perform a start/stop transition themselves. */
enum class TrackingLifecycleEvent {
    USER_ENABLE,
    USER_DISABLE,
    SYSTEM_PAUSE,
    USER_PAUSE,
    RESUME,
    CAMERA_BOUND,
    CAMERA_TEMPORARILY_UNAVAILABLE,
    CAMERA_AVAILABLE,
    CAMERA_BIND_FAILED,
    SHUTDOWN,
}

/**
 * Small, platform-free state machine used by tests and by CameraService's
 * transition discipline. The Android service remains responsible for the
 * side-effects (CameraX/MediaPipe); this class owns the decision and generation.
 */
class TrackingLifecycleStateMachine {
    data class Snapshot(
        val desiredTrackingEnabled: Boolean = false,
        val state: TrackingState = TrackingState.STOPPED,
        val generation: Long = 0L,
    )

    private var snapshot = Snapshot()

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    @Synchronized
    fun dispatch(event: TrackingLifecycleEvent): Snapshot {
        val before = snapshot
        snapshot = when (event) {
            TrackingLifecycleEvent.USER_ENABLE -> when (before.state) {
                TrackingState.STOPPED, TrackingState.FAILED -> before.copy(
                    desiredTrackingEnabled = true,
                    state = TrackingState.STARTING,
                    generation = before.generation + 1,
                )
                else -> before.copy(desiredTrackingEnabled = true)
            }
            TrackingLifecycleEvent.USER_DISABLE,
            TrackingLifecycleEvent.SHUTDOWN -> before.copy(
                desiredTrackingEnabled = false,
                state = TrackingState.STOPPED,
                generation = before.generation + 1,
            )
            TrackingLifecycleEvent.SYSTEM_PAUSE,
            TrackingLifecycleEvent.USER_PAUSE -> if (before.state == TrackingState.RUNNING) {
                before.copy(state = TrackingState.PAUSED)
            } else before
            TrackingLifecycleEvent.RESUME -> if (before.desiredTrackingEnabled &&
                before.state in setOf(TrackingState.PAUSED, TrackingState.CAMERA_LOST)
            ) before.copy(state = TrackingState.STARTING, generation = before.generation + 1)
            else before
            TrackingLifecycleEvent.CAMERA_BOUND -> if (before.desiredTrackingEnabled &&
                before.state in setOf(TrackingState.STARTING, TrackingState.WAITING_FOR_CAMERA, TrackingState.CAMERA_LOST)
            ) before.copy(state = TrackingState.RUNNING)
            else before
            TrackingLifecycleEvent.CAMERA_TEMPORARILY_UNAVAILABLE -> if (before.desiredTrackingEnabled &&
                before.state in setOf(TrackingState.STARTING, TrackingState.RUNNING)
            ) before.copy(state = if (before.state == TrackingState.RUNNING) TrackingState.CAMERA_LOST else TrackingState.WAITING_FOR_CAMERA)
            else before
            TrackingLifecycleEvent.CAMERA_AVAILABLE -> if (before.desiredTrackingEnabled &&
                before.state in setOf(TrackingState.WAITING_FOR_CAMERA, TrackingState.CAMERA_LOST)
            ) before.copy(state = TrackingState.STARTING, generation = before.generation + 1)
            else before
            TrackingLifecycleEvent.CAMERA_BIND_FAILED -> if (before.desiredTrackingEnabled) {
                before.copy(state = TrackingState.WAITING_FOR_CAMERA)
            } else before
        }
        return snapshot
    }
}
