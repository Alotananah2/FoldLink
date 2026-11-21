package com.murdhi.foldlink

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val ROLE = "role"            // "main" or "second"
    const val PARTNER = "partner"      // Bluetooth address of the other phone
    const val MIRROR_SCREEN = "mirror" // lock 2nd phone when main screen turns off
    const val OFF_ON_LOST = "offLost"  // power off 2nd phone if link drops
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("fold", Context.MODE_PRIVATE)
}
