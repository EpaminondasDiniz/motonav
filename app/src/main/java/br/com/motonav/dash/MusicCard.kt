package br.com.motonav.dash

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import br.com.motonav.MediaInfo
import kotlin.math.abs
import kotlin.math.sin

/**
 * Cartão "tocando agora" no topo do painel, no estilo da notificação de música do Android:
 * capa do álbum, nome e artista, barrinhas de equalizador pulando e a linha de progresso
 * ondulada que anda. O fundo pega a cor da capa. Usado só na thread de composição.
 */
class MusicCard(private val h: Int) {
    private val k = h / 480f
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = bold }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val artist = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val time = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 255, 255, 255); typeface = bold }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
    }
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.argb(90, 255, 255, 255)
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val m = Matrix()

    private var shaderArt: Bitmap? = null
    private var bgColor = 0
    private var bgRect = RectF()

    /** Desenha o cartão dentro de [r]. */
    fun draw(c: Canvas, r: RectF) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val accent = MediaInfo.artColor
        val pad = r.height() * 0.11f
        val radius = 14f * k

        // fundo: cor da capa escurecida -> quase preto
        if (accent != bgColor || r != bgRect) {
            bgColor = accent
            bgRect = RectF(r)
            bg.shader = LinearGradient(r.left, 0f, r.right, 0f,
                intArrayOf(blend(accent, Color.BLACK, 0.35f), blend(accent, Color.BLACK, 0.78f)), null, Shader.TileMode.CLAMP)
        }
        bg.alpha = 240
        c.drawRoundRect(r, radius, radius, bg)

        // capa (cantos arredondados)
        val side = r.height() - 2 * pad
        val ar = RectF(r.left + pad, r.top + pad, r.left + pad + side, r.top + pad + side)
        val a = MediaInfo.art
        if (a != null) {
            if (a !== shaderArt) {
                shaderArt = a
                artPaint.shader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            m.setRectToRect(RectF(0f, 0f, a.width.toFloat(), a.height.toFloat()), ar, Matrix.ScaleToFit.FILL)
            artPaint.shader.setLocalMatrix(m)
            c.drawRoundRect(ar, radius * 0.7f, radius * 0.7f, artPaint)
        } else {
            placeholder.color = blend(accent, Color.BLACK, 0.2f)
            c.drawRoundRect(ar, radius * 0.7f, radius * 0.7f, placeholder)
            note.textSize = side * 0.55f
            c.drawText("♪", ar.centerX(), ar.centerY() + note.textSize * 0.35f, note)
        }

        // textos
        val tx = ar.right + pad
        val eqW = 26f * k
        val textW = r.right - pad - tx - eqW - 6f * k
        title.textSize = r.height() * 0.21f
        artist.textSize = r.height() * 0.16f
        val ty = r.top + pad + title.textSize * 0.9f
        c.drawText(ellipsize(MediaInfo.title ?: "", title, textW), tx, ty, title)
        c.drawText(ellipsize(MediaInfo.artist ?: "", artist, textW + eqW), tx, ty + artist.textSize * 1.3f, artist)

        // equalizador: 4 barras pulando
        val ex = r.right - pad - eqW
        val eb = r.top + pad + title.textSize * 0.9f
        val bw = eqW / 4f
        eq.color = blend(accent, Color.WHITE, 0.45f)
        for (i in 0 until 4) {
            val v = 0.25f + 0.75f * abs(sin(t * (5.1f + i * 1.7f) + i * 1.3f))
            val bh = title.textSize * 0.95f * v
            c.drawRoundRect(RectF(ex + i * bw + bw * 0.15f, eb - bh, ex + (i + 1) * bw - bw * 0.15f, eb), bw * 0.3f, bw * 0.3f, eq)
        }

        // progresso ondulado
        val dur = MediaInfo.durationMs
        val pos = MediaInfo.positionMs()
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        time.textSize = r.height() * 0.13f
        val tPos = fmt(pos)
        val tDur = if (dur > 0) fmt(dur) else ""
        val wy = r.bottom - pad - time.textSize * 0.3f
        val x0 = tx + time.measureText(tPos) + 8f * k
        val x1 = r.right - pad - time.measureText(tDur) - 8f * k
        c.drawText(tPos, tx, wy + time.textSize * 0.35f, time)
        time.textAlign = Paint.Align.RIGHT
        c.drawText(tDur, r.right - pad, wy + time.textSize * 0.35f, time)
        time.textAlign = Paint.Align.LEFT
        if (x1 > x0) {
            val xp = x0 + (x1 - x0) * frac
            val amp = 3.2f * k
            val lambda = 18f * k
            val phase = t * 7f
            wave.strokeWidth = 3.2f * k
            rest.strokeWidth = 3.2f * k
            path.reset()
            var x = x0
            path.moveTo(x, wy + amp * sin((x - x0) / lambda * 6.283f - phase))
            while (x < xp) {
                x = minOf(x + 2f, xp)
                path.lineTo(x, wy + amp * sin((x - x0) / lambda * 6.283f - phase))
            }
            if (xp > x0 + 1f) c.drawPath(path, wave)
            if (x1 > xp + 4f * k) c.drawLine(xp + 4f * k, wy, x1, wy, rest)
            c.drawRoundRect(RectF(xp - 2.5f * k, wy - 9f * k, xp + 2.5f * k, wy + 9f * k), 3f * k, 3f * k, thumb)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${String.format(java.util.Locale.US, "%02d", s % 60)}"
    }

    private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
        (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
        (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
    )

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }
}
