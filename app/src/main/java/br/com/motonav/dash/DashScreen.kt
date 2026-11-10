package br.com.motonav.dash

import android.content.Context

/**
 * Preferências do painel da moto, salvas no celular:
 *  - o que aparece no painel (mapa de navegação ou painel "Race");
 *  - o nome do piloto e se a animação de entrada ("Bem-vindo, ...") toca ao conectar.
 */
object DashScreen {
    enum class Mode { MAP, RACE }

    private const val PREFS = "dash_screen"
    @Volatile var mode = Mode.MAP
        private set
    @Volatile var riderName = ""
        private set
    @Volatile var introEnabled = true
        private set
    /** Modelo da moto, digitado pela pessoa (vai nos relatórios e no aviso de compatibilidade). */
    @Volatile var bikeModel = ""
        private set

    fun setBike(ctx: Context, model: String) {
        bikeModel = model.trim().take(40)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bike", bikeModel).apply()
    }
    /** Estilo do painel Race (Neon, Clássico, Retrô, Caça, Telemetria). */
    @Volatile var raceStyle = RaceStyle.NEON
        private set

    fun setRaceStyle(ctx: Context, s: RaceStyle) {
        raceStyle = s
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("raceStyle", s.name).apply()
        DashLog.d("estilo do Race: ${s.title}")
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        raceStyle = runCatching { RaceStyle.valueOf(p.getString("raceStyle", "NEON") ?: "NEON") }.getOrDefault(RaceStyle.NEON)
        mode = runCatching { Mode.valueOf(p.getString("mode", Mode.MAP.name) ?: "") }.getOrDefault(Mode.MAP)
        riderName = p.getString("rider", "") ?: ""
        introEnabled = p.getBoolean("intro", true)
        bikeModel = p.getString("bike", "") ?: ""
    }

    fun set(ctx: Context, m: Mode) {
        if (m == mode) return
        mode = m
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m.name).apply()
        DashLog.d("tela do painel: ${if (m == Mode.RACE) "RACE" else "MAPA"}")
    }

    fun setRider(ctx: Context, name: String) {
        riderName = name.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rider", riderName).apply()
    }

    fun setIntro(ctx: Context, on: Boolean) {
        introEnabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("intro", on).apply()
    }
}
