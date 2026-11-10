package br.com.motonav

import android.location.Location
import android.os.SystemClock

/**
 * Números da viagem, calculados pelo GPS (a moto não manda telemetria pelo painel):
 * distância, tempo em movimento, velocidade média e máxima, aceleração.
 * Zera ao abrir o app ou pelo botão "Zerar viagem".
 */
object TripStats {
    @Volatile var distanceM = 0.0
        private set
    @Volatile var movingMs = 0L
        private set
    @Volatile var maxKmh = 0f
        private set
    /** Aceleração longitudinal suavizada (m/s²): positiva acelerando, negativa freando. */
    @Volatile var accel = 0f
        private set
    @Volatile var startedAt = SystemClock.elapsedRealtime()
        private set

    private var last: Location? = null

    val avgKmh: Float
        get() = if (movingMs > 5_000) (distanceM / (movingMs / 1000.0) * 3.6).toFloat() else 0f

    @Synchronized
    fun reset() {
        distanceM = 0.0
        movingMs = 0L
        maxKmh = 0f
        accel = 0f
        last = null
        startedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 30f) return
        val prev = last
        last = loc
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val kmh = speed * 3.6f
        if (kmh > maxKmh && kmh < 250f) maxKmh = kmh
        if (prev == null) return
        val dtMs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000
        if (dtMs <= 0 || dtMs > 10_000) return
        val d = prev.distanceTo(loc)
        // só conta andando de verdade, e ignora saltos do GPS
        if (speed > 1.5f && d < 80f * (dtMs / 1000f).coerceAtLeast(1f)) {
            distanceM += d
            movingMs += dtMs
        }
        val prevSpeed = if (prev.hasSpeed()) prev.speed else 0f
        val a = ((speed - prevSpeed) / (dtMs / 1000f)).coerceIn(-12f, 12f)
        accel = accel * 0.6f + a * 0.4f
    }
}
