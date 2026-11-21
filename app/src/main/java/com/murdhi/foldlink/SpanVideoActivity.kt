package com.murdhi.foldlink

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Plays one video stretched across both phones: each phone draws its own half. Tap = play/pause. */
class SpanVideoActivity : Activity(), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private val handler = Handler(Looper.getMainLooper())
    private var leader = false
    private var prepared = false
    private var peerPrepared = false
    private var started = false
    private var closing = false

    private val onMsg: (JSONObject) -> Unit = { o ->
        val pos = o.optInt("pos")
        when (o.optString("t")) {
            "vprepared" -> { peerPrepared = true; maybeStart() }
            "vplay" -> mp?.let { it.seekTo(pos); it.start() }
            "vpause" -> mp?.let { it.pause(); it.seekTo(pos) }
            "vsync" -> mp?.let { if (it.isPlaying && abs(it.currentPosition - pos) > 250) it.seekTo(pos + 80) }
            "vclose", "_disconnected" -> { closing = true; finish() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leader = intent.getBooleanExtra("leader", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        tv = TextureView(this).apply { surfaceTextureListener = this@SpanVideoActivity }
        root.addView(tv, FrameLayout.LayoutParams(1, 1))
        root.setOnClickListener { toggle() }
        setContentView(root)
        Link.listeners.add(onMsg)
        if (leader) {
            handler.postDelayed({ if (!started) { peerPrepared = true; maybeStart() } }, 5000)
            syncLoop()
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        mp = MediaPlayer().apply {
            setDataSource(File(cacheDir, "span.mp4").path)
            setSurface(Surface(st))
            setOnPreparedListener {
                prepared = true
                layoutVideo(it.videoWidth, it.videoHeight)
                if (leader) maybeStart() else Link.send(Link.msg("vprepared"))
            }
            prepareAsync()
        }
    }

    private fun maybeStart() {
        if (!leader || started || !prepared || !(peerPrepared || !Link.connected)) return
        started = true
        mp?.start()
        Link.send(Link.msg("vplay", "pos" to 0))
    }

    private fun syncLoop() {
        mp?.let { if (it.isPlaying) Link.send(Link.msg("vsync", "pos" to it.currentPosition)) }
        handler.postDelayed({ syncLoop() }, 1500)
    }

    private fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); Link.send(Link.msg("vpause", "pos" to p.currentPosition)) }
        else { p.start(); Link.send(Link.msg("vplay", "pos" to p.currentPosition)) }
    }

    /** Make the video twice as wide as this screen and shift it so only our half shows. */
    private fun layoutVideo(vw: Int, vh: Int) = root.post {
        if (vw <= 0 || vh <= 0) return@post
        val w1 = root.width
        val h1 = root.height
        val span = Link.connected
        val total = if (span) w1 * 2 else w1
        val scale = min(total / vw.toFloat(), h1 / vh.toFloat())
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        tv.layoutParams = FrameLayout.LayoutParams(w, h)
        tv.translationX = (total - w) / 2f - (if (span && !Prefs.isLeft(this)) w1 else 0)
        tv.translationY = (h1 - h) / 2f
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Link.listeners.remove(onMsg)
        if (!closing) Link.send(Link.msg("vclose"))
        mp?.release(); mp = null
        super.onDestroy()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
