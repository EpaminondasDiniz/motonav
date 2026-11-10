package br.com.motonav

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import android.content.Context

/**
 * Serviços da TomTom. A chave pode ser digitada no app (Ajustes -> Chave TomTom, guardada só no
 * celular) ou vir de local.properties -> BuildConfig.TOMTOM_KEY. Sem chave, o app usa só os
 * serviços gratuitos sem chave (Valhalla/OSRM, sem trânsito nem incidentes).
 *  - camada de trânsito (tiles de fluxo: verde/laranja/vermelho por cima das ruas);
 *  - incidentes (acidente, via fechada, obras...) consultados a cada 2 min perto da moto, com
 *    alerta quando há um à frente (até 1,5 km), em [NavState.incident];
 *  - rota de moto considerando o trânsito (usada pelo RoutingClient, se a Routing API estiver
 *    liberada para a chave).
 * Plano gratuito: 50 mil tiles e 2.500 consultas por dia, de sobra para uso pessoal.
 */
object TomTom {
    @Volatile private var userKey = ""
    val KEY: String get() = userKey.ifBlank { BuildConfig.TOMTOM_KEY }
    val enabled: Boolean get() = KEY.isNotBlank()

    /** Chave digitada no app (tem prioridade sobre a do local.properties). */
    fun loadKey(ctx: Context) {
        userKey = ctx.applicationContext.getSharedPreferences("tomtom", Context.MODE_PRIVATE).getString("key", "") ?: ""
    }

    fun setKey(ctx: Context, key: String) {
        userKey = key.trim()
        ctx.applicationContext.getSharedPreferences("tomtom", Context.MODE_PRIVATE).edit().putString("key", userKey).apply()
        routingPausedUntil = 0L
    }

    val hasUserKey: Boolean get() = userKey.isNotBlank()

    /**
     * Rotas e incidentes gastam a cota da chave. Com a chave embutida no app, só se a opção estiver
     * ligada no local.properties (TOMTOM_ROUTING / TOMTOM_INCIDENTS); com a chave da própria
     * pessoa (digitada no app), sempre.
     */
    val routingAllowed: Boolean get() = enabled && (hasUserKey || BuildConfig.TOMTOM_ROUTING)
    val incidentsAllowed: Boolean get() = enabled && (hasUserKey || BuildConfig.TOMTOM_INCIDENTS)

    /** Se a chave responder 401/403 na Routing API, pausa por 10 min e tenta de novo (liberação pode demorar). */
    @Volatile private var routingPausedUntil = 0L
    val routingEnabled: Boolean get() = SystemClock.elapsedRealtime() >= routingPausedUntil

    private const val USER_AGENT = "MotoNav/0.1 (Android; uso pessoal)"

    // ------------------------------------------------------------------ camada de trânsito

    private const val TRAFFIC_SOURCE = "tomtom-traffic"
    private const val TRAFFIC_LAYER = "tomtom-traffic-layer"
    private const val INCIDENT_SOURCE = "tomtom-incidents"
    private const val INCIDENT_LAYER = "tomtom-incidents-layer"

    /**
     * Adiciona o trânsito ao mapa, por baixo dos nomes de rua (que continuam legíveis).
     * Chamar antes de adicionar a linha da rota, para a rota ficar por cima.
     */
    fun addTrafficLayer(s: Style) {
        if (!enabled || s.getSource(TRAFFIC_SOURCE) != null) return
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png" +
            "?key=$KEY&tileSize=256&thickness=8"
        val tiles = TileSet("2.1.0", url).apply { minZoom = 6f; maxZoom = 22f }
        s.addSource(RasterSource(TRAFFIC_SOURCE, tiles, 256))
        val traffic = RasterLayer(TRAFFIC_LAYER, TRAFFIC_SOURCE)
            .withProperties(PropertyFactory.rasterOpacity(0.85f))
        val firstLabel = s.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) s.addLayerBelow(traffic, firstLabel) else s.addLayer(traffic)

        // incidentes como linhas vermelhas tracejadas
        s.addSource(GeoJsonSource(INCIDENT_SOURCE, incidentsGeoJson()))
        val inc = LineLayer(INCIDENT_LAYER, INCIDENT_SOURCE).withProperties(
            PropertyFactory.lineColor("#D50000"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1f)),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT)
        )
        if (firstLabel != null) s.addLayerBelow(inc, firstLabel) else s.addLayer(inc)
    }

    /** Atualiza as linhas de incidentes num mapa que já tem a camada. */
    fun refreshIncidents(s: Style) {
        s.getSourceAs<GeoJsonSource>(INCIDENT_SOURCE)?.setGeoJson(incidentsGeoJson())
    }

    // ------------------------------------------------------------------ incidentes

    data class Incident(val category: Int, val magnitude: Int, val label: String, val points: List<LatLng>)

    private const val POLL_MS = 120_000L
    private const val BOX_DEG = 0.05          // ~5,5 km para cada lado
    private const val ALERT_M = 1_500.0
    private const val AHEAD_DEG = 35.0

    @Volatile var incidents: List<Incident> = emptyList()
        private set
    private var lastPoll = 0L
    @Volatile private var polling = false
    private var alerted: Incident? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tomtom").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** A cada leitura do GPS (thread principal). */
    fun onLocation(loc: Location) {
        if (!incidentsAllowed) { if (NavState.incident != null) NavState.incident = null; return }
        val now = SystemClock.elapsedRealtime()
        if (!polling && (lastPoll == 0L || now - lastPoll > POLL_MS)) {
            lastPoll = now
            poll(loc.latitude, loc.longitude)
        }
        updateAlert(loc)
    }

    private fun poll(lat: Double, lon: Double) {
        polling = true
        io.execute {
            try {
                val fields = "{incidents{type,geometry{type,coordinates}," +
                    "properties{iconCategory,magnitudeOfDelay,events{description,code},from,to}}}"
                val bbox = "${lon - BOX_DEG},${lat - BOX_DEG},${lon + BOX_DEG},${lat + BOX_DEG}"
                val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=$KEY" +
                    "&bbox=$bbox&fields=${URLEncoder.encode(fields, "UTF-8")}" +
                    "&language=pt-PT&timeValidityFilter=present"
                val arr = JSONObject(httpGet(url)).optJSONArray("incidents")
                val out = ArrayList<Incident>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val p = f.optJSONObject("properties") ?: continue
                    val cat = p.optInt("iconCategory")
                    val mag = p.optInt("magnitudeOfDelay")
                    val label = labelFor(cat, mag) ?: continue
                    val street = p.optString("from").takeIf { it.isNotBlank() && it != "null" }
                    val pts = parsePoints(f.optJSONObject("geometry"))
                    if (pts.isEmpty()) continue
                    out.add(Incident(cat, mag, if (street != null) "$label · $street" else label, pts))
                }
                main.post {
                    val changed = out.size != incidents.size
                    incidents = out
                    if (changed) DashLog.d("TomTom: ${out.size} incidentes relevantes por perto")
                    listeners.forEach { it() }
                }
            } catch (e: Exception) {
                DashLog.d("TomTom incidentes falhou: ${e.message}")
            } finally {
                polling = false
            }
        }
    }

    /** Só os tipos que valem um alerta (o congestionamento comum já aparece nas cores do mapa). */
    private fun labelFor(cat: Int, mag: Int): String? = when (cat) {
        1 -> "Acidente"
        3 -> "Perigo na via"
        6 -> if (mag >= 3) "Congestionamento intenso" else null
        7 -> "Faixa interditada"
        8 -> "Via fechada"
        9 -> "Obras"
        11 -> "Alagamento"
        14 -> "Veículo parado na via"
        else -> null
    }

    private fun parsePoints(g: JSONObject?): List<LatLng> {
        if (g == null) return emptyList()
        val coords = g.optJSONArray("coordinates") ?: return emptyList()
        val out = ArrayList<LatLng>()
        if (g.optString("type") == "Point") {
            out.add(LatLng(coords.getDouble(1), coords.getDouble(0)))
        } else {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                out.add(LatLng(c.getDouble(1), c.getDouble(0)))
            }
        }
        return out
    }

    private fun updateAlert(loc: Location) {
        val list = incidents
        if (list.isEmpty()) { alerted = null; NavState.incident = null; return }
        val moving = loc.hasSpeed() && loc.speed > 2f && loc.hasBearing()
        // Navegando: só avisa do que está NA rota (antes avisava de ruas fechadas fora do caminho).
        val routeAhead = NavState.route?.points?.let { routeAhead(it) }
        val r = FloatArray(2)
        var best: Incident? = null
        var bestD = Double.MAX_VALUE
        for (inc in list) {
            for (p in inc.points) {
                Location.distanceBetween(loc.latitude, loc.longitude, p.latitude, p.longitude, r)
                val d = r[0].toDouble()
                if (d > ALERT_M || d >= bestD) continue
                if (routeAhead != null) {
                    if (!nearLine(p, routeAhead)) continue
                } else {
                    if (moving && d > 60 && angleDiff(r[1].toDouble(), loc.bearing.toDouble()) > AHEAD_DEG) continue
                    if (!moving && inc != alerted) continue
                }
                best = inc
                bestD = d
            }
        }
        if (best == null) { alerted = null; NavState.incident = null; return }
        if (best != alerted) {
            alerted = best
            DashLog.d("Alerta: ${best.label} a ${bestD.toInt()} m")
        }
        NavState.incident = NavState.IncidentAlert(best.category, best.label, bestD,
            SystemClock.elapsedRealtime(), if (loc.hasSpeed()) loc.speed else 0f)
    }

    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

    /** Trecho da rota que ainda falta, até ~2 km à frente (o alcance do alerta). */
    private fun routeAhead(pts: List<LatLng>): List<LatLng> {
        val out = ArrayList<LatLng>()
        val r = FloatArray(1)
        var acc = 0.0
        for (i in pts.indices) {
            out.add(pts[i])
            if (i > 0) {
                Location.distanceBetween(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude, r)
                acc += r[0]
                if (acc > ALERT_M + 500) break
            }
        }
        return out
    }

    /** O ponto do incidente está a até 40 m da linha da rota? */
    private fun nearLine(p: LatLng, line: List<LatLng>): Boolean {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(p.latitude))
        val ky = 110_540.0
        for (i in 0 until line.size - 1) {
            val ax = (line[i].longitude - p.longitude) * kx
            val ay = (line[i].latitude - p.latitude) * ky
            val bx = (line[i + 1].longitude - p.longitude) * kx
            val by = (line[i + 1].latitude - p.latitude) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            if (cx * cx + cy * cy <= 40.0 * 40.0) return true
        }
        return false
    }

    private fun incidentsGeoJson(): FeatureCollection = FeatureCollection.fromFeatures(
        incidents.filter { it.points.size >= 2 }.map { inc ->
            Feature.fromGeometry(LineString.fromLngLats(inc.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
    )

    // ------------------------------------------------------------------ rota com trânsito

    /** Bloqueante. Rota de moto considerando o trânsito atual. */
    fun route(from: LatLng, to: LatLng, heading: Float?): RoutingClient.Route {
        if (!enabled || !routingEnabled) throw IllegalStateException("TomTom Routing indisponível")
        var url = "https://api.tomtom.com/routing/1/calculateRoute/" +
            "${from.latitude},${from.longitude}:${to.latitude},${to.longitude}/json?key=$KEY" +
            "&traffic=true&travelMode=motorcycle&routeType=fastest" +
            "&instructionsType=text&language=pt-BR"
        if (heading != null) url += "&vehicleHeading=${heading.toInt().mod(360)}"
        val json = try {
            JSONObject(httpGet(url))
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) {
                routingPausedUntil = SystemClock.elapsedRealtime() + 10 * 60_000L
                DashLog.d("TomTom: Routing API recusada (HTTP ${e.code}); usando Valhalla/OSRM, tento de novo em 10 min")
            }
            throw e
        }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val summary = route.getJSONObject("summary")
        val pts = ArrayList<LatLng>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val arr = legs.getJSONObject(l).getJSONArray("points")
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                pts.add(LatLng(p.getDouble("latitude"), p.getDouble("longitude")))
            }
        }
        val mans = ArrayList<RoutingClient.Maneuver>()
        route.optJSONObject("guidance")?.optJSONArray("instructions")?.let { ins ->
            for (i in 0 until ins.length()) {
                val m = ins.getJSONObject(i)
                val turn = turnFor(m.optString("maneuver")) ?: continue
                val idx = m.optInt("pointIndex", -1)
                if (idx !in pts.indices) continue
                val text = m.optString("message").trim().trimEnd('.')
                mans.add(RoutingClient.Maneuver(idx, turn, text.ifEmpty { turn.name }))
            }
        }
        val delay = summary.optInt("trafficDelayInSeconds")
        return RoutingClient.Route(
            points = pts,
            distanceKm = summary.getDouble("lengthInMeters") / 1000.0,
            durationMin = summary.getDouble("travelTimeInSeconds") / 60.0,
            provider = "TomTom (moto + trânsito" + (if (delay > 60) ", +${delay / 60} min" else "") + ")",
            maneuvers = mans
        )
    }

    private fun turnFor(m: String): NavState.Turn? = when {
        m.startsWith("ARRIVE") -> NavState.Turn.ARRIVE
        m.startsWith("ROUNDABOUT") -> NavState.Turn.ROUNDABOUT
        m.contains("UTURN") -> NavState.Turn.UTURN_LEFT
        m == "SHARP_RIGHT" -> NavState.Turn.SHARP_RIGHT
        m == "SHARP_LEFT" -> NavState.Turn.SHARP_LEFT
        m == "TURN_RIGHT" -> NavState.Turn.RIGHT
        m == "TURN_LEFT" -> NavState.Turn.LEFT
        m == "KEEP_RIGHT" || m == "BEAR_RIGHT" || m.endsWith("EXIT_RIGHT") -> NavState.Turn.SLIGHT_RIGHT
        m == "KEEP_LEFT" || m == "BEAR_LEFT" || m.endsWith("EXIT_LEFT") -> NavState.Turn.SLIGHT_LEFT
        m.startsWith("ENTER_") || m == "ENTRANCE_RAMP" -> NavState.Turn.MERGE
        else -> null // DEPART, STRAIGHT, FOLLOW, WAYPOINT...
    }

    // ------------------------------------------------------------------ HTTP

    class HttpError(val code: Int) : Exception("HTTP $code")

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
