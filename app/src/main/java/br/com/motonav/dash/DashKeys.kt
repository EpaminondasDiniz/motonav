package br.com.motonav.dash

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Botões do guidão. O painel manda o C2P 0x10500 com {"actionId", "keyCode"} (no Carbit:
 * km.z -> Z(actionId, keyCode)). Em campo (Zontes T350X) o painel não mandou teclas: toda tecla
 * vai para o log e aparece 2 s no painel ("BOTÃO 22"), para descobrirmos qual é qual.
 *
 * Enquanto isso, ações provisórias para os códigos padrão do Android:
 *  - esquerda/direita, anterior/próxima mídia, MENU: troca Mapa ⇄ Race
 *  - cima/baixo, volume +/-: zoom do mapa do painel (+/- 0,5)
 *  - OK/ENTER/play: zoom volta ao automático
 */
object DashKeys {
    @Volatile private var lastCode = -1
    @Volatile private var lastAt = 0L

    fun onKey(ctx: Context, actionId: Int, keyCode: Int, raw: String) {
        DashLog.d("BOTÃO do guidão: keyCode=$keyCode actionId=$actionId ($raw)")
        val now = SystemClock.uptimeMillis()
        // o painel pode mandar "apertou" e "soltou": uma ação por toque
        if (keyCode == lastCode && now - lastAt < 450) return
        lastCode = keyCode
        lastAt = now
        val action = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MENU -> {
                val race = DashScreen.mode != DashScreen.Mode.RACE
                DashScreen.set(ctx, if (race) DashScreen.Mode.RACE else DashScreen.Mode.MAP)
                if (race) "RACE" else "MAPA"
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> { DashZoom.nudge(+0.5); "ZOOM +" }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> { DashZoom.nudge(-0.5); "ZOOM −" }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { DashZoom.reset(); "ZOOM AUTO" }
            else -> null
        }
        DashNotice.show(if (action != null) "BOTÃO $keyCode · $action" else "BOTÃO $keyCode")
    }
}

/** Aviso curto no topo do painel (2 s), por cima do mapa ou do Race. */
object DashNotice {
    enum class Kind(val color: Int) {
        INFO(android.graphics.Color.argb(235, 255, 122, 0)),
        MESSAGE(android.graphics.Color.argb(240, 18, 140, 126)),
        ALERT(android.graphics.Color.argb(245, 211, 30, 30))
    }

    @Volatile var text: String? = null
        private set
    @Volatile var kind = Kind.INFO
        private set
    @Volatile private var until = 0L

    fun show(t: String, ms: Long = 2_000L, k: Kind = Kind.INFO) {
        text = t
        kind = k
        until = SystemClock.uptimeMillis() + ms
    }

    fun current(): String? = if (SystemClock.uptimeMillis() < until) text else null
}
