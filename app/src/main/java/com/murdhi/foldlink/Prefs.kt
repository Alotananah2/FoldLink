package com.murdhi.foldlink

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ROLE = "role"            // "main" or "second"
    const val SWAP = "swap"            // main phone is on the right instead of left
    const val PARTNER = "partner"      // Bluetooth address of the other phone
    const val OFF_ON_LOST = "offLost"
    const val PINS = "dock"
    const val HOME = "home"            // apps placed on the home screen
    const val WIDGETS = "widgets"      // this phone's widget ids
    const val SPLIT_KB = "splitKb"
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("fold", Context.MODE_PRIVATE)
    fun isMain(c: Context) = get(c).getString(ROLE, "main") == "main"
    fun isLeft(c: Context) = isMain(c) xor get(c).getBoolean(SWAP, false)
    private val DEFAULT_HOME = listOf(
        "com.samsung.android.dialer", "com.google.android.dialer", "com.hihonor.contacts",
        "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.hihonor.mms",
        "com.sec.android.app.camera", "com.hihonor.camera", "com.sec.android.gallery3d", "com.hihonor.photos",
        "com.android.chrome", "com.whatsapp", "com.zhiliaoapp.musically", "com.google.android.youtube",
        "com.android.vending", "com.roblox.client", "com.android.settings")

    fun home(c: Context): MutableList<String> {
        val p = get(c)
        if (!p.contains(HOME)) {
            val installed = DEFAULT_HOME.filter { c.packageManager.getLaunchIntentForPackage(it) != null }
            p.edit().putString(HOME, installed.joinToString(",")).apply()
        }
        return p.getString(HOME, "")!!.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    fun widgets(c: Context): MutableList<Int> =
        get(c).getString(WIDGETS, "")!!.split(",").mapNotNull { it.toIntOrNull() }.toMutableList()

    fun pins(c: Context): MutableList<String> =
        get(c).getString(PINS, "")!!.split(",").filter { it.isNotBlank() }.toMutableList()
}
