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
import android.os.SystemClock
import br.com.motonav.NavState
import br.com.motonav.TripStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Painel "Race": tela alternativa ao mapa, desenhada direto no quadro do vídeo (800x480).
 *  - pista em perspectiva com faixas que correm na velocidade da moto (sensação de movimento);
 *  - barra de LEDs no topo que acende com a velocidade (estilo luz de troca de marcha);
 *  - velocímetro em arco com ponteiro suave, número grande e barra de aceleração/frenagem;
 *  - viagem (distância, tempo, média, máxima) à esquerda; hora, rumo, altitude e GPS à direita;
 *  - rodapé com a próxima manobra, o radar à frente ou o resumo da rota.
 * Todos os números vêm do GPS: o painel da moto não manda giro, marcha nem temperatura.
 * Usado só na thread de composição.
 */
class RaceDash(
    private val w: Int,
    private val h: Int,
    private val demo: Boolean = false,
    /** Estilo fixo (prévias); null = o escolhido no app ([DashScreen.raceStyle]). */
    private val fixedStyle: RaceStyle? = null
) {
    private val skins = HashMap<RaceStyle, RaceSkin>()
    private val data = RaceData()
    private var lastHeadingAt = 0L
    private var historyAt = 0L

    /** Modo demonstração (prévia no celular): moto "andando" de mentira para ver a animação parado. */
    private var demoT = 0f
    private fun demoKmh(t: Float) = (62 + 58 * sin(t * 0.5)).toFloat().coerceAtLeast(0f)

    private val cyan = Color.rgb(0, 229, 255)
    private val green = Color.rgb(0, 230, 118)
    private val yellow = Color.rgb(255, 214, 0)
    private val red = Color.rgb(255, 23, 68)
    private val dim = Color.rgb(127, 138, 163)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensedLight = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    private val gaugeMax = 160f
    private val cx = w / 2f
    private val cy = h * 0.60f
    private val radius = h * 0.36f
    private val arcW = h * 0.045f
    private val arcBox = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

    private val bg = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(12, 16, 28), Color.rgb(6, 8, 14), Color.rgb(2, 3, 6)), null, Shader.TileMode.CLAMP)
    }
    private val glow = Paint().apply {
        shader = RadialGradient(cx, cy, radius * 1.5f,
            Color.argb(70, 0, 229, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = arcW; strokeCap = Paint.Cap.BUTT; color = Color.rgb(28, 34, 50)
    }
    private val arcVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = arcW; strokeCap = Paint.Cap.BUTT
        // verde -> amarelo -> vermelho ao longo dos 270° do arco (começa em 135°)
        shader = SweepGradient(cx, cy,
            // posições em fração da volta inteira; o arco ocupa 0..0,75 (270°): 0,35 ≈ 75 km/h,
            // 0,50 ≈ 107 km/h, 0,70 ≈ 149 km/h
            intArrayOf(green, green, yellow, red, red, green),
            floatArrayOf(0f, 0.35f, 0.50f, 0.70f, 0.99f, 1f)).also {
            val m = android.graphics.Matrix(); m.setRotate(135f, cx, cy); it.setLocalMatrix(m)
        }
    }
    private val arcGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = arcW * 1.8f; color = Color.argb(90, 0, 229, 255)
        maskFilter = BlurMaskFilter(arcW, BlurMaskFilter.Blur.NORMAL)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 100, 125); strokeWidth = 2f }
    private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 208, 225); strokeWidth = 4f }
    private val tickLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim; textSize = h * 0.042f; textAlign = Paint.Align.CENTER; typeface = condensedLight
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = h * 0.012f; strokeCap = Paint.Cap.ROUND
    }
    private val needleGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 229, 255); strokeWidth = h * 0.03f; strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(h * 0.02f, BlurMaskFilter.Blur.NORMAL)
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 26, 40) }
    private val speedText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.23f; textAlign = Paint.Align.CENTER; typeface = condensed
    }
    private val unitText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; textSize = h * 0.045f; textAlign = Paint.Align.CENTER; typeface = condensed; letterSpacing = 0.2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim; textSize = h * 0.036f; typeface = condensed; letterSpacing = 0.15f
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.08f; typeface = condensed
    }
    private val valueUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim; textSize = h * 0.038f; typeface = condensedLight
    }
    private val sideLine = Paint().apply { color = Color.argb(120, 0, 229, 255); strokeWidth = 3f }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ledGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(h * 0.02f, BlurMaskFilter.Blur.NORMAL) }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 34, 50) }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val footerBg = Paint().apply {
        shader = LinearGradient(0f, h * 0.88f, 0f, h.toFloat(),
            Color.argb(0, 0, 0, 0), Color.argb(230, 0, 0, 0), Shader.TileMode.CLAMP)
    }
    private val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = h * 0.058f; textAlign = Paint.Align.CENTER; typeface = condensed
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    // animação
    private var shownKmh = 0f
    private var phase = 0f
    private var lastFrameAt = 0L
    private var lastHeading = 0f

    /** Avança a demonstração [seconds] segundos de uma vez (para gerar uma imagem num ponto da animação). */
    fun demoAdvance(seconds: Float) {
        demoT += seconds
        val k = demoKmh(demoT)
        shownKmh = k
        phase = (demoT * 0.6f) % 1f
        lastHeading = (demoT * 20f) % 360f
    }

    fun draw(c: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameAt == 0L) 0f else ((now - lastFrameAt) / 1000f).coerceIn(0f, 0.2f)
        lastFrameAt = now

        val loc = NavState.lastLocation
        val fresh = demo || loc != null && (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) < 3_000_000_000L
        if (demo) demoT += dt
        val speedMs = when {
            demo -> demoKmh(demoT) / 3.6f
            fresh && loc!!.hasSpeed() -> loc.speed
            else -> 0f
        }
        if (demo) lastHeading = (demoT * 20f) % 360f
        val kmh = speedMs * 3.6f
        // ponteiro suave entre as leituras do GPS (1/s)
        shownKmh += (kmh - shownKmh) * min(1f, dt * 5f)
        phase = (phase + speedMs * dt * 0.045f) % 1f
        if (loc != null && loc.hasBearing() && speedMs > 1.5f) lastHeading = loc.bearing

        val radar = if (demo) NavState.RadarAlert(280.0, 60, SystemClock.elapsedRealtime(), 0f) else NavState.radar
        val limit = radar?.maxspeed ?: (if (demo) null else NavState.speedLimit)
        val over = limit != null && kmh > limit + 2
        val blink = (now / 250) % 2 == 0L

        val style = fixedStyle ?: DashScreen.raceStyle
        if (style != RaceStyle.NEON) {
            fillData(now, dt, kmh, loc, fresh, radar, limit, over, blink)
            skins.getOrPut(style) { RaceSkin.create(style, w, h) }.draw(c, data)
            return
        }

        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        drawRoad(c)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
        drawLeds(c, shownKmh, blink)
        drawGauge(c, shownKmh, over && blink)
        drawLeft(c)
        drawRight(c, loc, fresh)
        drawFooter(c, radar, over)
    }

    private var turnRate = 0f       // graus/s, suavizado (para a força G lateral)
    private var rateHeading = Float.NaN

    /** Preenche os números para os outros estilos (os mesmos dados do Neon). */
    private fun fillData(
        now: Long, dt: Float, kmh: Float, loc: android.location.Location?, fresh: Boolean,
        radar: NavState.RadarAlert?, limit: Int?, over: Boolean, blink: Boolean
    ) {
        val d = data
        d.demo = demo
        d.dt = dt
        d.kmh = kmh
        d.shownKmh = shownKmh
        d.phase = phase
        d.heading = lastHeading
        d.limit = limit
        d.over = over
        d.blink = blink
        d.tripKm = tripKm()
        d.tripMs = tripMs()
        d.avgKmh = tripAvg()
        d.maxKmh = tripMax()
        d.accel = tripAccel()
        d.fresh = fresh
        d.altitude = if (demo) 24 else if (loc != null && loc.hasAltitude()) loc.altitude.roundToInt() else null
        d.gpsAcc = if (demo) 3 else if (fresh && loc!!.hasAccuracy()) loc.accuracy.roundToInt() else null
        d.clock = clock.format(Date())
        d.temperature = if (demo) 28 else NavState.temperature
        d.radarM = radar?.distanceNow()
        // curva: variação do rumo x velocidade = aceleração lateral
        if (demo) {
            d.latG = (0.35 * sin(demoT * 0.9)).toFloat()
        } else {
            if (lastHeading != rateHeading && now - lastHeadingAt > 300) {
                if (!rateHeading.isNaN() && lastHeadingAt != 0L) {
                    var diff = lastHeading - rateHeading
                    if (diff > 180) diff -= 360
                    if (diff < -180) diff += 360
                    val r = diff / ((now - lastHeadingAt) / 1000f)
                    turnRate = turnRate * 0.5f + r * 0.5f
                }
                rateHeading = lastHeading
                lastHeadingAt = now
            }
            if (now - lastHeadingAt > 2500) turnRate *= 0.9f
            d.latG = (Math.toRadians(turnRate.toDouble()) * (kmh / 3.6) / 9.81).toFloat().coerceIn(-1.2f, 1.2f)
        }
        // histórico de velocidade (2 amostras/s, últimos 60 s)
        val tHist = if (demo) (demoT * 1000).toLong() else now // na prévia, o tempo da demonstração
        if (tHist - historyAt >= 500 || tHist < historyAt) {
            historyAt = tHist
            d.history.addLast(shownKmh)
            while (d.history.size > 120) d.history.removeFirst()
        }
        val (text, kind) = footerInfo(radar, over)
        d.footer = text
        d.footerKind = kind
    }

    // números da viagem (na demonstração, fictícios)
    private fun tripKm() = if (demo) 12.4 + demoT * 0.015 else TripStats.distanceM / 1000
    private fun tripMs() = if (demo) 1_421_000L + (demoT * 1000).toLong() else TripStats.movingMs
    private fun tripAvg() = if (demo) 38f else TripStats.avgKmh
    private fun tripMax() = if (demo) 97f else TripStats.maxKmh
    private fun tripAccel() = if (demo) (58 * 0.5 * cos(demoT * 0.5) / 3.6).toFloat() else TripStats.accel

    /** Pista em perspectiva: bordas fixas e faixas que vêm em direção à tela conforme a velocidade. */
    private fun drawRoad(c: Canvas) {
        val vx = cx
        val vy = h * 0.30f
        val leftX = -w * 0.15f
        val rightX = w * 1.15f
        road.color = Color.argb(40, 0, 229, 255)
        road.strokeWidth = 2f
        c.drawLine(vx, vy, leftX, h.toFloat(), road)
        c.drawLine(vx, vy, rightX, h.toFloat(), road)
        val n = 7
        for (k in 0 until n) {
            val t0 = (k.toFloat() / n + phase) % 1f
            val t1 = (t0 + 0.06f).coerceAtMost(1f)
            val e0 = t0 * t0
            val e1 = t1 * t1
            val a = (40 + 140 * t0).roundToInt().coerceAtMost(180)
            road.strokeWidth = 2f + 10f * t0
            // faixas laterais (zebras)
            road.color = Color.argb(a, 0, 229, 255)
            c.drawLine(lerp(vx, leftX * 0.85f + vx * 0.15f, e0), lerp(vy, h.toFloat(), e0),
                lerp(vx, leftX * 0.85f + vx * 0.15f, e1), lerp(vy, h.toFloat(), e1), road)
            c.drawLine(lerp(vx, rightX * 0.85f + vx * 0.15f, e0), lerp(vy, h.toFloat(), e0),
                lerp(vx, rightX * 0.85f + vx * 0.15f, e1), lerp(vy, h.toFloat(), e1), road)
            // faixa central
            road.color = Color.argb(a / 2, 255, 255, 255)
            c.drawLine(vx, lerp(vy, h.toFloat(), e0), vx, lerp(vy, h.toFloat(), e1), road)
        }
    }

    /** 20 LEDs no topo: verdes, amarelos e vermelhos; piscam todos perto do fim da escala. */
    private fun drawLeds(c: Canvas, kmh: Float, blink: Boolean) {
        val count = 20
        val x0 = w * 0.2f
        val x1 = w * 0.8f
        val gap = (x1 - x0) / count
        val lw = gap * 0.72f
        val top = h * 0.035f
        val bottom = h * 0.085f
        val lit = (count * kmh / 140f).roundToInt().coerceIn(0, count)
        val maxed = kmh >= 140f
        for (i in 0 until count) {
            val col = when { i < 12 -> green; i < 17 -> yellow; else -> red }
            val on = i < lit && (!maxed || blink)
            val l = x0 + i * gap + (gap - lw) / 2
            val r = RectF(l, top, l + lw, bottom)
            if (on) {
                ledGlow.color = col
                c.drawRoundRect(r, 4f, 4f, ledGlow)
                led.color = col
            } else {
                led.color = Color.argb(255, 26, 30, 42)
            }
            c.drawRoundRect(r, 4f, 4f, led)
        }
    }

    private fun drawGauge(c: Canvas, kmh: Float, alert: Boolean) {
        c.drawArc(arcBox, 135f, 270f, false, arcBg)
        val sweep = 270f * (kmh / gaugeMax).coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            c.drawArc(arcBox, 135f, sweep, false, arcGlow)
            c.drawArc(arcBox, 135f, sweep, false, arcVal)
        }
        // marcas a cada 10 km/h, números a cada 20
        val inner = radius - arcW * 0.9f
        var v = 0
        while (v <= gaugeMax.toInt()) {
            val ang = Math.toRadians((135.0 + 270.0 * v / gaugeMax))
            val major = v % 20 == 0
            val len = if (major) h * 0.035f else h * 0.018f
            val p = if (major) tickMajor else tick
            c.drawLine(cx + (inner * cos(ang)).toFloat(), cy + (inner * sin(ang)).toFloat(),
                cx + ((inner - len) * cos(ang)).toFloat(), cy + ((inner - len) * sin(ang)).toFloat(), p)
            if (major) {
                val lr = inner - len - h * 0.04f
                c.drawText("$v", cx + (lr * cos(ang)).toFloat(),
                    cy + (lr * sin(ang)).toFloat() + tickLabel.textSize * 0.35f, tickLabel)
            }
            v += 10
        }
        // ponteiro
        val ang = Math.toRadians(135.0 + 270.0 * (kmh / gaugeMax).coerceIn(0f, 1f))
        val tipR = radius - arcW * 0.2f
        val baseR = radius * 0.55f
        val sx = cx + (baseR * cos(ang)).toFloat()
        val sy = cy + (baseR * sin(ang)).toFloat()
        val ex = cx + (tipR * cos(ang)).toFloat()
        val ey = cy + (tipR * sin(ang)).toFloat()
        c.drawLine(sx, sy, ex, ey, needleGlow)
        c.drawLine(sx, sy, ex, ey, needle)
        c.drawCircle(cx, cy, radius * 0.5f, hub)

        // número
        speedText.color = if (alert) red else Color.WHITE
        c.drawText("${kmh.roundToInt()}", cx, cy + speedText.textSize * 0.32f, speedText)
        c.drawText("KM/H", cx, cy + h * 0.105f, unitText)

        // aceleração (direita, verde) / frenagem (esquerda, vermelho)
        val bw = radius * 0.8f
        val by = cy + h * 0.15f
        val bh = h * 0.016f
        c.drawRoundRect(RectF(cx - bw / 2, by, cx + bw / 2, by + bh), bh, bh, barBg)
        val a = (tripAccel() / 4f).coerceIn(-1f, 1f)
        if (kotlin.math.abs(a) > 0.03f) {
            barFill.color = if (a > 0) green else red
            val x = cx + a * bw / 2
            c.drawRoundRect(RectF(min(cx, x), by, maxOf(cx, x), by + bh), bh, bh, barFill)
        }
    }

    private fun drawLeft(c: Canvas) {
        val x = w * 0.035f
        var y = h * 0.22f
        val step = h * 0.165f
        c.drawLine(x - w * 0.012f, y - label.textSize, x - w * 0.012f, y + step * 3.6f, sideLine)
        row(c, x, y, "VIAGEM", String.format(Locale("pt", "BR"), "%.1f", tripKm()), " km", false); y += step
        row(c, x, y, "TEMPO", duration(tripMs()), "", false); y += step
        row(c, x, y, "MÉDIA", "${tripAvg().roundToInt()}", " km/h", false); y += step
        row(c, x, y, "MÁXIMA", "${tripMax().roundToInt()}", " km/h", false)
    }

    private fun drawRight(c: Canvas, loc: android.location.Location?, fresh: Boolean) {
        val x = w * 0.965f
        var y = h * 0.22f
        val step = h * 0.165f
        c.drawLine(x + w * 0.012f, y - label.textSize, x + w * 0.012f, y + step * 3.6f, sideLine)
        row(c, x, y, "HORA", clock.format(Date()), "", true); y += step
        row(c, x, y, "RUMO", "${cardinal(lastHeading)} ${lastHeading.roundToInt()}°", "", true); y += step
        val alt = if (demo) "24" else if (loc != null && loc.hasAltitude()) "${loc.altitude.roundToInt()}" else "--"
        row(c, x, y, "ALTITUDE", alt, " m", true); y += step
        val acc = if (demo) "±3" else if (fresh && loc!!.hasAccuracy()) "±${loc.accuracy.roundToInt()}" else "SEM"
        row(c, x, y, "GPS", acc, if (fresh) " m" else "", true)
        dot.color = if (fresh) green else red
        c.drawCircle(x - value.measureText(acc + if (fresh) " m" else "") - h * 0.035f, y + value.textSize * 0.62f, h * 0.012f, dot)
    }

    /** Rótulo pequeno em cima, valor grande embaixo. */
    private fun row(c: Canvas, x: Float, y: Float, lab: String, v: String, unit: String, right: Boolean) {
        val vy = y + value.textSize * 0.95f
        if (right) {
            label.textAlign = Paint.Align.RIGHT
            c.drawText(lab, x, y, label)
            val uw = valueUnit.measureText(unit)
            valueUnit.textAlign = Paint.Align.RIGHT
            value.textAlign = Paint.Align.RIGHT
            c.drawText(unit, x, vy, valueUnit)
            c.drawText(v, x - uw, vy, value)
        } else {
            label.textAlign = Paint.Align.LEFT
            value.textAlign = Paint.Align.LEFT
            valueUnit.textAlign = Paint.Align.LEFT
            c.drawText(lab, x, y, label)
            c.drawText(v, x, vy, value)
            c.drawText(unit, x + value.measureText(v), vy, valueUnit)
        }
    }

    /** Rodapé: manobra da rota > radar à frente > resumo da rota > nome do app. */
    private fun drawFooter(c: Canvas, radar: NavState.RadarAlert?, over: Boolean) {
        c.drawRect(0f, h * 0.88f, w.toFloat(), h.toFloat(), footerBg)
        val y = h * 0.965f
        val (text, kind) = footerInfo(radar, over)
        footer.color = when (kind) {
            RaceData.Kind.MANEUVER -> cyan
            RaceData.Kind.RADAR -> yellow
            RaceData.Kind.DANGER -> red
            RaceData.Kind.FUEL -> Color.rgb(255, 150, 40)
            RaceData.Kind.RAIN -> Color.rgb(100, 180, 255)
            RaceData.Kind.MUSIC -> Color.rgb(30, 215, 96)
            RaceData.Kind.INFO -> Color.WHITE
            RaceData.Kind.IDLE -> dim
        }
        c.drawText(ellipsize(text, footer, w * 0.94f), cx, y, footer)
    }

    /**
     * Rodapé (igual em todos os estilos): manobra > radar > gasolina > chuva > resumo da rota >
     * limite da via > música tocando > nome do app.
     */
    private fun footerInfo(radar: NavState.RadarAlert?, over: Boolean): Pair<String, RaceData.Kind> {
        val route = NavState.route
        val m = route?.maneuver
        return when {
            m != null -> "${m.turn.arrow}  ${m.distanceText}  ·  ${m.text}" to RaceData.Kind.MANEUVER
            radar != null -> {
                val d = radar.distanceNow()
                ("●  RADAR ${(d / 10).toInt() * 10} m" + (radar.maxspeed?.let { "  ·  $it km/h" } ?: "")) to
                    (if (over) RaceData.Kind.DANGER else RaceData.Kind.RADAR)
            }
            !demo && NavState.fuel != null -> NavState.fuel!! to RaceData.Kind.FUEL
            !demo && NavState.weather != null -> NavState.weather!! to RaceData.Kind.RAIN
            else -> {
                // resumo da rota, limite da via e música se revezam a cada 5 s
                val opts = listOfNotNull(
                    route?.let { it.summary to RaceData.Kind.INFO },
                    if (!demo) NavState.speedLimit?.let {
                        "LIMITE DA VIA  ·  $it km/h" to (if (over) RaceData.Kind.DANGER else RaceData.Kind.INFO)
                    } else null,
                    (if (demo) "♪  Música tocando  ·  Artista" else br.com.motonav.MediaInfo.text())?.let { it to RaceData.Kind.MUSIC }
                )
                if (opts.isEmpty()) "M O T O N A V   ·   R A C E" to RaceData.Kind.IDLE
                else opts[((SystemClock.elapsedRealtime() / 5000) % opts.size).toInt()]
            }
        }
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun cardinal(deg: Float): String {
        val names = arrayOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
        return names[(((deg % 360) + 360 + 22.5f) % 360 / 45).toInt() % 8]
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }
}
