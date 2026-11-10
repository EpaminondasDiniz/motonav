package br.com.motonav

import android.content.Context
import br.com.motonav.dash.DashLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Procura versão nova no GitHub (releases do repositório [BuildConfig.UPDATE_REPO]), no máximo
 * 1x por dia. A versão publicada é a tag "v1.2" e o APK vai anexado à release. Só funciona com o
 * repositório público (a consulta é anônima). Sem UPDATE_REPO (versão pessoal), não faz nada.
 */
object Updater {
    data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

    private const val PREFS = "updater"
    private const val DAY_MS = 24 * 3600 * 1000L

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Bloqueia: chamar fora da thread principal. [force] ignora o "1x por dia" e a versão pulada. */
    fun check(ctx: Context, force: Boolean): Release? {
        if (!enabled) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("lastCheck", 0L) < DAY_MS) return null
        p.edit().putLong("lastCheck", now).apply()
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        val json = try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MotoNav-updater")
            if (conn.responseCode != 200) { DashLog.d("atualização: GitHub respondeu ${conn.responseCode}"); return null }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (version.isBlank() || !newer(version, BuildConfig.VERSION_NAME)) return null
        if (!force && p.getString("skipped", "") == version) return null
        val assets = json.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
        }
        return Release(version, json.optString("body").trim(), apk, json.optString("html_url"))
    }

    fun skip(ctx: Context, version: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", version).apply()
    }

    /** "1.10" > "1.9"; compara número por número. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
