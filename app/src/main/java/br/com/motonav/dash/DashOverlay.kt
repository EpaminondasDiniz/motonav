package br.com.motonav.dash

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import br.com.motonav.NavState

/**
 * Textos por cima do mapa do painel, desenhados no quadro final (vale para os modos ao vivo,
 * captura e instantâneos):
 *  - faixa no topo com o aviso "onde entrar" (seta + distância + rua), a partir de 500 m;
 *  - faixa laranja "Recalculando rota..." enquanto o servidor responde;
 *  - resumo de km/min restantes no canto inferior esquerdo.
 * Usado só na thread de composição.
 */
class DashOverlay(private val w: Int, private val h: Int) {

    private val bannerH = h * 0.22f
    private val pad = h * 0.03f

    private val bannerBg = Paint().apply { color = Color.argb(235, 13, 42, 92) }
    private val rerouteBg = Paint().apply { color = Color.argb(235, 230, 120, 0) }
    private val arrowBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 41, 121, 255) }
    private val arrowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = bannerH * 0.72f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val distText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.095f; typeface = Typeface.DEFAULT_BOLD
    }
    private val streetText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 220, 230, 255); textSize = h * 0.058f
    }
    private val summaryBg = Paint().apply { color = Color.argb(200, 20, 20, 20) }
    private val summaryText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.065f
    }

    // velocímetro e radar (canto inferior direito)
    private val speedBg = Paint().apply { color = Color.argb(200, 20, 20, 20) }
    private val speedNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.10f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val speedUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; textSize = h * 0.035f; textAlign = Paint.Align.CENTER
    }
    private val signFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val signRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 47, 47); style = Paint.Style.STROKE; strokeWidth = h * 0.022f
    }
    private val signNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = h * 0.075f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val radarLabelBg = Paint().apply { color = Color.argb(225, 211, 47, 47) }
    private val radarLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.045f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val overSpeed = Paint().apply { color = Color.argb(230, 211, 47, 47) }

    private val incidentBg = Paint().apply { color = Color.argb(235, 198, 40, 40) }
    private val incidentText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.058f; typeface = Typeface.DEFAULT_BOLD
    }

    private val music = MusicCard(h)
    private val rowH = h * 0.085f
    private val rowBg = Paint().apply { color = Color.argb(245, 8, 26, 60) }
    private val rerouteRowBg = Paint().apply { color = Color.argb(245, 150, 70, 0) }
    private val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.05f; typeface = Typeface.DEFAULT_BOLD
    }
    private val clip = android.graphics.Path()

    fun draw(c: Canvas, r: NavState.Route?) {
        var top = 0f
        // com música tocando, a parte de cima vira dois cartões iguais: navegação | música
        val playing = br.com.motonav.MediaInfo.text() != null
        val cardH = bannerH + rowH
        if (r != null) {
            val rect = if (playing) RectF(pad * 0.5f, pad * 0.5f, w * 0.5f - pad * 0.25f, cardH)
            else RectF(0f, 0f, w.toFloat(), cardH)
            drawNavCard(c, r, rect, rounded = playing)
            top = cardH
        }
        if (playing) {
            val hh = if (r != null) cardH else bannerH
            music.draw(c, RectF(w * 0.5f + pad * 0.25f, pad * 0.5f, w - pad * 0.5f, hh))
            top = maxOf(top, hh)
        }
        NavState.incident?.let { drawIncident(c, it, top + (if (playing) pad * 0.3f else 0f)) }
        drawSpeedAndRadar(c)
        drawPills(c, hasSummary = false)
    }

    /**
     * Cartão de navegação: seta + distância + rua (ou "Recalculando…") e, embaixo, a linha
     * "🏁 3,5 km · 7 min · chegada 21:45".
     */
    private fun drawNavCard(c: Canvas, r: NavState.Route, rect: RectF, rounded: Boolean) {
        c.save()
        if (rounded) {
            clip.reset()
            clip.addRoundRect(rect, 14f * h / 480f, 14f * h / 480f, android.graphics.Path.Direction.CW)
            c.clipPath(clip)
        }
        c.translate(rect.left, rect.top)
        val width = rect.width()
        val m = r.maneuver
        if (r.rerouting || m == null) drawReroute(c, width, bannerH) else drawManeuver(c, m, width)
        // linha de baixo: distância e tempo até o destino, com a hora de chegada
        c.drawRect(0f, bannerH, width, rect.height(), if (r.rerouting) rerouteRowBg else rowBg)
        val fm = rowText.fontMetrics
        val y = bannerH + (rect.height() - bannerH) / 2 - (fm.ascent + fm.descent) / 2
        val sum = if (rowText.measureText("🏁  ${r.summaryWithEta}") <= width - pad * 2) r.summaryWithEta else r.summaryShortEta
        c.drawText(ellipsize("🏁  $sum", rowText, width - pad * 2), pad, y, rowText)
        c.restore()
    }

    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.042f; typeface = Typeface.DEFAULT_BOLD
    }
    private val grayPill = Color.argb(220, 90, 90, 90)
    private val fuelPill = Color.argb(235, 230, 120, 0)
    private val rainPill = Color.argb(235, 21, 101, 192)

    /**
     * Selos empilhados no canto inferior esquerdo, acima do resumo: sem internet, pouca gasolina,
     * chuva no caminho.
     */
    private fun drawPills(c: Canvas, hasSummary: Boolean) {
        val pills = buildList {
            if (!br.com.motonav.Net.online) add("SEM INTERNET" to grayPill)
            NavState.fuel?.let { add(it to fuelPill) }
            NavState.weather?.let { add(it to rainPill) }
        }
        if (pills.isEmpty()) return
        val fm = pillText.fontMetrics
        val bh = fm.descent - fm.ascent + pad
        val sfm = summaryText.fontMetrics
        var bottom = h - pad - (if (hasSummary) sfm.descent - sfm.ascent + pad + pad else 0f)
        val maxW = w * 0.62f
        for ((label, color) in pills) {
            val txt = ellipsize(label, pillText, maxW - 2 * pad)
            val box = RectF(pad, bottom - bh, pad + pillText.measureText(txt) + 2 * pad, bottom)
            pillBg.color = color
            c.drawRoundRect(box, pad, pad, pillBg)
            c.drawText(txt, box.left + pad, box.top + pad / 2 - fm.ascent, pillText)
            bottom = box.top - pad / 2
        }
    }

    /** Faixa vermelha de incidente (logo abaixo do aviso de manobra, se houver). */
    private fun drawIncident(c: Canvas, inc: NavState.IncidentAlert, top: Float) {
        val fm = incidentText.fontMetrics
        val hh = fm.descent - fm.ascent + pad
        c.drawRect(0f, top, w.toFloat(), top + hh, incidentBg)
        val d = inc.distanceNow()
        val dist = if (d >= 1000) String.format(java.util.Locale.US, "%.1f km", d / 1000).replace('.', ',')
        else "${(d / 50).toInt() * 50} m"
        val text = ellipsize("⚠ ${inc.label} · $dist", incidentText, w - 3 * pad)
        c.drawText(text, pad * 1.5f, top + pad / 2 - fm.ascent, incidentText)
    }

    private fun drawSpeedAndRadar(c: Canvas) {
        val loc = NavState.lastLocation
        val kmh = if (loc != null && loc.hasSpeed()) (loc.speed * 3.6f).toInt() else null
        val radar = NavState.radar
        val roadLimit = NavState.speedLimit
        val limit = radar?.maxspeed ?: roadLimit
        val over = kmh != null && limit != null && kmh > limit + 2

        // velocímetro
        val size = h * 0.19f
        val right = w - pad
        val bottom = h - pad
        if (kmh != null) {
            val box = RectF(right - size, bottom - size, right, bottom)
            c.drawRoundRect(box, pad, pad, if (over) overSpeed else speedBg)
            c.drawText("$kmh", box.centerX(), box.centerY() + speedNum.textSize * 0.3f, speedNum)
            c.drawText("km/h", box.centerX(), box.bottom - pad * 0.6f, speedUnit)
        }

        // placa do radar à esquerda do velocímetro
        if (radar != null) {
            val cx = right - size - pad - size / 2
            val cy = bottom - size / 2
            val rad = size / 2 - signRing.strokeWidth / 2
            c.drawCircle(cx, cy, rad, signFill)
            c.drawCircle(cx, cy, rad, signRing)
            val txt = limit?.toString() ?: "📷"
            c.drawText(txt, cx, cy + signNum.textSize * 0.35f, signNum)
            val d = radar.distanceNow()
            val label = if (d < 30) "RADAR" else "RADAR ${(d / 10).toInt() * 10} m"
            val lw = radarLabel.measureText(label) + pad * 2
            val lb = RectF(cx - lw / 2, cy - size / 2 - pad * 0.5f - radarLabel.textSize * 1.4f, cx + lw / 2, cy - size / 2 - pad * 0.5f)
            c.drawRoundRect(lb, pad / 2, pad / 2, radarLabelBg)
            c.drawText(label, cx, lb.bottom - radarLabel.textSize * 0.35f, radarLabel)
        } else if (roadLimit != null) {
            // sem radar: só a placa com o limite da rua (OpenStreetMap)
            val cx = right - size - pad - size / 2
            val cy = bottom - size / 2
            val rad = size / 2 - signRing.strokeWidth / 2
            c.drawCircle(cx, cy, rad, signFill)
            c.drawCircle(cx, cy, rad, signRing)
            c.drawText("$roadLimit", cx, cy + signNum.textSize * 0.35f, signNum)
        }
    }

    private fun drawManeuver(c: Canvas, m: NavState.Maneuver, width: Float) {
        c.drawRect(0f, 0f, width, bannerH, bannerBg)
        // quadrado com a seta
        val box = RectF(pad, pad, pad + bannerH - 2 * pad, bannerH - pad)
        c.drawRoundRect(box, pad, pad, arrowBox)
        val afm = arrowText.fontMetrics
        c.drawText(m.turn.arrow, box.centerX(), box.centerY() - (afm.ascent + afm.descent) / 2, arrowText)
        // distância e rua
        val x = box.right + pad * 1.5f
        val maxW = width - x - pad
        c.drawText(m.distanceText, x, pad - distText.fontMetrics.ascent, distText)
        val streetY = bannerH - pad - streetText.fontMetrics.descent
        // cartão estreito (dividido com a música): a seta já diz o lado, mostra só a rua
        val street = if (width < w * 0.75f) streetOnly(m.text) else m.text
        c.drawText(ellipsize(street, streetText, maxW), x, streetY, streetText)
    }

    /** "Vire à esquerda para a Rua Tuibas" -> "Rua Tuibas". */
    private fun streetOnly(s: String): String {
        val r = Regex("\\b(para|na|no|em|pela|pelo|sentido)\\s+(a\\s+|o\\s+|as\\s+|os\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        return r.find(s)?.groupValues?.get(3)?.trim()?.ifEmpty { null } ?: s
    }

    private fun drawReroute(c: Canvas, width: Float, hh: Float = bannerH * 0.6f) {
        c.drawRect(0f, 0f, width, hh, rerouteBg)
        val fm = distText.fontMetrics
        c.drawText(ellipsize("Recalculando rota…", distText, width - pad * 2.5f), pad * 1.5f, hh / 2 - (fm.ascent + fm.descent) / 2, distText)
    }

    private fun drawSummary(c: Canvas, text: String) {
        val fm = summaryText.fontMetrics
        val bw = summaryText.measureText(text) + 2 * pad
        val bh = fm.descent - fm.ascent + pad
        val top = h - bh - pad
        c.drawRect(pad, top, pad + bw, top + bh, summaryBg)
        c.drawText(text, 2 * pad, top + pad / 2 - fm.ascent, summaryText)
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }
}
