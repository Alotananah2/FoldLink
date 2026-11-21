package com.murdhi.foldlink

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager

object PowerOff {
    /** Real power-off needs root. Without root we turn the screen off and lock instead. */
    fun shutdown(ctx: Context): String {
        if (runSu("reboot -p")) return "powered off (root)"
        if (lock(ctx)) return "no root, so screen locked instead"
        return "can't: no root and screen-lock not allowed (setup step 2)"
    }

    fun lock(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val comp = ComponentName(ctx, AdminReceiver::class.java)
        return if (dpm.isAdminActive(comp)) { dpm.lockNow(); true } else false
    }

    @Suppress("DEPRECATION")
    fun wake(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "foldlink:wake").acquire(3000)
    }

    private fun runSu(cmd: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
    } catch (e: Exception) { false }
}
