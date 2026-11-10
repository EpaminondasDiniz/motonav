package br.com.motonav

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Calcula rotas usando servidores públicos e gratuitos baseados em OpenStreetMap.
 *  - Valhalla (FOSSGIS) com perfil "motorcycle": rota pensada para moto (preferida)
 *  - OSRM (demo) com perfil de carro: reserva
 * Os dois são consultados AO MESMO TEMPO: o Valhalla tem até 2,5 s de preferência; depois disso
 * vale o primeiro que responder. Assim um servidor lento/fora do ar não atrasa o recálculo.
 * Os dois são de uso justo: ótimo para uso pessoal, não para milhares de requisições.
 */
object RoutingClient {

    private const val USER_AGENT = "MotoNav/0.1 (Android; uso pessoal)"
    private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
    private const val OSRM_URL = "https://router.project-osrm.org/route/v1/driving/"
    private const val PREFER_VALHALLA_MS = 2_500L
    private const val TOTAL_TIMEOUT_MS = 9_000L

    /** Manobra: [index] é o ponto da linha da rota onde ela acontece. */
    data class Maneuver(val index: Int, val turn: NavState.Turn, val text: String)

    data class Route(
        val points: List<LatLng>,
        val distanceKm: Double,
        val durationMin: Double,
        val provider: String,
        val maneuvers: List<Maneuver> = emptyList()
    )

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "rota").apply { isDaemon = true } }

    /**
     * Bloqueante: chame fora da thread principal.
     * [heading] = direção atual da moto (graus), para a rota nova seguir em frente em vez de
     * mandar fazer meia-volta.
     */
    fun route(from: LatLng, to: LatLng, heading: Float? = null): Route {
        // ordem de preferência: TomTom (moto + trânsito) > Valhalla (moto) > OSRM (carro)
        val jobs = buildList {
            if (TomTom.routingAllowed && TomTom.routingEnabled) add(pool.submit<Route> { TomTom.route(from, to, heading) })
            add(pool.submit<Route> { valhalla(from, to, heading) })
            add(pool.submit<Route> { osrm(from, to, heading) })
        }
        var lastError: Throwable? = null
        val start = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - start < TOTAL_TIMEOUT_MS) {
                val preferWindow = System.currentTimeMillis() - start < PREFER_VALHALLA_MS
                // Na janela de preferência só aceita o melhor que já respondeu, na ordem; depois, qualquer um.
                for (j in jobs) {
                    if (!j.isDone) { if (preferWindow) break else continue }
                    try { return j.get() } catch (e: Exception) { lastError = (e as? ExecutionException)?.cause ?: e }
                }
                if (jobs.all { it.isDone }) break
                Thread.sleep(40)
            }
        } finally {
            jobs.forEach { it.cancel(true) }
        }
        throw IllegalStateException("nenhum servidor de rota respondeu (${lastError?.message ?: "tempo esgotado"})")
    }

    // ------------------------------------------------------------------ Valhalla

    private fun valhalla(from: LatLng, to: LatLng, heading: Float?): Route {
        val start = JSONObject().put("lat", from.latitude).put("lon", from.longitude)
        if (heading != null) start.put("heading", heading.toInt()).put("heading_tolerance", 60)
        val body = JSONObject().apply {
            put("locations", JSONArray()
                .put(start)
                .put(JSONObject().put("lat", to.latitude).put("lon", to.longitude)))
            put("costing", "motorcycle")
            put("directions_options", JSONObject()
                .put("units", "kilometers")
                .put("language", "pt-BR")
                .put("directions_type", "instructions"))
        }
        val json = JSONObject(http(VALHALLA_URL, body.toString()))
        val trip = json.getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = decodePolyline6(leg.getString("shape"))
        val mans = ArrayList<Maneuver>()
        leg.optJSONArray("maneuvers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val turn = valhallaTurn(m.optInt("type")) ?: continue
                val idx = m.optInt("begin_shape_index", -1)
                if (idx !in points.indices) continue
                val text = m.optString("instruction").trim().trimEnd('.')
                mans.add(Maneuver(idx, turn, text.ifEmpty { defaultText(turn, streetOf(m)) }))
            }
        }
        return Route(
            points = points,
            distanceKm = summary.getDouble("length"),
            durationMin = summary.getDouble("time") / 60.0,
            provider = "Valhalla (moto)",
            maneuvers = mans
        )
    }

    private fun streetOf(m: JSONObject): String =
        m.optJSONArray("street_names")?.optString(0).orEmpty()

    /** Tipos de manobra do Valhalla -> o que avisamos (null = não avisa). */
    private fun valhallaTurn(type: Int): NavState.Turn? = when (type) {
        9, 18, 20, 23, 37 -> NavState.Turn.SLIGHT_RIGHT
        10 -> NavState.Turn.RIGHT
        11 -> NavState.Turn.SHARP_RIGHT
        12 -> NavState.Turn.UTURN_RIGHT
        13 -> NavState.Turn.UTURN_LEFT
        14 -> NavState.Turn.SHARP_LEFT
        15 -> NavState.Turn.LEFT
        16, 19, 21, 24, 38 -> NavState.Turn.SLIGHT_LEFT
        25 -> NavState.Turn.MERGE
        26 -> NavState.Turn.ROUNDABOUT
        4, 5, 6 -> NavState.Turn.ARRIVE
        else -> null // início, "siga em frente", nome da rua muda, saída da rotatória...
    }

    // ------------------------------------------------------------------ OSRM

    private fun osrm(from: LatLng, to: LatLng, heading: Float?): Route {
        var url = OSRM_URL +
            "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
            "?overview=full&geometries=geojson&steps=true"
        if (heading != null) url += "&bearings=${heading.toInt()},60;"
        val json = JSONObject(http(url, null))
        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) throw IllegalStateException("OSRM não encontrou rota")
        val r = routes.getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<LatLng>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(LatLng(c.getDouble(1), c.getDouble(0)))
        }
        val mans = ArrayList<Maneuver>()
        var searchFrom = 0
        r.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps")?.let { steps ->
            for (i in 0 until steps.length()) {
                val st = steps.getJSONObject(i)
                val m = st.getJSONObject("maneuver")
                val turn = osrmTurn(m.optString("type"), m.optString("modifier")) ?: continue
                val loc = m.getJSONArray("location")
                val idx = nearestIndex(pts, LatLng(loc.getDouble(1), loc.getDouble(0)), searchFrom)
                searchFrom = idx
                mans.add(Maneuver(idx, turn, defaultText(turn, st.optString("name"))))
            }
        }
        return Route(
            points = pts,
            distanceKm = r.getDouble("distance") / 1000.0,
            durationMin = r.getDouble("duration") / 60.0,
            provider = "OSRM (carro)",
            maneuvers = mans
        )
    }

    private fun osrmTurn(type: String, modifier: String): NavState.Turn? {
        if (type == "arrive") return NavState.Turn.ARRIVE
        if (type == "roundabout" || type == "rotary" || type == "roundabout turn") return NavState.Turn.ROUNDABOUT
        if (type == "depart" || type == "new name" || type == "continue" && modifier == "straight") return null
        if (type == "exit roundabout" || type == "exit rotary") return null
        if (type == "merge") return NavState.Turn.MERGE
        return when (modifier) {
            "slight right" -> NavState.Turn.SLIGHT_RIGHT
            "right" -> NavState.Turn.RIGHT
            "sharp right" -> NavState.Turn.SHARP_RIGHT
            "slight left" -> NavState.Turn.SLIGHT_LEFT
            "left" -> NavState.Turn.LEFT
            "sharp left" -> NavState.Turn.SHARP_LEFT
            "uturn" -> NavState.Turn.UTURN_LEFT
            else -> null
        }
    }

    private fun nearestIndex(pts: List<LatLng>, p: LatLng, from: Int): Int {
        var best = from
        var bestD = Float.MAX_VALUE
        val r = FloatArray(1)
        for (i in from until pts.size) {
            Location.distanceBetween(p.latitude, p.longitude, pts[i].latitude, pts[i].longitude, r)
            if (r[0] < bestD) { bestD = r[0]; best = i }
            if (bestD < 1f) break
        }
        return best
    }

    /** Texto em português quando o servidor não manda instrução pronta. */
    private fun defaultText(turn: NavState.Turn, street: String): String {
        val verb = when (turn) {
            NavState.Turn.SLIGHT_RIGHT -> "Mantenha-se à direita"
            NavState.Turn.RIGHT -> "Vire à direita"
            NavState.Turn.SHARP_RIGHT -> "Vire acentuadamente à direita"
            NavState.Turn.SLIGHT_LEFT -> "Mantenha-se à esquerda"
            NavState.Turn.LEFT -> "Vire à esquerda"
            NavState.Turn.SHARP_LEFT -> "Vire acentuadamente à esquerda"
            NavState.Turn.UTURN_LEFT, NavState.Turn.UTURN_RIGHT -> "Faça o retorno"
            NavState.Turn.ROUNDABOUT -> "Entre na rotatória"
            NavState.Turn.MERGE -> "Entre na via"
            NavState.Turn.ARRIVE -> return "Destino"
        }
        return if (street.isBlank()) verb else "$verb na $street"
    }

    // ------------------------------------------------------------------ HTTP

    /** GET quando postBody == null, senão POST com JSON. Tempos curtos: rota tem pressa. */
    private fun http(url: String, postBody: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Decodifica polyline com precisão 6 (formato do Valhalla). */
    private fun decodePolyline6(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)

            out.add(LatLng(lat / 1e6, lng / 1e6))
        }
        return out
    }
}
