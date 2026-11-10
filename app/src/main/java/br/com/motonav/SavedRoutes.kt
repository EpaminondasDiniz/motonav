package br.com.motonav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.UUID

/**
 * Rotas frequentes e viagens guardadas no celular (`filesDir/rotas.json`): linha da rota,
 * manobras e, nas viagens, os radares do caminho. Iniciar uma rota salva não precisa de internet.
 */
object SavedRoutes {
    data class Saved(
        val id: String,
        val name: String,
        val trip: Boolean,          // viagem (com mapa do caminho baixado) ou rota frequente
        val dest: LatLng,
        val route: RoutingClient.Route,
        val radars: List<Radars.Radar> = emptyList(),
        val savedAt: Long = System.currentTimeMillis()
    )

    private var file: File? = null
    @Volatile var all: List<Saved> = emptyList()
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "rotas.json")
        file = f
        all = runCatching { if (f.exists()) parse(f.readText()) else emptyList() }.getOrDefault(emptyList())
        Radars.setTripRadars(all.flatMap { it.radars })
    }

    @Synchronized
    fun add(name: String, trip: Boolean, dest: LatLng, route: RoutingClient.Route): Saved {
        val s = Saved(UUID.randomUUID().toString().take(8), name, trip, dest, route)
        all = all + s
        save()
        return s
    }

    @Synchronized
    fun update(s: Saved) {
        all = all.map { if (it.id == s.id) s else it }
        save()
        Radars.setTripRadars(all.flatMap { it.radars })
    }

    @Synchronized
    fun remove(id: String) {
        all = all.filterNot { it.id == id }
        save()
        Radars.setTripRadars(all.flatMap { it.radars })
    }

    private fun save() {
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        runCatching { file?.writeText(arr.toString()) }
    }

    private fun toJson(s: Saved): JSONObject {
        val pts = JSONArray()
        s.route.points.forEach { pts.put(Math.round(it.latitude * 1e6)).put(Math.round(it.longitude * 1e6)) }
        val mans = JSONArray()
        s.route.maneuvers.forEach { mans.put(JSONObject().put("i", it.index).put("t", it.turn.name).put("x", it.text)) }
        val rads = JSONArray()
        s.radars.forEach {
            rads.put(JSONObject().put("lat", it.lat).put("lon", it.lon)
                .put("max", it.maxspeed ?: JSONObject.NULL).put("dir", it.direction ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("id", s.id).put("name", s.name).put("trip", s.trip)
            .put("lat", s.dest.latitude).put("lon", s.dest.longitude).put("at", s.savedAt)
            .put("km", s.route.distanceKm).put("min", s.route.durationMin).put("prov", s.route.provider)
            .put("pts", pts).put("man", mans).put("radars", rads)
    }

    private fun parse(text: String): List<Saved> {
        val arr = JSONArray(text)
        val out = ArrayList<Saved>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getJSONArray("pts")
            val pts = ArrayList<LatLng>(p.length() / 2)
            var k = 0
            while (k + 1 < p.length()) { pts.add(LatLng(p.getLong(k) / 1e6, p.getLong(k + 1) / 1e6)); k += 2 }
            val m = o.optJSONArray("man") ?: JSONArray()
            val mans = ArrayList<RoutingClient.Maneuver>()
            for (j in 0 until m.length()) {
                val mj = m.getJSONObject(j)
                val turn = runCatching { NavState.Turn.valueOf(mj.getString("t")) }.getOrNull() ?: continue
                mans.add(RoutingClient.Maneuver(mj.getInt("i"), turn, mj.getString("x")))
            }
            val r = o.optJSONArray("radars") ?: JSONArray()
            val rads = ArrayList<Radars.Radar>()
            for (j in 0 until r.length()) {
                val rj = r.getJSONObject(j)
                rads.add(Radars.Radar(rj.getDouble("lat"), rj.getDouble("lon"),
                    if (rj.isNull("max")) null else rj.getInt("max"),
                    if (rj.isNull("dir")) null else rj.getDouble("dir")))
            }
            out.add(Saved(
                o.getString("id"), o.getString("name"), o.optBoolean("trip"),
                LatLng(o.getDouble("lat"), o.getDouble("lon")),
                RoutingClient.Route(pts, o.getDouble("km"), o.getDouble("min"),
                    o.optString("prov") + " · salva", mans),
                rads, o.optLong("at")
            ))
        }
        return out
    }
}
