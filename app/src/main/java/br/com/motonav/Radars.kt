package br.com.motonav

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Radares fixos do OpenStreetMap (`highway=speed_camera`, com `maxspeed` quando mapeado).
 *
 * Os servidores Overpass são públicos e lentos (30 s+, às vezes lotados). Por isso baixamos uma
 * região grande (raio de 40 km) de uma vez, guardamos em arquivo por 14 dias e só baixamos de
 * novo se a moto sair dessa região. A cada leitura do GPS procuramos o radar à frente e, a até
 * 400 m, publicamos o alerta em [NavState.radar].
 */
object Radars {
    data class Radar(val lat: Double, val lon: Double, val maxspeed: Int?, val direction: Double?)

    private const val RADIUS_M = 40_000
    private const val REFETCH_DISTANCE_M = 25_000f
    private const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
    private const val ALERT_M = 400.0
    private const val LOOK_M = 600.0
    private const val AHEAD_DEG = 35.0
    private const val USER_AGENT = "MotoNav/0.1 (Android; uso pessoal)"
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    /** Radares da região (40 km) + os das viagens salvas. */
    @Volatile var list: List<Radar> = emptyList()
        private set
    @Volatile private var base: List<Radar> = emptyList()
    @Volatile private var trips: List<Radar> = emptyList()

    /** Radares guardados nas viagens (funcionam sem internet, longe da região baixada). */
    fun setTripRadars(r: List<Radar>) {
        main.post {
            trips = r
            list = (base + trips).distinct()
            listeners.forEach { it() }
        }
    }

    /**
     * Bloqueante: radares ao longo de uma rota (até 1,5 km da linha), para uma viagem.
     * O Overpass aceita uma linha em "around" (lat,lon,lat,lon...).
     */
    fun downloadAlong(points: List<org.maplibre.android.geometry.LatLng>): List<Radar> {
        val sampled = ArrayList<org.maplibre.android.geometry.LatLng>()
        val r = FloatArray(1)
        for (p in points) {
            val l = sampled.lastOrNull()
            if (l == null) { sampled.add(p); continue }
            Location.distanceBetween(l.latitude, l.longitude, p.latitude, p.longitude, r)
            if (r[0] >= 1000f) sampled.add(p)
        }
        if (points.isNotEmpty() && sampled.last() != points.last()) sampled.add(points.last())
        val coords = sampled.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.US, it.latitude, it.longitude) }
        val q = "[out:json][timeout:90];node[\"highway\"=\"speed_camera\"](around:1500,$coords);out;"
        val json = query(q)
        return parseElements(JSONObject(json).getJSONArray("elements"))
    }
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var fetchedAt = 0L
    private var file: File? = null
    @Volatile private var fetching = false
    private var nextAttempt = 0L
    private var backoffMs = 30_000L
    private var alerted: Radar? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "radares").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "radares.json")
        file = f
        io.execute { runCatching { if (f.exists()) parseCache(f.readText()) } }
    }

    /** Chamado a cada leitura do GPS (thread principal). */
    fun onLocation(loc: Location) {
        maybeFetch(loc)
        updateAlert(loc)
    }

    // ------------------------------------------------------------------ alerta

    private fun updateAlert(loc: Location) {
        val radars = list
        if (radars.isEmpty()) { NavState.radar = null; return }
        val moving = loc.hasSpeed() && loc.speed > 2f && loc.hasBearing()
        val r = FloatArray(2)
        var best: Radar? = null
        var bestD = Double.MAX_VALUE
        for (rd in radars) {
            Location.distanceBetween(loc.latitude, loc.longitude, rd.lat, rd.lon, r)
            val d = r[0].toDouble()
            if (d > LOOK_M || d >= bestD) continue
            if (moving) {
                if (angleDiff(r[1].toDouble(), loc.bearing.toDouble()) > AHEAD_DEG) continue // não está à frente
                val dir = rd.direction
                if (dir != null && angleDiff(dir, loc.bearing.toDouble()) > 60) continue      // fiscaliza o outro sentido
            } else if (rd != alerted) {
                continue // parado: só mantém o alerta que já estava aparecendo
            }
            best = rd
            bestD = d
        }
        if (best == null || bestD > ALERT_M) {
            alerted = null
            NavState.radar = null
            return
        }
        if (best != alerted) {
            alerted = best
            DashLog.d("Radar a ${bestD.toInt()} m" + (best.maxspeed?.let { " ($it km/h)" } ?: ""))
        }
        NavState.radar = NavState.RadarAlert(
            bestD, best.maxspeed, SystemClock.elapsedRealtime(), if (loc.hasSpeed()) loc.speed else 0f
        )
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 540) % 360 - 180)
        return d
    }

    // ------------------------------------------------------------------ download e cache

    private fun maybeFetch(loc: Location) {
        if (fetching || file == null) return
        val now = System.currentTimeMillis()
        if (now < nextAttempt) return
        val far = if (fetchedAt == 0L) true else {
            val r = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, centerLat, centerLon, r)
            r[0] > REFETCH_DISTANCE_M
        }
        if (!far && now - fetchedAt < MAX_AGE_MS) return
        fetching = true
        val lat = loc.latitude
        val lon = loc.longitude
        io.execute {
            try {
                val json = download(lat, lon)
                val items = parseElements(JSONObject(json).getJSONArray("elements"))
                val cache = JSONObject()
                    .put("lat", lat).put("lon", lon).put("at", System.currentTimeMillis())
                    .put("radars", JSONArray().apply {
                        items.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)
                            .put("maxspeed", it.maxspeed ?: JSONObject.NULL)
                            .put("direction", it.direction ?: JSONObject.NULL)) }
                    })
                file?.writeText(cache.toString())
                setData(items, lat, lon, System.currentTimeMillis())
                DashLog.d("Radares: ${items.size} baixados do OpenStreetMap (raio de ${RADIUS_M / 1000} km)")
                backoffMs = 30_000L
            } catch (e: Exception) {
                DashLog.d("Radares: download falhou (${e.message}); nova tentativa em ${backoffMs / 1000} s")
                nextAttempt = System.currentTimeMillis() + backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(30 * 60_000L)
            } finally {
                fetching = false
            }
        }
    }

    private fun download(lat: Double, lon: Double): String =
        query("[out:json][timeout:60];node[\"highway\"=\"speed_camera\"](around:$RADIUS_M,$lat,$lon);out;")

    /** Consulta o Overpass (3 servidores). Usado também por Garage e SpeedLimits. */
    fun query(q: String): String {
        val body = "data=" + URLEncoder.encode(q, "UTF-8")
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                val conn = URL(server).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 75_000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                    return conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("sem servidor")
    }

    private fun parseCache(text: String) {
        val o = JSONObject(text)
        val arr = o.getJSONArray("radars")
        val items = ArrayList<Radar>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            items.add(Radar(
                r.getDouble("lat"), r.getDouble("lon"),
                if (r.isNull("maxspeed")) null else r.getInt("maxspeed"),
                if (r.isNull("direction")) null else r.getDouble("direction")
            ))
        }
        setData(items, o.getDouble("lat"), o.getDouble("lon"), o.getLong("at"))
        DashLog.d("Radares: ${items.size} carregados do celular")
    }

    private fun parseElements(arr: JSONArray): List<Radar> {
        val out = ArrayList<Radar>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            out.add(Radar(
                e.getDouble("lat"), e.getDouble("lon"),
                parseSpeed(tags.optString("maxspeed")),
                parseDirection(tags.optString("direction").ifEmpty { tags.optString("camera:direction") })
            ))
        }
        return out
    }

    private fun parseSpeed(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()

    private fun parseDirection(s: String): Double? {
        s.trim().toDoubleOrNull()?.let { return it }
        return when (s.trim().uppercase()) {
            "N" -> 0.0; "NE" -> 45.0; "E" -> 90.0; "SE" -> 135.0
            "S" -> 180.0; "SW" -> 225.0; "W" -> 270.0; "NW" -> 315.0
            else -> null // "forward"/"backward" dependem da via: ignoramos
        }
    }

    private fun setData(items: List<Radar>, lat: Double, lon: Double, at: Long) {
        main.post {
            base = items
            list = (base + trips).distinct()
            centerLat = lat
            centerLon = lon
            fetchedAt = at
            listeners.forEach { it() }
        }
    }

    /** Pontos para desenhar no mapa. */
    fun geoJson(): FeatureCollection =
        FeatureCollection.fromFeatures(list.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
}
