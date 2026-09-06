package com.aircontrol.nativeinput

/**
 * Standard Bluetooth/USB HID mouse descriptor for a relative 3-button mouse
 * with a wheel. Independently written from the public USB HID usage-table
 * specification — no third-party code copied (see NativeHidMouse-POC.md,
 * "License / attribution").
 *
 * Report layout (Report ID 1, 4 bytes):
 *   byte 0: buttons (bit0 left, bit1 right, bit2 middle)
 *   byte 1: relative X  (-127..127)
 *   byte 2: relative Y  (-127..127)
 *   byte 3: relative wheel (-127..127) — NOT wired in the Phase 1 MVP
 */
object HidMouseDescriptor {

    const val REPORT_ID = 1

    /** Total payload bytes per input report (buttons + X + Y + wheel). */
    const val REPORT_SIZE = 4

    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x02,        // Usage (Mouse)
        0xA1.toByte(), 0x01, // Collection (Application)
        0x85.toByte(), REPORT_ID.toByte(), //   Report ID (1)
        0x09, 0x01,        //   Usage (Pointer)
        0xA1.toByte(), 0x00, //   Collection (Physical)
        0x05, 0x09,        //     Usage Page (Buttons)
        0x19, 0x01,        //     Usage Minimum (1)
        0x29, 0x03,        //     Usage Maximum (3)
        0x15, 0x00,        //     Logical Minimum (0)
        0x25, 0x01,        //     Logical Maximum (1)
        0x95.toByte(), 0x03,        //     Report Count (3)
        0x75, 0x01,        //     Report Size (1)
        0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01,        //     Report Count (1)
        0x75, 0x05,        //     Report Size (5)
        0x81.toByte(), 0x01, //     Input (Constant) — padding
        0x05, 0x01,        //     Usage Page (Generic Desktop)
        0x09, 0x30,        //     Usage (X)
        0x09, 0x31,        //     Usage (Y)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F,        //     Logical Maximum (127)
        0x75, 0x08,        //     Report Size (8)
        0x95.toByte(), 0x02,        //     Report Count (2)
        0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)
        0x09, 0x38,        //     Usage (Wheel)
        0x15, 0x81.toByte(), //     Logical Minimum (-127)
        0x25, 0x7F,        //     Logical Maximum (127)
        0x75, 0x08,        //     Report Size (8)
        0x95.toByte(), 0x01,        //     Report Count (1)
        0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)
        0xC0.toByte(),     //   End Collection (Physical)
        0xC0.toByte(),     // End Collection (Application)
    )
}
