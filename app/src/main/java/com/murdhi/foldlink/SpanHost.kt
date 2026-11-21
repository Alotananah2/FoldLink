package com.murdhi.foldlink

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Runs on phone 1 (rooted). Uses the scrcpy server (Apache 2.0, bundled as an asset) to create a
 * hidden virtual screen as wide as both phones, opens an app on it, and streams the picture:
 * phone 1 draws the left half itself, phone 2 gets the stream over Wi-Fi and draws the right half.
 * Touches from both phones are injected back into the app.
 */
object SpanHost {
    const val PORT = 47700
    private const val SERVER_VERSION = "3.1"
    private const val REMOTE_POINTER_OFFSET = 10

    interface Viewer {
        fun onSize(w: Int, h: Int)
        fun onPacket(data: ByteArray, pts: Long, config: Boolean, key: Boolean)
        fun onStatus(s: String)
        fun onEnd()
    }

    @Volatile var viewer: Viewer? = null
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var running = false
    @Volatile var lastConfig: ByteArray? = null
    @Volatile var status = ""

    private var proc: Process? = null
    private const val BRIDGE_PORT = 47701
    private var video: Socket? = null
    private var control: Socket? = null
    @Volatile private var ctrlOut: DataOutputStream? = null
    private var tcp: ServerSocket? = null
    private var client: Socket? = null
    @Volatile private var clientOut: DataOutputStream? = null
    private val ctrlExec = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()

    fun start(ctx: Context, pkg: String) {
        if (running) stop()
        running = true
        lastConfig = null; width = 0; height = 0
        val app = ctx.applicationContext
        Thread {
            try { run(app, pkg) } catch (e: Exception) {
                if (running) { post("Stretch failed: ${e.message}\n${tail()}"); stop() }
            }
        }.start()
    }

    private fun post(s: String) { status = s; viewer?.onStatus(s) }

    private fun addLog(l: String) { synchronized(logLines) { logLines.addLast(l); while (logLines.size > 12) logLines.removeFirst() } }
    private fun tail() = synchronized(logLines) { logLines.joinToString("\n") }

    fun hasRoot(): Boolean = su("true") == 0

    private fun privileged(cmd: String): Process =
        ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()

    private fun su(cmd: String): Int = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
    } catch (e: Exception) { -1 }

    private fun align8(v: Int) = v / 8 * 8

    @Suppress("DEPRECATION")
    private fun run(ctx: Context, pkg: String) {
        post("Preparing…")
        if (!hasRoot()) throw Exception("Root access is needed. Allow FoldLink in Magisk (or your root app) and try again")
        // Put the scrcpy server where the shell user can read it.
        val jar = File(ctx.getExternalFilesDir(null), "foldspan.jar")
        ctx.assets.open("foldspan-server.jar").use { i -> jar.outputStream().use { i.copyTo(it) } }
        val apk = ctx.applicationInfo.sourceDir

        // Virtual screen = two of this phone side by side, rendered at 2/3 size for a smooth stream.
        val dm = DisplayMetrics()
        ctx.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        val w1 = minOf(dm.widthPixels, dm.heightPixels)
        val h1 = maxOf(dm.widthPixels, dm.heightPixels)
        val scale = 1.5f
        val vw = align8((2 * w1 / scale).toInt())
        val vh = align8((h1 / scale).toInt())
        val dpi = (dm.densityDpi / scale).toInt()

        // 1) Invite phone 2 over Bluetooth, it joins over Wi-Fi.
        tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)); soTimeout = 12000 }
        Link.send(Link.msg("span_start", "ips" to localIps(), "port" to PORT))
        ctx.startActivity(Intent(ctx, SpanViewActivity::class.java)
            .putExtra("host", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        post("Waiting for phone 2 to join over Wi-Fi…")
        val c = try { tcp!!.accept() } catch (e: SocketTimeoutException) { null }
        try { tcp?.close() } catch (_: Exception) {}
        if (c != null) {
            c.tcpNoDelay = true
            client = c
            clientOut = DataOutputStream(BufferedOutputStream(c.getOutputStream(), 1 shl 16))
            Thread { readClient(c) }.start()
        } else post("Phone 2 didn't join over Wi-Fi (same Wi-Fi or hotspot?). Showing this half only…")
        if (!running) return

        // 2) Start the scrcpy server (via our bridge) with root.
        val scid = String.format("%08x", Random.nextInt(0x7fffffff))
        val cmd = "cp '${jar.path}' /data/local/tmp/foldspan.jar && chmod 644 /data/local/tmp/foldspan.jar && " +
            "CLASSPATH=/data/local/tmp/foldspan.jar:$apk app_process / com.murdhi.foldlink.SpanBridge $BRIDGE_PORT $scid " +
            "$SERVER_VERSION scid=$scid log_level=info tunnel_forward=false audio=false control=true cleanup=false " +
            "video_codec=h264 video_bit_rate=8000000 max_fps=60 send_device_meta=false send_dummy_byte=false " +
            "new_display=${vw}x${vh}/$dpi vd_system_decorations=false"
        val p = privileged(cmd)
        proc = p
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { addLog(it) } } catch (_: Exception) {}
            if (running) { post("Stretch engine stopped:\n${tail()}"); stop() }
        }.start()
        post("Opening the app on the stretched screen…")

        // 3) Connect to the bridge: first the video stream, then control.
        val v = connectBridge(); video = v
        val ctl = connectBridge(); control = ctl
        ctrlOut = DataOutputStream(BufferedOutputStream(ctl.getOutputStream()))
        Thread { try { val b = ByteArray(4096); while (ctl.getInputStream().read(b) >= 0) {} } catch (_: Exception) {} }.start()

        val vin = DataInputStream(BufferedInputStream(v.getInputStream(), 1 shl 16))
        val codec = vin.readInt()
        if (codec == 0 || codec == 1) throw Exception("The phone's video encoder refused the stream.\n${tail()}")
        width = vin.readInt(); height = vin.readInt()
        clientOut?.let { o -> try { synchronized(o) { o.writeInt(width); o.writeInt(height); o.flush() } } catch (_: Exception) { clientOut = null } }
        viewer?.onSize(width, height)
        startApp("+$pkg")
        post("")

        while (running) {
            val ptsFlags = vin.readLong()
            val len = vin.readInt()
            val data = ByteArray(len)
            vin.readFully(data)
            val config = (ptsFlags and (1L shl 63)) != 0L
            val key = (ptsFlags and (1L shl 62)) != 0L
            val pts = ptsFlags and ((1L shl 62) - 1)
            if (config) lastConfig = data
            viewer?.onPacket(data, pts, config, key)
            clientOut?.let { o ->
                try { synchronized(o) { o.writeLong(ptsFlags); o.writeInt(len); o.write(data); o.flush() } }
                catch (e: Exception) { clientOut = null }
            }
        }
    }

    private fun connectBridge(): Socket {
        val until = System.currentTimeMillis() + 15000
        while (running && System.currentTimeMillis() < until) {
            try { return Socket().apply { connect(InetSocketAddress("127.0.0.1", BRIDGE_PORT), 1000); tcpNoDelay = true } }
            catch (_: Exception) { Thread.sleep(300) }
        }
        throw Exception("The stretch engine didn't start.\n${tail()}")
    }

    /** Messages from phone 2: 1 = touch, 2 = key, 3 = close. */
    private fun readClient(s: Socket) {
        try {
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            while (running) {
                when (din.readUnsignedByte()) {
                    1 -> { val a = din.readUnsignedByte(); val id = din.readInt(); val x = din.readInt(); val y = din.readInt()
                           touch(a, id + REMOTE_POINTER_OFFSET, x, y) }
                    2 -> key(din.readInt())
                    3 -> { stop(); return }
                }
            }
        } catch (_: Exception) { clientOut = null }
    }

    fun touch(action: Int, id: Int, x: Int, y: Int) {
        if (width == 0) return
        ctrlExec.execute {
            val o = ctrlOut ?: return@execute
            try {
                o.writeByte(2); o.writeByte(action); o.writeLong(id.toLong())
                o.writeInt(x); o.writeInt(y); o.writeShort(width); o.writeShort(height)
                o.writeShort(if (action == 1) 0 else 0xffff); o.writeInt(0); o.writeInt(0)
                o.flush()
            } catch (_: Exception) {}
        }
    }

    fun key(code: Int) {
        ctrlExec.execute {
            val o = ctrlOut ?: return@execute
            try {
                for (action in 0..1) { o.writeByte(0); o.writeByte(action); o.writeInt(code); o.writeInt(0); o.writeInt(0) }
                o.flush()
            } catch (_: Exception) {}
        }
    }

    fun startApp(name: String) {
        ctrlExec.execute {
            val o = ctrlOut ?: return@execute
            try {
                val b = name.toByteArray(Charsets.UTF_8)
                o.writeByte(16); o.writeByte(b.size); o.write(b); o.flush()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        if (!running && proc == null) return
        running = false
        listOf(client, video, control).forEach { try { it?.close() } catch (_: Exception) {} }
        try { tcp?.close() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        Thread { try { if (hasRoot()) su("pkill -f foldspan.jar") } catch (_: Exception) {} }.start()
        proc = null; client = null; clientOut = null; ctrlOut = null; video = null; control = null; tcp = null
        width = 0; height = 0
        Link.send(Link.msg("span_stop"))
        viewer?.onEnd()
    }

    fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .joinToString(",")
    } catch (e: Exception) { "" }
}
