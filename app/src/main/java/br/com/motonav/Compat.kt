package br.com.motonav

import android.content.Context
import android.os.Build
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashScreen
import java.security.MessageDigest

/**
 * Compatibilidade: que moto e que painel a pessoa usa.
 *  - O painel se apresenta ao conectar (CLIENT_INFO): modelo (HUName), canal do fabricante e, no
 *    vídeo, a resolução. Fica guardado para ir nos relatórios.
 *  - Na PRIMEIRA conexão que dá certo com cada painel, avisa o canal de suporte (Discord):
 *    nome, moto, painel e celular. Uma vez só por painel; dá para desligar em Ajustes.
 * O identificador do painel (HUID) nunca sai do celular: só um resumo (hash) dele, guardado aqui,
 * para saber se esse painel já foi avisado.
 */
object Compat {
    private const val PREFS = "compat"

    @Volatile var panelModel = ""
        private set
    @Volatile var panelChannel = ""
        private set
    @Volatile var screen = ""
        private set
    @Volatile private var panelKey = ""

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        panelModel = p.getString("model", "") ?: ""
        panelChannel = p.getString("channel", "") ?: ""
        screen = p.getString("screen", "") ?: ""
    }

    /** CLIENT_INFO do painel. */
    fun onPanelInfo(ctx: Context, huName: String, channel: String, huid: String) {
        // o painel novo (350R 2026) se apresenta com HUName vazio
        panelModel = huName.ifBlank { "sem nome" }
        panelChannel = channel
        panelKey = hash(huid.ifBlank { huName + channel })
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("model", panelModel).putString("channel", channel).apply()
    }

    /** Configuração de vídeo pedida pelo painel (resolução da tela). */
    fun onScreen(ctx: Context, w: Int, h: Int) {
        screen = "${w}x$h"
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("screen", screen).apply()
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("share", true)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("share", on).apply()

    /** "Zontes T350X · painel ZHKJ08-1122 (800x480)" */
    fun summary(): String {
        val bike = DashScreen.bikeModel.ifBlank { "moto não informada" }
        val panel = if (panelModel.isBlank()) "painel ainda não conectado"
        else "painel $panelModel" + (if (screen.isNotBlank()) " ($screen)" else "")
        return "$bike · $panel"
    }

    /**
     * O vídeo começou: se for a 1ª vez com este painel, avisa o canal de suporte (em segundo plano).
     * Se falhar (sem internet), tenta de novo na próxima conexão.
     */
    fun onConnected(ctx: Context) {
        if (!Report.canSend || !enabled(ctx) || panelKey.isBlank()) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = p.getStringSet("sent", emptySet()) ?: emptySet()
        if (panelKey in sent) return
        val key = panelKey
        Thread {
            val name = DashScreen.riderName.ifBlank { "Sem nome" }
            val msg = "🟢 **Nova moto conectada**\n" +
                "👤 $name\n" +
                "🏍 ${summary()}" + (if (panelChannel.isNotBlank()) " · canal $panelChannel" else "") + "\n" +
                "📱 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v${BuildConfig.VERSION_NAME}"
            if (Report.post(msg)) {
                p.edit().putStringSet("sent", sent + key).apply()
                DashLog.d("compatibilidade: aviso de nova moto enviado")
            }
        }.start()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
