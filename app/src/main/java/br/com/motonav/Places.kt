package br.com.motonav

import android.content.Context
import android.location.Location
import br.com.motonav.dash.DashLog
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sugestões de endereço/lugar enquanto a pessoa digita, perto de onde ela está:
 *  - TomTom Search (busca "fuzzy": aceita erro de digitação, nome de loja, bairro...), se a chave
 *    tiver acesso à Search API; se responder 401/403, fica pausada por 10 min;
 *  - Photon (komoot, dados do OpenStreetMap, gratuito e sem chave).
 * As duas rodam em paralelo (até ~3,5 s) e os resultados são juntados sem repetir.
 * Também guarda os últimos destinos escolhidos ("recentes").
 */
object Places {
    data class Place(
        val title: String,
        val subtitle: String,
        val pos: LatLng,
        val poi: Boolean = false,
        val recent: Boolean = false,
        val distanceM: Double? = null
    )

    private const val UA = "MotoNav/0.1 (Android; uso pessoal)"
    private const val MAX_RECENTS = 12
    private val pool = Executors.newFixedThreadPool(3)
    /** Sem acesso à Search API (401/403): pausa 10 min e tenta de novo (a liberação demora a valer). */
    @Volatile private var tomTomSearchOffUntil = 0L
    private val tomTomSearchOff: Boolean get() = android.os.SystemClock.elapsedRealtime() < tomTomSearchOffUntil

    /**
     * Busca (bloqueia: chamar fora da thread principal). Com número no texto ("rua x 100"), o
     * Geocoder do celular (dados do Google) entra primeiro: o OpenStreetMap quase não tem número de
     * casa no Brasil.
     */
    fun search(ctx: Context, query: String, near: LatLng?): List<Place> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val hasNumber = q.any { it.isDigit() }
        val tt = if (TomTom.enabled && !tomTomSearchOff) pool.submit(Callable { tomTom(q, near) }) else null
        val ph = pool.submit(Callable { photon(q, near) })
        val gc = pool.submit(Callable { geocoder(ctx, q, near) })
        val a = runCatching { tt?.get(3_500, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        val b = runCatching { ph.get(3_500, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        val g = runCatching { gc.get(3_500, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        val out = ArrayList<Place>()
        val order = if (hasNumber) listOf(g, a, b) else listOf(a, b, g)
        for (src in order) for (p in src) if (out.none { same(it, p) }) out.add(p)
        // a ordem de relevância dos serviços já favorece o que está perto; só calcula a distância
        return out.take(8).withDistance(near)
    }

    private fun List<Place>.withDistance(near: LatLng?): List<Place> = map { p ->
        if (near == null) p else {
            val r = FloatArray(1)
            Location.distanceBetween(near.latitude, near.longitude, p.pos.latitude, p.pos.longitude, r)
            p.copy(distanceM = r[0].toDouble())
        }
    }

    private fun same(a: Place, b: Place): Boolean {
        // mesma rua/avenida em trechos diferentes do mesmo bairro conta como repetida
        if (a.title.equals(b.title, true) && a.subtitle.equals(b.subtitle, true)) return true
        val r = FloatArray(1)
        Location.distanceBetween(a.pos.latitude, a.pos.longitude, b.pos.latitude, b.pos.longitude, r)
        return r[0] < 120f && (a.title.equals(b.title, true) || a.title.contains(b.title, true) || b.title.contains(a.title, true))
    }

    // ------------------------------------------------------------------ TomTom

    private fun tomTom(q: String, near: LatLng?): List<Place> {
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: ""
        val url = "https://api.tomtom.com/search/2/search/${URLEncoder.encode(q, "UTF-8").replace("+", "%20")}.json" +
            "?key=${TomTom.KEY}&limit=7&countrySet=BR&language=pt-BR&typeahead=true$bias"
        val (code, text) = get(url)
        if (code == 401 || code == 403) {
            tomTomSearchOffUntil = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L
            DashLog.d("Busca TomTom sem acesso (HTTP $code): tento de novo em 10 min. Marque a Search API na chave.")
            return emptyList()
        }
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(text).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address")
            val free = addr?.optString("freeformAddress").orEmpty()
            val poiName = r.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() }
            val street = addr?.optString("streetName")?.takeIf { it.isNotBlank() }
            val number = addr?.optString("streetNumber")?.takeIf { it.isNotBlank() }
            val title = poiName ?: street?.let { s -> number?.let { "$s, $it" } ?: s } ?: free.ifBlank { continue }
            val sub = listOfNotNull(
                if (poiName != null) free.takeIf { it.isNotBlank() } else null,
                addr?.optString("municipalitySubdivision")?.takeIf { it.isNotBlank() && poiName == null },
                addr?.optString("municipality")?.takeIf { it.isNotBlank() && poiName == null }
            ).distinct().joinToString(" · ")
            out.add(Place(title, sub, LatLng(pos.getDouble("lat"), pos.getDouble("lon")), poi = poiName != null))
        }
        return out
    }

    // ------------------------------------------------------------------ Geocoder do celular

    @Suppress("DEPRECATION")
    private fun geocoder(ctx: Context, q: String, near: LatLng?): List<Place> {
        if (!android.location.Geocoder.isPresent()) return emptyList()
        val g = android.location.Geocoder(ctx, Locale("pt", "BR"))
        val list = runCatching {
            if (near != null) {
                val d = 0.6 // ~65 km em volta primeiro
                g.getFromLocationName(q, 3, near.latitude - d, near.longitude - d, near.latitude + d, near.longitude + d)
                    ?.takeIf { it.isNotEmpty() } ?: g.getFromLocationName(q, 3)
            } else g.getFromLocationName(q, 3)
        }.getOrNull().orEmpty()
        return list.mapNotNull { a ->
            if (!a.hasLatitude() || !a.hasLongitude()) return@mapNotNull null
            val street = a.thoroughfare
            val title = when {
                street != null && a.subThoroughfare != null -> "$street, ${a.subThoroughfare}"
                street != null -> street
                else -> a.featureName ?: a.getAddressLine(0) ?: return@mapNotNull null
            }
            val sub = listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea, a.adminArea)
                .distinct().filter { it != title }.joinToString(" · ")
            Place(title, sub, LatLng(a.latitude, a.longitude))
        }
    }

    // ------------------------------------------------------------------ Photon (OpenStreetMap)

    private fun photon(q: String, near: LatLng?): List<Place> {
        val bias = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: ""
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=8&lang=default$bias"
        val (code, text) = get(url)
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(text).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val pr = f.optJSONObject("properties") ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val cc = pr.optString("countrycode")
            if (cc.isNotBlank() && !cc.equals("BR", true) && near != null) continue
            val name = pr.optString("name").takeIf { it.isNotBlank() }
            val street = pr.optString("street").takeIf { it.isNotBlank() }
            val number = pr.optString("housenumber").takeIf { it.isNotBlank() }
            val title = name ?: street?.let { s -> number?.let { "$s, $it" } ?: s } ?: continue
            val sub = listOfNotNull(
                if (name != null) street?.let { s -> number?.let { "$s, $it" } ?: s } else null,
                pr.optString("district").takeIf { it.isNotBlank() } ?: pr.optString("locality").takeIf { it.isNotBlank() },
                pr.optString("city").takeIf { it.isNotBlank() },
                pr.optString("state").takeIf { it.isNotBlank() }
            ).distinct().filter { it != title }.joinToString(" · ")
            val isPoi = name != null && pr.optString("osm_key") !in setOf("highway", "place", "boundary")
            out.add(Place(title, sub, LatLng(c.getDouble(1), c.getDouble(0)), poi = isPoi))
        }
        return out
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_500
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ recentes

    fun recents(ctx: Context): List<Place> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences("places", Context.MODE_PRIVATE).getString("recent", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("t"), o.optString("s"), LatLng(o.getDouble("lat"), o.getDouble("lon")), recent = true)
        }
    }.getOrDefault(emptyList())

    fun addRecent(ctx: Context, p: Place) {
        val list = (listOf(p) + recents(ctx).filterNot { same(it, p) }).take(MAX_RECENTS)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("s", it.subtitle).put("lat", it.pos.latitude).put("lon", it.pos.longitude)) }
        ctx.getSharedPreferences("places", Context.MODE_PRIVATE).edit().putString("recent", a.toString()).apply()
    }

    fun distanceText(m: Double?): String = when {
        m == null -> ""
        m < 1000 -> "${(m / 10).toInt() * 10} m"
        m < 100_000 -> String.format(Locale("pt", "BR"), "%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }
}
