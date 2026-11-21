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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 20, 0, 8) }
        fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }

        col.addView(text("FoldLink", 28f))
        statusView = text("Status: …")
        col.addView(statusView)

        col.addView(text("This phone is the:"))
        val main = RadioButton(this).apply { text = "Main phone (1st – has the power button)"; id = View.generateViewId() }
        val second = RadioButton(this).apply { text = "Second phone (follows + keyboard)"; id = View.generateViewId() }
        val group = RadioGroup(this).apply { addView(main); addView(second) }
        group.check(if (prefs.getString(Prefs.ROLE, "main") == "main") main.id else second.id)
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Prefs.ROLE, if (id == main.id) "main" else "second").apply()
        }
        col.addView(group)

        col.addView(text("The other phone (pair them in Bluetooth settings first):"))
        spinner = Spinner(this)
        col.addView(spinner)
        col.addView(button("Refresh paired phones") { loadDevices() })

        col.addView(CheckBox(this).apply {
            text = "Lock 2nd phone when main phone's screen turns off"
            isChecked = prefs.getBoolean(Prefs.MIRROR_SCREEN, false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(Prefs.MIRROR_SCREEN, b).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Power off 2nd phone if Bluetooth link is lost"
            isChecked = prefs.getBoolean(Prefs.OFF_ON_LOST, false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(Prefs.OFF_ON_LOST, b).apply() }
        })

        col.addView(text("Setup"))
        col.addView(button("1. Allow Bluetooth + notifications") { askPermissions() })
        col.addView(button("2. Allow screen lock (needed without root)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets FoldLink turn this screen off when the other phone powers off."))
        })
        col.addView(button("3. Don't let battery saver kill it") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })

        col.addView(button("4. Use FoldLink Home as launcher") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
        col.addView(button("5. Turn on FoldLink Keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })

        col.addView(text("Run"))
        col.addView(button("Start link") { startForegroundService(Intent(this, LinkService::class.java)) })
        col.addView(button("Stop link") { stopService(Intent(this, LinkService::class.java)) })
        col.addView(button("Open keyboard (on 2nd phone)") { startActivity(Intent(this, KeyboardActivity::class.java)) })
        col.addView(button("Test power-off on THIS phone") {
            Toast.makeText(this, PowerOff.shutdown(this), Toast.LENGTH_LONG).show()
        })

        setContentView(ScrollView(this).apply { addView(col) })
        askPermissions()
        loadDevices()
        tick()
    }

    private fun tick() {
        statusView.text = "Status: ${LinkService.status}"
        handler.postDelayed({ tick() }, 1000)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun askPermissions() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        if (p.isNotEmpty()) requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
    }

    private fun loadDevices() {
        val prefs = Prefs.get(this)
        val devices = try {
            getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList()
        } catch (e: SecurityException) { emptyList() }
        addrs = devices.map { it.address }
        val names = devices.map { "${it.name} (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf("No paired phones yet") })
        val saved = addrs.indexOf(prefs.getString(Prefs.PARTNER, null))
        if (saved >= 0) spinner.setSelection(saved)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos < addrs.size) prefs.edit().putString(Prefs.PARTNER, addrs[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
