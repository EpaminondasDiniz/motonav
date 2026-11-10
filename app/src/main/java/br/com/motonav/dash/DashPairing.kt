package br.com.motonav.dash

import android.content.Context
import android.net.Uri

/** Guarda o painel pareado (vem do QR code do painel: ...?mac=aa:bb:...&name=XXXX). */
object DashPairing {
    private const val PREFS = "dash_pairing"

    data class Dash(val mac: String, val name: String)

    fun parseQr(raw: String): Dash? {
        return try {
            val uri = Uri.parse(raw.trim())
            val mac = uri.getQueryParameter("mac")?.lowercase()?.trim()
            val name = uri.getQueryParameter("name") ?: uri.getQueryParameter("ssid") ?: "Painel"
            if (mac.isNullOrEmpty() || !mac.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}"))) null
            else Dash(mac, name)
        } catch (_: Exception) {
            null
        }
    }

    fun save(ctx: Context, dash: Dash) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mac", dash.mac).putString("name", dash.name).apply()
    }

    fun load(ctx: Context): Dash? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mac = p.getString("mac", null) ?: return null
        return Dash(mac, p.getString("name", "Painel") ?: "Painel")
    }
}
