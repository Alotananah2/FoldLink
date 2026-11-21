package com.murdhi.foldlink

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bluetooth link between the two phones: one JSON message per line. */
object Link {
    val LINK_UUID: UUID = UUID.fromString("7f3c2a10-5b1e-4c8a-9d2f-0a1b2c3d4e5f")
    val FILE_UUID: UUID = UUID.fromString("7f3c2a11-5b1e-4c8a-9d2f-0a1b2c3d4e5f")

    @Volatile var connected = false
    @Volatile var status = "Stopped"
    @Volatile var peerAddr: String? = null

    val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val sendExec = Executors.newSingleThreadExecutor()
    @Volatile private var sock: Closeable? = null
    @Volatile private var out: BufferedWriter? = null

    fun msg(t: String, vararg kv: Pair<String, Any?>) = JSONObject().apply {
        put("t", t); kv.forEach { put(it.first, it.second) }
    }

    fun send(o: JSONObject) {
        val line = o.toString()
        sendExec.execute { write(line) }
    }

    /** Blocking send, used while the phone is shutting down. */
    fun sendNow(o: JSONObject) {
        val line = o.toString()
        try { sendExec.submit { write(line) }.get(1500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    private fun write(line: String) {
        try { out?.apply { write(line); write("\n"); flush() } } catch (_: Exception) {}
    }

    @Synchronized fun attach(s: Closeable, output: OutputStream, peer: String) {
        sock = s
        out = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))
        peerAddr = peer
        connected = true
        emit(msg("_connected"))
    }

    fun isCurrent(s: Closeable) = sock === s

    fun readLoop(input: InputStream) {
        val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (true) {
            val line = r.readLine() ?: break
            val o = try { JSONObject(line) } catch (e: Exception) { continue }
            if (o.optString("t") == "ping") continue
            emit(o)
        }
    }

    @Synchronized fun detach(s: Closeable) {
        if (sock !== s) return
        try { s.close() } catch (_: Exception) {}
        sock = null; out = null; connected = false
        emit(msg("_disconnected"))
    }

    fun closeCurrent() { try { sock?.close() } catch (_: Exception) {} }

    /** Deliver a message to every listener on the main thread. */
    fun emit(o: JSONObject) {
        main.post { listeners.forEach { try { it(o) } catch (_: Exception) {} } }
    }
}
