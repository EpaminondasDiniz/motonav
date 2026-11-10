package br.com.motonav

import android.location.Location
import android.os.Handler
import android.os.Looper
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CopyOnWriteArrayList

/** Estado compartilhado entre o mapa do celular, o mapa do painel e a navegação. */
object NavState {
    /** Tipo de manobra, com a seta mostrada no aviso. */
    enum class Turn(val arrow: String) {
        SLIGHT_RIGHT("↗"), RIGHT("→"), SHARP_RIGHT("↘"),
        SLIGHT_LEFT("↖"), LEFT("←"), SHARP_LEFT("↙"),
        UTURN_LEFT("↶"), UTURN_RIGHT("↷"),
        ROUNDABOUT("↻"), MERGE("↑"), ARRIVE("⚑")
    }

    /** Próxima manobra, quando faltam até 500 m para ela. */
    data class Maneuver(
        val turn: Turn,
        val text: String,
        val distanceM: Double,
        val atMs: Long = 0L,      // SystemClock.elapsedRealtime() da leitura do GPS
        val speedMs: Float = 0f   // velocidade nessa leitura (m/s)
    ) {
        /** Distância estimada agora: entre uma leitura e outra do GPS (1/s) desconta o que a moto andou. */
        fun distanceNow(): Double {
            if (atMs == 0L || speedMs < 1f) return distanceM
            val dt = ((android.os.SystemClock.elapsedRealtime() - atMs) / 1000.0).coerceIn(0.0, 2.0)
            return (distanceM - speedMs * dt).coerceAtLeast(0.0)
        }

        val distanceText: String
            get() {
                val d = distanceNow()
                return when {
                    d < 30 -> "Agora"
                    d < 100 -> "${(d / 10).toInt() * 10} m"
                    d < 1000 -> "${(d / 50).toInt() * 50} m"
                    else -> String.format(java.util.Locale("pt", "BR"), "%.1f km", d / 1000)
                }
            }
    }

    /** Rota exibida: só o trecho que falta percorrer. */
    data class Route(
        val points: List<LatLng>,
        val destination: LatLng,
        val summary: String,
        /** Próxima manobra (qualquer distância; o aviso "onde entrar" no log é a partir de 500 m). */
        val maneuver: Maneuver? = null,
        val rerouting: Boolean = false,
        val remainingMin: Double = 0.0
    ) {
        /** Hora prevista de chegada ("21:45"), ou null sem estimativa. */
        val eta: String?
            get() {
                if (remainingMin <= 0.0) return null
                val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, (remainingMin * 60).toInt()) }
                return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
            }

        /** "3,5 km · 7 min · chegada 21:45" */
        val summaryWithEta: String get() = eta?.let { "$summary · chegada $it" } ?: summary

        /** Versão curta, para o cartão estreito: "3,5 km · 7 min · 21:45" */
        val summaryShortEta: String get() = eta?.let { "$summary · $it" } ?: summary
    }

    /** Radar fixo à frente (até 400 m). */
    data class RadarAlert(
        val distanceM: Double,
        val maxspeed: Int?,
        val atMs: Long,
        val speedMs: Float
    ) {
        fun distanceNow(): Double {
            if (speedMs < 1f) return distanceM
            val dt = ((android.os.SystemClock.elapsedRealtime() - atMs) / 1000.0).coerceIn(0.0, 2.0)
            return (distanceM - speedMs * dt).coerceAtLeast(0.0)
        }
    }

    @Volatile var radar: RadarAlert? = null

    /** Incidente de trânsito à frente (TomTom), até 1,5 km. */
    data class IncidentAlert(
        val category: Int,
        val label: String,
        val distanceM: Double,
        val atMs: Long,
        val speedMs: Float
    ) {
        fun distanceNow(): Double {
            if (speedMs < 1f) return distanceM
            val dt = ((android.os.SystemClock.elapsedRealtime() - atMs) / 1000.0).coerceIn(0.0, 2.0)
            return (distanceM - speedMs * dt).coerceAtLeast(0.0)
        }
    }

    @Volatile var incident: IncidentAlert? = null

    /** Pouca gasolina: "⛽ ~40 km de gasolina · Posto X 1,2 km" (Garage). */
    @Volatile var fuel: String? = null
    /** Chuva na rota ou aqui: "🌧 Chuva à frente em ~20 min" (Weather). */
    @Volatile var weather: String? = null
    /** Temperatura atual (°C), do Open-Meteo. */
    @Volatile var temperature: Int? = null
    /** Limite de velocidade da rua atual (OpenStreetMap maxspeed). */
    @Volatile var speedLimit: Int? = null

    /** Chegada ao destino: mostrada no painel por alguns segundos (tela de chegada). */
    data class Arrival(
        val label: String,
        val distanceM: Double,
        val movingMs: Long,
        val avgKmh: Float,
        val maxKmh: Float,
        val atMs: Long = android.os.SystemClock.uptimeMillis()
    )

    @Volatile var arrival: Arrival? = null

    @Volatile var route: Route? = null
        private set
    @Volatile var lastLocation: Location? = null
        private set

    private val routeListeners = CopyOnWriteArrayList<(Route?) -> Unit>()
    private val locationListeners = CopyOnWriteArrayList<(Location) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(r: Route?) {
        route = r
        main.post { routeListeners.forEach { it(r) } }
    }

    fun publishLocation(l: Location) {
        lastLocation = l
        main.post { locationListeners.forEach { it(l) } }
    }

    fun addListener(l: (Route?) -> Unit) { routeListeners.add(l) }
    fun removeListener(l: (Route?) -> Unit) { routeListeners.remove(l) }
    fun addLocationListener(l: (Location) -> Unit) { locationListeners.add(l) }
    fun removeLocationListener(l: (Location) -> Unit) { locationListeners.remove(l) }
}
