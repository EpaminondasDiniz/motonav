package br.com.motonav.dash

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log em memória, mostrado na tela e copiável (para mandar no chat sem precisar do PC). */
object DashLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var listener: (() -> Unit)? = null

    fun d(msg: String) {
        Log.d("MotoNavDash", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())} $msg")
            while (lines.size > MAX) lines.removeFirst()
        }
        listener?.invoke()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        listener?.invoke()
    }
}
