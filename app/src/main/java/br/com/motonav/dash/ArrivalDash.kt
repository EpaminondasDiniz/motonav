package br.com.motonav.dash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import br.com.motonav.NavState
import java.util.Locale
import kotlin.math.pow

/**
 * Tela de chegada (8 s por cima do mapa ou do Race): faixas quadriculadas correndo, "CHEGOU!"
 * caindo com brilho laranja, o destino e quatro números da viagem contando (distância, tempo em
 * movimento, média, máxima). Some sozinha.
 */
class ArrivalDash(private val w: Int, private val h: Int) {
    companion object {
        const val DURATION_S = 8f
        private val ORANGE = Color.parseColor("#FF7A00")
    }

    private val k = h / 480f
    private val cx = w / 2f
    private val black = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), 900, true)
    private val bold = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 700, false)

    private val dim = Paint()
    private val square = Paint()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = black; textSize = 92f * k; color = Color.WHITE; textAlign = Paint.Align.CENTER
        setShadowLayer(20f * k, 0f, 0f, ORANGE)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE }

    fun draw(c: Canvas, a: NavState.Arrival, t: Float) {
        val out = 1f - p(t, DURATION_S - 0.8f, DURATION_S)
        val alpha = p(t, 0f, 0.35f) * out
        if (alpha <= 0f) return
        dim.color = Color.argb((235 * alpha).toInt(), 6, 6, 9)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dim)

        // faixas quadriculadas correndo (em cima e embaixo)
        val sq = 20f * k
        val shift = (t * 90f * k) % (2 * sq)
        for (row in 0..1) for (band in 0..1) {
            val y = if (band == 0) row * sq else h - (2 - row) * sq
            var x = -2 * sq + (if (band == 0) shift else -shift)
            var col = row
            while (x < w + sq) {
                square.color = if (col % 2 == 0) Color.WHITE else Color.BLACK
                square.alpha = (255 * alpha).toInt()
                c.drawRect(x, y, x + sq, y + sq, square)
                x += sq; col++
            }
        }

        // CHEGOU!
        val q = p(t, 0.25f, 0.55f)
        if (q > 0f) {
            val s = 1f + 1.6f * (1f - ease(q))
            c.save(); c.scale(s, s, cx, 150f * k)
            title.alpha = (255 * q * out).toInt()
            c.drawText("CHEGOU!", cx, 175f * k, title)
            c.restore()
        }
        val la = p(t, 0.6f, 0.9f) * out
        if (la > 0f) {
            text.textSize = 24f * k; text.color = Color.rgb(200, 205, 215); text.alpha = (255 * la).toInt()
            c.drawText(ellipsize(a.label, text, w * 0.85f), cx, 220f * k, text)
            accent.alpha = (255 * la).toInt()
            c.drawRect(cx - 60f * k, 236f * k, cx + 60f * k, 241f * k, accent)
        }

        // quatro números
        val stats = listOf(
            "DISTÂNCIA" to { f: Float -> String.format(Locale("pt", "BR"), "%.1f", a.distanceM / 1000 * f) to "km" },
            "TEMPO" to { f: Float -> "${(a.movingMs * f / 60_000).toInt()}" to "min" },
            "MÉDIA" to { f: Float -> "${(a.avgKmh * f).toInt()}" to "km/h" },
            "MÁXIMA" to { f: Float -> "${(a.maxKmh * f).toInt()}" to "km/h" }
        )
        val tw = 165f * k
        val gap = 18f * k
        val x0 = cx - (4 * tw + 3 * gap) / 2
        for ((i, st) in stats.withIndex()) {
            val ti = p(t, 0.9f + i * 0.15f, 1.25f + i * 0.15f)
            if (ti <= 0f) continue
            val count = ease(p(t, 1.0f + i * 0.15f, 2.0f + i * 0.15f))
            val x = x0 + i * (tw + gap)
            val y = 275f * k + (1f - ease(ti)) * 40f * k
            val box = RectF(x, y, x + tw, y + 120f * k)
            tile.color = Color.argb((200 * ti * out).toInt(), 28, 30, 38)
            c.drawRoundRect(box, 12f * k, 12f * k, tile)
            accent.alpha = (255 * ti * out).toInt()
            c.drawRect(box.left, box.top, box.right, box.top + 4f * k, accent)
            text.textSize = 15f * k; text.color = Color.rgb(150, 155, 165); text.alpha = (255 * ti * out).toInt()
            text.letterSpacing = 0.15f
            c.drawText(st.first, box.centerX(), box.top + 30f * k, text)
            text.letterSpacing = 0f
            val (v, unit) = st.second(count)
            text.textSize = 46f * k; text.typeface = black; text.color = Color.WHITE; text.alpha = (255 * ti * out).toInt()
            c.drawText(v, box.centerX(), box.top + 82f * k, text)
            text.typeface = bold
            text.textSize = 15f * k; text.color = ORANGE; text.alpha = (255 * ti * out).toInt()
            c.drawText(unit, box.centerX(), box.top + 106f * k, text)
        }
    }

    private fun p(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(x: Float) = 1f - (1f - x).pow(3)
    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }
}
