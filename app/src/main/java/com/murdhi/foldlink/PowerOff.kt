package com.murdhi.foldlink

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

object PowerOff {
    /** Real power-off needs root. Without root we turn the screen off and lock instead. */
    fun shutdown(ctx: Context): String {
        if (runSu("reboot -p")) return "Powered off (root)"
        if (lock(ctx)) return "No root: screen locked instead"
        return "Can't power off: no root and screen-lock not allowed"
    }

    fun lock(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val comp = ComponentName(ctx, AdminReceiver::class.java)
        return if (dpm.isAdminActive(comp)) { dpm.lockNow(); true } else false
    }

    private fun runSu(cmd: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
    } catch (e: Exception) { false }
}
