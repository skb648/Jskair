package com.aircontrol.nativeinput

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Experimental: turns this device into a Bluetooth HID mouse using the public
 * android.bluetooth.BluetoothHidDevice API (Android 10 / API 29+).
 *
 * IMPORTANT (see NativeHidMouse-POC.md):
 *  - This whole path is opt-in and ISOLATED. It never touches the existing
 *    accessibility/dispatchGesture control path, and no cursor is rendered.
 *  - The RECEIVER device's Android input system owns the cursor. This class
 *    only transports relative mouse reports.
 *  - OEM support varies (Samsung/Xiaomi/etc. may restrict HID Device mode).
 *    Every failure degrades to a clean [NativeHidMouseStatus] — never a crash.
 *
 * Independently implemented against the public Android SDK documentation;
 * no third-party code copied (PhonePad was NOT used as source — license risk).
 */
private const val TAG = "NativeHidController"

@Singleton
class NativeHidMouseController @Inject constructor(
    @ApplicationContext private val context: Context,
) : NativeMouseInput {

    private val _status = MutableStateFlow(NativeHidMouseStatus(state = NativeHidMouseState.OFF))
    val status: StateFlow<NativeHidMouseStatus> = _status.asStateFlow()

    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var wantActive = false

    /** Reused report buffer — one allocation for the whole session. */
    private val reportBuffer = ByteArray(HidMouseDescriptor.REPORT_SIZE)

    private val callbackExecutor: Executor = Executor { it.run() }

    private val sdpSettings by lazy {
        BluetoothHidDeviceAppSdpSettings(
            /* name = */ "AirControl Mouse",
            /* description = */ "AirControl hand-tracking mouse (experimental)",
            /* provider = */ "AirControl",
            /* subclass = */ SUBCLASS_MOUSE,
            /* descriptors = */ HidMouseDescriptor.DESCRIPTOR,
        )
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app status: registered=$registered host=${pluggedDevice?.address}")
            if (registered) {
                setState(NativeHidMouseState.REGISTERED)
            } else if (wantActive) {
                // Unregistered while the user wants the feature — OEM refusal or
                // profile teardown. Report it; do not retry-loop.
                setState(NativeHidMouseState.ERROR, "HID app unregistered (OEM/profile limitation)")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "HID connection state ${stateName(state)} for ${device.address}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    setState(NativeHidMouseState.CONNECTED, host = device)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    setState(NativeHidMouseState.CONNECTING, host = device)
                BluetoothProfile.STATE_DISCONNECTING ->
                    setState(NativeHidMouseState.CONNECTED, host = device)
                else -> {
                    connectedHost = null
                    if (wantActive) setState(NativeHidMouseState.REGISTERED) else setState(NativeHidMouseState.OFF)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport (type=$type id=$id) — not implemented")
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(TAG, "onSetReport (type=$type id=$id)")
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            Log.d(TAG, "onSetProtocol (protocol=$protocol)")
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            Log.d(TAG, "onInterruptData (reportId=$reportId)")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.i(TAG, "Virtual cable unplugged by ${device.address}")
            connectedHost = null
            if (wantActive) setState(NativeHidMouseState.REGISTERED) else setState(NativeHidMouseState.OFF)
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on — (re)trying HID registration")
                    if (wantActive) registerProxy()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth off — HID path suspended")
                    hidDevice = null
                    connectedHost = null
                    if (wantActive) setState(NativeHidMouseState.AVAILABLE, "Bluetooth is off")
                }
            }
        }
    }

    /** True when the platform exposes the public BluetoothHidDevice API. */
    val isHidApiAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Feature toggle ON: detect capabilities and try to register. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (wantActive) return // already started; stop() resets the latch
        wantActive = true
        if (!isHidApiAvailable) {
            setState(NativeHidMouseState.UNSUPPORTED, "BluetoothHidDevice requires Android 10+ (API 29); device runs API ${Build.VERSION.SDK_INT}")
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        adapter = manager?.adapter
        if (adapter == null) {
            setState(NativeHidMouseState.UNSUPPORTED, "No Bluetooth adapter on this device")
            return
        }
        try {
            context.registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        } catch (_: Exception) {
            // Already registered or OEM restriction on receiver registration.
        }
        if (adapter?.isEnabled != true) {
            setState(NativeHidMouseState.AVAILABLE, "Bluetooth is off — turn it on to register the HID mouse")
            return
        }
        registerProxy()
    }

    /** Feature toggle OFF: unregister the HID app and release the proxy. */
    @Synchronized
    fun stop() {
        wantActive = false
        val hid = hidDevice
        hidDevice = null
        connectedHost = null
        if (hid != null) {
            try {
                hid.unregisterApp()
            } catch (se: SecurityException) {
                Log.w(TAG, "unregisterApp needs BLUETOOTH_CONNECT: ${se.message}")
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterApp failed: ${t.message}")
            }
            try {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (_: Throwable) {
            }
        }
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {
        }
        setState(NativeHidMouseState.OFF)
    }

    /** Bonded (system-paired) devices usable as the HID host/receiver. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun bondedHosts(): List<HidHostInfo> {
        val adapter = this.adapter ?: context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !isHidApiAvailable) return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { HidHostInfo(name = it.name ?: it.address, address = it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (se: SecurityException) {
            Log.w(TAG, "bondedDevices needs BLUETOOTH_CONNECT: ${se.message}")
            emptyList()
        }
    }

    /** Initiate the HID connection to an already system-paired host. */
    @Synchronized
    fun connectHost(address: String) {
        val adapter = this.adapter ?: return setStateSafe(NativeHidMouseState.ERROR, "Bluetooth adapter unavailable")
        val hid = hidDevice ?: return setStateSafe(NativeHidMouseState.ERROR, "HID device not registered yet")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            return setStateSafe(NativeHidMouseState.ERROR, "Invalid host address: $address")
        }
        try {
            setState(NativeHidMouseState.CONNECTING, host = device)
            val ok = hid.connect(device)
            if (!ok) setState(NativeHidMouseState.ERROR, "HID connect() returned false (OEM may refuse the HID Device role)")
        } catch (se: SecurityException) {
            setState(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission: ${se.message}")
        } catch (t: Throwable) {
            setState(NativeHidMouseState.ERROR, "HID connect failed: ${t.message}")
        }
    }

    @Synchronized
    fun disconnectHost() {
        val hid = hidDevice ?: return
        val host = connectedHost ?: return
        try {
            hid.disconnect(host)
        } catch (se: SecurityException) {
            Log.w(TAG, "disconnect needs BLUETOOTH_CONNECT: ${se.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect failed: ${t.message}")
        }
    }

    /** [NativeMouseInput] — called per hand frame by the adapter; never blocks. */
    @Synchronized
    override fun move(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        val hid = hidDevice ?: return false
        val host = connectedHost ?: return false
        if (_status.value.state != NativeHidMouseState.CONNECTED) return false
        HidMouseReport.writeMovement(reportBuffer, dx, dy)
        return try {
            hid.sendReport(host, HidMouseDescriptor.REPORT_ID, reportBuffer)
        } catch (se: SecurityException) {
            setStateSafe(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission: ${se.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport failed: ${t.message}")
            false
        }
    }

    // ------------------- internals -------------------

    private fun registerProxy() {
        if (hidDevice != null) return
        setState(NativeHidMouseState.REGISTERING)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val hid = proxy as? BluetoothHidDevice
                if (hid == null) {
                    setStateSafe(NativeHidMouseState.ERROR, "HID_DEVICE proxy is not a BluetoothHidDevice")
                    return
                }
                hidDevice = hid
                try {
                    val ok = hid.registerApp(sdpSettings, qosSettings(), qosSettings(), callbackExecutor, hidCallback)
                    if (!ok) setStateSafe(NativeHidMouseState.ERROR, "registerApp returned false (OEM may block the HID Device role)")
                } catch (se: SecurityException) {
                    setStateSafe(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission: ${se.message}")
                } catch (t: Throwable) {
                    setStateSafe(NativeHidMouseState.ERROR, "registerApp failed: ${t.message}")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                Log.w(TAG, "HID profile service disconnected")
                hidDevice = null
                connectedHost = null
                if (wantActive) setStateSafe(NativeHidMouseState.AVAILABLE, "Bluetooth HID service disconnected — toggle the feature to retry")
            }
        }
        try {
            if (adapter?.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE) != true) {
                setState(NativeHidMouseState.ERROR, "getProfileProxy failed (HID Device role unavailable on this build)")
            }
        } catch (se: SecurityException) {
            setState(NativeHidMouseState.ERROR, "Missing Bluetooth permission: ${se.message}")
        } catch (t: Throwable) {
            setState(NativeHidMouseState.ERROR, "getProfileProxy failed: ${t.message}")
        }
    }

    private fun qosSettings(): BluetoothHidDeviceAppQosSettings? =
        try {
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_TYPE_BEST_EFFORT,
                0, 0, 0, 0, 0,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "QoS settings unavailable: ${t.message}")
            null
        }

    private fun setState(state: NativeHidMouseState, reason: String? = null, host: BluetoothDevice? = null) {
        _status.value = NativeHidMouseStatus(
            state = state,
            reason = reason,
            hostName = try { host?.name } catch (_: SecurityException) { host?.address },
            hostAddress = host?.address,
        )
        Log.i(TAG, "state=$state reason=$reason host=${host?.address}")
    }

    /** Callbacks arrive on binder threads; state writes must be thread-safe. */
    private fun setStateSafe(state: NativeHidMouseState, reason: String? = null, host: BluetoothDevice? = null) {
        Handler(Looper.getMainLooper()).post { setState(state, reason, host) }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
    }

    companion object {
        /** HID subclass code for a mouse (AOSP HID subclass constants). */
        private const val SUBCLASS_MOUSE: Byte = 0x80
    }
}

/** A system-paired device selectable as the HID host (receiver). */
data class HidHostInfo(val name: String, val address: String)
