package br.com.motonav

import android.location.Location
import android.location.LocationManager
import android.util.Log
import br.com.motonav.dash.DashLog
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Navegação: a cada leitura do GPS calcula onde você está na rota, apaga o trecho percorrido,
 * atualiza km/tempo restantes e recalcula a rota se você sair dela.
 */
object NavEngine {
    private const val OFF_ROUTE_M = 40.0      // distância da linha para considerar "fora da rota"
    private const val OFF_ROUTE_FIXES = 2     // leituras seguidas fora da rota antes de recalcular (~2 s)
    private const val REROUTE_MIN_MS = 6_000L // intervalo mínimo entre recálculos
    private const val ARRIVED_M = 30.0
    private const val MAX_ACCURACY_M = 35f      // fused/rede: acima disso a leitura é ignorada
    private const val MAX_ACCURACY_GPS_M = 80f  // GPS de verdade: só ignora se estiver muito ruim

    /** Leituras descartadas por precisão (aparece no log do GPS a cada 30 s). */
    @Volatile var ignored = 0

    private class Active(
        val points: List<LatLng>,
        val suffix: DoubleArray,   // distância do ponto i até o fim (m)
        val totalM: Double,
        val totalMin: Double,
        val dest: LatLng,
        val label: String,
        val maneuvers: List<RoutingClient.Maneuver>,
        var idx: Int = 0
    )

    private const val ANNOUNCE_M = 500.0     // aviso "onde entrar" a partir de 500 m da manobra

    @Volatile private var active: Active? = null
    private var offCount = 0
    private var lastReroute = 0L
    @Volatile private var rerouting = false
    private val io = Executors.newSingleThreadExecutor()

    fun setRoute(r: RoutingClient.Route, dest: LatLng, label: String) {
        val pts = r.points
        val suffix = DoubleArray(pts.size)
        for (i in pts.size - 2 downTo 0) suffix[i] = suffix[i + 1] + dist(pts[i], pts[i + 1])
        val a = Active(pts, suffix, suffix.firstOrNull() ?: 0.0, r.durationMin, dest, label, r.maneuvers)
        // números para a tela de chegada: recalcular a rota (mesmo destino) não zera
        if (active?.dest != dest) {
            startDistM = TripStats.distanceM
            startMovingMs = TripStats.movingMs
            routeMaxKmh = 0f
        }
        // Rota salva pode começar com a moto no meio do caminho: acha o trecho mais próximo na rota toda.
        NavState.lastLocation?.let { loc ->
            if (pts.size >= 2) {
                val here = LatLng(loc.latitude, loc.longitude)
                var best = Double.MAX_VALUE
                for (i in 0 until pts.size - 1) {
                    val d = distToSegment(here, pts[i], pts[i + 1]).first
                    if (d < best) { best = d; a.idx = i }
                }
            }
        }
        active = a
        offCount = 0
        lastAnnounced = -1
        NavState.set(NavState.Route(pts, dest, summary(r.distanceKm * 1000, r.durationMin)))
        NavState.lastLocation?.let { onLocation(it) }
    }

    fun clear() {
        active = null
        NavState.set(null)
    }

    fun onLocation(loc: Location) {
        val a = active ?: return
        if (a.points.size < 2) return
        // Leitura imprecisa não mexe na rota (uma leitura de rede/Wi-Fi "pulava" o progresso e a
        // rota sumia). Mas o GPS de verdade, em movimento, às vezes passa de 35 m: barrar isso
        // congelava a rota e atrasava o aviso de manobra. Por isso o limite depende da fonte.
        val maxAcc = if (loc.provider == LocationManager.GPS_PROVIDER) MAX_ACCURACY_GPS_M else MAX_ACCURACY_M
        if (loc.hasAccuracy() && loc.accuracy > maxAcc) {
            ignored++
            return
        }
        val here = LatLng(loc.latitude, loc.longitude)
        lastFix = loc
        if (loc.hasSpeed() && loc.speed * 3.6f > routeMaxKmh && loc.speed < 70f) routeMaxKmh = loc.speed * 3.6f

        // procura o segmento mais próximo, olhando um pouco para trás e bastante para frente
        val from = (a.idx - 5).coerceAtLeast(0)
        val to = (a.idx + 400).coerceAtMost(a.points.size - 2)
        var best = Double.MAX_VALUE
        var bestI = a.idx
        var bestP = a.points[a.idx]
        for (i in from..to) {
            val (d, p) = distToSegment(here, a.points[i], a.points[i + 1])
            if (d < best) { best = d; bestI = i; bestP = p }
        }

        val remaining = dist(bestP, a.points[bestI + 1]) + a.suffix[bestI + 1]
        if (remaining < ARRIVED_M || dist(here, a.dest) < ARRIVED_M) {
            val dM = (TripStats.distanceM - startDistM).coerceAtLeast(0.0)
            val ms = (TripStats.movingMs - startMovingMs).coerceAtLeast(0L)
            val avg = if (ms > 5_000) (dM / (ms / 1000.0) * 3.6).toFloat() else 0f
            NavState.arrival = NavState.Arrival(a.label, dM, ms, avg, routeMaxKmh)
            DashLog.d("Chegou ao destino: ${a.label} (${"%.1f".format(dM / 1000)} km, ${ms / 60_000} min em movimento)")
            clear()
            return
        }

        // com GPS menos preciso, exige uma distância maior para dizer "fora da rota"
        val offLimit = maxOf(OFF_ROUTE_M, if (loc.hasAccuracy()) loc.accuracy * 1.2 else 0.0)
        if (best > offLimit) {
            offCount++
            if (offCount >= OFF_ROUTE_FIXES) reroute(here, loc, a)
            return // fora da rota: mantém a linha como está até recalcular
        }
        offCount = 0
        a.idx = bestI

        val pts = ArrayList<LatLng>(a.points.size - bestI)
        pts.add(bestP)
        pts.addAll(a.points.subList(bestI + 1, a.points.size))
        val minutes = if (a.totalM > 0) a.totalMin * remaining / a.totalM else 0.0
        NavState.set(NavState.Route(pts, a.dest, summary(remaining, minutes), nextManeuver(a, bestI, remaining), remainingMin = minutes))
    }

    private var startDistM = 0.0
    private var startMovingMs = 0L
    private var routeMaxKmh = 0f
    private var lastAnnounced = -1
    private var waitingNet = false
    private var lastFix: Location? = null

    /**
     * Próxima manobra à frente, a qualquer distância (o painel mostra sempre a seta e a distância).
     * Sem manobras pela frente: "Siga até o destino". O log "Aviso" sai a partir de 500 m.
     */
    private fun nextManeuver(a: Active, segment: Int, remaining: Double): NavState.Maneuver? {
        val speed0 = if (lastFix?.hasSpeed() == true) lastFix!!.speed else 0f
        val m = a.maneuvers.firstOrNull { it.index > segment && it.turn != NavState.Turn.ARRIVE }
            ?: return NavState.Maneuver(NavState.Turn.ARRIVE, "Siga até o destino", remaining,
                android.os.SystemClock.elapsedRealtime(), speed0)
        val distTo = remaining - a.suffix[m.index]
        if (distTo <= ANNOUNCE_M && m.index != lastAnnounced) {
            lastAnnounced = m.index
            DashLog.d("Aviso: ${m.turn.arrow} ${distTo.toInt()} m · ${m.text}")
        }
        val speed = if (lastFix?.hasSpeed() == true) lastFix!!.speed else 0f
        return NavState.Maneuver(m.turn, m.text, distTo.coerceAtLeast(0.0), android.os.SystemClock.elapsedRealtime(), speed)
    }

    private fun reroute(from: LatLng, loc: Location, a: Active) {
        val now = System.currentTimeMillis()
        if (rerouting || now - lastReroute < REROUTE_MIN_MS) return
        if (!Net.online) {
            // Sem internet não há como calcular caminho novo: mantém a rota e tenta assim que voltar.
            if (!waitingNet) { waitingNet = true; DashLog.d("Fora da rota, mas sem internet: recalculo quando a conexão voltar") }
            return
        }
        waitingNet = false
        rerouting = true
        lastReroute = now
        DashLog.d("Fora da rota: recalculando...")
        // mostra "Recalculando..." na hora, sem esperar o servidor
        NavState.route?.let { NavState.set(it.copy(maneuver = null, rerouting = true)) }
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2f) loc.bearing else null
        io.execute {
            try {
                val r = RoutingClient.route(from, a.dest, heading)
                if (active === a) setRoute(r, a.dest, a.label)
                DashLog.d("Rota recalculada em ${System.currentTimeMillis() - now} ms " +
                    "(${r.provider}): ${"%.1f".format(r.distanceKm)} km")
            } catch (e: Exception) {
                Log.w("MotoNav", "recalcular rota falhou", e)
                DashLog.d("Recalcular falhou: ${e.message}")
                // falhou: tenta de novo em ~1,5 s em vez de esperar o intervalo inteiro
                lastReroute = System.currentTimeMillis() - REROUTE_MIN_MS + 1_500
            } finally {
                rerouting = false
            }
        }
    }

    private fun summary(meters: Double, minutes: Double): String {
        val dist = if (meters >= 1000) String.format(Locale("pt", "BR"), "%.1f km", meters / 1000)
        else "${(meters / 10).toInt() * 10} m"
        val min = minutes.toInt().coerceAtLeast(1)
        val time = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        return "$dist · $time"
    }

    // ---------- geometria simples (aproximação plana, ótima para distâncias de rua) ----------

    private fun dist(a: LatLng, b: LatLng): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
        return r[0].toDouble()
    }

    /** Distância (m) de [p] ao segmento [a]-[b] e o ponto mais próximo no segmento. */
    private fun distToSegment(p: LatLng, a: LatLng, b: LatLng): Pair<Double, LatLng> {
        val kx = 111_320.0 * cos(Math.toRadians(p.latitude))
        val ky = 110_540.0
        val ax = (a.longitude - p.longitude) * kx
        val ay = (a.latitude - p.latitude) * ky
        val bx = (b.longitude - p.longitude) * kx
        val by = (b.latitude - p.latitude) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        val closest = LatLng(p.latitude + cy / ky, p.longitude + cx / kx)
        return sqrt(cx * cx + cy * cy) to closest
    }
}
