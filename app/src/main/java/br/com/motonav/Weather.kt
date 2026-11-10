package br.com.motonav

import android.location.Location
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Chuva no caminho (Open-Meteo, gratuito e sem chave), a cada 10 min com internet.
 * Consulta a posição atual e, se houver rota, pontos a 8, 16 e 24 km à frente; estima quando a
 * moto passa em cada ponto e olha a previsão de 15 em 15 min para aquela hora.
 * Resultado em NavState.weather ("🌧 Chuva à frente em ~20 min · 16 km") e NavState.temperature.
 */
object Weather {
    private const val INTERVAL_MS = 10 * 60_000L
    private const val RAIN_MM = 0.2          // mm em 15 min: garoa de verdade
    private val AHEAD_KM = listOf(8.0, 16.0, 24.0)

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastFetch = 0L
    @Volatile private var busy = false
    private var lastText: String? = null

    fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (busy || !Net.online || (lastFetch != 0L && now - lastFetch < INTERVAL_MS)) return
        busy = true
        lastFetch = now
        val here = LatLng(loc.latitude, loc.longitude)
        val ahead = NavState.route?.points?.let { pointsAhead(it) } ?: emptyList()
        val speedKmh = if (loc.hasSpeed() && loc.speed > 5f) loc.speed * 3.6 else 40.0
        io.execute {
            try {
                fetch(listOf(here to 0.0) + ahead, speedKmh.coerceIn(30.0, 90.0))
            } catch (e: Exception) {
                DashLog.d("Clima falhou: ${e.message}")
                lastFetch = SystemClock.elapsedRealtime() - INTERVAL_MS + 60_000 // tenta de novo em 1 min
            } finally {
                busy = false
            }
        }
    }

    /** Pontos da rota a 8/16/24 km (com a distância até eles). */
    private fun pointsAhead(pts: List<LatLng>): List<Pair<LatLng, Double>> {
        val out = mutableListOf<Pair<LatLng, Double>>()
        var acc = 0.0
        var next = 0
        val r = FloatArray(1)
        for (i in 1 until pts.size) {
            Location.distanceBetween(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude, r)
            acc += r[0] / 1000.0
            while (next < AHEAD_KM.size && acc >= AHEAD_KM[next]) { out.add(pts[i] to acc); next++ }
            if (next >= AHEAD_KM.size) break
        }
        return out
    }

    private fun fetch(points: List<Pair<LatLng, Double>>, speedKmh: Double) {
        val lats = points.joinToString(",") { String.format(Locale.US, "%.4f", it.first.latitude) }
        val lons = points.joinToString(",") { String.format(Locale.US, "%.4f", it.first.longitude) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&minutely_15=precipitation&forecast_minutely_15=12&current=temperature_2m&timezone=GMT"
        val conn = URL(url).openConnection() as HttpURLConnection
        val text = try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "MotoNav/0.1 (Android; uso pessoal)")
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val list: List<JSONObject> = text.trimStart().let { t ->
            if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            else listOf(JSONObject(t))
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val now = System.currentTimeMillis()

        list.firstOrNull()?.optJSONObject("current")?.let {
            if (it.has("temperature_2m")) NavState.temperature = Math.round(it.getDouble("temperature_2m")).toInt()
        }

        var alert: String? = null
        for ((i, o) in list.withIndex()) {
            if (i >= points.size) break
            val m = o.optJSONObject("minutely_15") ?: continue
            val times = m.getJSONArray("time")
            val rain = m.getJSONArray("precipitation")
            if (times.length() == 0) continue
            val t0 = fmt.parse(times.getString(0))?.time ?: continue
            val distKm = points[i].second
            val etaMin = distKm / speedKmh * 60
            // aqui: próximos 30 min; na rota: a janela de 15 min em que a moto passa por lá
            val from = ((now - t0) / 900_000L).toInt() + (etaMin / 15).toInt()
            val to = if (i == 0) from + 2 else from + 1
            var wet = false
            for (k in from..to) if (k in 0 until rain.length() && rain.optDouble(k, 0.0) >= RAIN_MM) wet = true
            if (wet) {
                alert = if (i == 0) "🌧 Chuva aqui nos próximos 30 min"
                else "🌧 Chuva à frente em ~${etaMin.toInt()} min · ${distKm.toInt()} km"
                break
            }
        }
        NavState.weather = alert
        if (alert != lastText) {
            lastText = alert
            DashLog.d("Clima: ${alert ?: "sem chuva no caminho"} (${NavState.temperature ?: "?"}°C, ${points.size} pontos)")
        }
    }
}
