package com.aircontrol.camera

import kotlinx.coroutines.flow.StateFlow

/**
 * Camera management is now handled by [CameraService].
 * This interface is retained for backward compatibility with existing DI bindings.
 * The implementation delegates to CameraService's state flows.
 */
interface CameraManager {
    val isCameraActive: StateFlow<Boolean>
}
