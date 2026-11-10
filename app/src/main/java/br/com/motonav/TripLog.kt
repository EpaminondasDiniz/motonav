package br.com.motonav

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.concurrent.Executors

/**
 * Diário de viagens (filesDir/viagens.json): cada passeio fica guardado com o trajeto, km, tempo
 * em movimento, média e máxima. Uma viagem termina depois de 15 min parado; a próxima saída abre
 * outra. Viagens com menos de 300 m são descartadas. Também alimenta o hodômetro da [Garage].
 */
object TripLog {
    class Trip(
        val id: Long,
        val startMs: Long,
        var endMs: Long,
        var distanceM: Double = 0.0,
        var movingMs: Long = 0L,
        var maxKmh: Float = 0f,
        val points: MutableList<LatLng> = mutableListOf(),
        /** Horário de cada ponto (ms); viagens antigas não têm (o GPX estima pela distância). */
        val times: MutableList<Long> = mutableListOf()
    ) {
        val avgKmh: Float get() = if (movingMs > 5_000) (distanceM / (movingMs / 1000.0) * 3.6).toFloat() else 0f
    }

    private const val IDLE_END_MS = 15 * 60_000L
    private const val MIN_KEEP_M = 300.0
    private const val POINT_EVERY_M = 25f
    private const val MAX_TRIPS = 60
    private const val MAX_POINTS = 4000

    private var file: File? = null
    private val trips = mutableListOf<Trip>()
    private var current: Trip? = null
    private var last: Location? = null
    private var lastMoveAt = 0L
    private var lastSave = 0L
    private val io = Executors.newSingleThreadExecutor()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "viagens.json")
        file = f
        runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("trips")
            for (i in 0 until arr.length()) trips.add(fromJson(arr.getJSONObject(i)))
        }
        // a última pode ter ficado aberta (app fechado no meio): continua se for recente
        trips.lastOrNull()?.let { if (System.currentTimeMillis() - it.endMs < IDLE_END_MS) { current = it; lastMoveAt = it.endMs } }
    }

    /** Viagens, da mais nova para a mais antiga (cópia). */
    @Synchronized
    fun all(): List<Trip> = trips.filter { it.distanceM >= MIN_KEEP_M }.reversed()

    @Synchronized
    fun delete(id: Long) {
        trips.removeAll { it.id == id }
        if (current?.id == id) current = null
        save(force = true)
    }

    @Synchronized
    fun onLocation(loc: Location) {
        if (file == null) return
        if (loc.hasAccuracy() && loc.accuracy > 30f) return
        val prev = last
        last = loc
        if (prev == null) return
        val dtMs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000
        if (dtMs <= 0 || dtMs > 10_000) return
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val d = prev.distanceTo(loc)
        if (speed <= 1.5f || d >= 80f * (dtMs / 1000f).coerceAtLeast(1f)) return // parado ou salto do GPS

        val now = System.currentTimeMillis()
        var t = current
        if (t == null || now - lastMoveAt > IDLE_END_MS) {
            current?.let { if (it.distanceM < MIN_KEEP_M) trips.remove(it) }
            t = Trip(now, now, now)
            t.points.add(LatLng(prev.latitude, prev.longitude))
            t.times.add(now - dtMs)
            trips.add(t)
            while (trips.size > MAX_TRIPS) trips.removeAt(0)
            current = t
        }
        lastMoveAt = now
        t.endMs = now
        t.distanceM += d
        t.movingMs += dtMs
        val kmh = speed * 3.6f
        if (kmh > t.maxKmh && kmh < 250f) t.maxKmh = kmh
        val lp = t.points.lastOrNull()
        val r = FloatArray(1)
        if (lp != null) Location.distanceBetween(lp.latitude, lp.longitude, loc.latitude, loc.longitude, r)
        if (lp == null || r[0] >= POINT_EVERY_M) {
            t.points.add(LatLng(loc.latitude, loc.longitude))
            if (t.times.size == t.points.size - 1) t.times.add(now)
            if (t.points.size > MAX_POINTS) { // trajeto muito longo: guarda 1 ponto a cada 2
                val keep = t.points.filterIndexed { i, _ -> i % 2 == 0 }
                t.points.clear(); t.points.addAll(keep)
                if (t.times.isNotEmpty()) {
                    val keepT = t.times.filterIndexed { i, _ -> i % 2 == 0 }
                    t.times.clear(); t.times.addAll(keepT)
                }
            }
        }
        Garage.addKm(d / 1000.0)
        save(force = false)
    }

    private fun save(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 60_000) return
        lastSave = now
        val arr = JSONArray()
        trips.forEach { arr.put(toJson(it)) }
        val text = JSONObject().put("trips", arr).toString()
        io.execute { runCatching { f.writeText(text) } }
    }

    private fun toJson(t: Trip): JSONObject {
        val pts = JSONArray()
        t.points.forEach { pts.put(Math.round(it.longitude * 1e5) / 1e5); pts.put(Math.round(it.latitude * 1e5) / 1e5) }
        val ts = JSONArray()
        if (t.times.size == t.points.size) t.times.forEach { ts.put((it - t.startMs) / 1000) }
        return JSONObject().put("id", t.id).put("start", t.startMs).put("end", t.endMs)
            .put("dist", t.distanceM).put("moving", t.movingMs).put("max", t.maxKmh.toDouble()).put("pts", pts).put("ts", ts)
    }

    private fun fromJson(o: JSONObject): Trip {
        val t = Trip(o.getLong("id"), o.getLong("start"), o.getLong("end"), o.getDouble("dist"),
            o.getLong("moving"), o.getDouble("max").toFloat())
        val pts = o.getJSONArray("pts")
        var i = 0
        while (i + 1 < pts.length()) { t.points.add(LatLng(pts.getDouble(i + 1), pts.getDouble(i))); i += 2 }
        o.optJSONArray("ts")?.let { ts ->
            if (ts.length() == t.points.size) for (k in 0 until ts.length()) t.times.add(t.startMs + ts.getLong(k) * 1000)
        }
        return t
    }

    /**
     * Arquivo GPX 1.1 (Strava, Google Earth, Wikiloc...). Viagens antigas sem horário por ponto
     * recebem horários estimados pela distância, entre o início e o fim da viagem.
     */
    @Synchronized
    fun gpx(t: Trip): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val times: List<Long> = if (t.times.size == t.points.size) t.times.toList() else {
            val cum = DoubleArray(t.points.size)
            val r = FloatArray(1)
            for (k in 1 until t.points.size) {
                Location.distanceBetween(t.points[k - 1].latitude, t.points[k - 1].longitude, t.points[k].latitude, t.points[k].longitude, r)
                cum[k] = cum[k - 1] + r[0]
            }
            val total = cum.lastOrNull()?.takeIf { it > 0 } ?: 1.0
            cum.map { t.startMs + ((t.endMs - t.startMs) * (it / total)).toLong() }
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MotoNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>Viagem de moto ").append(fmt.format(java.util.Date(t.startMs))).append("</name>")
        sb.append("<time>").append(fmt.format(java.util.Date(t.startMs))).append("</time></metadata>\n")
        sb.append("  <trk><name>Viagem de moto</name><type>motorcycling</type><trkseg>\n")
        t.points.forEachIndexed { k, p ->
            sb.append("    <trkpt lat=\"").append(String.format(java.util.Locale.US, "%.6f", p.latitude))
                .append("\" lon=\"").append(String.format(java.util.Locale.US, "%.6f", p.longitude)).append("\">")
                .append("<time>").append(fmt.format(java.util.Date(times[k]))).append("</time></trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }
}
