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
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Runs on phone 1 (rooted). Uses the scrcpy server (Apache 2.0, bundled) to make a hidden screen
 * as wide as both phones, with its own home screen, status bar and keyboard. Phone 1 draws the left
 * half, phone 2 gets the stream over Wi-Fi and draws the right half. Touches from both are injected.
 */
object SpanHost {
    const val PORT = 47700
    private const val BRIDGE_PORT = 47701
    const val SERVER_VERSION = "5.0.1"
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
    private var video: Socket? = null
    private var control: Socket? = null
    @Volatile private var ctrlOut: DataOutputStream? = null
    private var tcp: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var clientOut: DataOutputStream? = null
    private val ctrlExec = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()

    /** Start the wide screen. If [pkg] is given, open that app on it. */
    fun start(ctx: Context, pkg: String? = null) {
        val app = ctx.applicationContext
        if (running) {
            if (pkg != null) startApp("+$pkg")
            openViewer(app)
            return
        }
        running = true
        lastConfig = null; width = 0; height = 0
        synchronized(logLines) { logLines.clear() }
        Thread {
            try { run(app, pkg) } catch (e: Exception) {
                if (running) { post("Stretch failed: ${e.message}\n${tail()}"); stop() }
            }
        }.start()
    }

    fun openViewer(ctx: Context) {
        ctx.startActivity(Intent(ctx, SpanViewActivity::class.java)
            .putExtra("host", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun post(s: String) { status = s; if (s.isNotEmpty()) android.util.Log.i("FoldSpan", "status: $s"); viewer?.onStatus(s) }
    private fun addLog(l: String) { android.util.Log.i("FoldSpan", l); synchronized(logLines) { logLines.addLast(l); while (logLines.size > 14) logLines.removeFirst() } }
    fun tail() = synchronized(logLines) { logLines.joinToString("\n") }

    @Volatile private var suBin: String? = null

    /** Find a working su (KernelSU / Magisk / APatch put it in different places). Logs every attempt. */
    fun findSu(): String? {
        suBin?.let { return it }
        for (b in listOf("su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")) {
            try {
                val p = ProcessBuilder(b, "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                val code = p.waitFor()
                addLog("su try $b → exit $code: $out")
                if (code == 0 && out.contains("uid=0")) { suBin = b; return b }
            } catch (e: Exception) { addLog("su try $b → ${e.message}") }
        }
        return null
    }

    fun su(cmd: String): Int {
        val b = findSu() ?: return -1
        return try { Runtime.getRuntime().exec(arrayOf(b, "-c", cmd)).waitFor() } catch (e: Exception) { -1 }
    }

    private fun align8(v: Int) = v / 8 * 8

    @Suppress("DEPRECATION")
    private fun run(ctx: Context, pkg: String?) {
        post("Preparing…")
        if (findSu() == null) throw Exception("No root for FoldLink. Open KernelSU → Superuser → turn on FoldLink, then try again")
        val jar = File(ctx.filesDir, "foldspan.jar")
        ctx.assets.open("foldspan-server.jar").use { i -> jar.outputStream().use { i.copyTo(it) } }
        val apk = ctx.applicationInfo.sourceDir

        // Wide screen = two of this phone side by side, rendered at 2/3 size so the stream stays smooth.
        val dm = DisplayMetrics()
        ctx.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        val w1 = minOf(dm.widthPixels, dm.heightPixels)
        val h1 = maxOf(dm.widthPixels, dm.heightPixels)
        val scale = 1.5f
        val vw = align8((2 * w1 / scale).toInt())
        val vh = align8((h1 / scale).toInt())
        val dpi = (dm.densityDpi / scale).toInt()

        // Phone 2 can join (and re-join) any time over Wi-Fi.
        tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        Thread { acceptClients() }.start()
        Link.send(Link.msg("span_start", "ips" to localIps(), "port" to PORT))
        openViewer(ctx)

        su("pkill -f foldspan.jar")
        val scid = String.format("%08x", Random.nextInt(0x7fffffff))
        val cmd = "cp '${jar.path}' /data/local/tmp/foldspan.jar && chmod 644 /data/local/tmp/foldspan.jar && " +
            "CLASSPATH=/data/local/tmp/foldspan.jar:$apk app_process / com.murdhi.foldlink.SpanBridge $BRIDGE_PORT $scid " +
            "$SERVER_VERSION scid=$scid log_level=info tunnel_forward=false audio=false control=true cleanup=false " +
            "video_codec=h264 video_bit_rate=10000000 max_fps=60 send_device_meta=false send_dummy_byte=false " +
            "new_display=${vw}x${vh}/$dpi vd_system_decorations=true display_ime_policy=local"
        addLog("$ $cmd")
        val p = ProcessBuilder(findSu()!!, "-c", cmd).redirectErrorStream(true).start()
        proc = p
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { addLog(it) } } catch (_: Exception) {}
            if (running) { post("Stretch engine stopped:\n${tail()}"); stop() }
        }.start()
        post("Starting the wide screen…")

        val v = connectBridge(); video = v
        val ctl = connectBridge(); control = ctl
        ctrlOut = DataOutputStream(BufferedOutputStream(ctl.getOutputStream()))
        Thread { try { val b = ByteArray(4096); while (ctl.getInputStream().read(b) >= 0) {} } catch (_: Exception) {} }.start()

        val vin = DataInputStream(BufferedInputStream(v.getInputStream(), 1 shl 16))
        val codec = vin.readInt()
        if (codec == 0 || codec == 1) throw Exception("The video encoder refused the stream.\n${tail()}")
        sendClient { it.writeInt(codec) }
        if (pkg != null) startApp("+$pkg")
        Thread { openWideHome(ctx) }.start()
        post("")

        while (running) {
            val head = vin.readLong()
            val n = vin.readInt()
            if ((head and (1L shl 63)) != 0L) {           // session packet: new size
                width = (head and 0xffffffffL).toInt(); height = n
                sendClient { it.writeLong(head); it.writeInt(n) }
                viewer?.onSize(width, height)
                continue
            }
            val data = ByteArray(n)
            vin.readFully(data)
            val config = (head and (1L shl 62)) != 0L
            val key = (head and (1L shl 61)) != 0L
            val pts = head and ((1L shl 61) - 1)
            if (config) lastConfig = data
            viewer?.onPacket(data, pts, config, key)
            sendClient { it.writeLong(head); it.writeInt(n); it.write(data) }
        }
    }

    /** Put FoldLink's wide home screen on the new wide screen (Samsung doesn't do it by itself). */
    private fun openWideHome(ctx: Context) {
        val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
        var id = -1
        repeat(20) {
            if (id < 0) {
                id = dm.displays.filter { it.displayId != 0 && it.name.contains("scrcpy", true) }
                    .maxByOrNull { it.displayId }?.displayId ?: -1
                if (id < 0) Thread.sleep(250)
            }
        }
        addLog("wide display id = $id")
        if (id < 0) return
        val r = su("am start --display $id -n ${ctx.packageName}/.WideHomeActivity")
        addLog("open wide home on display $id → exit $r")
    }

    private fun sendClient(block: (DataOutputStream) -> Unit) {
        val o = clientOut ?: return
        try { synchronized(o) { block(o); o.flush() } } catch (e: Exception) { clientOut = null; try { client?.close() } catch (_: Exception) {} }
    }

    private fun acceptClients() {
        while (running) {
            val c = try { tcp?.accept() } catch (e: Exception) { null } ?: break
            try { client?.close() } catch (_: Exception) {}
            c.tcpNoDelay = true
            client = c
            val o = DataOutputStream(BufferedOutputStream(c.getOutputStream(), 1 shl 16))
            // New viewer: tell it the codec + size, then ask the encoder for a fresh keyframe.
            try {
                synchronized(o) {
                    o.writeInt(0x68323634)
                    if (width > 0) { o.writeLong((1L shl 63) or width.toLong()); o.writeInt(height) }
                    o.flush()
                }
                clientOut = o
                resetVideo()
            } catch (_: Exception) {}
            Thread { readClient(c) }.start()
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
        } catch (_: Exception) {}
    }

    fun touch(action: Int, id: Int, x: Int, y: Int) {
        val w = width; val h = height
        if (w == 0) return
        ctrlExec.execute {
            val o = ctrlOut ?: return@execute
            try {
                o.writeByte(2); o.writeByte(action); o.writeLong(id.toLong())
                o.writeInt(x); o.writeInt(y); o.writeShort(w); o.writeShort(h)
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
            try { val b = name.toByteArray(Charsets.UTF_8); o.writeByte(16); o.writeByte(b.size); o.write(b); o.flush() } catch (_: Exception) {}
        }
    }

    private fun resetVideo() {
        ctrlExec.execute { val o = ctrlOut ?: return@execute; try { o.writeByte(17); o.flush() } catch (_: Exception) {} }
    }

    fun stop() {
        if (!running && proc == null) return
        running = false
        listOf(client, video, control).forEach { try { it?.close() } catch (_: Exception) {} }
        try { tcp?.close() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        Thread { su("pkill -f foldspan.jar") }.start()
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
