package com.murdhi.foldlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class LinkService : Service() {
    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, LinkService::class.java))
        fun restart(c: Context) {
            c.stopService(Intent(c, LinkService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ start(c) }, 700)
        }

        @Volatile private var pendingShare = false

        /** Copy a video, send it to the other phone, then both play it stretched across the two screens. */
        fun shareVideo(c: Context, uri: Uri) = Thread {
            try {
                Link.status = "Preparing video…"
                val f = File(c.cacheDir, "span.mp4")
                c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                if (!Link.connected) { openVideo(c, true); return@Thread }
                pendingShare = true
                val adapter = c.getSystemService(BluetoothManager::class.java).adapter
                val ss = adapter.listenUsingInsecureRfcommWithServiceRecord("FoldLink-file", Link.FILE_UUID)
                try {
                    Link.send(Link.msg("video_offer", "size" to f.length()))
                    Link.status = "Sending video over Bluetooth (can take a minute)…"
                    val s = ss.accept(30000)
                    try { f.inputStream().use { it.copyTo(s.outputStream, 32 * 1024) }; s.outputStream.flush(); Thread.sleep(1500) }
                    finally { s.close() }
                } finally { ss.close() }
            } catch (e: Exception) {
                pendingShare = false
                Link.status = "Video failed: ${e.message}"
            }
        }.start()

        fun openVideo(c: Context, leader: Boolean) {
            c.startActivity(Intent(c, SpanVideoActivity::class.java)
                .putExtra("leader", leader).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Volatile private var running = false
    @Volatile private var gotOff = false
    @Volatile private var servers = listOf<BluetoothServerSocket>()
    @Volatile private var pending: BluetoothSocket? = null
    private var overlay: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var suppressScreenUntil = 0L
    private var suppressVolUntil = 0L

    private val onMsg: (JSONObject) -> Unit = { handle(it) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_SHUTDOWN -> Link.sendNow(Link.msg("off"))
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON ->
                    if (now > suppressScreenUntil)
                        Link.send(Link.msg("screen", "on" to (i.action == Intent.ACTION_SCREEN_ON)))
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (stream != AudioManager.STREAM_MUSIC && stream != AudioManager.STREAM_RING) return
                    if (now < suppressVolUntil) return
                    val am = getSystemService(AudioManager::class.java)
                    val frac = am.getStreamVolume(stream).toDouble() / am.getStreamMaxVolume(stream)
                    Link.send(Link.msg("vol", "s" to stream, "v" to frac))
                }
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
            .setContentTitle("FoldLink")
            .setContentText("Linking both screens over Wi-Fi")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON); addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, f)
        Link.listeners.add(onMsg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) { running = true; Thread { loop() }.start(); Thread { foldLoop() }.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        servers.forEach { try { it.close() } catch (_: Exception) {} }
        try { pending?.close() } catch (_: Exception) {}
        Link.closeCurrent()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        Link.listeners.remove(onMsg)
        hideOverlay()
        Link.status = "Stopped"
        super.onDestroy()
    }

    // ---------- always-on wide screen ("like a Z Fold") ----------

    /** Phone 1 keeps the wide screen running; phone 2 keeps looking for it on Wi-Fi and opens its half. */
    private fun foldLoop() {
        var backoff = 0L
        while (running) {
            try {
                val p = Prefs.get(this)
                val on = p.getBoolean(Prefs.ALWAYS_EXTEND, true) && !p.getBoolean(Prefs.EXTEND_PAUSED, false)
                if (on && Prefs.isMain(this)) {
                    if (!SpanHost.running && System.currentTimeMillis() > backoff) {
                        SpanHost.start(this)
                        backoff = System.currentTimeMillis() + 45_000
                    }
                } else if (on && !SpanViewActivity.active) {
                    findWideScreen()?.let { (ip, port) ->
                        SpanViewActivity.live = true
                        SpanViewActivity.lastIps = ip; SpanViewActivity.lastPort = port
                        handler.post { openSpanClient() }
                        Thread.sleep(8000)
                    }
                }
            } catch (_: Exception) {}
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun findWideScreen(): Pair<String, Int>? {
        val nsd = getSystemService(android.net.nsd.NsdManager::class.java)
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = wifi.createMulticastLock("foldspan").apply { setReferenceCounted(false); acquire() }
        val latch = java.util.concurrent.CountDownLatch(1)
        var found: Pair<String, Int>? = null
        val disc = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: android.net.nsd.NsdServiceInfo) {}
            override fun onServiceFound(i: android.net.nsd.NsdServiceInfo) {
                if (!i.serviceName.startsWith("FoldSpan")) return
                nsd.resolveService(i, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: android.net.nsd.NsdServiceInfo) {
                        val h = i.host?.hostAddress
                        if (h != null) { found = h to i.port; latch.countDown() }
                    }
                })
            }
        }
        try {
            nsd.discoverServices(SpanHost.NSD_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, disc)
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
        try { nsd.stopServiceDiscovery(disc) } catch (_: Exception) {}
        lock.release()
        return found
    }

    // ---------- connection ----------

    private fun loop() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        while (running) {
            try {
                if (!adapter.isEnabled) throw Exception("Bluetooth is off. Turn it on")
                if (Prefs.isMain(this)) connectToSecond(adapter) else waitForMain(adapter)
            } catch (e: SecurityException) {
                Link.status = "Bluetooth (Nearby devices) permission missing. Tap setup step 1. (${e.message})"
            } catch (e: Exception) {
                if (running) Link.status = "Not connected, retrying… ${e.message}"
            }
            try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        }
    }

    /** Main phone: connect to the second phone, trying both Bluetooth socket types. */
    private fun connectToSecond(adapter: BluetoothAdapter) {
        val addr = Prefs.get(this).getString(Prefs.PARTNER, null)
            ?: throw Exception("pick the second phone in FoldLink settings")
        val dev = adapter.getRemoteDevice(addr)
        val name = dev.name ?: addr
        if (dev.bondState != BluetoothDevice.BOND_BONDED) throw Exception("$name isn't paired. Pair it in Bluetooth settings")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        var last: Exception? = null
        for ((kind, make) in listOf<Pair<String, () -> BluetoothSocket>>(
            "fast" to { dev.createInsecureRfcommSocketToServiceRecord(Link.LINK_UUID) },
            "secure" to { dev.createRfcommSocketToServiceRecord(Link.LINK_UUID) })) {
            if (!running) return
            Link.status = "Connecting to $name ($kind)…"
            val s = try { make() } catch (e: Exception) { last = e; continue }
            pending = s
            try { s.connect() } catch (e: Exception) {
                last = e; try { s.close() } catch (_: Exception) {}; continue
            }
            pending = null
            runSession(s, name); return
        }
        throw Exception("can't reach $name. Is FoldLink started on it as Second phone? (${last?.message})")
    }

    /** Second phone: listen with both socket types at once; take whichever the main phone uses. */
    private fun waitForMain(adapter: BluetoothAdapter) {
        Link.status = "Waiting for main phone… now tap Start on the main phone"
        val list = listOfNotNull(
            try { adapter.listenUsingInsecureRfcommWithServiceRecord("FoldLink", Link.LINK_UUID) } catch (e: Exception) { null },
            try { adapter.listenUsingRfcommWithServiceRecord("FoldLink-secure", Link.LINK_UUID) } catch (e: Exception) { null })
        if (list.isEmpty()) throw Exception("couldn't open Bluetooth listener")
        servers = list
        val q = LinkedBlockingQueue<Any>()
        list.forEach { ss -> Thread { try { q.put(ss.accept()) } catch (e: Exception) { q.put(e) } }.start() }
        var errors = 0
        var sock: BluetoothSocket? = null
        while (sock == null) {
            val r = q.take()
            if (r is BluetoothSocket) sock = r
            else if (++errors >= list.size) { servers = emptyList(); throw r as Exception }
        }
        list.forEach { try { it.close() } catch (_: Exception) {} }
        servers = emptyList()
        runSession(sock, sock.remoteDevice.name ?: sock.remoteDevice.address)
    }

    private fun runSession(s: BluetoothSocket, name: String) {
        gotOff = false
        Link.attach(s, s.outputStream, s.remoteDevice.address)
        Link.status = "Connected ✓ to $name"
        Thread {
            while (Link.isCurrent(s)) {
                Link.send(Link.msg("ping"))
                try { Thread.sleep(4000) } catch (_: InterruptedException) {}
            }
        }.start()
        try { Link.readLoop(s.inputStream) } catch (_: Exception) {} finally { Link.detach(s) }
        if (running) {
            Link.status = "Connection lost, reconnecting…"
            if (!gotOff && Prefs.get(this).getBoolean(Prefs.OFF_ON_LOST, false))
                Link.status = "Link lost → " + PowerOff.shutdown(this)
        }
    }

    // ---------- messages from the other phone ----------

    private fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "_connected" -> {
                if (Prefs.isMain(this)) {
                    val p = Prefs.get(this)
                    p.edit().putBoolean(Prefs.EXTEND_PAUSED, false).apply()
                    if (p.getBoolean(Prefs.ALWAYS_EXTEND, true))
                        handler.postDelayed({ if (Link.connected && !SpanHost.running) SpanHost.start(this) }, 1500)
                    Link.send(Link.msg("pins", "list" to Prefs.pins(this).joinToString(",")))
                    Link.send(Link.msg("home", "list" to Prefs.home(this).joinToString(",")))
                }
                else Link.send(Link.msg("apps_req"))
            }
            "_disconnected" -> hideOverlay()
            "off" -> { gotOff = true; Thread { Link.status = "Other phone powered off → " + PowerOff.shutdown(this) }.start() }
            "screen" -> {
                suppressScreenUntil = System.currentTimeMillis() + 2500
                if (o.optBoolean("on")) PowerOff.wake(this) else PowerOff.lock(this)
            }
            "vol" -> {
                val am = getSystemService(AudioManager::class.java)
                val s = o.optInt("s")
                val target = (o.optDouble("v") * am.getStreamMaxVolume(s)).roundToInt()
                if (target != am.getStreamVolume(s)) {
                    suppressVolUntil = System.currentTimeMillis() + 800
                    try { am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
                }
            }
            "kb_show" -> showOverlay()
            "kb_hide" -> hideOverlay()
            "launch" -> packageManager.getLaunchIntentForPackage(o.optString("p"))?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
            }
            "apps_req" -> Thread { sendApps() }.start()
            "apps" -> { val arr = o.getJSONArray("list"); Thread { RemoteApps.load(arr) }.start() }
            "pins" -> Prefs.get(this).edit().putString(Prefs.PINS, o.optString("list")).apply()
            "home" -> Prefs.get(this).edit().putString(Prefs.HOME, o.optString("list")).apply()
            "video_offer" -> Thread { receiveVideo(o.optLong("size")) }.start()
            "video_ready" -> if (pendingShare) {
                pendingShare = false
                Link.status = "Connected ✓"
                openVideo(this, true)
                Link.send(Link.msg("video_open"))
            }
            "video_open" -> openVideo(this, false)
            "span_req" -> if (Prefs.isMain(this)) SpanHost.start(this, o.optString("p"))
            "span_start" -> {
                SpanViewActivity.live = true
                SpanViewActivity.lastIps = o.optString("ips"); SpanViewActivity.lastPort = o.optInt("port")
                openSpanClient()
            }
            "span_stop" -> SpanViewActivity.live = false
        }
    }

    /** Open the right-half viewer. Uses root so Android's background limits can't block it. */
    private fun openSpanClient() {
        Thread {
            val ok = SpanHost.su("am start -n $packageName/.SpanViewActivity --ez host false " +
                "--es ips '${SpanViewActivity.lastIps}' --ei port ${SpanViewActivity.lastPort} -f 0x10000000") == 0
            if (!ok) handler.post {
                try {
                    startActivity(Intent(this, SpanViewActivity::class.java).putExtra("host", false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendApps() {
        val pm = packageManager
        val arr = JSONArray()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { ri ->
                val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                val d = ri.loadIcon(pm)
                d.setBounds(0, 0, 72, 72); d.draw(Canvas(bmp))
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                arr.put(JSONObject().apply {
                    put("p", ri.activityInfo.packageName)
                    put("l", ri.loadLabel(pm).toString())
                    put("i", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                })
            }
        Link.send(Link.msg("apps", "list" to arr))
    }

    private fun receiveVideo(size: Long) {
        try {
            val f = File(cacheDir, "span.mp4")
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            adapter.getRemoteDevice(Link.peerAddr).createInsecureRfcommSocketToServiceRecord(Link.FILE_UUID).use { s ->
                s.connect()
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    val input = s.inputStream
                    while (size <= 0 || got < size) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (size > 0) Link.status = "Receiving video ${got * 100 / size}%"
                    }
                }
            }
            Link.status = "Connected ✓"
            Link.send(Link.msg("video_ready"))
        } catch (e: Exception) { Link.status = "Video receive failed: ${e.message}" }
    }

    // ---------- other half of the keyboard ----------

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val mode = if (Prefs.isLeft(this)) GlassKeyboardView.Mode.LEFT else GlassKeyboardView.Mode.RIGHT
        val v = GlassKeyboardView(this, mode, object : GlassKeyboardView.Listener {
            override fun onChar(c: Char) { Link.send(Link.msg("key", "c" to c.toString())) }
            override fun onSpecial(k: GlassKeyboardView.Special) { Link.send(Link.msg("special", "k" to k.name)) }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        try { getSystemService(WindowManager::class.java).addView(v, lp); overlay = v } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        overlay = null
    }
}
