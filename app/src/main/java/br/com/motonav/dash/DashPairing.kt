package br.com.motonav.dash

import android.content.Context
import android.net.Uri

/**
 * Guarda o painel pareado. Há dois tipos de QR, reconhecidos sozinhos:
 *  - WIFI_DIRECT (painéis até 2024): ...?modelid=…&action=8&ssid=…&mac=aa:bb:...&name=XXXX
 *    O celular se liga ao painel por Wi-Fi Direct.
 *  - BLE (painéis 2025+, texto "ID:CARBIT" + 12 letras embaixo do QR): ...?modelid=…&sn=…&action=128&bm=AA:BB:...
 *    ou só o código CARBITxxxxxxxxxxxx. O celular fala com o painel pelo Bluetooth LE, cria um
 *    hotspot próprio e o painel entra nele.
 */
object DashPairing {
    private const val PREFS = "dash_pairing"

    enum class Kind { WIFI_DIRECT, BLE }

    /**
     * [mac]: no Wi-Fi Direct, o endereço do painel; no BLE, o endereço Bluetooth (bm=) ou, sem ele,
     * o do código CARBIT. [bleName]: nome que o painel anuncia no Bluetooth ("EC" + 8 letras).
     */
    data class Dash(val mac: String, val name: String, val kind: Kind = Kind.WIFI_DIRECT,
                    val bleMac: String? = null, val bleName: String? = null) {
        val isBle: Boolean get() = kind == Kind.BLE
    }

    private val MAC = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val TOKEN = Regex("CARBIT([0-9A-Fa-f]{12})")

    fun parseQr(raw: String): Dash? {
        val text = raw.trim()
        // Só o código impresso embaixo do QR (ex.: "ID:CARBITDC0D302487D2")
        if (!text.contains('?')) {
            val t = TOKEN.find(text) ?: return null
            return ble(null, formatMac(t.groupValues[1]), null)
        }
        return try {
            val uri = Uri.parse(text)
            val mac = uri.getQueryParameter("mac")?.let { normMac(it) }
            if (mac != null) {
                val name = uri.getQueryParameter("name") ?: uri.getQueryParameter("ssid") ?: "Painel"
                return Dash(mac, name, Kind.WIFI_DIRECT)
            }
            val bm = uri.getQueryParameter("bm")?.let { normMac(it) }
            val action = uri.getQueryParameter("action")?.toIntOrNull()
            val token = TOKEN.find(text)?.let { formatMac(it.groupValues[1]) }
            // action com o bit 0x80: o celular cria a rede (painel novo)
            if (bm == null && token == null && (action == null || action and 0x80 == 0)) return null
            if (!text.contains("carbit", ignoreCase = true) && bm == null) return null
            ble(bm, token, uri.getQueryParameter("name"))
        } catch (_: Exception) {
            null
        }
    }

    private fun ble(bm: String?, token: String?, name: String?): Dash {
        val id = (bm ?: token)
        val bleName = id?.replace(":", "")?.takeLast(8)?.uppercase()?.let { "EC$it" }
        return Dash(id ?: "", name ?: bleName ?: "Painel", Kind.BLE, bm, bleName)
    }

    private fun normMac(v: String): String? {
        val s = v.lowercase().trim()
        if (s.matches(MAC)) return s
        if (s.matches(Regex("[0-9a-f]{12}"))) return formatMac(s)
        return null
    }

    private fun formatMac(hex12: String) = hex12.lowercase().chunked(2).joinToString(":")

    fun save(ctx: Context, dash: Dash) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mac", dash.mac).putString("name", dash.name)
            .putString("kind", dash.kind.name)
            .putString("bleMac", dash.bleMac).putString("bleName", dash.bleName)
            .apply()
    }

    fun load(ctx: Context): Dash? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mac = p.getString("mac", null) ?: return null
        val kind = runCatching { Kind.valueOf(p.getString("kind", null) ?: "") }.getOrDefault(Kind.WIFI_DIRECT)
        return Dash(mac, p.getString("name", "Painel") ?: "Painel", kind,
            p.getString("bleMac", null), p.getString("bleName", null))
    }
}
