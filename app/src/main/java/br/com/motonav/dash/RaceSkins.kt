package br.com.motonav.dash

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Estilos do painel Race (todos com os mesmos dados). */
enum class RaceStyle(val title: String, val subtitle: String) {
    NEON("Neon", "Pista em perspectiva, arco colorido e LEDs"),
    CLASSIC("Clássico", "Dois mostradores analógicos com bússola"),
    RETRO("Retrô", "Painel LCD âmbar dos anos 80"),
    HUD("Caça", "HUD verde de avião de combate"),
    TELEMETRY("Telemetria", "Gráfico de velocidade e medidor de força G")
}

/** Números do quadro atual, calculados por [RaceDash] e desenhados por um [RaceSkin]. */
class RaceData {
    enum class Kind { MANEUVER, RADAR, DANGER, FUEL, RAIN, MUSIC, INFO, IDLE }

    var demo = false
    var dt = 0f
    var kmh = 0f
    var shownKmh = 0f
    var phase = 0f
    var heading = 0f
    var limit: Int? = null
    var over = false
    var blink = false
    var tripKm = 0.0
    var tripMs = 0L
    var avgKmh = 0f
    var maxKmh = 0f
    var accel = 0f          // m/s² (+ acelerando, - freando)
    var latG = 0f           // força G lateral (+ direita)
    var fresh = false
    var altitude: Int? = null
    var gpsAcc: Int? = null
    var clock = "--:--"
    var temperature: Int? = null
    var radarM: Double? = null
    var footer = ""
    var footerKind = Kind.IDLE
    val history = ArrayDeque<Float>()

    val trip: String get() = String.format(Locale("pt", "BR"), "%.1f", tripKm)
    val time: String
        get() {
            val s = tripMs / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    val cardinal: String
        get() {
            val names = arrayOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
            return names[(((heading % 360) + 360 + 22.5f) % 360 / 45).toInt() % 8]
        }
}

/** Um estilo de desenho do painel Race (800x480, thread de composição). */
abstract class RaceSkin(protected val w: Int, protected val h: Int) {
    protected val k = h / 480f
    protected val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    protected val black: Typeface = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), 900, true)
    protected val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    abstract fun draw(c: Canvas, d: RaceData)

    protected fun p(color: Int, size: Float = 20f, tf: Typeface = condensed, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size * k; typeface = tf; textAlign = align }

    protected fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width * k; strokeCap = Paint.Cap.ROUND
    }

    protected fun kindColor(kind: RaceData.Kind, base: Int): Int = when (kind) {
        RaceData.Kind.MANEUVER -> Color.rgb(0, 229, 255)
        RaceData.Kind.RADAR -> Color.rgb(255, 214, 0)
        RaceData.Kind.DANGER -> Color.rgb(255, 40, 60)
        RaceData.Kind.FUEL -> Color.rgb(255, 150, 40)
        RaceData.Kind.RAIN -> Color.rgb(100, 180, 255)
        RaceData.Kind.MUSIC -> Color.rgb(30, 215, 96)
        RaceData.Kind.INFO -> Color.WHITE
        RaceData.Kind.IDLE -> base
    }

    protected fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    companion object {
        fun create(style: RaceStyle, w: Int, h: Int): RaceSkin = when (style) {
            RaceStyle.CLASSIC -> ClassicSkin(w, h)
            RaceStyle.RETRO -> RetroSkin(w, h)
            RaceStyle.HUD -> HudSkin(w, h)
            RaceStyle.TELEMETRY, RaceStyle.NEON -> TelemetrySkin(w, h)
        }
    }
}

// =====================================================================================  CLÁSSICO

/** Dois mostradores analógicos: velocímetro com ponteiro laranja e uma bússola que gira. */
class ClassicSkin(w: Int, h: Int) : RaceSkin(w, h) {
    private val orange = Color.rgb(255, 122, 0)
    private val sx = 255f * k
    private val sy = 245f * k
    private val sr = 200f * k
    private val cxR = 630f * k
    private val cyR = 170f * k
    private val cr = 120f * k

    private val bg = Paint().apply {
        shader = RadialGradient(w / 2f, h / 2f, w * 0.7f, Color.rgb(28, 28, 32), Color.rgb(6, 6, 8), Shader.TileMode.CLAMP)
    }
    private val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 12f * k
        shader = SweepGradient(0f, 0f, intArrayOf(Color.rgb(90, 90, 96), Color.rgb(230, 230, 236), Color.rgb(70, 70, 76),
            Color.rgb(210, 210, 216), Color.rgb(90, 90, 96)), null)
    }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, Color.rgb(24, 24, 28), Color.rgb(4, 4, 6), Shader.TileMode.CLAMP)
    }
    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 10, 12) }
    private val tickMinor = stroke(Color.rgb(150, 150, 158), 2f)
    private val tickMajor = stroke(Color.WHITE, 4f)
    private val num = p(Color.WHITE, 26f, black, Paint.Align.CENTER)
    private val redZone = stroke(Color.rgb(230, 30, 30), 10f).apply { strokeCap = Paint.Cap.BUTT }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val needleGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 122, 0); maskFilter = BlurMaskFilter(10f * k, BlurMaskFilter.Blur.NORMAL)
    }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 46) }
    private val capRing = stroke(Color.rgb(160, 160, 168), 3f)
    private val digit = p(Color.WHITE, 46f, black, Paint.Align.CENTER)
    private val small = p(Color.rgb(150, 150, 160), 15f, condensed, Paint.Align.CENTER).apply { letterSpacing = 0.15f }
    private val compassText = p(Color.WHITE, 22f, black, Paint.Align.CENTER)
    private val compassN = p(orange, 26f, black, Paint.Align.CENTER)
    private val lubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 16, 20) }
    private val boxLine = stroke(Color.rgb(60, 60, 68), 1.5f)
    private val boxVal = p(Color.WHITE, 30f, condensed, Paint.Align.CENTER)
    private val footerP = p(Color.rgb(160, 160, 170), 26f, condensed, Paint.Align.CENTER)

    private fun ring(c: Canvas, x: Float, y: Float, r: Float) {
        c.save(); c.translate(x, y)
        c.drawCircle(0f, 0f, r, faceFill)
        c.drawCircle(0f, 0f, r, chrome)
        c.restore()
    }

    override fun draw(c: Canvas, d: RaceData) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // ---- velocímetro 0-160
        ring(c, sx, sy, sr)
        val max = 160f
        fun ang(v: Float) = Math.toRadians(135.0 + 270.0 * (v / max).coerceIn(0f, 1f))
        val inner = sr - 16f * k
        c.drawArc(RectF(sx - inner + 6 * k, sy - inner + 6 * k, sx + inner - 6 * k, sy + inner - 6 * k),
            (135 + 270 * 130 / max), 270 * 30 / max, false, redZone)
        var v = 0
        while (v <= 160) {
            val a = ang(v.toFloat())
            val major = v % 20 == 0
            val mid = v % 10 == 0
            val len = (if (major) 26f else if (mid) 16f else 9f) * k
            c.drawLine(sx + (inner * cos(a)).toFloat(), sy + (inner * sin(a)).toFloat(),
                sx + ((inner - len) * cos(a)).toFloat(), sy + ((inner - len) * sin(a)).toFloat(),
                if (major) tickMajor else tickMinor)
            if (major) {
                val lr = inner - 52f * k
                c.drawText("$v", sx + (lr * cos(a)).toFloat(), sy + (lr * sin(a)).toFloat() + 9f * k, num)
            }
            v += 5
        }
        // mostrador digital
        val db = RectF(sx - 62f * k, sy + 62f * k, sx + 62f * k, sy + 128f * k)
        c.drawRoundRect(db, 8f * k, 8f * k, boxBg); c.drawRoundRect(db, 8f * k, 8f * k, boxLine)
        digit.color = if (d.over && d.blink) Color.rgb(255, 50, 50) else Color.WHITE
        c.drawText("${d.kmh.roundToInt()}", sx, db.bottom - 16f * k, digit)
        c.drawText("KM/H", sx, sy + 50f * k, small)
        // ponteiro afinado
        val a = ang(d.shownKmh)
        val tip = inner - 6f * k
        val path = Path().apply {
            val perp = a + Math.PI / 2
            moveTo(sx + (tip * cos(a)).toFloat(), sy + (tip * sin(a)).toFloat())
            lineTo(sx + (9f * k * cos(perp)).toFloat(), sy + (9f * k * sin(perp)).toFloat())
            lineTo(sx - (34f * k * cos(a)).toFloat(), sy - (34f * k * sin(a)).toFloat())
            lineTo(sx - (9f * k * cos(perp)).toFloat(), sy - (9f * k * sin(perp)).toFloat())
            close()
        }
        c.drawPath(path, needleGlow)
        c.drawPath(path, needle)
        c.drawCircle(sx, sy, 20f * k, cap); c.drawCircle(sx, sy, 20f * k, capRing)

        // ---- bússola
        ring(c, cxR, cyR, cr)
        c.save()
        c.rotate(-d.heading, cxR, cyR)
        val names = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
        for (i in 0 until 72) {
            val deg = i * 5
            val aa = Math.toRadians(deg - 90.0)
            val r0 = cr - 12f * k
            val len = (if (deg % 45 == 0) 16f else if (deg % 15 == 0) 10f else 5f) * k
            c.drawLine(cxR + (r0 * cos(aa)).toFloat(), cyR + (r0 * sin(aa)).toFloat(),
                cxR + ((r0 - len) * cos(aa)).toFloat(), cyR + ((r0 - len) * sin(aa)).toFloat(),
                if (deg % 45 == 0) tickMajor else tickMinor)
            if (deg % 45 == 0) {
                val lr = r0 - 38f * k
                val tx = cxR + (lr * cos(aa)).toFloat()
                val ty = cyR + (lr * sin(aa)).toFloat()
                c.save(); c.rotate(deg.toFloat(), tx, ty)
                c.drawText(names[deg / 45], tx, ty + 8f * k, if (deg == 0) compassN else compassText)
                c.restore()
            }
        }
        c.restore()
        val lub = Path().apply {
            moveTo(cxR, cyR - cr + 4f * k); lineTo(cxR - 9f * k, cyR - cr - 12f * k); lineTo(cxR + 9f * k, cyR - cr - 12f * k); close()
        }
        c.drawPath(lub, lubber)
        boxVal.textSize = 34f * k
        c.drawText("${d.heading.roundToInt()}°", cxR, cyR + 12f * k, boxVal)
        c.drawText(d.cardinal, cxR, cyR + 40f * k, small)

        // ---- caixinhas
        val labels = listOf("VIAGEM" to "${d.trip} km", "MÉDIA" to "${d.avgKmh.roundToInt()}", "MÁX" to "${d.maxKmh.roundToInt()}",
            "TEMPO" to d.time, "HORA" to d.clock, "TEMP" to (d.temperature?.let { "$it°" } ?: "--"))
        val bw = 92f * k; val bh = 62f * k; val gx = 8f * k
        for ((i, pair) in labels.withIndex()) {
            val col = i % 3; val row = i / 3
            val x = 498f * k + col * (bw + gx)
            val y = 312f * k + row * (bh + 8f * k)
            val r = RectF(x, y, x + bw, y + bh)
            c.drawRoundRect(r, 8f * k, 8f * k, boxBg); c.drawRoundRect(r, 8f * k, 8f * k, boxLine)
            small.textSize = 12f * k
            c.drawText(pair.first, r.centerX(), y + 18f * k, small)
            boxVal.textSize = 24f * k
            c.drawText(pair.second, r.centerX(), y + 48f * k, boxVal)
        }
        small.textSize = 15f * k

        // ---- rodapé
        footerP.color = kindColor(d.footerKind, Color.rgb(110, 110, 120))
        footerP.textSize = 22f * k
        c.drawText(ellipsize(d.footer, footerP, w * 0.94f), w / 2f, h - 10f * k, footerP)
    }
}

// =====================================================================================  RETRÔ

/** LCD âmbar dos anos 80: barra em degraus, dígitos gigantes com "888" apagado atrás. */
class RetroSkin(w: Int, h: Int) : RaceSkin(w, h) {
    private val amber = Color.rgb(255, 176, 0)
    private val amberDim = Color.argb(28, 255, 176, 0)
    private val bg = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(26, 14, 2), Color.rgb(8, 4, 0), Shader.TileMode.CLAMP)
    }
    private val scan = Paint().apply { color = Color.argb(60, 0, 0, 0); strokeWidth = 1.5f }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val segGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(8f * k, BlurMaskFilter.Blur.NORMAL) }
    private val bigGhost = p(amberDim, 230f, mono, Paint.Align.RIGHT)
    private val big = p(amber, 230f, mono, Paint.Align.RIGHT).apply { setShadowLayer(16f * k, 0f, 0f, Color.argb(200, 255, 140, 0)) }
    private val unit = p(amber, 26f, mono).apply { letterSpacing = 0.2f }
    private val frame = stroke(amber, 2f).apply { strokeCap = Paint.Cap.BUTT }
    private val lab = p(Color.argb(170, 255, 176, 0), 14f, mono).apply { letterSpacing = 0.15f }
    private val vl = p(amber, 30f, mono, Paint.Align.RIGHT)
    private val foot = p(amber, 22f, mono, Paint.Align.CENTER)

    override fun draw(c: Canvas, d: RaceData) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        // barra em degraus (40 segmentos), cada vez mais altos
        val n = 40
        val x0 = 30f * k; val x1 = w - 30f * k
        val gap = (x1 - x0) / n
        val lit = ((d.shownKmh / 160f) * n).roundToInt().coerceIn(0, n)
        for (i in 0 until n) {
            val hh = (14f + 46f * i / n) * k
            val r = RectF(x0 + i * gap, 96f * k - hh, x0 + i * gap + gap * 0.72f, 96f * k)
            val on = i < lit
            val col = if (i >= 34) Color.rgb(255, 60, 30) else amber
            if (on) { segGlow.color = col; c.drawRect(r, segGlow); seg.color = col }
            else seg.color = amberDim
            c.drawRect(r, seg)
        }
        // escala da barra
        lab.textAlign = Paint.Align.CENTER
        for (v in 0..160 step 40) c.drawText("$v", x0 + (x1 - x0) * v / 160f, 118f * k, lab)
        lab.textAlign = Paint.Align.LEFT

        // velocidade gigante
        val sxR = 470f * k
        val sy = 340f * k
        c.drawText("888", sxR, sy, bigGhost)
        big.color = if (d.over && d.blink) Color.rgb(255, 60, 30) else amber
        c.drawText("${d.kmh.roundToInt()}", sxR, sy, big)
        c.drawText("KM/H", sxR - unit.measureText("KM/H"), sy + 34f * k, unit)
        d.limit?.let {
            val t = "LIM $it"
            c.drawText(t, 40f * k, sy + 34f * k, unit)
        }

        // coluna de caixas à direita
        val items = listOf("VIAGEM" to "${d.trip}", "TEMPO" to d.time, "MEDIA" to "${d.avgKmh.roundToInt()}",
            "MAXIMA" to "${d.maxKmh.roundToInt()}", "HORA" to d.clock)
        val bx = 508f * k; val bw = 262f * k; val bh = 50f * k
        for ((i, it) in items.withIndex()) {
            val y = 136f * k + i * (bh + 5f * k)
            val r = RectF(bx, y, bx + bw, y + bh)
            c.drawRect(r, frame)
            c.drawText(it.first, bx + 10f * k, y + 20f * k, lab)
            c.drawText(it.second, bx + bw - 10f * k, y + 41f * k, vl)
        }

        // rodapé
        val fr = RectF(30f * k, h - 52f * k, w - 30f * k, h - 12f * k)
        c.drawRect(fr, frame)
        foot.color = if (d.footerKind == RaceData.Kind.DANGER) Color.rgb(255, 60, 30) else amber
        c.drawText(ellipsize(d.footer.uppercase(Locale("pt", "BR")), foot, fr.width() - 20f * k), w / 2f, fr.bottom - 12f * k, foot)

        // linhas de varredura (cara de tela antiga)
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y, scan); y += 4f }
    }
}

// =====================================================================================  CAÇA (HUD)

/** HUD de caça: fita de rumo no topo, fitas de velocidade e altitude, escada inclinada pela curva. */
class HudSkin(w: Int, h: Int) : RaceSkin(w, h) {
    private val green = Color.rgb(60, 255, 122)
    private val warn = Color.rgb(255, 80, 60)
    private val line = stroke(green, 2.5f).apply { setShadowLayer(6f * k, 0f, 0f, Color.argb(160, 60, 255, 122)) }
    private val thin = stroke(Color.argb(150, 60, 255, 122), 1.5f)
    private val txt = p(green, 18f, mono, Paint.Align.CENTER).apply { setShadowLayer(6f * k, 0f, 0f, Color.argb(160, 60, 255, 122)) }
    private val bigTxt = p(green, 40f, mono, Paint.Align.CENTER).apply { setShadowLayer(8f * k, 0f, 0f, Color.argb(180, 60, 255, 122)) }
    private val boxFill = Paint().apply { color = Color.BLACK }
    private val bg = Paint().apply {
        shader = RadialGradient(w / 2f, h / 2f, w * 0.75f, Color.rgb(4, 18, 10), Color.BLACK, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas, d: RaceData) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val cx = w / 2f
        val cy = 250f * k
        val col = if (d.over && d.blink) warn else green
        line.color = col; txt.color = col; bigTxt.color = col

        // ---- fita de rumo (topo)
        val tapeY = 62f * k
        val pxPerDeg = 6f * k
        c.save(); c.clipRect(160f * k, 20f * k, w - 160f * k, 100f * k)
        val start = (d.heading - 45).toInt() / 5 * 5
        for (deg in start..(start + 95) step 5) {
            val x = cx + (deg - d.heading) * pxPerDeg
            val dd = ((deg % 360) + 360) % 360
            val major = dd % 30 == 0
            c.drawLine(x, tapeY, x, tapeY + (if (major) 16f else 8f) * k, line)
            if (major) {
                val name = when (dd) { 0 -> "N"; 90 -> "L"; 180 -> "S"; 270 -> "O"; else -> String.format(Locale.US, "%02d", dd / 10) }
                txt.textSize = 18f * k
                c.drawText(name, x, tapeY - 8f * k, txt)
            }
        }
        c.restore()
        // caixa com o rumo
        val hb = RectF(cx - 36f * k, tapeY + 22f * k, cx + 36f * k, tapeY + 52f * k)
        c.drawRect(hb, boxFill); c.drawRect(hb, line)
        txt.textSize = 22f * k
        c.drawText(String.format(Locale.US, "%03d", d.heading.roundToInt() % 360), cx, hb.bottom - 7f * k, txt)

        // ---- escada (inclina com a curva) e marcador de trajetória
        val bank = (d.latG * 35f).coerceIn(-45f, 45f)
        c.save(); c.rotate(-bank, cx, cy)
        for (i in -2..2) {
            if (i == 0) continue
            val y = cy + i * 52f * k
            val half = 120f * k
            val dash = i > 0
            if (dash) {
                var x = cx - half
                while (x < cx - 40f * k) { c.drawLine(x, y, minOf(x + 14f * k, cx - 40f * k), y, thin); x += 24f * k }
                x = cx + 40f * k
                while (x < cx + half) { c.drawLine(x, y, minOf(x + 14f * k, cx + half), y, thin); x += 24f * k }
            } else {
                c.drawLine(cx - half, y, cx - 40f * k, y, thin); c.drawLine(cx + 40f * k, y, cx + half, y, thin)
            }
            c.drawLine(cx - half, y, cx - half, y + (if (i < 0) 10f else -10f) * k, thin)
            c.drawLine(cx + half, y, cx + half, y + (if (i < 0) 10f else -10f) * k, thin)
        }
        c.drawLine(cx - 200f * k, cy, cx - 60f * k, cy, line) // horizonte
        c.drawLine(cx + 60f * k, cy, cx + 200f * k, cy, line)
        c.restore()
        // mira fixa "W"
        val wPath = Path().apply {
            moveTo(cx - 34f * k, cy); lineTo(cx - 16f * k, cy); lineTo(cx - 8f * k, cy + 12f * k)
            lineTo(cx, cy); lineTo(cx + 8f * k, cy + 12f * k); lineTo(cx + 16f * k, cy); lineTo(cx + 34f * k, cy)
        }
        line.style = Paint.Style.STROKE
        c.drawPath(wPath, line)
        // marcador de trajetória: anda com a força G
        val fx = cx + d.latG * 120f * k
        val fy = cy - (d.accel / 9.81f) * 120f * k
        c.drawCircle(fx, fy, 10f * k, line)
        c.drawLine(fx - 22f * k, fy, fx - 10f * k, fy, line)
        c.drawLine(fx + 10f * k, fy, fx + 22f * k, fy, line)
        c.drawLine(fx, fy - 10f * k, fx, fy - 20f * k, line)

        // ---- fita de velocidade (esquerda) e altitude (direita)
        tape(c, 70f * k, cy, d.shownKmh, 10f, 5f * k, "${d.kmh.roundToInt()}", "KM/H", left = true)
        tape(c, w - 70f * k, cy, (d.altitude ?: 0).toFloat(), 10f, 3f * k, d.altitude?.toString() ?: "---", "ALT M", left = false)

        // ---- dados nos cantos de baixo
        txt.textSize = 18f * k
        txt.textAlign = Paint.Align.LEFT
        val l = listOf("TRP ${d.trip} KM", "TME ${d.time}", "AVG ${d.avgKmh.roundToInt()}  MAX ${d.maxKmh.roundToInt()}")
        l.forEachIndexed { i, s -> c.drawText(s, 24f * k, 370f * k + i * 24f * k, txt) }
        txt.textAlign = Paint.Align.RIGHT
        val gTotal = kotlin.math.sqrt(d.latG * d.latG + (d.accel / 9.81f) * (d.accel / 9.81f))
        val r = listOf(String.format(Locale.US, "G %.2f", gTotal), "TMP ${d.temperature?.let { "${it}C" } ?: "--"}",
            "${d.clock}  ${d.limit?.let { "LIM $it" } ?: ""}".trim())
        r.forEachIndexed { i, s -> c.drawText(s, w - 24f * k, 370f * k + i * 24f * k, txt) }
        txt.textAlign = Paint.Align.CENTER

        // rodapé
        txt.color = if (d.footerKind == RaceData.Kind.IDLE) Color.argb(150, 60, 255, 122) else
            if (d.footerKind == RaceData.Kind.DANGER) warn else green
        txt.textSize = 20f * k
        c.drawText(ellipsize(d.footer.uppercase(Locale("pt", "BR")), txt, w * 0.6f), cx, h - 14f * k, txt)
    }

    /** Fita vertical que corre com o valor, caixa com o número no meio. */
    private fun tape(c: Canvas, x: Float, cy: Float, value: Float, step: Float, pxPerUnit: Float, shown: String, title: String, left: Boolean) {
        val half = 150f * k
        c.save(); c.clipRect(x - 60f * k, cy - half, x + 60f * k, cy + half)
        val dir = if (left) 1f else -1f
        val base = x + dir * 30f * k
        val first = ((value - half / pxPerUnit) / step).toInt() - 1
        val last = ((value + half / pxPerUnit) / step).toInt() + 1
        txt.textSize = 16f * k
        for (i in first..last) {
            val v = i * step
            if (v < 0) continue
            val y = cy - (v - value) * pxPerUnit
            val major = (v % (step * 2)) == 0f
            c.drawLine(base, y, base - dir * (if (major) 16f else 8f) * k, y, thin)
            if (major) c.drawText("${v.toInt()}", base - dir * 38f * k, y + 6f * k, txt)
        }
        c.restore()
        c.drawLine(base, cy - half, base, cy + half, thin)
        val b = RectF(x - 52f * k, cy - 24f * k, x + 52f * k, cy + 24f * k)
        c.drawRect(b, boxFill); c.drawRect(b, line)
        bigTxt.textSize = 34f * k
        c.drawText(shown, x, cy + 12f * k, bigTxt)
        txt.textSize = 14f * k
        c.drawText(title, x, cy - half - 10f * k, txt)
    }
}

// =====================================================================================  TELEMETRIA

/** Telemetria de pista: velocidade enorme, gráfico dos últimos 60 s, medidor de força G e blocos. */
class TelemetrySkin(w: Int, h: Int) : RaceSkin(w, h) {
    private val orange = Color.rgb(255, 122, 0)
    private val bg = Paint().apply { color = Color.rgb(12, 13, 18) }
    private val grid = stroke(Color.rgb(34, 37, 48), 1f)
    private val graphLine = stroke(orange, 3f).apply { strokeJoin = Paint.Join.ROUND }
    private val graphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val speed = p(Color.WHITE, 150f, black)
    private val unit = p(orange, 22f, condensed).apply { letterSpacing = 0.25f }
    private val lab = p(Color.rgb(130, 136, 150), 14f, condensed).apply { letterSpacing = 0.15f }
    private val vl = p(Color.WHITE, 34f, black)
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 24, 32) }
    private val accent = Paint().apply { color = orange }
    private val ring = stroke(Color.rgb(50, 54, 68), 1.5f)
    private val axis = stroke(Color.rgb(40, 44, 56), 1f)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange; setShadowLayer(10f * k, 0f, 0f, orange) }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foot = p(Color.WHITE, 22f, condensed, Paint.Align.CENTER)
    private val gTrail = ArrayDeque<Pair<Float, Float>>()

    private val gx0 = 380f * k; private val gx1 = w - 24f * k
    private val gy0 = 34f * k; private val gy1 = 214f * k

    init {
        graphFill.shader = LinearGradient(0f, gy0, 0f, gy1, Color.argb(140, 255, 122, 0), Color.argb(0, 255, 122, 0), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas, d: RaceData) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // ---- velocidade
        speed.color = if (d.over && d.blink) Color.rgb(255, 50, 60) else Color.WHITE
        c.drawText("${d.kmh.roundToInt()}", 28f * k, 168f * k, speed)
        c.drawText("KM/H", 34f * k, 200f * k, unit)
        d.limit?.let {
            lab.color = if (d.over) Color.rgb(255, 80, 80) else Color.rgb(130, 136, 150)
            c.drawText("LIMITE $it", 150f * k, 200f * k, lab)
            lab.color = Color.rgb(130, 136, 150)
        }
        // barra de aceleração
        val a = (d.accel / 4f).coerceIn(-1f, 1f)
        val bx = 34f * k; val bw = 300f * k; val by = 220f * k
        c.drawRect(bx, by, bx + bw, by + 6f * k, tile)
        accent.color = if (a >= 0) Color.rgb(0, 220, 120) else Color.rgb(255, 60, 70)
        val mid = bx + bw / 2
        c.drawRect(minOf(mid, mid + a * bw / 2), by, maxOf(mid, mid + a * bw / 2), by + 6f * k, accent)
        accent.color = orange

        // ---- gráfico de velocidade (60 s)
        for (i in 0..4) { val y = gy0 + (gy1 - gy0) * i / 4; c.drawLine(gx0, y, gx1, y, grid) }
        lab.textAlign = Paint.Align.RIGHT
        for (i in 0..4) c.drawText("${160 - i * 40}", gx0 - 6f * k, gy0 + (gy1 - gy0) * i / 4 + 5f * k, lab)
        lab.textAlign = Paint.Align.LEFT
        c.drawText("VELOCIDADE · 60 s", gx0, gy0 - 10f * k, lab)
        val hist = d.history
        if (hist.size >= 2) {
            val path = Path(); val fill = Path()
            val n = 120
            hist.forEachIndexed { i, v ->
                val x = gx1 - (hist.size - 1 - i) * (gx1 - gx0) / (n - 1)
                val y = gy1 - (v / 160f).coerceIn(0f, 1f) * (gy1 - gy0)
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, gy1); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(gx1, gy1); fill.close()
            c.drawPath(fill, graphFill)
            c.drawPath(path, graphLine)
        }

        // ---- blocos 2x2 + linha de extras
        val tiles = listOf("VIAGEM" to "${d.trip} km", "TEMPO" to d.time, "MÉDIA" to "${d.avgKmh.roundToInt()} km/h", "MÁXIMA" to "${d.maxKmh.roundToInt()} km/h")
        val tw = 196f * k; val th = 74f * k
        for ((i, t) in tiles.withIndex()) {
            val x = 28f * k + (i % 2) * (tw + 10f * k)
            val y = 248f * k + (i / 2) * (th + 10f * k)
            val r = RectF(x, y, x + tw, y + th)
            c.drawRoundRect(r, 10f * k, 10f * k, tile)
            c.drawRect(x, y + 10f * k, x + 4f * k, y + th - 10f * k, accent)
            c.drawText(t.first, x + 16f * k, y + 24f * k, lab)
            vl.textSize = 30f * k
            c.drawText(t.second, x + 16f * k, y + 60f * k, vl)
        }
        // extras ao lado dos blocos
        lab.textSize = 15f * k
        val ex = listOf("HORA  ${d.clock}", "RUMO  ${d.cardinal} ${d.heading.roundToInt()}°",
            "TEMP  ${d.temperature?.let { "$it°C" } ?: "--"}", "ALT  ${d.altitude?.let { "$it m" } ?: "--"}")
        ex.forEachIndexed { i, s -> c.drawText(s, 440f * k, 268f * k + i * 22f * k, lab) }
        lab.textSize = 14f * k

        // ---- medidor de força G
        val gcx = 690f * k; val gcy = 320f * k; val gr = 78f * k
        c.drawCircle(gcx, gcy, gr, ring); c.drawCircle(gcx, gcy, gr / 2, ring)
        c.drawLine(gcx - gr, gcy, gcx + gr, gcy, axis); c.drawLine(gcx, gcy - gr, gcx, gcy + gr, axis)
        val gx = (d.latG / 1f).coerceIn(-1f, 1f) * gr
        val gy = (-(d.accel / 9.81f) / 1f).coerceIn(-1f, 1f) * gr
        gTrail.addLast(gx to gy)
        while (gTrail.size > 40) gTrail.removeFirst()
        gTrail.forEachIndexed { i, (tx, ty) ->
            trail.color = Color.argb((120 * i / gTrail.size), 255, 122, 0)
            c.drawCircle(gcx + tx, gcy + ty, 4f * k, trail)
        }
        c.drawCircle(gcx + gx, gcy + gy, 9f * k, dot)
        lab.textAlign = Paint.Align.CENTER
        val gTotal = kotlin.math.sqrt(d.latG * d.latG + (d.accel / 9.81f) * (d.accel / 9.81f))
        c.drawText(String.format(Locale.US, "%.2f G", gTotal), gcx, gcy + gr + 20f * k, lab)
        lab.textAlign = Paint.Align.LEFT

        // ---- rodapé
        c.drawRect(0f, h - 40f * k, w.toFloat(), h.toFloat(), tile)
        foot.color = kindColor(d.footerKind, Color.rgb(110, 116, 130))
        c.drawText(ellipsize(d.footer, foot, w * 0.94f), w / 2f, h - 13f * k, foot)
    }
}
