package com.murdhi.foldlink

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Shows this phone's half of the stretched app.
 * host = phone 1 (gets the picture straight from SpanHost), otherwise phone 2 (gets it over Wi-Fi).
 */
class SpanViewActivity : Activity(), TextureView.SurfaceTextureListener, SpanHost.Viewer {
    private var isHost = false
    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var statusView: TextView
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private val decLock = Object()
    private var vw = 0
    private var vh = 0
    @Volatile private var needKey = true
    @Volatile private var pendingConfig: ByteArray? = null
    private var sock: Socket? = null
    private var sockOut: DataOutputStream? = null
    private val sendExec = Executors.newSingleThreadExecutor()
    @Volatile private var ended = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isHost = intent.getBooleanExtra("host", false)
        active = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this).apply { surfaceTextureListener = this@SpanViewActivity }
        root.addView(tv, FrameLayout.LayoutParams(1, 1))
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(60, 0, 60, 0)
            text = if (isHost) SpanHost.status.ifEmpty { "Starting…" } else "Joining phone 1 over Wi-Fi…"
        }
        root.addView(statusView, FrameLayout.LayoutParams(-1, -1))
        root.addView(controls(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 24 })
        setContentView(root)
        root.setOnTouchListener { _, e -> onTouch(e); true }
        Link.listeners.add(onLink)

        if (isHost) {
            SpanHost.viewer = this
            if (SpanHost.width > 0) onSize(SpanHost.width, SpanHost.height)
            pendingConfig = SpanHost.lastConfig
        } else Thread { runClient() }.start()
    }

    private val onLink: (org.json.JSONObject) -> Unit = { o ->
        if (!isHost && o.optString("t") == "span_stop") { ended = true; finish() }
    }

    private fun controls(): View = LinearLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = 60f; setColor(Color.argb(120, 30, 30, 30)) }
        setPadding(18, 6, 18, 6)
        fun btn(t: String, f: () -> Unit) = addView(TextView(this@SpanViewActivity).apply {
            text = t; textSize = 18f; setTextColor(Color.WHITE); setPadding(34, 10, 34, 10); setOnClickListener { f() }
        })
        btn("◀") { sendKey(KeyEvent.KEYCODE_BACK) }
        btn("●") { sendKey(KeyEvent.KEYCODE_HOME) }
        btn("▢") { sendKey(KeyEvent.KEYCODE_APP_SWITCH) }
        btn("✕") { close() }
    }

    // ---------- phone 2: receive over Wi-Fi ----------

    companion object {
        @Volatile var active = false
        @Volatile var live = false
        @Volatile var lastIps = ""
        @Volatile var lastPort = SpanHost.PORT
    }

    private fun runClient() {
        val ips = (intent.getStringExtra("ips") ?: lastIps).split(",").filter { it.isNotBlank() }
        val port = intent.getIntExtra("port", lastPort)
        lastIps = ips.joinToString(","); lastPort = port
        var fails = 0
        while (!ended) {
            var s: Socket? = null
            for (ip in ips) {
                try { s = Socket().apply { connect(InetSocketAddress(ip, port), 2500); tcpNoDelay = true }; break } catch (_: Exception) {}
            }
            if (s == null) {
                fails++
                runOnUiThread { statusView.visibility = View.VISIBLE
                    statusView.text = "Can't reach phone 1 over Wi-Fi (try $fails).\nPut both phones on the same Wi-Fi, or turn on phone 1's hotspot and join it." }
                Thread.sleep(2000); continue
            }
            fails = 0
            sock = s
            sockOut = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            runOnUiThread { statusView.visibility = View.VISIBLE; statusView.text = "Connected. Waiting for the picture…" }
            try {
                val din = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
                din.readInt() // codec id
                while (!ended) {
                    val head = din.readLong()
                    val n = din.readInt()
                    if ((head and (1L shl 63)) != 0L) { onSize((head and 0xffffffffL).toInt(), n); continue }
                    val data = ByteArray(n)
                    din.readFully(data)
                    onPacket(data, head and ((1L shl 61) - 1), (head and (1L shl 62)) != 0L, (head and (1L shl 61)) != 0L)
                }
            } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
            sockOut = null
            if (!ended) Thread.sleep(1000)
        }
    }

    // ---------- video ----------

    override fun onSize(w: Int, h: Int) {
        runOnUiThread {
            if (w == vw && h == vh && decoder != null) return@runOnUiThread
            needKey = true
            synchronized(decLock) { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}; decoder = null }
            vw = w; vh = h
            layoutVideo()
            startDecoder()
        }
    }

    override fun onStatus(s: String) { runOnUiThread { statusView.text = s; statusView.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE } }
    override fun onEnd() { ended = true; runOnUiThread { finish() } }

    override fun onPacket(data: ByteArray, pts: Long, config: Boolean, key: Boolean) {
        if (config) { pendingConfig = data }
        if (statusView.visibility == View.VISIBLE && (!isHost || SpanHost.status.isEmpty())) runOnUiThread { statusView.visibility = View.GONE }
        if (vw == 0 && isHost && SpanHost.width > 0) onSize(SpanHost.width, SpanHost.height)
        synchronized(decLock) {
            val d = decoder ?: return
            if (config) { feed(d, data, pts, true); return }
            if (needKey && !key) return
            needKey = false
            feed(d, data, pts, false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHost) {
            // Re-attach to the running engine (we may have missed its updates while in the background).
            SpanHost.viewer = this
            onStatus(SpanHost.status)
            if (SpanHost.width > 0) onSize(SpanHost.width, SpanHost.height)
            pendingConfig = SpanHost.lastConfig ?: pendingConfig
            SpanHost.requestKeyframe()
        }
    }

    private fun feed(d: MediaCodec, data: ByteArray, pts: Long, config: Boolean) {
        try {
            val idx = d.dequeueInputBuffer(20000)
            if (idx >= 0) {
                val buf = d.getInputBuffer(idx)!!
                buf.clear(); buf.put(data)
                d.queueInputBuffer(idx, 0, data.size, pts, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = d.dequeueOutputBuffer(info, 0)
                if (out >= 0) d.releaseOutputBuffer(out, true) else break
            }
        } catch (_: Exception) {}
    }

    private fun startDecoder() {
        val s = surface ?: return
        if (vw == 0) return
        synchronized(decLock) {
            if (decoder != null) return
            try {
                val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                d.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh), s, null, 0)
                d.start()
                decoder = d
                needKey = true
                pendingConfig?.let { feed(d, it, 0, true) }
            } catch (e: Exception) { statusView.text = "Video decoder failed: ${e.message}" }
        }
    }

    /** The stretched app is twice this screen wide; shift it so only our half shows. */
    private fun layoutVideo() = root.post {
        if (vw <= 0) return@post
        val w1 = root.width; val h1 = root.height
        val total = w1 * 2
        val scale = min(total / vw.toFloat(), h1 / vh.toFloat())
        val w = (vw * scale).toInt(); val h = (vh * scale).toInt()
        tv.layoutParams = FrameLayout.LayoutParams(w, h)
        tv.translationX = (total - w) / 2f - (if (Prefs.isLeft(this)) 0 else w1)
        tv.translationY = (h1 - h) / 2f
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); startDecoder() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        synchronized(decLock) { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}; decoder = null }
        surface = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---------- touch & keys ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(e: MotionEvent) {
        if (vw == 0 || tv.width <= 1) return
        fun send(action: Int, i: Int) {
            val x = ((e.getX(i) - tv.translationX) / tv.width * vw).toInt().coerceIn(0, vw - 1)
            val y = ((e.getY(i) - tv.translationY) / tv.height * vh).toInt().coerceIn(0, vh - 1)
            sendTouch(action, e.getPointerId(i), x, y)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(0, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(1, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) send(2, i)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) send(1, i)
        }
    }

    private fun sendTouch(action: Int, id: Int, x: Int, y: Int) {
        if (isHost) { SpanHost.touch(action, id, x, y); return }
        sendExec.execute {
            val o = sockOut ?: return@execute
            try { o.writeByte(1); o.writeByte(action); o.writeInt(id); o.writeInt(x); o.writeInt(y); o.flush() } catch (_: Exception) {}
        }
    }

    private fun sendKey(code: Int) {
        if (isHost) { SpanHost.key(code); return }
        sendExec.execute { val o = sockOut ?: return@execute; try { o.writeByte(2); o.writeInt(code); o.flush() } catch (_: Exception) {} }
    }

    private fun close() {
        Prefs.get(this).edit().putBoolean(Prefs.EXTEND_PAUSED, true).apply()
        if (isHost) SpanHost.stop()
        else sendExec.execute {
            try { sockOut?.apply { writeByte(3); flush() } } catch (_: Exception) {}
            runOnUiThread { finish() }
        }
    }

    @Deprecated("back goes to the stretched app")
    override fun onBackPressed() { sendKey(KeyEvent.KEYCODE_BACK) }

    override fun onDestroy() {
        ended = true
        active = false
        Link.listeners.remove(onLink)
        if (isHost && SpanHost.viewer === this) SpanHost.viewer = null
        try { sock?.close() } catch (_: Exception) {}
        synchronized(decLock) { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}; decoder = null }
        super.onDestroy()
    }
}
