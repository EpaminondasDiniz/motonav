package br.com.motonav

import android.content.Context
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Estilo noturno do mapa do painel.
 *  - AUTO (padrão): noite quando o sol está abaixo de -2° no lugar onde a moto está (calculado,
 *    sem internet). Se o painel mandar o C2P 0x10200 {"night": ...}, o painel manda por 30 min.
 *  - DAY / NIGHT: fixo, escolhido no app.
 * O estilo escuro só é usado sem internet se já tiver sido aberto alguma vez com internet (o mapa
 * offline guarda os tiles, mas as fontes/ícones do estilo escuro precisam ter vindo antes).
 */
object NightMode {
    enum class Pref { AUTO, DAY, NIGHT }

    const val DAY_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val NIGHT_STYLE = "https://tiles.openfreemap.org/styles/dark"
    private const val PREFS = "night_mode"
    private const val PANEL_VALID_MS = 30 * 60_000L

    @Volatile var pref = Pref.AUTO
        private set
    @Volatile private var panelNight: Boolean? = null
    @Volatile private var panelAt = 0L
    @Volatile private var darkReady = false
    private var appCtx: Context? = null
    private var lastLogged: Boolean? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appCtx = ctx.applicationContext
        pref = runCatching { Pref.valueOf(p.getString("pref", "AUTO") ?: "AUTO") }.getOrDefault(Pref.AUTO)
        darkReady = p.getBoolean("darkReady", false)
    }

    fun setPref(ctx: Context, v: Pref) {
        pref = v
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pref", v.name).apply()
    }

    fun fromPanel(night: Boolean) {
        panelNight = night
        panelAt = SystemClock.elapsedRealtime()
        DashLog.d("painel avisou modo ${if (night) "noturno" else "diurno"}")
    }

    /** O estilo escuro abriu com sucesso: pode ser usado também sem internet. */
    fun markDarkLoaded() {
        if (darkReady) return
        darkReady = true
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("darkReady", true)?.apply()
    }

    fun isNight(): Boolean {
        val n = when (pref) {
            Pref.DAY -> false
            Pref.NIGHT -> true
            Pref.AUTO -> {
                val pn = panelNight
                if (pn != null && SystemClock.elapsedRealtime() - panelAt < PANEL_VALID_MS) pn
                else {
                    val loc = NavState.lastLocation
                    sunElevation(loc?.latitude ?: -15.79, loc?.longitude ?: -47.88, System.currentTimeMillis()) < -2.0
                }
            }
        }
        if (n != lastLogged) { lastLogged = n; DashLog.d("mapa do painel: estilo ${if (n) "NOTURNO" else "diurno"}") }
        return n
    }

    /** Estilo para o mapa do painel agora. */
    fun styleUrl(): String =
        if (isNight() && (darkReady || Net.online)) NIGHT_STYLE else DAY_STYLE

    /** Altura do sol em graus (aproximação astronômica simples, erro de poucos minutos). */
    fun sunElevation(lat: Double, lon: Double, timeMs: Long): Double {
        val d = (timeMs - 946_728_000_000L) / 86_400_000.0 // dias desde 01/01/2000 12:00 UTC
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val dec = asin(sin(e) * sin(l))
        val ra = atan2(cos(e) * sin(l), cos(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }
}
