package com.aircontrol.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for end-to-end AirControl readiness. */
object RuntimeHealth {
    data class Snapshot(
        val readiness: RuntimeReadiness = RuntimeReadiness.OFF,
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
        accessibilityConnected: Boolean = _state.value.accessibilityConnected,
        cameraRunning: Boolean = _state.value.cameraRunning,
        cameraPaused: Boolean = _state.value.cameraPaused,
        handTrackerReady: Boolean = _state.value.handTrackerReady,
        freshFrames: Boolean = _state.value.freshFrames,
        reason: String? = _state.value.reason,
        readiness: RuntimeReadiness? = null,
    ) {
        val nextReadiness = readiness ?: derive(
            accessibilityConnected,
            cameraRunning,
            cameraPaused,
            handTrackerReady,
            freshFrames,
            reason,
        )
        _state.value = Snapshot(
            nextReadiness,
            accessibilityConnected,
            cameraRunning,
            cameraPaused,
            handTrackerReady,
            freshFrames,
            reason,
        )
    }

    fun reset() = _state.value.let {
        _state.value = Snapshot()
    }

    private fun derive(
        accessibilityConnected: Boolean,
        cameraRunning: Boolean,
        cameraPaused: Boolean,
        handTrackerReady: Boolean,
        freshFrames: Boolean,
        reason: String?,
    ): RuntimeReadiness = when {
        !accessibilityConnected && !cameraRunning -> RuntimeReadiness.OFF
        cameraPaused -> RuntimeReadiness.PAUSED
        !accessibilityConnected -> RuntimeReadiness.BLOCKED
        !cameraRunning -> RuntimeReadiness.RECOVERING
        !handTrackerReady || !freshFrames -> RuntimeReadiness.DEGRADED
        reason != null -> RuntimeReadiness.DEGRADED
        else -> RuntimeReadiness.READY
    }
}
