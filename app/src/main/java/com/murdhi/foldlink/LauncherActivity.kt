package com.murdhi.foldlink

import android.app.Activity
import android.app.SearchManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * FoldLink Home — One UI style.
 *  • Linked: home grid is 8 columns across both phones (4 per phone), pages swipe together.
 *  • Search bar + swipe up = app drawer with search (and web search).
 *  • Real Android widgets: long-press empty space → Add widget.
 *  • Swipe down = notifications.
 */
class LauncherActivity : Activity() {
    data class App(val pkg: String, val label: String, val icon: Drawable?)

    companion object { const val HOST_ID = 4096; const val REQ_PICK = 11; const val REQ_CONF = 12 }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var gestures: GestureDetector
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var awm: AppWidgetManager
    private val widgetViews = mutableMapOf<Int, AppWidgetHostView>()
    private var pendingWidget = -1

    private var local = listOf<App>()
    private var page = 0
    private var flung = false
    private var drawer: View? = null
    private var timeView: TextView? = null
    private var dateView: TextView? = null

    private val cols = 4

    private val onMsg: (JSONObject) -> Unit = { o ->
        when (o.optString("t")) {
            "page" -> { page = o.optInt("n"); render() }
            "apps_loaded", "pins", "home", "_connected", "_disconnected" -> if (drawer == null) render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        awm = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(applicationContext, HOST_ID)
        frame = FrameLayout(this)
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(home, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) * 1.3f && abs(vx) > 700) { flung = true; go(if (vx < 0) page + 1 else page - 1); return true }
                if (abs(vy) > abs(vx) * 1.3f && abs(vy) > 900) {
                    flung = true
                    if (vy < 0) openDrawer(false) else expandNotifications()
                    return true
                }
                return false
            }
        })
        LinkService.start(this)
    }

    override fun onStart() { super.onStart(); try { widgetHost.startListening() } catch (_: Exception) {} }
    override fun onStop() { try { widgetHost.stopListening() } catch (_: Exception) {}; super.onStop() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (drawer != null) return super.dispatchTouchEvent(ev)
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
        if (drawer == null) render()
        tick()
    }

    override fun onPause() {
        Link.listeners.remove(onMsg)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeDrawer()     // pressing Home while on home closes the drawer / goes to page 1
        go(0)
    }

    @Deprecated("Home stays home")
    override fun onBackPressed() { if (drawer != null) closeDrawer() else go(0) }

    // ---------- apps ----------

    private fun loadLocal() {
        val pm = packageManager
        local = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Linked second phone shows the main phone's apps so the home grid lines up across the fold. */
    private fun allApps(): List<App> =
        if (!Prefs.isMain(this) && Link.connected && RemoteApps.list.isNotEmpty())
            RemoteApps.list.map { a -> App(a.pkg, a.label, a.icon?.let { BitmapDrawable(resources, it) }) }
                .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        else local

    private fun homeApps(): List<App> {
        val all = allApps().associateBy { it.pkg }
        return Prefs.home(this).mapNotNull { all[it] }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i)
        else if (Link.connected) {
            Link.send(Link.msg("launch", "p" to pkg))
            Toast.makeText(this, "Opening on the other phone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stretch(pkg: String) {
        when {
            Prefs.isMain(this) -> SpanHost.start(this, pkg)
            Link.connected -> { Link.send(Link.msg("span_req", "p" to pkg)); Toast.makeText(this, "Asking phone 1 to stretch it…", Toast.LENGTH_SHORT).show() }
            else -> Toast.makeText(this, "Link the phones first", Toast.LENGTH_SHORT).show()
        }
        closeDrawer()
    }

    private fun saveList(key: String, list: List<String>) {
        val joined = list.joinToString(",")
        Prefs.get(this).edit().putString(key, joined).apply()
        Link.send(Link.msg(if (key == Prefs.PINS) "pins" else "home", "list" to joined))
    }

    private fun toggleHome(pkg: String) {
        val h = Prefs.home(this)
        if (pkg in h) h.remove(pkg) else h.add(pkg)
        saveList(Prefs.HOME, h)
    }

    private fun toggleDock(pkg: String) {
        val pins = Prefs.pins(this)
        val max = if (span) 6 else 4
        when {
            pkg in pins -> pins.remove(pkg)
            pins.size < max -> pins.add(pkg)
            else -> { Toast.makeText(this, "Dock is full", Toast.LENGTH_SHORT).show(); return }
        }
        saveList(Prefs.PINS, pins)
    }

    private fun appMenu(anchor: View, app: App, onChange: () -> Unit) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (app.pkg in Prefs.home(this)) "Remove from Home" else "Add to Home")
        m.menu.add(0, 2, 1, if (app.pkg in Prefs.pins(this)) "Remove from dock" else "Add to dock")
        m.menu.add(0, 5, 2, "⧉ Stretch across both screens")
        m.menu.add(0, 3, 2, "App info")
        m.menu.add(0, 4, 3, "Uninstall")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> toggleHome(app.pkg)
                2 -> toggleDock(app.pkg)
                3 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                5 -> stretch(app.pkg)
            }
            onChange(); true
        }
        m.show()
    }

    // ---------- home pages ----------

    private val span get() = Link.connected
    private val onLeft get() = Prefs.isLeft(this)
    private val width get() = if (span) cols * 2 else cols
    private fun rowsOn(p: Int) = if (p == 0) 2 else 6
    private fun startOf(p: Int) = if (p == 0) 0 else 2 * width + (p - 1) * 6 * width
    private fun pages(): Int {
        val rest = homeApps().size - 2 * width
        return 1 + if (rest > 0) ceil(rest / (6.0 * width)).toInt() else 0
    }

    private fun go(n: Int) {
        if (n < 0 || n >= pages() || n == page) return
        page = n
        render()
        if (span) Link.send(Link.msg("page", "n" to n))
    }

    private fun glassPill() = GradientDrawable().apply {
        cornerRadius = dpf(28f)
        setColor(Color.argb(70, 255, 255, 255))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun shadowText(t: TextView) = t.setShadowLayer(dpf(4f), 0f, dpf(1f), Color.argb(120, 0, 0, 0))

    private fun render() {
        val list = homeApps()
        page = page.coerceIn(0, pages() - 1)
        home.removeAllViews()
        home.setPadding(dp(14), dp(46), dp(14), dp(22))

        // Search bar (tap = drawer with search)
        if (!span || onLeft || true) {
            val search = TextView(this).apply {
                text = "  🔍   Search apps and web"
                textSize = 15f; setTextColor(Color.argb(230, 255, 255, 255)); gravity = Gravity.CENTER_VERTICAL
                background = glassPill(); setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { openDrawer(true) }
            }
            home.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
        }

        val pageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnLongClickListener { homeMenu(it); true }
        }

        if (page == 0) {
            pageBox.addView(clockWidget(), LinearLayout.LayoutParams(-1, dp(130)))
            Prefs.widgets(this).forEach { id -> widgetView(id)?.let { pageBox.addView(it) } }
        }

        // App grid
        val rows = rowsOn(page)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val idx = startOf(page) + r * width + c + (if (span && !onLeft) cols else 0)
                val cell = if (idx < list.size) appCell(list[idx], true, false)
                           else View(this).apply { setOnLongClickListener { homeMenu(it); true } }
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(98)))
        }
        pageBox.addView(grid, LinearLayout.LayoutParams(-1, -2))
        pageBox.addView(View(this).apply { setOnLongClickListener { homeMenu(it); true } }, LinearLayout.LayoutParams(-1, 0, 1f))
        home.addView(pageBox, LinearLayout.LayoutParams(-1, 0, 1f))

        // Page dots
        val dots = LinearLayout(this).apply {
            gravity = if (!span) Gravity.CENTER else if (onLeft) Gravity.END else Gravity.START
            setPadding(dp(6), dp(8), dp(6), dp(12))
        }
        for (p in 0 until pages()) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            alpha = if (p == page) 1f else 0.45f
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
        home.addView(dots, LinearLayout.LayoutParams(-1, -2))

        // Dock: 3 per phone when linked, 4 when alone
        val all = allApps().associateBy { it.pkg }
        val pins = Prefs.pins(this).mapNotNull { all[it] }
        val mine = if (span) (if (onLeft) pins.take(3) else pins.drop(3).take(3)) else pins.take(4)
        val dock = LinearLayout(this).apply {
            gravity = if (!span) Gravity.CENTER else if (onLeft) Gravity.END else Gravity.START
        }
        mine.forEach { dock.addView(appCell(it, false, true), LinearLayout.LayoutParams(dp(82), dp(72))) }
        if (pins.isEmpty() && (!span || onLeft)) dock.addView(TextView(this).apply {
            text = "Long-press an app → Add to dock"; textSize = 12f; setTextColor(Color.argb(210, 255, 255, 255)); shadowText(this)
        })
        home.addView(dock, LinearLayout.LayoutParams(-1, dp(76)))
        tickOnce()
    }

    /** One UI–style clock widget. Linked: time on the left phone, date + battery on the right. */
    private fun clockWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
            setOnLongClickListener { homeMenu(it); true }
            setOnClickListener {
                try { startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) } catch (_: Exception) {}
            }
        }
        val showTime = !span || onLeft
        timeView = if (showTime) TextView(this).apply {
            textSize = 68f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); shadowText(this)
            includeFontPadding = false
        } else null
        dateView = TextView(this).apply {
            textSize = if (showTime) 16f else 24f; setTextColor(Color.WHITE); shadowText(this)
            if (!showTime) { gravity = Gravity.END; setPadding(0, dp(20), 0, 0) }
        }
        timeView?.let { box.addView(it) }
        box.addView(dateView, LinearLayout.LayoutParams(-1, -2))
        if (!showTime || !span) box.addView(TextView(this).apply {
            textSize = 13f; setTextColor(Color.argb(220, 255, 255, 255)); shadowText(this)
            gravity = if (span) Gravity.END else Gravity.START
            tag = "battery"
        }, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun appCell(app: App, label: Boolean, inDock: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@LauncherActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(58), dp(58)))
        if (label) addView(TextView(this@LauncherActivity).apply {
            text = app.label; textSize = 12f; setTextColor(Color.WHITE); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; shadowText(this)
            setPadding(dp(4), dp(5), dp(4), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { launch(app.pkg) }
        setOnLongClickListener { v -> appMenu(v, app) { render() }; true }
    }

    // ---------- long-press menu & widgets ----------

    private fun homeMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Add widget")
        if (Prefs.widgets(this).isNotEmpty()) m.menu.add(0, 2, 1, "Remove a widget")
        m.menu.add(0, 3, 2, "Wallpaper")
        m.menu.add(0, 4, 3, "FoldLink settings")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> addWidget()
                2 -> removeWidgetMenu(anchor)
                3 -> try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) } catch (_: Exception) {}
                4 -> startActivity(Intent(this, MainActivity::class.java))
            }
            true
        }
        m.show()
    }

    @Suppress("DEPRECATION")
    private fun addWidget() {
        pendingWidget = widgetHost.allocateAppWidgetId()
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget), REQ_PICK)
        } catch (e: Exception) {
            widgetHost.deleteAppWidgetId(pendingWidget)
            Toast.makeText(this, "This phone has no widget picker", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("widget picker result")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK && requestCode != REQ_CONF) return
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) ?: pendingWidget
        if (resultCode != RESULT_OK || id < 0) { if (id >= 0) widgetHost.deleteAppWidgetId(id); return }
        val info = awm.getAppWidgetInfo(id)
        if (requestCode == REQ_PICK && info?.configure != null) {
            try { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONF, null); return }
            catch (_: Exception) {}
        }
        val w = Prefs.widgets(this); w.add(id)
        Prefs.get(this).edit().putString(Prefs.WIDGETS, w.joinToString(",")).apply()
        page = 0
        render()
    }

    private fun widgetView(id: Int): View? {
        val info = awm.getAppWidgetInfo(id) ?: return null
        val v = widgetViews.getOrPut(id) { widgetHost.createView(applicationContext, id, info).apply { setAppWidget(id, info) } }
        (v.parent as? ViewGroup)?.removeView(v)
        val h = maxOf(info.minHeight, dp(110))
        v.layoutParams = LinearLayout.LayoutParams(-1, h).apply { bottomMargin = dp(8) }
        return v
    }

    private fun removeWidgetMenu(anchor: View) {
        val ids = Prefs.widgets(this)
        val m = PopupMenu(this, anchor)
        ids.forEachIndexed { i, id ->
            val label = awm.getAppWidgetInfo(id)?.loadLabel(packageManager) ?: "Widget $id"
            m.menu.add(0, i, i, "Remove: $label")
        }
        m.setOnMenuItemClickListener {
            val id = ids[it.itemId]
            widgetHost.deleteAppWidgetId(id); widgetViews.remove(id)
            ids.removeAt(it.itemId)
            Prefs.get(this).edit().putString(Prefs.WIDGETS, ids.joinToString(",")).apply()
            render(); true
        }
        m.show()
    }

    // ---------- app drawer with search ----------

    private fun openDrawer(focusSearch: Boolean) {
        if (drawer != null) return
        var shown = local
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 14, 15, 18))
            setPadding(dp(14), dp(50), dp(14), dp(16))
            isClickable = true
        }
        val webRow = TextView(this).apply {
            textSize = 15f; setTextColor(Color.rgb(120, 175, 255)); setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val a = shown[p]
                return LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(10), 0, dp(10))
                    addView(ImageView(this@LauncherActivity).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(54), dp(54)))
                    addView(TextView(this@LauncherActivity).apply {
                        text = a.label; textSize = 12f; setTextColor(Color.WHITE); maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(2), dp(5), dp(2), 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
        val search = EditText(this).apply {
            hint = "Search"; setHintTextColor(Color.argb(160, 255, 255, 255)); setTextColor(Color.WHITE)
            textSize = 16f; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = glassPill(); setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        fun webSearch(q: String) {
            if (q.isBlank()) return
            try { startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))) }
            closeDrawer()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val q = s.toString().trim()
                shown = if (q.isEmpty()) local else local.filter { it.label.contains(q, ignoreCase = true) }
                adapter.notifyDataSetChanged()
                webRow.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                webRow.text = "🌐  Search the web for \"$q\""
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        search.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                if (shown.size == 1) { launch(shown[0].pkg); closeDrawer() } else webSearch(search.text.toString())
                true
            } else false
        }
        webRow.setOnClickListener { webSearch(search.text.toString()) }
        val grid = GridView(this).apply {
            numColumns = 4; this.adapter = adapter; verticalSpacing = dp(4)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, p, _ -> launch(shown[p].pkg); closeDrawer() }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, v, p, _ -> appMenu(v, shown[p]) {}; true }
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2))
        box.addView(webRow, LinearLayout.LayoutParams(-1, -2))
        box.addView(TextView(this).apply {
            text = "Apps"; textSize = 13f; setTextColor(Color.argb(170, 255, 255, 255)); setPadding(dp(8), dp(14), 0, dp(4))
        })
        box.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(box, FrameLayout.LayoutParams(-1, -1))
        box.alpha = 0f; box.translationY = dpf(60f)
        box.animate().alpha(1f).translationY(0f).setDuration(180).start()
        drawer = box
        if (focusSearch) {
            search.requestFocus()
            search.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(search, 0) }, 150)
        }
    }

    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        d.animate().alpha(0f).translationY(dpf(60f)).setDuration(150).withEndAction { frame.removeView(d) }.start()
        render()
    }

    @Suppress("WrongConstant")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) {}
    }

    // ---------- clock ----------

    private fun tickOnce() {
        val now = Date()
        timeView?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        dateView?.text = SimpleDateFormat("EEE, d MMMM", Locale.getDefault()).format(now)
        home.findViewWithTag<TextView>("battery")?.let { b ->
            val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = st?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val link = if (Link.connected) "⧉ Fold linked" else "⧉ Not linked"
            b.text = if (lvl >= 0) "🔋 $lvl%   ·   $link" else link
        }
    }

    private fun tick() {
        tickOnce()
        handler.postDelayed({ tick() }, 5000)
    }
}
