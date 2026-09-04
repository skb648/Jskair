package com.aircontrol.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for end-to-end AirControl readiness. */
object RuntimeHealth {
    data class Snapshot(
        val readiness: RuntimeReadiness = RuntimeReadiness.OFF,
        val trackingRequested: Boolean = false,
        val accessibilityConnected: Boolean = false,
        val cameraRunning: Boolean = false,
        val cameraPaused: Boolean = false,
        val handTrackerReady: Boolean = false,
        val freshFrames: Boolean = false,
        val reason: String? = null,
    ) {
        val isReady: Boolean get() = readiness == RuntimeReadiness.READY
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(
        trackingRequested: Boolean = _state.value.trackingRequested,
        accessibilityConnected: Boolean = _state.value.accessibilityConnected,
        cameraRunning: Boolean = _state.value.cameraRunning,
        cameraPaused: Boolean = _state.value.cameraPaused,
        handTrackerReady: Boolean = _state.value.handTrackerReady,
        freshFrames: Boolean = _state.value.freshFrames,
        reason: String? = _state.value.reason,
        readiness: RuntimeReadiness? = null,
    ) {
        val nextReadiness = readiness ?: derive(
            trackingRequested,
            accessibilityConnected,
            cameraRunning,
            cameraPaused,
            handTrackerReady,
            freshFrames,
            reason,
        )
        _state.value = Snapshot(
            nextReadiness,
            trackingRequested,
            accessibilityConnected,
            cameraRunning,
            cameraPaused,
            handTrackerReady,
            freshFrames,
            reason,
        )
    }

    fun reset() {
        _state.value = Snapshot()
    }

    private fun derive(
        trackingRequested: Boolean,
        accessibilityConnected: Boolean,
        cameraRunning: Boolean,
        cameraPaused: Boolean,
        handTrackerReady: Boolean,
        freshFrames: Boolean,
        reason: String?,
    ): RuntimeReadiness = when {
        !trackingRequested -> RuntimeReadiness.OFF
        !accessibilityConnected -> RuntimeReadiness.BLOCKED
        cameraPaused -> RuntimeReadiness.PAUSED
        !cameraRunning -> RuntimeReadiness.RECOVERING
        !handTrackerReady -> RuntimeReadiness.STARTING
        !freshFrames -> RuntimeReadiness.DEGRADED
        reason != null -> RuntimeReadiness.DEGRADED
        else -> RuntimeReadiness.READY
    }
}
