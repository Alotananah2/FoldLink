package com.murdhi.foldlink

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs inside the privileged (shell or root) process next to the scrcpy server, started with:
 *   CLASSPATH=scrcpy.jar:FoldLink.apk app_process / com.murdhi.foldlink.SpanBridge <port> <scid> <scrcpy args…>
 * The scrcpy server connects to an abstract socket in this same process, and we hand the streams to the
 * FoldLink app over 127.0.0.1 TCP, so no SELinux socket rules get in the way.
 */
object SpanBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args[0].toInt()
        val scid = args[1]
        val serverArgs = args.drop(2).toTypedArray()

        val local = LocalServerSocket("scrcpy_$scid")
        val tcp = ServerSocket()
        tcp.reuseAddress = true
        tcp.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        println("FOLDSPAN bridge ready")

        Thread {
            try {
                val srvVideo: LocalSocket = local.accept()
                val srvControl: LocalSocket = local.accept()
                val appVideo: Socket = tcp.accept()
                val appControl: Socket = tcp.accept()
                appVideo.tcpNoDelay = true; appControl.tcpNoDelay = true
                pipe(srvVideo.inputStream, appVideo.getOutputStream())
                pipe(srvControl.inputStream, appControl.getOutputStream())
                pipe(appControl.getInputStream(), srvControl.outputStream, exitWhenDone = true)
                pipe(appVideo.getInputStream(), null, exitWhenDone = true)
            } catch (e: Exception) {
                println("FOLDSPAN bridge error: ${e.message}")
                System.exit(1)
            }
        }.start()

        // Run the real scrcpy server in this process.
        Class.forName("com.genymobile.scrcpy.Server")
            .getMethod("main", Array<String>::class.java)
            .invoke(null, serverArgs as Any)
    }

    private fun pipe(input: InputStream, out: OutputStream?, exitWhenDone: Boolean = false) {
        Thread {
            val buf = ByteArray(1 shl 16)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out?.write(buf, 0, n); out?.flush()
                }
            } catch (_: Exception) {}
            if (exitWhenDone) System.exit(0)   // the app hung up → shut down the virtual screen
        }.start()
    }
}
