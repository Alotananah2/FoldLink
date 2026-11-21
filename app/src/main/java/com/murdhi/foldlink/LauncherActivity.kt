package com.murdhi.foldlink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One UI–style home screen. When the phones are linked, the home grid is 8 columns wide
 * and split down the middle: left 4 columns on the left phone, right 4 on the right phone,
 * like an unfolded Galaxy Fold. Swiping pages on either phone moves both.
 */
class LauncherActivity : Activity() {
    data class App(val pkg: String, val label: String, val icon: Drawable?)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var gestures: GestureDetector
    private var local = listOf<App>()
    private var page = 0
    private var flung = false
    private var timeView: TextView? = null
    private var dateView: TextView? = null

    private val cols = 4
    private val rowsN = 6

    private val onMsg: (JSONObject) -> Unit = { o ->
        when (o.optString("t")) {
            "page" -> { page = o.optInt("n"); render() }
            "apps_loaded", "pins", "_connected", "_disconnected" -> render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 700) { flung = true; go(if (vx < 0) page + 1 else page - 1); return true }
                return false
            }
        })
        LinkService.start(this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        gestures.onTouchEvent(ev)
        if (flung && ev.actionMasked == MotionEvent.ACTION_UP) {
            ev.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        Link.listeners.add(onMsg)
        loadLocal()
        if (!Prefs.isMain(this) && Link.connected && RemoteApps.list.isEmpty()) Link.send(Link.msg("apps_req"))
        render()
        tick()
    }

    override fun onPause() {
        Link.listeners.remove(onMsg)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    @Deprecated("Home stays home")
    override fun onBackPressed() { go(0) }

    private fun loadLocal() {
        val pm = packageManager
        local = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Both phones show the main phone's apps so the grid lines up across the fold. */
    private fun apps(): List<App> =
        if (!Prefs.isMain(this) && Link.connected && RemoteApps.list.isNotEmpty())
            RemoteApps.list.map { a -> App(a.pkg, a.label, a.icon?.let { BitmapDrawable(resources, it) }) }
                .sortedBy { it.label.lowercase() }
        else local

    private val span get() = Link.connected
    private val onLeft get() = Prefs.isLeft(this)
    private fun perPage() = if (span) cols * 2 * rowsN else cols * rowsN
    private fun pages() = maxOf(1, ceil(apps().size / perPage().toDouble()).toInt())

    private fun go(n: Int) {
        if (n < 0 || n >= pages() || n == page) return
        page = n
        render()
        if (span) Link.send(Link.msg("page", "n" to n))
    }

    private fun render() {
        val list = apps()
        page = page.coerceIn(0, pages() - 1)
        root.removeAllViews()
        root.setPadding(dp(12), dp(44), dp(12), dp(28))

        // Clock widget, stretched across the fold: time on the left phone, date on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), 0)
            setOnLongClickListener { startActivity(Intent(this@LauncherActivity, MainActivity::class.java)); true }
        }
        val showTime = !span || onLeft
        timeView = if (showTime) TextView(this).apply {
            textSize = 64f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.argb(90, 0, 0, 0))
        } else null
        dateView = TextView(this).apply {
            textSize = if (showTime) 16f else 22f; setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.argb(90, 0, 0, 0))
            if (!showTime) { gravity = Gravity.END; setPadding(0, dp(30), 0, 0) }
        }
        timeView?.let { header.addView(it) }
        header.addView(dateView, LinearLayout.LayoutParams(-1, -2))
        if (!showTime || !span) header.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.argb(210, 255, 255, 255))
            text = if (Link.connected) "⧉ Fold linked" else "⧉ Not linked · hold here for settings"
            gravity = if (span) Gravity.END else Gravity.START
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(160)))

        // App grid
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until rowsN) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val idx = if (span) page * perPage() + r * cols * 2 + c + (if (onLeft) 0 else cols)
                          else page * perPage() + r * cols + c
                val cell: View = if (idx < list.size) appCell(list[idx], true) else View(this)
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        // Page dots
        val dots = LinearLayout(this).apply {
            gravity = if (!span) Gravity.CENTER else if (onLeft) Gravity.END else Gravity.START
            setPadding(0, dp(10), 0, dp(14))
        }
        for (p in 0 until pages()) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            alpha = if (p == page) 1f else 0.4f
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        root.addView(dots, LinearLayout.LayoutParams(-1, -2))

        // Dock (no background, like One UI). 6 slots when unfolded, 3 per phone.
        val pins = Prefs.pins(this).mapNotNull { p -> list.find { it.pkg == p } }
        val mine = if (span) (if (onLeft) pins.take(3) else pins.drop(3).take(3)) else pins.take(4)
        val dock = LinearLayout(this).apply {
            gravity = if (!span) Gravity.CENTER else if (onLeft) Gravity.END else Gravity.START
        }
        mine.forEach { dock.addView(appCell(it, false), LinearLayout.LayoutParams(dp(84), dp(76))) }
        if (pins.isEmpty() && (!span || left)) dock.addView(TextView(this).apply {
            text = "Long-press an app to add it to the dock"; textSize = 12f; setTextColor(Color.argb(200, 255, 255, 255))
        })
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(80)))
        tickOnce()
    }

    private fun appCell(app: App, label: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@LauncherActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        if (label) addView(TextView(this@LauncherActivity).apply {
            text = app.label; textSize = 12f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1.5f, Color.argb(160, 0, 0, 0)); setPadding(dp(4), dp(5), dp(4), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { launch(app.pkg) }
        setOnLongClickListener { togglePin(app.pkg); true }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        else if (Link.connected) {
            Link.send(Link.msg("launch", "p" to pkg))
            Toast.makeText(this, "Not on this phone. Opening on the other one", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePin(pkg: String) {
        val pins = Prefs.pins(this)
        val max = if (span) 6 else 4
        when {
            pkg in pins -> pins.remove(pkg)
            pins.size < max -> pins.add(pkg)
            else -> { Toast.makeText(this, "Dock is full", Toast.LENGTH_SHORT).show(); return }
        }
        val joined = pins.joinToString(",")
        Prefs.get(this).edit().putString(Prefs.PINS, joined).apply()
        Link.send(Link.msg("pins", "list" to joined))
        render()
    }

    private fun tickOnce() {
        val now = Date()
        timeView?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        dateView?.text = SimpleDateFormat("EEE, d MMMM", Locale.getDefault()).format(now)
    }

    private fun tick() {
        tickOnce()
        handler.postDelayed({ tick() }, 5000)
    }
}
