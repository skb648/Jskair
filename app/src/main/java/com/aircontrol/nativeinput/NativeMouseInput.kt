package com.aircontrol.nativeinput

/**
 * Minimal abstraction of a native (non-accessibility) mouse input transport.
 *
 * Phase 1 implements ONLY [move]: relative mouse motion, in HID "counts".
 * The unit is deliberately the same unit a physical mouse reports — no screen
 * coordinates, no absolute positioning — so any transport (BluetoothHidDevice
 * today, other native paths later) can implement it identically.
 *
 * Buttons/scroll are intentionally NOT part of this interface yet; they join
 * in Phase 2+ (see NativeHidMouse-POC.md).
 */
interface NativeMouseInput {

    /**
     * Relative mouse movement. Returns true if the report was handed to the
     * transport, false when there is no connected receiver (senders must be
     * able to call this every frame without checking state themselves).
     */
    fun move(dx: Int, dy: Int): Boolean
}
