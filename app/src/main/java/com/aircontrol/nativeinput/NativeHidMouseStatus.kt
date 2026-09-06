package com.aircontrol.nativeinput

/**
 * Runtime state model for the experimental Native HID Mouse path
 * (see NativeHidMouse-POC.md).
 *
 * The app must be able to answer, at any time:
 *  - is Bluetooth HID Device API available on THIS device/OEM at all,
 *  - did we manage to register as a HID mouse,
 *  - is a host (receiver) connected,
 *  - and if anything failed — why.
 */
enum class NativeHidMouseState {
    /** BluetoothHidDevice API not available (Android < 10, or no BT adapter). */
    UNSUPPORTED,

    /** Feature off (default). Existing AirControl behavior untouched. */
    OFF,

    /** API + adapter present, Bluetooth on, but not registered yet. */
    AVAILABLE,

    /** Profile proxy / HID app registration in progress. */
    REGISTERING,

    /** Registered as a HID mouse; waiting for a host to connect. */
    REGISTERED,

    /** Connecting to the selected host. */
    CONNECTING,

    /** Host connected — HID reports are transmitted. */
    CONNECTED,

    /** Recoverable failure (see [NativeHidMouseStatus.reason]). */
    ERROR,
}

/**
 * Immutable snapshot of the HID controller state. [state] plus a human-readable
 * [reason] for every non-happy-path state; [hostName]/[hostAddress] describe
 * the connected receiver when one exists.
 */
data class NativeHidMouseStatus(
    val state: NativeHidMouseState = NativeHidMouseState.OFF,
    val reason: String? = null,
    val hostName: String? = null,
    val hostAddress: String? = null,
)
