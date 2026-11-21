package com.murdhi.foldlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class LinkService : Service() {
    companion object {
        val LINK_UUID: UUID = UUID.fromString("7f3c2a10-5b1e-4c8a-9d2f-0a1b2c3d4e5f")
        @Volatile var status = "Stopped"
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var out: OutputStream? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SHUTDOWN -> { send("OFF"); Thread.sleep(500) }
                Intent.ACTION_SCREEN_OFF ->
                    if (Prefs.get(c).getBoolean(Prefs.MIRROR_SCREEN, false)) send("SCREEN_OFF")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "FoldLink", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "link")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("FoldLink is running")
            .setContentText("Watching the other phone's power button")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN); addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val prefs = Prefs.get(this)
        while (running) {
            val role = prefs.getString(Prefs.ROLE, "main")
            var connected = false
            var gotOff = false
            try {
                val s: BluetoothSocket
                if (role == "main") {
                    val addr = prefs.getString(Prefs.PARTNER, null)
                    if (addr == null) { status = "Pick the second phone first"; Thread.sleep(3000); continue }
                    status = "Connecting to second phone…"
                    adapter.cancelDiscovery()
                    s = adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(LINK_UUID)
                    socket = s
                    s.connect()
                } else {
                    status = "Waiting for main phone…"
                    val ss = adapter.listenUsingRfcommWithServiceRecord("FoldLink", LINK_UUID)
                    server = ss
                    s = ss.accept()
                    ss.close()
                    socket = s
                }
                out = s.outputStream
                connected = true
                status = "Connected to ${s.remoteDevice.name ?: s.remoteDevice.address}"
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    when (line.trim()) {
                        "OFF" -> { gotOff = true; status = PowerOff.shutdown(this) }
                        "SCREEN_OFF" -> PowerOff.lock(this)
                    }
                }
            } catch (e: Exception) {
                if (running) status = "Not connected (retrying): ${e.message}"
            }
            closeSockets()
            if (running && role == "second" && connected && !gotOff &&
                prefs.getBoolean(Prefs.OFF_ON_LOST, false)) {
                status = "Link lost → " + PowerOff.shutdown(this)
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
        }
    }

    private fun send(msg: String) {
        try { out?.write("$msg\n".toByteArray()); out?.flush() } catch (_: Exception) {}
    }

    private fun closeSockets() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null; server = null; out = null
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        closeSockets()
        worker?.interrupt()
        status = "Stopped"
        super.onDestroy()
    }
}
