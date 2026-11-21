package com.murdhi.foldlink

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** FoldLink home screen: liquid-glass clock, search, app grid and a dock. */
class LauncherActivity : Activity() {
    data class App(val label: String, val pkg: String, val icon: Drawable)

    private val handler = Handler(Looper.getMainLooper())
    private var all = listOf<App>()
    private var shown = listOf<App>()
    private lateinit var grid: GridView
    private lateinit var dock: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText

    private fun glass(radius: Float = 36f, alpha: Int = 50) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.argb(alpha, 255, 255, 255))
        setStroke(2, Color.argb(90, 255, 255, 255))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 90, 32, 40)
        }
        clock = TextView(this).apply {
            textSize = 64f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        date = TextView(this).apply { textSize = 16f; setTextColor(Color.argb(220, 255, 255, 255)); gravity = Gravity.CENTER }
        status = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = glass(40f, 40); setPadding(28, 12, 28, 12)
            setOnClickListener { startActivity(Intent(this@LauncherActivity, MainActivity::class.java)) }
        }
        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(Color.argb(170, 255, 255, 255)); setTextColor(Color.WHITE)
            background = glass(48f, 45); setPadding(40, 26, 40, 26); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filter(s.toString()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        grid = GridView(this).apply {
            numColumns = 4; verticalSpacing = 30; horizontalSpacing = 10; setPadding(0, 30, 0, 30)
            adapter = AppAdapter()
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> togglePin(shown[pos].pkg); true }
        }
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = glass(60f, 55); setPadding(20, 22, 20, 22)
        }

        root.addView(clock)
        root.addView(date)
        root.addView(status, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 20 })
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 40 })
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(dock, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        tick()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Home screen ignores back")
    override fun onBackPressed() { search.setText("") }

    private fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        val role = if (Prefs.get(this).getString(Prefs.ROLE, "main") == "main") "Main screen" else "Second screen"
        status.text = "⧉ $role · ${LinkService.status}"
        handler.postDelayed({ tick() }, 1000)
    }

    private fun loadApps() {
        val pm = packageManager
        val list: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        all = list.filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
        filter(search.text.toString())
        buildDock()
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) all else all.filter { it.label.contains(q, ignoreCase = true) }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun pins(): MutableList<String> =
        Prefs.get(this).getString("dock", "")!!.split(",").filter { it.isNotBlank() }.toMutableList()

    private fun togglePin(pkg: String) {
        val p = pins()
        if (pkg in p) p.remove(pkg) else if (p.size < 4) p.add(pkg)
        Prefs.get(this).edit().putString("dock", p.joinToString(",")).apply()
        buildDock()
    }

    private fun buildDock() {
        dock.removeAllViews()
        val second = Prefs.get(this).getString(Prefs.ROLE, "main") == "second"
        pins().mapNotNull { pkg -> all.find { it.pkg == pkg } }.forEach { app ->
            dock.addView(icon(app.icon) { launch(app) }.apply { setOnLongClickListener { togglePin(app.pkg); true } })
        }
        if (second) dock.addView(textTile("⌨") { startActivity(Intent(this, KeyboardActivity::class.java)) })
        dock.addView(textTile("⧉") { startActivity(Intent(this, MainActivity::class.java)) })
        if (dock.childCount == 1 || (second && dock.childCount == 2)) {
            dock.addView(TextView(this).apply {
                text = "Long-press an app to add it here"; setTextColor(Color.argb(200, 255, 255, 255)); textSize = 13f
                setPadding(20, 0, 0, 0)
            })
        }
    }

    private fun icon(d: Drawable, onTap: () -> Unit) = ImageView(this).apply {
        setImageDrawable(d)
        layoutParams = LinearLayout.LayoutParams(150, 150).apply { setMargins(18, 0, 18, 0) }
        setOnClickListener { onTap() }
    }

    private fun textTile(t: String, onTap: () -> Unit) = TextView(this).apply {
        text = t; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = glass(40f, 60)
        layoutParams = LinearLayout.LayoutParams(150, 150).apply { setMargins(18, 0, 18, 0) }
        setOnClickListener { onTap() }
    }

    private fun launch(app: App) {
        packageManager.getLaunchIntentForPackage(app.pkg)?.let { startActivity(it) }
        search.setText("")
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val app = shown[p]
            val cell = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            cell.addView(ImageView(this@LauncherActivity).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(140, 140))
            cell.addView(TextView(this@LauncherActivity).apply {
                text = app.label; setTextColor(Color.WHITE); textSize = 12f; maxLines = 1
                gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK)
            })
            cell.setOnLongClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                true
            }
            cell.setOnClickListener { launch(app) }
            return cell
        }
    }
}
