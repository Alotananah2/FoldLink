package com.murdhi.foldlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var spinner: Spinner
    private var addrs = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 60) }
        fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 24, 0, 8) }
        fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { f() } }

        col.addView(text("FoldLink", 30f))
        statusView = text("…", 15f)
        col.addView(statusView)

        col.addView(text("This phone is the:"))
        val main = RadioButton(this).apply { text = "Main phone (phone 1)"; id = View.generateViewId() }
        val second = RadioButton(this).apply { text = "Second phone (phone 2)"; id = View.generateViewId() }
        val group = RadioGroup(this).apply { addView(main); addView(second) }
        group.check(if (Prefs.isMain(this)) main.id else second.id)
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Prefs.ROLE, if (id == main.id) "main" else "second").apply()
            LinkService.restart(this)
        }
        col.addView(group)
        col.addView(CheckBox(this).apply {
            text = "Main phone sits on the RIGHT (default: left)"
            isChecked = prefs.getBoolean(Prefs.SWAP, false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(Prefs.SWAP, b).apply() }
        })

        col.addView(text("The other phone (pair them in Bluetooth settings first):"))
        spinner = Spinner(this)
        col.addView(spinner)
        col.addView(button("Refresh paired phones") { loadDevices() })
        col.addView(CheckBox(this).apply {
            text = "Always extend: phone 2 is always the right half of phone 1 (needs Wi-Fi + root)"
            isChecked = prefs.getBoolean(Prefs.ALWAYS_EXTEND, true)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(Prefs.ALWAYS_EXTEND, b).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Power off this phone if the link is lost"
            isChecked = prefs.getBoolean(Prefs.OFF_ON_LOST, false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(Prefs.OFF_ON_LOST, b).apply() }
        })

        col.addView(text("Setup (do on BOTH phones)", 20f))
        col.addView(button("1. Allow Bluetooth + notifications") { askPermissions() })
        col.addView(button("2. Allow screen lock (power button sync)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets FoldLink turn this screen off when the other phone's screen turns off."))
        })
        col.addView(button("3. Allow display over other apps (split keyboard)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("4. Don't let battery saver kill FoldLink") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        col.addView(button("5. Set FoldLink Home as home screen") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
        col.addView(button("6. Turn on FoldLink Keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        col.addView(button("7. Make FoldLink Keyboard the default") {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        })

        col.addView(text("Run", 20f))
        col.addView(button("Start / restart link") { LinkService.restart(this) })
        col.addView(button("Stop link") { stopService(Intent(this, LinkService::class.java)) })
        col.addView(button("⧉ Start wide screen now (phone 1)") {
            if (Prefs.isMain(this)) SpanHost.start(this) else Toast.makeText(this, "Do this on phone 1", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("⧉ Stretch an app across both screens") { pickStretchApp() })
        col.addView(button("▶ Play a video across both screens") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), 7)
        })
        col.addView(button("Test power-off on THIS phone") {
            Thread { val r = PowerOff.shutdown(this); runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }.start()
        })

        setContentView(ScrollView(this).apply { addView(col) })
        askPermissions()
        loadDevices()
        tick()
    }

    @Deprecated("simple picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) data?.data?.let { LinkService.shareVideo(this, it) }
    }

    private fun pickStretchApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        android.app.AlertDialog.Builder(this).setTitle("Stretch which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                val pkg = apps[i].first
                if (Prefs.isMain(this)) SpanHost.start(this, pkg)
                else if (Link.connected) Link.send(Link.msg("span_req", "p" to pkg))
                else Toast.makeText(this, "Link the phones first", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun tick() {
        val side = if (Prefs.isLeft(this)) "left" else "right"
        val role = if (Prefs.isMain(this)) "MAIN" else "SECOND"
        val span = if (SpanHost.status.isNotEmpty()) "\nWide screen: ${SpanHost.status}" else if (SpanHost.running) "\nWide screen: running" else ""
        statusView.text = "This phone: $role ($side side)\nStatus: ${Link.status}$span"
        handler.postDelayed({ tick() }, 1000)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun askPermissions() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_CONNECT; p += Manifest.permission.BLUETOOTH_SCAN }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        if (p.isNotEmpty()) requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
        LinkService.restart(this)
    }

    private fun loadDevices() {
        val prefs = Prefs.get(this)
        val devices = try {
            getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        } catch (e: SecurityException) { emptyList() }
        addrs = devices.map { it.address }
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf("No paired phones yet. Pair in Bluetooth settings") })
        val saved = addrs.indexOf(prefs.getString(Prefs.PARTNER, null))
        if (saved >= 0) spinner.setSelection(saved)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < addrs.size && prefs.getString(Prefs.PARTNER, null) != addrs[pos]) {
                    prefs.edit().putString(Prefs.PARTNER, addrs[pos]).apply()
                    LinkService.restart(this@MainActivity)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
