package br.com.motonav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Limite de velocidade da rua atual (tag maxspeed do OpenStreetMap), mesmo onde não há radar.
 * O mapa é dividido em quadrados de 0,03° (~3,3 km). Ao entrar num quadrado ainda não baixado,
 * busca no Overpass o bloco 3x3 em volta (uma consulta), guarda em filesDir/limites/ por 30 dias.
 * A rua escolhida é a mais próxima (até 25 m) que vai na mesma direção da moto.
 */
object SpeedLimits {
    private class Way(val limit: Int, val lat: DoubleArray, val lon: DoubleArray)

    private const val TILE = 0.03
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private const val MATCH_M = 25.0

    private var dir: File? = null
    private val tiles = ConcurrentHashMap<String, List<Way>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var failUntil = 0L
    private var misses = 0
    private var lastLogged: Int? = -1

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.applicationContext.filesDir, "limites").apply { mkdirs() }
    }

    private fun key(i: Int, j: Int) = "${i}_$j"

    fun onLocation(loc: Location) {
        val d = dir ?: return
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        val i = floor(loc.latitude / TILE).toInt()
        val j = floor(loc.longitude / TILE).toInt()
        val k = key(i, j)
        val ways = tiles[k]
        if (ways == null) { load(d, i, j); return }

        val limit = match(ways, loc)
        if (limit != null) { misses = 0; NavState.speedLimit = limit }
        else if (++misses >= 3) NavState.speedLimit = null
        val now = NavState.speedLimit
        if (now != lastLogged) { lastLogged = now; DashLog.d("Limite da via: ${now?.let { "$it km/h" } ?: "desconhecido"}") }
    }

    private fun load(d: File, i: Int, j: Int) {
        val k = key(i, j)
        if (!pending.add(k)) return
        io.execute {
            try {
                // 1) cache no celular
                val f = File(d, "$k.json")
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) {
                    tiles[k] = parse(JSONArray(f.readText()))
                    return@execute
                }
                // 2) Overpass: bloco 3x3 em volta (só com internet)
                if (!Net.online || SystemClock.elapsedRealtime() < failUntil) return@execute
                val s = (i - 1) * TILE; val n = (i + 2) * TILE
                val w = (j - 1) * TILE; val e = (j + 2) * TILE
                val q = "[out:json][timeout:40];way[\"highway\"][\"maxspeed\"]($s,$w,$n,$e);out geom;"
                val t0 = SystemClock.elapsedRealtime()
                val arr = JSONObject(Radars.query(q)).getJSONArray("elements")
                // separa por quadrado (uma rua que cruza quadrados entra em todos)
                val byTile = HashMap<String, JSONArray>()
                for (di in -1..1) for (dj in -1..1) byTile[key(i + di, j + dj)] = JSONArray()
                for (x in 0 until arr.length()) {
                    val el = arr.getJSONObject(x)
                    val g = el.optJSONArray("geometry") ?: continue
                    val seen = HashSet<String>()
                    for (p in 0 until g.length()) {
                        val pt = g.getJSONObject(p)
                        val tk = key(floor(pt.getDouble("lat") / TILE).toInt(), floor(pt.getDouble("lon") / TILE).toInt())
                        if (seen.add(tk)) byTile[tk]?.put(el)
                    }
                }
                for ((tk, a) in byTile) {
                    File(d, "$tk.json").writeText(a.toString())
                    tiles[tk] = parse(a)
                }
                DashLog.d("Limites de velocidade: ${arr.length()} ruas baixadas em ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Exception) {
                failUntil = SystemClock.elapsedRealtime() + 2 * 60_000
                DashLog.d("Limites de velocidade falharam: ${e.message}")
            } finally {
                pending.remove(k)
            }
        }
    }

    private fun parse(a: JSONArray): List<Way> {
        val out = ArrayList<Way>(a.length())
        for (x in 0 until a.length()) {
            val el = a.getJSONObject(x)
            val tags = el.optJSONObject("tags") ?: continue
            val limit = parseLimit(tags.optString("maxspeed")) ?: continue
            val g = el.optJSONArray("geometry") ?: continue
            if (g.length() < 2) continue
            val lat = DoubleArray(g.length()) { g.getJSONObject(it).getDouble("lat") }
            val lon = DoubleArray(g.length()) { g.getJSONObject(it).getDouble("lon") }
            out.add(Way(limit, lat, lon))
        }
        return out
    }

    private fun parseLimit(s: String): Int? {
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return null
        val kmh = if (s.contains("mph")) (n * 1.609).toInt() else n
        return kmh.takeIf { it in 5..150 }
    }

    /** Rua mais próxima (até 25 m), na direção do movimento quando a moto está andando. */
    private fun match(ways: List<Way>, loc: Location): Int? {
        val kx = 111_320.0 * cos(Math.toRadians(loc.latitude))
        val ky = 110_540.0
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) loc.bearing.toDouble() else null
        var best = MATCH_M
        var limit: Int? = null
        for (w in ways) {
            for (p in 0 until w.lat.size - 1) {
                val ax = (w.lon[p] - loc.longitude) * kx; val ay = (w.lat[p] - loc.latitude) * ky
                val bx = (w.lon[p + 1] - loc.longitude) * kx; val by = (w.lat[p + 1] - loc.latitude) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx; val cy = ay + t * dy
                val dist = sqrt(cx * cx + cy * cy)
                if (dist >= best) continue
                if (heading != null) {
                    val segDeg = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                    var diff = abs(segDeg - heading) % 180 // rua serve nos dois sentidos
                    if (diff > 90) diff = 180 - diff
                    if (diff > 40) continue
                }
                best = dist
                limit = w.limit
            }
        }
        return limit
    }
}
