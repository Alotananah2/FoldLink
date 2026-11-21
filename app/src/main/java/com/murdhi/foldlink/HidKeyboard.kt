package com.murdhi.foldlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/** Makes this phone act as a real Bluetooth keyboard for the main phone. */
@SuppressLint("MissingPermission")
class HidKeyboard(private val ctx: Context, private val hostAddr: String, private val onStatus: (String) -> Unit) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                onStatus("Connecting keyboard…")
                hid?.connect(adapter.getRemoteDevice(hostAddr))
            } else onStatus("Keyboard not registered")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = device; onStatus("Typing into ${device.name}") }
                BluetoothProfile.STATE_DISCONNECTED -> { host = null; onStatus("Keyboard disconnected") }
            }
        }
    }

    fun start() {
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "FoldLink Keyboard", "Fold keyboard", "Murdhi",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR)
                h.registerApp(sdp, null, null, ctx.mainExecutor, callback)
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        val h = hid ?: return
        host?.let { h.disconnect(it) }
        h.unregisterApp()
        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        hid = null
    }

    fun press(code: Int, shift: Boolean = false) {
        val h = host ?: return
        val d = hid ?: return
        d.sendReport(h, 1, byteArrayOf(if (shift) 0x02 else 0, 0, code.toByte(), 0, 0, 0, 0, 0))
        d.sendReport(h, 1, ByteArray(8))
    }

    fun type(c: Char) { map(c)?.let { press(it.first, it.second) } }

    companion object {
        const val ENTER = 0x28; const val BACKSPACE = 0x2A; const val TAB = 0x2B
        const val RIGHT = 0x4F; const val LEFT = 0x50; const val DOWN = 0x51; const val UP = 0x52

        val DESCRIPTOR: ByteArray = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0
        ).map { it.toByte() }.toByteArray()

        private val SYM: Map<Char, Pair<Int, Boolean>> = mapOf(
            ' ' to (0x2C to false), '\n' to (ENTER to false),
            '-' to (0x2D to false), '_' to (0x2D to true), '=' to (0x2E to false), '+' to (0x2E to true),
            '[' to (0x2F to false), '{' to (0x2F to true), ']' to (0x30 to false), '}' to (0x30 to true),
            '\\' to (0x31 to false), '|' to (0x31 to true), ';' to (0x33 to false), ':' to (0x33 to true),
            '\'' to (0x34 to false), '"' to (0x34 to true), '`' to (0x35 to false), '~' to (0x35 to true),
            ',' to (0x36 to false), '<' to (0x36 to true), '.' to (0x37 to false), '>' to (0x37 to true),
            '/' to (0x38 to false), '?' to (0x38 to true),
            '!' to (0x1E to true), '@' to (0x1F to true), '#' to (0x20 to true), '$' to (0x21 to true),
            '%' to (0x22 to true), '^' to (0x23 to true), '&' to (0x24 to true), '*' to (0x25 to true),
            '(' to (0x26 to true), ')' to (0x27 to true)
        )

        fun map(c: Char): Pair<Int, Boolean>? = when (c) {
            in 'a'..'z' -> (0x04 + (c - 'a')) to false
            in 'A'..'Z' -> (0x04 + (c - 'A')) to true
            in '1'..'9' -> (0x1E + (c - '1')) to false
            '0' -> 0x27 to false
            else -> SYM[c]
        }
    }
}
