package com.aircontrol.nativeinput

/**
 * Builds standard mouse input reports for [HidMouseDescriptor].
 *
 * Phase 1 MVP only uses [movement] (buttons = 0). Button/wheel builders exist
 * for the later phases but are intentionally NOT wired anywhere yet.
 */
object HidMouseReport {

    /**
     * Writes a movement-only report into [buffer] (reused by the controller to
     * avoid per-frame allocations). [buffer] must be at least
     * [HidMouseDescriptor.REPORT_SIZE] bytes.
     */
    fun writeMovement(buffer: ByteArray, dx: Int, dy: Int) {
        buffer[0] = 0 // no buttons
        buffer[1] = dx.coerceIn(-127, 127).toByte()
        buffer[2] = dy.coerceIn(-127, 127).toByte()
        buffer[3] = 0 // wheel unused in Phase 1
    }

    /** Allocating builder — used outside the hot path (tests, later phases). */
    fun movement(dx: Int, dy: Int): ByteArray {
        val out = ByteArray(HidMouseDescriptor.REPORT_SIZE)
        writeMovement(out, dx, dy)
        return out
    }
}
