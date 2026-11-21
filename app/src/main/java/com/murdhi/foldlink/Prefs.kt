package com.murdhi.foldlink

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ROLE = "role"            // "main" or "second"
    const val SWAP = "swap"            // main phone is on the right instead of left
    const val PARTNER = "partner"      // Bluetooth address of the other phone
    const val OFF_ON_LOST = "offLost"
    const val PINS = "dock"
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("fold", Context.MODE_PRIVATE)
    fun isMain(c: Context) = get(c).getString(ROLE, "main") == "main"
    fun isLeft(c: Context) = isMain(c) xor get(c).getBoolean(SWAP, false)
    fun pins(c: Context): MutableList<String> =
        get(c).getString(PINS, "")!!.split(",").filter { it.isNotBlank() }.toMutableList()
}
