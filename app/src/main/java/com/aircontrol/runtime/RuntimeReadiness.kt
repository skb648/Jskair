package com.aircontrol.runtime

/**
 * End-to-end runtime health exposed to the UI and diagnostics.
 * READY means the accessibility service, camera, tracker and fresh frames are all healthy.
 */
enum class RuntimeReadiness {
    OFF,
    STARTING,
    READY,
    DEGRADED,
    RECOVERING,
    PAUSED,
    BLOCKED,
    FAILED,
}
