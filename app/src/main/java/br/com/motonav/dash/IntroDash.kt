package br.com.motonav.dash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Animação de entrada do painel (≈5,4 s), desenhada direto no quadro 800x480:
 *
 *  0,0 s  ignição: um risco de luz abre no meio da tela e cantoneiras de HUD entram
 *  0,3 s  "warp": riscos de velocidade saindo do centro, cada vez mais rápidos
 *  0,4 s  teste de ignição: anel de LEDs varre até a faixa vermelha e volta, com o giro contando
 *  1,1 s  as letras de M O T O caem uma a uma, com tranco na tela a cada letra
 *  1,75 s clarão branco; brilho passa pelo logo; a faixa vermelha "NAV" corre por baixo
 *  2,4 s  saudação pela hora e "BEM-VINDO, <NOME>" revelado da esquerda para a direita
 *  4,6 s  saída: a tela abre ao meio como portão e mostra o mapa (ou o Race) por baixo
 */
class IntroDash(
    private val w: Int,
    private val h: Int,
    name: String,
    private val subtitle: String? = null
) {
    companion object {
        const val DURATION_S = 5.4f
        private const val LOGO = "MOTO"
        private const val LETTER_T0 = 1.1f
        private const val LETTER_DT = 0.09f
        private const val LETTER_FALL = 0.18f
        private const val EXIT_T0 = 4.6f
        private val ORANGE = Color.parseColor("#FF7A00")
        private val REDLINE = Color.parseColor("#FF2A1A")
    }

    private val cx = w / 2f
    private val cy = h / 2f
    private val k = h / 480f // tudo foi medido para 480 px de altura
    private val maxR = hypot(cx, cy)

    private val title = if (name.isBlank()) "BEM-VINDO" else "BEM-VINDO, ${name.uppercase(Locale("pt", "BR"))}"
    private val greeting: String
    private val info: String

    init {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        greeting = when (hour) {
            in 5..11 -> "BOM DIA"
            in 12..17 -> "BOA TARDE"
            else -> "BOA NOITE"
        }
        val clock = String.format(Locale.US, "%02d:%02d", hour, cal.get(Calendar.MINUTE))
        val date = String.format(Locale.US, "%02d/%02d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1)
        info = listOfNotNull("$date · $clock", "MOTO PRONTA", subtitle?.uppercase(Locale("pt", "BR")))
            .joinToString("   •   ")
    }

    // riscos de velocidade: ângulo, fase, velocidade, cor
    private val streaks = Random(7).let { r ->
        Array(110) { floatArrayOf(r.nextFloat() * 6.2832f, r.nextFloat(), 0.55f + r.nextFloat() * 0.9f, r.nextFloat()) }
    }

    private val black = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), 900, true)
    private val bold = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false)

    private val bg = Paint().apply { color = Color.parseColor("#050507") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy, maxR, intArrayOf(Color.parseColor("#9A4300"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val led = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 20f * k }
    private val logo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = black; textSize = 118f * k; color = Color.WHITE; textAlign = Paint.Align.LEFT
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = black; textSize = 118f * k }
    private val shineMatrix = Matrix()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }
    private val ringRect = RectF(cx - 190f * k, cy - 190f * k, cx + 190f * k, cy + 190f * k)

    // posição de cada letra do logo (centralizado)
    private val logoSpacing = 6f * k
    private val letterX: FloatArray
    private val logoW: Float
    private val logoBase = cy + 10f * k

    init {
        val ws = LOGO.map { logo.measureText(it.toString()) }
        logoW = ws.sum() + logoSpacing * (LOGO.length - 1)
        var x = cx - logoW / 2
        letterX = FloatArray(LOGO.length)
        for (i in LOGO.indices) { letterX[i] = x; x += ws[i] + logoSpacing }
        shine.shader = LinearGradient(0f, 0f, 120f * k, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(230, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
    }

    /** [t] em segundos desde o início; [under] desenha o que fica por baixo (mapa ou Race). */
    fun draw(c: Canvas, t: Float, under: (Canvas) -> Unit) {
        if (t < EXIT_T0) { scene(c, t); return }
        // saída: portão abrindo ao meio
        under(c)
        val off = ease(p(t, EXIT_T0, DURATION_S)).pow(1.3f) * (h / 2f + 10f * k)
        c.save(); c.translate(0f, -off); c.clipRect(0f, 0f, w.toFloat(), cy); scene(c, t); c.restore()
        c.save(); c.translate(0f, off); c.clipRect(0f, cy, w.toFloat(), h.toFloat()); scene(c, t); c.restore()
        line.color = ORANGE; line.strokeWidth = 4f * k; line.alpha = 255
        c.drawLine(0f, cy - off, w.toFloat(), cy - off, line)
        c.drawLine(0f, cy + off, w.toFloat(), cy + off, line)
    }

    private fun scene(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        glow.alpha = (150 * p(t, 0.3f, 1.6f)).toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)

        c.save()
        val amp = shake(t)
        if (amp > 0f) c.translate(amp * sin(t * 97f), amp * cos(t * 71f))

        warp(c, t)
        ignition(c, t)
        corners(c, t)
        revRing(c, t)
        logo(c, t)
        texts(c, t)
        c.restore()

        // clarão branco quando a última letra cai
        if (t >= 1.75f) {
            val a = 1f - p(t, 1.75f, 2.1f)
            if (a > 0f) { fill.color = Color.WHITE; fill.alpha = (220 * a).toInt(); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill) }
        }
    }

    private fun ignition(c: Canvas, t: Float) {
        val a = 1f - p(t, 0.45f, 0.9f)
        if (a <= 0f) return
        val half = ease(p(t, 0f, 0.35f)) * w / 2f
        line.color = Color.WHITE; line.alpha = (255 * a).toInt(); line.strokeWidth = 3f * k
        c.drawLine(cx - half, cy, cx + half, cy, line)
        line.color = ORANGE; line.alpha = (200 * a).toInt(); line.strokeWidth = 12f * k
        c.drawLine(cx - half * 0.6f, cy, cx + half * 0.6f, cy, line)
    }

    private fun warp(c: Canvas, t: Float) {
        val alpha = p(t, 0.25f, 0.6f) * (1f - p(t, 2.3f, 3.0f))
        if (alpha <= 0f) return
        val v = p(t, 0.25f, 1.3f) // aceleração do "warp"
        for (s in streaks) {
            val u = ((s[1] + t * s[2] * (0.3f + 1.2f * v)) % 1f)
            val r1 = u * u * maxR * 1.15f
            val len = (0.04f + 0.4f * v) * r1
            val r0 = r1 - len
            val dx = cos(s[0]); val dy = sin(s[0])
            streak.color = if (s[3] < 0.3f) ORANGE else Color.rgb(200, 210, 230)
            streak.alpha = (255 * u * alpha).toInt().coerceIn(0, 255)
            streak.strokeWidth = (1f + 3.5f * u) * k
            c.drawLine(cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, streak)
        }
    }

    private fun corners(c: Canvas, t: Float) {
        val q = ease(p(t, 0.3f, 0.8f))
        if (q <= 0f) return
        val m = 22f * k + (1f - q) * 60f * k
        val l = 46f * k
        line.color = Color.WHITE; line.alpha = (170 * q).toInt(); line.strokeWidth = 3f * k
        val r = w - m; val b = h - m
        c.drawLine(m, m, m + l, m, line); c.drawLine(m, m, m, m + l, line)
        c.drawLine(r, m, r - l, m, line); c.drawLine(r, m, r, m + l, line)
        c.drawLine(m, b, m + l, b, line); c.drawLine(m, b, m, b - l, line)
        c.drawLine(r, b, r - l, b, line); c.drawLine(r, b, r, b - l, line)
        // "sistemas" sendo checados no canto
        text.textAlign = Paint.Align.LEFT; text.textSize = 13f * k; text.color = Color.rgb(150, 160, 175)
        text.alpha = (255 * q).toInt()
        val checks = listOf("GPS", "MAPA", "VÍDEO", "PAINEL")
        for ((i, s) in checks.withIndex()) {
            val ok = t > 0.6f + i * 0.25f
            text.color = if (ok) Color.rgb(90, 230, 120) else Color.rgb(150, 160, 175)
            text.alpha = (255 * q).toInt()
            c.drawText(if (ok) "■ $s OK" else "□ $s", m + 6f * k, m + l + 22f * k + i * 18f * k, text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun revRing(c: Canvas, t: Float) {
        val alpha = p(t, 0.3f, 0.5f) * (1f - p(t, 1.45f, 1.8f))
        if (alpha <= 0f) return
        val f = if (t < 0.95f) ease(p(t, 0.4f, 0.95f)) else 1f - ease(p(t, 0.95f, 1.5f))
        val n = 44
        val span = 270f / n
        for (i in 0 until n) {
            val x = i / n.toFloat()
            val lit = x < f
            led.color = when {
                !lit -> Color.rgb(40, 40, 48)
                x < 0.6f -> Color.rgb(60, 230, 110)
                x < 0.8f -> Color.rgb(255, 200, 40)
                else -> REDLINE
            }
            led.alpha = (255 * alpha).toInt()
            c.drawArc(ringRect, 135f + i * span, span * 0.7f, false, led)
        }
        // giro contando no centro (some antes das letras caírem)
        val ra = alpha * (1f - p(t, 0.95f, 1.15f))
        if (ra > 0f) {
            text.textSize = 64f * k; text.color = Color.WHITE; text.alpha = (255 * ra).toInt()
            text.typeface = black
            c.drawText(String.format(Locale("pt", "BR"), "%,d", (f * 11000).toInt() / 100 * 100), cx, cy + 22f * k, text)
            text.typeface = bold
            text.textSize = 16f * k; text.color = Color.rgb(160, 160, 170); text.alpha = (255 * ra).toInt()
            c.drawText("RPM", cx, cy + 50f * k, text)
        }
    }

    private fun logo(c: Canvas, t: Float) {
        for (i in LOGO.indices) {
            val t0 = LETTER_T0 + i * LETTER_DT
            if (t < t0) continue
            val q = p(t, t0, t0 + LETTER_FALL)
            val s = 1f + 2.2f * (1f - backOut(q))
            val ch = LOGO[i].toString()
            val lw = logo.measureText(ch)
            c.save()
            c.scale(s, s, letterX[i] + lw / 2, logoBase - 40f * k)
            logo.alpha = (255 * q.coerceAtMost(1f)).toInt()
            logo.setShadowLayer(18f * k, 0f, 0f, ORANGE)
            c.drawText(ch, letterX[i], logoBase, logo)
            c.restore()
        }
        // brilho passando pelo logo
        val sh = p(t, 2.0f, 2.6f)
        if (sh > 0f && sh < 1f) {
            shineMatrix.setTranslate(cx - logoW / 2 - 120f * k + sh * (logoW + 240f * k), 0f)
            shine.shader.setLocalMatrix(shineMatrix)
            for (i in LOGO.indices) c.drawText(LOGO[i].toString(), letterX[i], logoBase, shine)
        }
        // faixa vermelha por baixo, com a etiqueta NAV
        val u = ease(p(t, 1.9f, 2.3f))
        if (u > 0f) {
            val x0 = cx - logoW / 2
            val y = logoBase + 18f * k
            fill.color = ORANGE; fill.alpha = 255
            c.drawRect(x0, y, x0 + logoW * u, y + 7f * k, fill)
            val ta = p(t, 2.25f, 2.45f)
            if (ta > 0f) {
                val bw = 74f * k
                val bx = x0 + logoW - bw
                fill.alpha = (255 * ta).toInt()
                c.drawRect(bx, y + 11f * k, bx + bw, y + 37f * k, fill)
                text.textSize = 19f * k; text.color = Color.WHITE; text.alpha = (255 * ta).toInt()
                text.letterSpacing = 0.35f
                c.drawText("NAV", bx + bw / 2 + 3f * k, y + 31f * k, text)
                text.letterSpacing = 0f
            }
        }
    }

    private fun texts(c: Canvas, t: Float) {
        val ga = p(t, 2.4f, 2.7f)
        if (ga <= 0f) return
        text.textSize = 22f * k; text.color = Color.rgb(170, 175, 185); text.alpha = (255 * ga).toInt()
        text.letterSpacing = 0.4f
        c.drawText(greeting, cx, cy + 108f * k, text)
        text.letterSpacing = 0f

        // "BEM-VINDO, NOME" revelado da esquerda para a direita, com um cursor de luz
        val rv = ease(p(t, 2.6f, 3.2f))
        if (rv > 0f) {
            text.textSize = 46f * k; text.typeface = black; text.color = Color.WHITE; text.alpha = 255
            val tw = text.measureText(title)
            val x0 = cx - tw / 2 - 10f * k
            val edge = x0 + (tw + 20f * k) * rv
            val y = cy + 160f * k
            c.save(); c.clipRect(x0, y - 50f * k, edge, y + 14f * k)
            c.drawText(title, cx, y, text)
            c.restore()
            text.typeface = bold
            if (rv < 1f) {
                fill.color = ORANGE; fill.alpha = 255
                c.drawRect(edge - 3f * k, y - 46f * k, edge + 3f * k, y + 10f * k, fill)
            }
        }
        val ia = p(t, 3.15f, 3.6f)
        if (ia > 0f) {
            text.textSize = 17f * k; text.color = Color.rgb(140, 145, 155); text.alpha = (255 * ia).toInt()
            c.drawText(info, cx, cy + 196f * k, text)
        }
    }

    /** Tranco da tela quando cada letra cai (maior na última). */
    private fun shake(t: Float): Float {
        var a = 0f
        for (i in LOGO.indices) {
            val land = LETTER_T0 + i * LETTER_DT + LETTER_FALL
            val d = t - land
            if (d in 0f..0.25f) a += (if (i == LOGO.length - 1) 14f else 6f) * k * (1f - d / 0.25f)
        }
        return a
    }

    private fun p(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = 1f - (1f - x).pow(3)
    private fun backOut(x: Float): Float {
        val s = 1.9f
        val y = x - 1f
        return y * y * ((s + 1) * y + s) + 1f
    }
}
