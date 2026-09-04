package com.aircontrol.runtime

/** End-to-end runtime state exposed to UI and diagnostics. */
enum class RuntimeReadiness {
    OFF,
    STARTING,
    READY,
    DEGRADED,
    RECOVERING,
    PAUSED,
    BLOCKED,
}
