package com.murdhi.foldlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray

/** The main phone's app list, mirrored on the second phone so the home grid matches. */
object RemoteApps {
    data class A(val pkg: String, val label: String, val icon: Bitmap?)
    @Volatile var list: List<A> = emptyList()

    fun load(arr: JSONArray) {
        val out = ArrayList<A>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bytes = try { Base64.decode(o.optString("i"), Base64.NO_WRAP) } catch (e: Exception) { null }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            out += A(o.getString("p"), o.getString("l"), bmp)
        }
        list = out
        Link.emit(Link.msg("apps_loaded"))
    }
}
