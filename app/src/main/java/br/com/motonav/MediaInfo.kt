package br.com.motonav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import br.com.motonav.dash.DashLog

/**
 * Música tocando no celular (Spotify ou qualquer player), para mostrar pequena no painel:
 * "♪ Música · Artista". O Android só deixa ler o player de outro app com o "acesso a
 * notificações" ligado para o MotoNav ([MediaListener]); o app não lê as notificações em si.
 */
object MediaInfo {
    @Volatile var title: String? = null
        private set
    @Volatile var artist: String? = null
        private set
    @Volatile var playing = false
        private set
    /** Capa do álbum (pequena, 160x160) e a cor média dela (para o fundo do cartão). */
    @Volatile var art: android.graphics.Bitmap? = null
        private set
    @Volatile var artColor = android.graphics.Color.rgb(30, 215, 96)
        private set
    @Volatile var durationMs = 0L
        private set
    @Volatile private var posMs = 0L
    @Volatile private var posAt = 0L
    @Volatile private var speed = 1f
    private var artKey: String? = null

    /** Posição atual da música (estimada entre as atualizações do player). */
    fun positionMs(): Long {
        if (!playing) return posMs
        val p = posMs + ((android.os.SystemClock.elapsedRealtime() - posAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p
    }

    private var msm: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastLogged: String? = null

    fun hasPermission(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** Abre a tela do Android para ligar o acesso. */
    fun openPermissionSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Só para a imagem de prévia da versão de debug: finge uma música tocando e devolve como desfazer. */
    fun fakeForPreview(t: String, a: String): () -> Unit {
        val old = arrayOf<Any?>(title, artist, playing, durationMs, posMs, posAt, art, artColor)
        title = t; artist = a; playing = true; durationMs = 245_000; posMs = 83_000
        posAt = android.os.SystemClock.elapsedRealtime(); art = null; artColor = android.graphics.Color.rgb(40, 110, 200)
        return {
            title = old[0] as String?; artist = old[1] as String?; playing = old[2] as Boolean
            durationMs = old[3] as Long; posMs = old[4] as Long; posAt = old[5] as Long
            art = old[6] as android.graphics.Bitmap?; artColor = old[7] as Int
        }
    }

    /** "♪ Música · Artista" enquanto estiver tocando. */
    fun text(): String? {
        if (!playing) return null
        val t = title ?: return null
        return "♪  $t" + (artist?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "")
    }

    /** Começa a acompanhar o player (pode ser chamado várias vezes). */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            if (msm != null || !hasPermission(app)) return@post
            try {
                val m = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val comp = ComponentName(app, MediaListener::class.java)
                m.addOnActiveSessionsChangedListener({ list -> pick(list) }, comp, main)
                msm = m
                component = comp
                pick(m.getActiveSessions(comp))
                DashLog.d("música: acompanhando o player do celular")
            } catch (e: Exception) {
                DashLog.d("música: sem acesso ao player (${e.message})")
            }
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() { controller = null; playing = false }
    }

    /** Escolhe o player que está tocando (ou o primeiro da lista). */
    private fun pick(list: List<MediaController>?) {
        val l = list ?: emptyList()
        val best = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) { read(); return }
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, main)
        read()
    }

    private fun read() {
        val c = controller
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val st = c?.playbackState
        playing = st?.state == PlaybackState.STATE_PLAYING
        durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0L
        if (st != null) {
            posMs = st.position
            posAt = if (st.lastPositionUpdateTime > 0) st.lastPositionUpdateTime else android.os.SystemClock.elapsedRealtime()
            speed = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
        }
        // capa: só refaz quando a música muda
        val key = "$title|$artist"
        if (key != artKey) {
            artKey = key
            val src = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            art = src?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it.copy(android.graphics.Bitmap.Config.ARGB_8888, false), 160, 160, true) }.getOrNull() }
            artColor = art?.let { a ->
                val px = android.graphics.Bitmap.createScaledBitmap(a, 1, 1, true).getPixel(0, 0)
                // cor média, um pouco mais viva
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(px, hsv)
                hsv[1] = (hsv[1] * 1.3f).coerceAtMost(1f)
                hsv[2] = hsv[2].coerceIn(0.35f, 0.75f)
                android.graphics.Color.HSVToColor(hsv)
            } ?: android.graphics.Color.rgb(30, 215, 96)
        }
        val t = text()
        if (t != lastLogged) { lastLogged = t; t?.let { DashLog.d("música: $it (${c?.packageName})") } }
    }
}

/**
 * Só existe para o Android permitir ler o player (MediaSessionManager.getActiveSessions).
 * Não faz nada com as notificações.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Messages.load(this)
        MediaInfo.start(this)
    }

    /** Mensagens (WhatsApp, Telegram, SMS...): só app e remetente vão para o painel ([Messages]). */
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        sbn?.let { runCatching { Messages.onPosted(this, it) } }
    }
}
