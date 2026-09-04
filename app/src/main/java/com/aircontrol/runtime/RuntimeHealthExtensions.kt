package com.aircontrol.runtime

import com.aircontrol.camera.CameraService
import com.aircontrol.service.CameraServiceManager

/** Publishes the camera-side state without making UI code depend on the service implementation. */
object RuntimeHealthExtensions {
    fun publishCameraState(manager: CameraServiceManager) {
        val snapshot = CameraService.serviceState.value
        RuntimeHealth.update(
            cameraRunning = snapshot.isRunning,
            cameraPaused = snapshot.isPaused,
            reason = when {
                snapshot.isPaused -> "camera-paused"
                !snapshot.isRunning -> "camera-not-running"
                else -> null
            },
        )
    }
}
