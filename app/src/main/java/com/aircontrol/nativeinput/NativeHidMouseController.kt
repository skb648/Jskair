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
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Experimental Bluetooth HID mouse path. Public BluetoothHidDevice APIs are
 * available from Android 9 / API 28. Every public operation therefore exits
 * before touching an API-28 symbol on older devices.
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
    private val reportBuffer = ByteArray(HidMouseDescriptor.REPORT_SIZE)
    private val callbackExecutor: Executor = Executor { it.run() }

    /**
     * Keep the API-28-only constructor inside an explicitly API-gated method.
     *
     * A property/getter annotation does not make the constructor invocation in
     * the lazy initializer safe from Android Lint's NewApi analysis.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun createSdpSettings(): BluetoothHidDeviceAppSdpSettings =
        BluetoothHidDeviceAppSdpSettings(
            "AirControl Mouse",
            "AirControl hand-tracking mouse (experimental)",
            "AirControl",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidMouseDescriptor.DESCRIPTOR,
        )

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createHidCallback(): BluetoothHidDevice.Callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app status: registered=$registered hostPresent=${pluggedDevice != null}")
            if (registered) setState(NativeHidMouseState.REGISTERED)
            else if (wantActive) setState(NativeHidMouseState.ERROR, "HID app unregistered (OEM/profile limitation)")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "HID connection state ${stateName(state)}")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    setState(NativeHidMouseState.CONNECTED, host = device)
                }
                BluetoothProfile.STATE_CONNECTING -> setState(NativeHidMouseState.CONNECTING, host = device)
                BluetoothProfile.STATE_DISCONNECTING -> setState(NativeHidMouseState.CONNECTED, host = device)
                else -> {
                    connectedHost = null
                    if (wantActive) setState(NativeHidMouseState.REGISTERED) else setState(NativeHidMouseState.OFF)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport type=$type id=$id")
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(TAG, "onSetReport type=$type id=$id")
        }
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            Log.d(TAG, "onSetProtocol protocol=$protocol")
        }
        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray?) {
            Log.d(TAG, "onInterruptData reportId=$reportId")
        }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.i(TAG, "Virtual cable unplugged")
            connectedHost = null
            if (wantActive) setState(NativeHidMouseState.REGISTERED) else setState(NativeHidMouseState.OFF)
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth on — trying HID registration")
                    if (wantActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) registerProxy()
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

    val isHidApiAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    @Synchronized
    fun start() {
        if (wantActive) return
        wantActive = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setState(NativeHidMouseState.UNSUPPORTED, "BluetoothHidDevice requires Android 9+ (API 28)")
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
        } catch (_: Exception) { }
        if (adapter?.isEnabled != true) {
            setState(NativeHidMouseState.AVAILABLE, "Bluetooth is off — turn it on to register the HID mouse")
            return
        }
        registerProxyIfSupported()
    }

    @Synchronized
    fun stop() {
        wantActive = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hidDevice = null
            connectedHost = null
            setState(NativeHidMouseState.OFF)
            return
        }
        val hid = hidDevice
        hidDevice = null
        connectedHost = null
        if (hid != null) {
            try { hid.unregisterApp() } catch (_: SecurityException) { Log.w(TAG, "unregisterApp permission") } catch (t: Throwable) { Log.w(TAG, "unregisterApp failed: ${t.message}") }
            try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Throwable) { }
        }
        try { context.unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) { }
        setState(NativeHidMouseState.OFF)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun bondedHosts(): List<HidHostInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val adapter = this.adapter ?: context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { HidHostInfo(name = it.name ?: "Bluetooth device", address = it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (se: SecurityException) {
            Log.w(TAG, "bondedDevices permission denied")
            emptyList()
        }
    }

    @Synchronized
    fun connectHost(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setStateSafe(NativeHidMouseState.UNSUPPORTED, "Bluetooth HID requires Android 9+")
            return
        }
        val adapter = this.adapter ?: return setStateSafe(NativeHidMouseState.ERROR, "Bluetooth adapter unavailable")
        val hid = hidDevice ?: return setStateSafe(NativeHidMouseState.ERROR, "HID device not registered yet")
        val device = try { adapter.getRemoteDevice(address) } catch (_: Throwable) { return setStateSafe(NativeHidMouseState.ERROR, "Invalid host address") }
        try {
            setState(NativeHidMouseState.CONNECTING, host = device)
            val ok = hid.connect(device)
            if (!ok) setStateSafe(NativeHidMouseState.ERROR, "HID connect() returned false (OEM may refuse HID Device role)")
        } catch (_: SecurityException) { setStateSafe(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission") }
          catch (t: Throwable) { setStateSafe(NativeHidMouseState.ERROR, "HID connect failed: ${t.message}") }
    }

    @Synchronized
    fun disconnectHost() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val hid = hidDevice ?: return
        val host = connectedHost ?: return
        try { hid.disconnect(host) } catch (_: SecurityException) { Log.w(TAG, "disconnect permission denied") } catch (t: Throwable) { Log.w(TAG, "disconnect failed: ${t.message}") }
    }

    @Synchronized
    override fun move(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val hid = hidDevice ?: return false
        val host = connectedHost ?: return false
        if (_status.value.state != NativeHidMouseState.CONNECTED) return false
        HidMouseReport.writeMovement(reportBuffer, dx, dy)
        return try {
            hid.sendReport(host, HidMouseDescriptor.REPORT_ID, reportBuffer)
        } catch (_: SecurityException) {
            setStateSafe(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport failed: ${t.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun registerProxyIfSupported() = registerProxy()

    @RequiresApi(Build.VERSION_CODES.P)
    private fun registerProxy() {
        if (hidDevice != null) return
        setState(NativeHidMouseState.REGISTERING)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val hid = proxy as? BluetoothHidDevice ?: run {
                    setStateSafe(NativeHidMouseState.ERROR, "HID_DEVICE proxy unavailable")
                    return
                }
                hidDevice = hid
                try {
                    val ok = hid.registerApp(createSdpSettings(), qosSettings(), qosSettings(), callbackExecutor, createHidCallback())
                    if (!ok) setStateSafe(NativeHidMouseState.ERROR, "registerApp returned false (OEM may block HID Device role)")
                } catch (se: SecurityException) {
                    setStateSafe(NativeHidMouseState.ERROR, "Missing BLUETOOTH_CONNECT permission")
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
            setState(NativeHidMouseState.ERROR, "Missing Bluetooth permission")
        } catch (t: Throwable) {
            setState(NativeHidMouseState.ERROR, "getProfileProxy failed: ${t.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun qosSettings(): BluetoothHidDeviceAppQosSettings? = try {
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
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
            hostName = try { host?.name } catch (_: SecurityException) { "Bluetooth device" },
            hostAddress = host?.address,
        )
        Log.i(TAG, "state=$state reason=$reason hostPresent=${host != null}")
    }

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
}

data class HidHostInfo(val name: String, val address: String)
