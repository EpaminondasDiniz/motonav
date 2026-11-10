package br.com.motonav

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashNotice

/**
 * Avisos de mensagem no painel: "💬 WhatsApp · Fulano" por 6 s. Mostra só o app e quem mandou
 * (o título da notificação), nunca o texto, para não distrair. Usa o mesmo "acesso a
 * notificações" da música ([MediaListener]).
 */
object Messages {
    private val APPS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.messaging" to "SMS",
        "com.samsung.android.messaging" to "SMS",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram"
    )
    private const val PREFS = "messages"
    private const val SHOW_MS = 6_000L

    @Volatile var enabled = true
        private set
    private val lastShown = HashMap<String, Long>()

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (!enabled) return
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return   // resumo "3 mensagens"
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return   // chamada em andamento etc.
        val extras = n.extras ?: return
        // em conversas, o remetente da última mensagem; senão, o título
        val who = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return
        if (who.isBlank() || who.equals(app, ignoreCase = true) || who == "WhatsApp") return
        // a mesma conversa só avisa de novo depois de 30 s (grupos mandam várias seguidas)
        val key = "${sbn.packageName}|$who"
        val now = SystemClock.elapsedRealtime()
        if (now - (lastShown[key] ?: 0L) < 30_000L) return
        lastShown[key] = now
        DashNotice.show("💬  $app  ·  ${who.take(28)}", SHOW_MS, DashNotice.Kind.MESSAGE)
        DashLog.d("mensagem: aviso de $app no painel")
    }
}
