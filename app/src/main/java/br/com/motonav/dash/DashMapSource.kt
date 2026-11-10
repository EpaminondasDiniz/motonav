package br.com.motonav.dash

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import br.com.motonav.NavState
import br.com.motonav.NightMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Desenha o mapa do painel (800x480) e entrega os quadros na superfície do codificador H.264.
 *
 * Modos, trocados sozinhos conforme a tela do CELULAR:
 *  - AO VIVO (tela ligada): MapView numa tela virtual privada (Presentation), ~30 fps.
 *    A tela virtual escreve num ImageReader e nós copiamos o quadro para o codificador.
 *  - CAPTURA (tela apagada): o Android PAUSA a tela virtual quando a tela do celular apaga, mas
 *    o MapView continua desenhando dentro do próprio TextureView. Copiamos esse TextureView
 *    (~30 fps) direto para o codificador: mesma fluidez e animação do modo ao vivo.
 *  - INSTANTÂNEOS (reserva): se a captura não funcionar no aparelho, o mapa é renderizado fora
 *    da tela (MapSnapshotter, ~6 fps) e a rota, a moto e o resumo são desenhados por cima.
 *
 * Só esta classe escreve na superfície do codificador (thread "dash-compose"), então trocar de
 * modo não exige recriar o codificador. Se nada novo chegar, repete o último quadro (o painel
 * derruba o vídeo se ficar muito tempo sem quadros).
 */
class DashMapSource(private val ctx: Context) : FrameSource {

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val DPI = 240              // letras maiores: o painel é visto de longe, em movimento
        const val ZOOM = 16.5
        const val TILT = 45.0
        const val PUCK_TOP_PADDING = 0.35 // fração da altura: moto mais embaixo, mostra o caminho à frente
        // Repete o último quadro se nada novo chegar: mantém no mínimo ~10 quadros/s chegando ao
        // painel (com ~6/s no modo instantâneos ele chegou a fechar o vídeo).
        private const val KEEPALIVE_MS = 100L
        private const val STATS_MS = 10_000L
        private const val CAPTURE_INTERVAL_MS = 33L   // ~30 fps

        /**
         * Intervalo mínimo entre quadros mandados ao codificador (0 = sem limite). Painéis novos
         * (Bluetooth + hotspot): 66 ms = 15 quadros/s, o ritmo do ZC-Panel, que funciona na 350R
         * (no 1º teste, a ~25 quadros/s, o painel conectou e caiu em seguida).
         */
        @Volatile var minFrameMs = 0L
        private const val CAPTURE_CHECK_MS = 1_000L
        // a animação de entrada não repete se a conexão só caiu e voltou logo
        private const val INTRO_AGAIN_MS = 3 * 60_000L
        @Volatile private var lastIntroEnd = 0L
    }

    private enum class Mode(val label: String) {
        NONE("—"), LIVE("AO VIVO"), CAPTURE("CAPTURA"), SNAPSHOT("INSTANTÂNEOS")
    }

    private val main = Handler(Looper.getMainLooper())
    private var composeThread: HandlerThread? = null
    @Volatile private var compose: Handler? = null
    @Volatile private var encSurface: Surface? = null
    private var w = 0
    private var h = 0

    private var dm: DisplayManager? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: DashPresentation? = null
    private var snap: DashSnapshotRenderer? = null
    @Volatile private var overlay: DashOverlay? = null
    @Volatile private var race: RaceDash? = null
    @Volatile private var intro: IntroDash? = null
    @Volatile private var arrivalDash: ArrivalDash? = null
    private var introStart = 0L

    @Volatile private var attached = false
    @Volatile private var mode = Mode.NONE
    @Volatile private var vdPaused = false
    @Volatile private var captureBroken = false

    // ---- captura (thread principal); 3 bitmaps circulam entre a captura e a composição
    private val pool = ArrayBlockingQueue<Bitmap>(3)
    private var capturing = false
    private var lastCheck = 0L
    private var lastHash = 0L
    private var hashChangedAt = 0L
    private var hashLoc: Location? = null
    private var blankSince = 0L

    // ---- usados só na thread de composição
    private var heldImage: Image? = null
    private var heldBitmap: Bitmap? = null
    private var heldCapture: Bitmap? = null
    private var lastFrame: ((Canvas) -> Unit)? = null
    private var lastDrawAt = 0L
    private var lastLiveAt = 0L
    private var liveFrames = 0
    private var captureFrames = 0
    private var snapFrames = 0
    private var repeats = 0
    private var lastStats = 0L
    private var lastError = 0L

    override fun attach(surface: Surface, width: Int, height: Int) {
        encSurface = surface
        w = width
        h = height
        repeat(3) { pool.offer(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)) }
        overlay = DashOverlay(width, height)
        race = RaceDash(width, height)
        arrivalDash = ArrivalDash(width, height)
        DashScreen.load(ctx)
        br.com.motonav.LocationHub.initData(ctx)
        val again = lastIntroEnd == 0L || SystemClock.uptimeMillis() - lastIntroEnd > INTRO_AGAIN_MS
        if (DashScreen.introEnabled && again) {
            val extra = listOfNotNull(
                NavState.temperature?.let { "$it°C" },
                br.com.motonav.Garage.dueText()?.let { "🔧 $it" },
                NavState.route?.let { "rota ativa" }
            ).joinToString("   •   ").ifEmpty { null }
            intro = IntroDash(width, height, DashScreen.riderName, extra)
            introStart = SystemClock.uptimeMillis()
            DashLog.d("painel: animação de entrada (${DashScreen.riderName})")
        }
        val t = HandlerThread("dash-compose").also { it.start() }
        composeThread = t
        val ch = Handler(t.looper)
        compose = ch

        val done = CountDownLatch(1)
        var error: Throwable? = null
        main.post {
            try {
                val m = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm = m
                val r = ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 4,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
                r.setOnImageAvailableListener({ onLiveImage(it) }, ch)
                reader = r
                val vd = m.createVirtualDisplay(
                    "MotoNavDash", width, height, DPI, r.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, vdCallback, main
                )
                display = vd
                presentation = DashPresentation(ctx, vd.display, width, height).also { it.show() }
                snap = DashSnapshotRenderer(ctx, width, height) { frame ->
                    compose?.post { drawFrom(frame, Mode.SNAPSHOT) }
                }
                m.registerDisplayListener(displayListener, main)
                attached = true
                DashLog.d("mapa do painel aberto (${width}x$height, $DPI dpi)")
                updateMode("início")
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        error?.let { e ->
            detach()
            throw e
        }
        lastStats = SystemClock.uptimeMillis()
        ch.postDelayed(keepAlive, KEEPALIVE_MS)
        ch.post(raceTick)
    }

    override fun detach() {
        attached = false
        main.post {
            stopCapture()
            runCatching { dm?.unregisterDisplayListener(displayListener) }
            runCatching { snap?.stop() }
            snap = null
            runCatching { presentation?.dismiss() }
            runCatching { display?.release() }
            presentation = null
            display = null
            mode = Mode.NONE
            val r = reader
            reader = null
            val ct = composeThread
            composeThread = null
            val ch = compose
            compose = null
            ch?.post {
                runCatching { heldBitmap?.recycle() }
                runCatching { heldImage?.close() }
                heldBitmap = null
                heldImage = null
                heldCapture = null
                lastFrame = null
                runCatching { r?.close() }
                ct?.quitSafely()
            }
            pool.clear()
        }
    }

    // ------------------------------------------------------------ escolha do modo (thread principal)

    private val vdCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            vdPaused = true
            DashLog.d("tela virtual do painel pausada pelo Android")
            updateMode("tela virtual pausada")
        }

        override fun onResumed() {
            vdPaused = false
            DashLog.d("tela virtual do painel retomada")
            updateMode("tela virtual retomada")
        }

        override fun onStopped() = DashLog.d("tela virtual do painel parada")
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateMode("tela do celular mudou")
        }
    }

    private fun phoneScreenState(): Int =
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_ON

    private fun updateMode(why: String) {
        if (!attached) return
        val s = snap ?: return
        val st = phoneScreenState()
        val want = when {
            st == Display.STATE_ON && !vdPaused -> Mode.LIVE
            captureBroken -> Mode.SNAPSHOT
            else -> Mode.CAPTURE
        }
        if (want == mode) return
        mode = want
        if (want == Mode.CAPTURE) startCapture() else stopCapture()
        if (want == Mode.SNAPSHOT) s.start() else s.stop()
        val stName = when (st) {
            Display.STATE_ON -> "ligada"
            Display.STATE_OFF -> "apagada"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "always-on"
            else -> "estado $st"
        }
        DashLog.d("mapa do painel: modo ${want.label} ($why; tela do celular $stName)")
    }

    // ------------------------------------------------------------ captura do TextureView (principal)

    private fun startCapture() {
        if (capturing) return
        capturing = true
        val now = SystemClock.uptimeMillis()
        lastCheck = now
        hashChangedAt = now
        hashLoc = NavState.lastLocation
        blankSince = 0L
        main.removeCallbacks(captureTick)
        main.post(captureTick)
    }

    private fun stopCapture() {
        capturing = false
        main.removeCallbacks(captureTick)
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!capturing || !attached) return
            val t0 = SystemClock.uptimeMillis()
            val tv = presentation?.mapTexture()
            var grabbed = false
            if (tv != null && tv.isAvailable) {
                val b = pool.poll()
                if (b != null) {
                    val ok = try { tv.getBitmap(b); true } catch (e: Exception) { false }
                    if (ok) {
                        grabbed = true
                        checkCapture(b, t0)
                        val ch = compose
                        if (ch == null || !ch.post { onCaptured(b) }) pool.offer(b)
                    } else {
                        pool.offer(b)
                    }
                } else {
                    grabbed = true // composição ainda ocupada com os anteriores: só pula este
                }
            }
            if (!grabbed) noteBlank(t0, "mapa sem imagem")
            if (!capturing) return // a verificação pode ter trocado o modo
            val wait = CAPTURE_INTERVAL_MS - (SystemClock.uptimeMillis() - t0)
            main.postDelayed(this, wait.coerceAtLeast(5L))
        }
    }

    /** Confere 1x/s se a captura está viva: imagem não vazia e mudando quando a moto anda. */
    private fun checkCapture(b: Bitmap, now: Long) {
        if (now - lastCheck < CAPTURE_CHECK_MS) return
        lastCheck = now
        var hash = 17L
        var nonEmpty = false
        for (i in 1..4) for (j in 1..3) {
            val p = b.getPixel(w * i / 5, h * j / 4)
            if (p != 0) nonEmpty = true
            hash = hash * 31 + p
        }
        if (!nonEmpty) { noteBlank(now, "imagem vazia"); return }
        blankSince = 0L
        val loc = NavState.lastLocation
        if (hash != lastHash || hashLoc == null) {
            lastHash = hash
            hashChangedAt = now
            hashLoc = loc
            return
        }
        val from = hashLoc ?: return
        val fresh = loc != null &&
            SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 3_000_000_000L
        if (fresh && loc!!.distanceTo(from) > 25f && now - hashChangedAt > 6_000L) {
            captureFailed("o mapa parou de desenhar")
        }
    }

    private fun noteBlank(now: Long, why: String) {
        if (blankSince == 0L) blankSince = now
        if (now - blankSince > 2_000L) captureFailed(why)
    }

    private fun captureFailed(why: String) {
        if (captureBroken) return
        captureBroken = true
        DashLog.d("captura do mapa não funcionou ($why): usando instantâneos")
        updateMode("captura falhou")
    }

    // ------------------------------------------------------------ composição (thread dash-compose)

    private fun onCaptured(b: Bitmap) {
        if (drawFrom({ c -> c.drawBitmap(b, 0f, 0f, null) }, Mode.CAPTURE)) {
            heldCapture?.let { pool.offer(it) } // o anterior volta para a captura
            heldCapture = b
        } else {
            pool.offer(b)
        }
    }

    private fun onLiveImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        if (mode != Mode.LIVE || !attached) { img.close(); return }
        // no máximo ~40 quadros/s para o codificador
        val now = SystemClock.uptimeMillis()
        if (now - lastLiveAt < 24) { img.close(); return }
        lastLiveAt = now
        val hb = try { img.hardwareBuffer } catch (e: Exception) { null }
        val bmp = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        hb?.close()
        if (bmp == null) { img.close(); return }
        if (drawFrom({ c -> c.drawBitmap(bmp, 0f, 0f, null) }, Mode.LIVE)) {
            // mantém o quadro atual vivo (o "repetir último quadro" usa ele); solta o anterior
            heldBitmap?.recycle()
            heldImage?.close()
            heldBitmap = bmp
            heldImage = img
        } else {
            bmp.recycle()
            img.close()
        }
    }

    /** Desenha um quadro novo se ele for do modo atual. */
    private fun drawFrom(frame: (Canvas) -> Unit, from: Mode): Boolean {
        if (from != mode) return false
        if (DashScreen.mode == DashScreen.Mode.RACE) return false // tela Race: quem desenha é o raceTick
        if (animating()) {
            // durante as animações (entrada, chegada) quem desenha é o raceTick; o mapa só fica
            // guardado para aparecer por baixo
            lastFrame = frame
            return true
        }
        if (!render(frame)) return false
        lastFrame = frame
        when (from) {
            Mode.LIVE -> liveFrames++
            Mode.CAPTURE -> captureFrames++
            Mode.SNAPSHOT -> snapFrames++
            Mode.NONE -> {}
        }
        return true
    }

    private fun render(frame: (Canvas) -> Unit): Boolean {
        if (!attached) return false
        val s = encSurface ?: return false
        if (minFrameMs > 0 && SystemClock.uptimeMillis() - lastDrawAt < minFrameMs) return false
        return try {
            val c = s.lockHardwareCanvas()
            try {
                val it = intro
                val t = (SystemClock.uptimeMillis() - introStart) / 1000f
                if (it != null && t < IntroDash.DURATION_S) {
                    it.draw(c, t) { cc -> screen(cc, frame) }
                } else {
                    if (it != null) endIntro()
                    screen(c, frame)
                    arrivalTime()?.let { at -> NavState.arrival?.let { a -> arrivalDash?.draw(c, a, at) } }
                }
                DashNotice.current()?.let { drawNotice(c, it) }
            } finally {
                s.unlockCanvasAndPost(c)
            }
            lastDrawAt = SystemClock.uptimeMillis()
            true
        } catch (e: Exception) {
            val now = SystemClock.uptimeMillis()
            if (now - lastError > 5000) { lastError = now; DashLog.d("desenho do painel falhou: ${e.message}") }
            false
        }
    }

    /** O quadro normal: painel Race ou mapa com os avisos por cima. */
    private fun screen(c: Canvas, frame: (Canvas) -> Unit) {
        val r = race
        if (DashScreen.mode == DashScreen.Mode.RACE && r != null) {
            r.draw(c) // painel Race no lugar do mapa
        } else {
            frame(c)
            overlay?.draw(c, NavState.route) // aviso de manobra e resumo, iguais nos 3 modos
        }
    }

    /** Segundos desde a chegada ao destino, enquanto a tela de chegada estiver valendo. */
    private fun arrivalTime(): Float? {
        val a = NavState.arrival ?: return null
        val t = (SystemClock.uptimeMillis() - a.atMs) / 1000f
        return if (t in 0f..ArrivalDash.DURATION_S) t else null
    }

    /** Animação por cima (entrada ou chegada): o raceTick desenha a 30 fps. */
    private fun animating() = intro != null || arrivalTime() != null

    private val noticeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 122, 0) }
    private val noticeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    /** Aviso no meio da tela, abaixo dos cartões de cima (botão, mensagem, queda). */
    private fun drawNotice(c: Canvas, text: String) {
        val big = DashNotice.kind == DashNotice.Kind.ALERT
        noticeText.textSize = if (big) 40f else 30f
        val tw = noticeText.measureText(text) + 48f
        val top = h * 0.36f
        val bh = if (big) 70f else 54f
        val box = RectF(w / 2f - tw / 2, top, w / 2f + tw / 2, top + bh)
        noticeBg.color = DashNotice.kind.color
        c.drawRoundRect(box, bh / 2, bh / 2, noticeBg)
        c.drawText(text, w / 2f, box.centerY() + noticeText.textSize * 0.35f, noticeText)
    }

    private fun endIntro() {
        intro = null
        lastIntroEnd = SystemClock.uptimeMillis()
        DashLog.d("painel: animação de entrada terminou")
    }

    /**
     * Tela Race e animação de entrada: 30 quadros/s desenhados aqui (não dependem do mapa nem da
     * tela do celular).
     */
    private val raceTick = object : Runnable {
        override fun run() {
            if (!attached) return
            if (DashScreen.mode == DashScreen.Mode.RACE || animating()) {
                val t0 = SystemClock.uptimeMillis()
                if (render(lastFrame ?: { c -> c.drawColor(Color.BLACK) })) raceFrames++
                compose?.postDelayed(this, (33 - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(5))
            } else {
                compose?.postDelayed(this, 250)
            }
        }
    }
    private var raceFrames = 0

    private val keepAlive = object : Runnable {
        override fun run() {
            if (!attached) return
            val now = SystemClock.uptimeMillis()
            if (now - lastDrawAt >= KEEPALIVE_MS) lastFrame?.let { if (render(it)) repeats++ }
            if (now - lastStats >= STATS_MS) {
                val gpsAge = NavState.lastLocation?.let {
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000
                }
                DashLog.d(
                    "painel ${if (DashScreen.mode == DashScreen.Mode.RACE) "RACE" else mode.label}: " +
                        "$liveFrames ao vivo, $captureFrames captura, $snapFrames instantâneos, " +
                        "$raceFrames race, $repeats repetidos em 10 s; GPS " +
                        (gpsAge?.let { "de $it s atrás" } ?: "sem leitura")
                )
                liveFrames = 0; captureFrames = 0; snapFrames = 0; raceFrames = 0; repeats = 0; lastStats = now
            }
            compose?.postDelayed(this, KEEPALIVE_MS)
        }
    }
}

/**
 * Mapa renderizado fora da tela (não depende da tela do celular estar ligada).
 * Tudo aqui roda na thread principal; [onFrame] recebe um quadro pronto para desenhar.
 */
private class DashSnapshotRenderer(
    ctx: Context,
    private val w: Int,
    private val h: Int,
    private val onFrame: ((Canvas) -> Unit) -> Unit
) {
    companion object {
        private const val INTERVAL_MS = 150L
        private const val ROUTE_AHEAD_M = 3000.0
    }

    private val main = Handler(Looper.getMainLooper())
    private val ratio = DashMapSource.DPI / 160f
    private val lw = (w / ratio).roundToInt()
    private val lh = (h / ratio).roundToInt()
    private var lastBearing = 0.0
    private var styleUrl = DashMapSource.STYLE_URL
    private var busy = false
    private var failures = 0
    var running = false
        private set

    private val snapshotter = MapSnapshotter(
        ctx,
        MapSnapshotter.Options(lw, lh)
            .withPixelRatio(ratio)
            .withStyle(DashMapSource.STYLE_URL)
            .withLogo(false)
            .withCameraPosition(camera(NavState.lastLocation))
    )

    // pincéis (medidas em pixels do painel = medidas do modo ao vivo x 1,5)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val casing = stroke(Color.WHITE, 13f * ratio)
    private val line = stroke(Color.parseColor("#2979FF"), 8f * ratio)
    private val destFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val destStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * ratio
    }
    private val puckFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2979FF") }
    private val puckStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * ratio
        strokeJoin = Paint.Join.ROUND
    }
    private val dst = Rect(0, 0, w, h)

    private fun stroke(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = width
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private val tickRunnable = Runnable { tick() }

    fun start() {
        if (running) return
        running = true
        tick()
    }

    fun stop() {
        running = false
        main.removeCallbacks(tickRunnable)
        if (busy) runCatching { snapshotter.cancel() }
        busy = false
    }

    private fun tick() {
        if (!running || busy) return
        busy = true
        val loc = NavState.lastLocation
        val route = NavState.route
        val cam = camera(loc)
        val t0 = SystemClock.uptimeMillis()
        try {
            val url = NightMode.styleUrl()
            if (url != styleUrl) { styleUrl = url; snapshotter.setStyleUrl(url) }
            snapshotter.setCameraPosition(cam)
            snapshotter.start(
                object : MapSnapshotter.SnapshotReadyCallback {
                    override fun onSnapshotReady(snapshot: MapSnapshot) {
                        busy = false
                        if (!running) return
                        failures = 0
                        try {
                            cam.target?.let { onFrame(buildFrame(snapshot, it, loc, route)) }
                        } catch (e: Exception) {
                            DashLog.d("montar instantâneo falhou: ${e.message}")
                        }
                        val wait = INTERVAL_MS - (SystemClock.uptimeMillis() - t0)
                        main.postDelayed(tickRunnable, wait.coerceAtLeast(0L))
                    }
                },
                object : MapSnapshotter.ErrorHandler {
                    override fun onError(error: String) {
                        busy = false
                        if (!running) return
                        if (failures++ % 10 == 0) DashLog.d("instantâneo do mapa falhou: $error")
                        main.postDelayed(tickRunnable, 1000)
                    }
                }
            )
        } catch (e: Exception) {
            busy = false
            DashLog.d("instantâneo do mapa falhou: ${e.message}")
            main.postDelayed(tickRunnable, 1000)
        }
    }

    /** Câmera parecida com o modo ao vivo: segue a moto, gira com o movimento, inclinada. */
    private fun camera(loc: Location?): CameraPosition {
        if (loc == null) {
            return CameraPosition.Builder().target(LatLng(-15.7939, -47.8828))
                .zoom(DashMapSource.ZOOM).tilt(DashMapSource.TILT).build()
        }
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) lastBearing = loc.bearing.toDouble()
        DashZoom.onSpeed(if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
        val zoom = DashZoom.current()
        val tilt = DashZoom.tilt()
        // O snapshotter não tem o "padding" do modo ao vivo: em vez disso o centro do mapa fica
        // um pouco à frente da moto, o que deixa a moto mais embaixo na tela.
        val metersPerPx = 40_075_016.686 * cos(Math.toRadians(loc.latitude)) /
            (512.0 * 2.0.pow(zoom))
        val shiftPx = h * DashMapSource.PUCK_TOP_PADDING / 2 / ratio
        val ahead = shiftPx * metersPerPx / cos(Math.toRadians(tilt))
        return CameraPosition.Builder()
            .target(offset(loc.latitude, loc.longitude, lastBearing, ahead))
            .zoom(zoom)
            .tilt(tilt)
            .bearing(lastBearing)
            .build()
    }

    private fun buildFrame(
        s: MapSnapshot, target: LatLng, loc: Location?, route: NavState.Route?
    ): (Canvas) -> Unit {
        val bmp = s.bitmap
        // pixelForLatLng pode vir em pontos lógicos ou em pixels do bitmap: calibra pelo centro
        val pc = s.pixelForLatLng(target)
        val logical = hypot(pc.x - lw / 2f, pc.y - lh / 2f) <
            hypot(pc.x - bmp.width / 2f, pc.y - bmp.height / 2f)
        val kx = w.toFloat() / (if (logical) lw else bmp.width)
        val ky = h.toFloat() / (if (logical) lh else bmp.height)
        fun px(ll: LatLng): PointF = s.pixelForLatLng(ll).let { PointF(it.x * kx, it.y * ky) }

        val path = route?.takeIf { it.points.size >= 2 }?.let { r ->
            val p = Path()
            var acc = 0.0
            var prev: LatLng? = null
            for (ll in r.points) {
                val q = px(ll)
                if (prev == null) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y)
                prev?.let { acc += dist(it, ll) }
                prev = ll
                if (acc > ROUTE_AHEAD_M) break // o resto está fora da tela
            }
            p
        }
        val dest = route?.destination?.let { px(it) }
        val me = loc?.let { px(LatLng(it.latitude, it.longitude)) }

        return { c ->
            c.drawBitmap(bmp, null, dst, bitmapPaint)
            path?.let { c.drawPath(it, casing); c.drawPath(it, line) }
            dest?.let {
                c.drawCircle(it.x, it.y, 11f * ratio, destFill)
                c.drawCircle(it.x, it.y, 11f * ratio, destStroke)
            }
            me?.let { drawPuck(c, it) }
        }
    }

    /** Seta da moto apontando para cima (o mapa já gira na direção do movimento). */
    private fun drawPuck(c: Canvas, p: PointF) {
        val k = ratio
        val arrow = Path().apply {
            moveTo(p.x, p.y - 15f * k)
            lineTo(p.x + 11f * k, p.y + 11f * k)
            lineTo(p.x, p.y + 5f * k)
            lineTo(p.x - 11f * k, p.y + 11f * k)
            close()
        }
        c.drawPath(arrow, puckFill)
        c.drawPath(arrow, puckStroke)
    }

    private fun dist(a: LatLng, b: LatLng): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
        return r[0].toDouble()
    }

    private fun offset(lat: Double, lon: Double, bearingDeg: Double, meters: Double): LatLng {
        val earth = 6_371_000.0
        val b = Math.toRadians(bearingDeg)
        val dLat = meters * cos(b) / earth
        val dLon = meters * sin(b) / (earth * cos(Math.toRadians(lat)))
        return LatLng(lat + Math.toDegrees(dLat), lon + Math.toDegrees(dLon))
    }
}

private class DashPresentation(
    outer: Context, display: Display, private val w: Int, private val h: Int
) : Presentation(outer, display) {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val routeListener: (NavState.Route?) -> Unit = { showRoute(it) }
    private val locationListener: (Location) -> Unit = { loc ->
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.let { lc ->
            lc.forceLocationUpdate(loc)
            // zoom pela velocidade (+ ajuste dos botões do guidão)
            DashZoom.onSpeed(if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
            val z = DashZoom.current()
            if (kotlin.math.abs(z - appliedZoom) > 0.15) {
                appliedZoom = z
                lc.zoomWhileTracking(z, 1500)
            }
            // inclinação: mais baixa na cidade, mais alta na estrada
            val t = DashZoom.tilt()
            if (kotlin.math.abs(t - appliedTilt) > 2.0) {
                appliedTilt = t
                lc.tiltWhileTracking(t, 2000)
            }
        }
    }
    private var appliedZoom = DashMapSource.ZOOM
    private var appliedTilt = DashMapSource.TILT
    private var styleUrl = ""
    private var listenersOn = false
    private val styleCheck = object : Runnable {
        override fun run() {
            if (NightMode.styleUrl() != styleUrl) loadStyle()
            mapView.postDelayed(this, 30_000)
        }
    }
    private var texture: TextureView? = null

    /** O TextureView onde o MapView desenha (modo captura, com a tela do celular apagada). */
    fun mapTexture(): TextureView? {
        if (!::mapView.isInitialized) return null
        texture?.let { return it }
        return findTexture(mapView).also { texture = it }
    }

    private fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Esta janela é só imagem para o painel: NÃO pode roubar toque nem teclado do celular.
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        val opts = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .compassEnabled(false)
            .camera(CameraPosition.Builder().target(LatLng(-15.7939, -47.8828)).zoom(15.0).build())
        mapView = MapView(context, opts)
        // Desenhar o mapa a 60-120 fps só gastava bateria: com a tela apagada (modo captura) o
        // painel já recebia ~25 quadros/s e estava fluido. 40 dá folga (o Carbit usa 36).
        mapView.setMaximumFps(40)
        // Textos (aviso de manobra, resumo) são desenhados depois, por DashOverlay, iguais nos 3 modos.
        val root = FrameLayout(context)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled = true
            loadStyle()
            mapView.postDelayed(styleCheck, 30_000)
        }
    }

    /** Abre o estilo diurno ou noturno (e recoloca rota, radares, trânsito e a moto). */
    private fun loadStyle() {
        val m = map ?: return
        val url = NightMode.styleUrl()
        styleUrl = url
        style = null
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            if (url != styleUrl) return@setStyle
            style = s
            if (url == NightMode.NIGHT_STYLE) NightMode.markDarkLoaded()
            addRouteLayers(s)
            enableLocation(m, s)
            if (!listenersOn) {
                listenersOn = true
                NavState.addListener(routeListener)
                NavState.addLocationListener(locationListener)
                br.com.motonav.TomTom.addListener(incidentListener)
                br.com.motonav.Radars.addListener(radarListener)
            }
            showRoute(NavState.route)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(m: MapLibreMap, s: Style) {
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, s)
                    .useDefaultLocationEngine(false) // GPS vem do LocationHub (funciona com a tela apagada)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.GPS
            lc.cameraMode = CameraMode.TRACKING_GPS          // gira o mapa na direção do movimento
            appliedZoom = DashZoom.current()
            lc.zoomWhileTracking(appliedZoom)
            appliedTilt = DashZoom.tilt()
            lc.tiltWhileTracking(appliedTilt)
            // posição da moto mais para baixo da tela: mostra mais do caminho à frente
            lc.paddingWhileTracking(doubleArrayOf(0.0, h * DashMapSource.PUCK_TOP_PADDING, 0.0, 0.0))
            NavState.lastLocation?.let { lc.forceLocationUpdate(it) }
        } catch (e: Exception) {
            DashLog.d("GPS no mapa do painel falhou: ${e.message}")
        }
    }

    private val radarListener: () -> Unit = {
        style?.getSourceAs<GeoJsonSource>("dash-radars")?.setGeoJson(br.com.motonav.Radars.geoJson())
    }

    private val incidentListener: () -> Unit = { style?.let { br.com.motonav.TomTom.refreshIncidents(it) } }

    private fun addRouteLayers(s: Style) {
        // trânsito da TomTom (cores nas ruas) e incidentes, por baixo dos nomes de rua
        br.com.motonav.TomTom.addTrafficLayer(s)
        // radares fixos (OpenStreetMap): bolinhas vermelhas no mapa
        s.addSource(GeoJsonSource("dash-radars", br.com.motonav.Radars.geoJson()))
        s.addLayer(CircleLayer("dash-radars-layer", "dash-radars").withProperties(
            PropertyFactory.circleColor("#D32F2F"),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f)
        ))
        s.addSource(GeoJsonSource("dash-route"))
        s.addSource(GeoJsonSource("dash-dest"))
        s.addLayer(LineLayer("dash-route-casing", "dash-route").withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(13f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        s.addLayer(LineLayer("dash-route-line", "dash-route").withProperties(
            PropertyFactory.lineColor("#2979FF"),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        s.addLayer(CircleLayer("dash-dest-layer", "dash-dest").withProperties(
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f)
        ))
    }

    private fun showRoute(r: NavState.Route?) {
        val s = style ?: return
        if (r == null) {
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            s.getSourceAs<GeoJsonSource>("dash-route")?.setGeoJson(empty)
            s.getSourceAs<GeoJsonSource>("dash-dest")?.setGeoJson(empty)
            return
        }
        val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.longitude, it.latitude) })
        s.getSourceAs<GeoJsonSource>("dash-route")?.setGeoJson(Feature.fromGeometry(line))
        s.getSourceAs<GeoJsonSource>("dash-dest")?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(r.destination.longitude, r.destination.latitude))
        )
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        mapView.onResume()
    }

    override fun onStop() {
        mapView.removeCallbacks(styleCheck)
        NavState.removeListener(routeListener)
        NavState.removeLocationListener(locationListener)
        br.com.motonav.Radars.removeListener(radarListener)
        br.com.motonav.TomTom.removeListener(incidentListener)
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        super.onStop()
    }
}
