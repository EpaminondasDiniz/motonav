package br.com.motonav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import br.com.motonav.dash.DashLog

/**
 * Fonte única de GPS (1 leitura/s). Fica ligada enquanto a tela do app estiver aberta OU o
 * serviço do painel estiver rodando (é o serviço que mantém o GPS com a tela apagada).
 *
 * Usa o GPS puro e, como reserva, o provedor "fused" do Android: se o GPS ficar mais de 3 s
 * sem leitura (túnel, economia de energia, tela apagada em alguns aparelhos), as leituras do
 * fused passam a alimentar a navegação.
 */
object LocationHub {
    private const val GPS_STALE_MS = 3_000L
    private const val FUSED_MAX_ACCURACY_M = 50f

    private var users = 0
    private var lm: LocationManager? = null
    private val providers = mutableListOf<String>()

    private var lastGpsAt = 0L
    private var gpsFixes = 0
    private var fusedFixes = 0
    private var accSum = 0f
    private var accMax = 0f
    private var lastReport = 0L

    private fun publish(loc: Location) {
        NavState.publishLocation(loc)
        NavEngine.onLocation(loc)
        Radars.onLocation(loc)
        TomTom.onLocation(loc)
        TripStats.onLocation(loc)
        TripLog.onLocation(loc)
        Garage.onLocation(loc)
        Weather.onLocation(loc)
        SpeedLimits.onLocation(loc)
        CrashDetector.onLocation(loc)
    }

    /** Dados guardados no celular que o GPS alimenta (pode ser chamado mais de uma vez). */
    fun initData(ctx: Context) {
        TomTom.loadKey(ctx)
        Compat.load(ctx)
        Garage.init(ctx)
        TripLog.init(ctx)
        SpeedLimits.init(ctx)
        NightMode.init(ctx)
        MediaInfo.start(ctx)
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastGpsAt = SystemClock.elapsedRealtime()
            gpsFixes++
            if (loc.hasAccuracy()) { accSum += loc.accuracy; if (loc.accuracy > accMax) accMax = loc.accuracy }
            publish(loc)
            report()
        }
        override fun onProviderDisabled(provider: String) = DashLog.d("GPS DESLIGADO pelo sistema ($provider)")
        override fun onProviderEnabled(provider: String) = DashLog.d("GPS religado ($provider)")
    }

    private val fusedListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            fusedFixes++
            // reserva só quando o GPS sumiu, e só se a leitura prestar (rede/Wi-Fi erra centenas de metros)
            val good = !loc.hasAccuracy() || loc.accuracy <= FUSED_MAX_ACCURACY_M
            if (good && SystemClock.elapsedRealtime() - lastGpsAt > GPS_STALE_MS) publish(loc)
            report()
        }
        override fun onProviderDisabled(provider: String) = DashLog.d("localização fused desligada ($provider)")
        override fun onProviderEnabled(provider: String) {}
    }

    /** Diagnóstico: confirma no log que a posição continua chegando com a tela apagada. */
    private fun report() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < 30_000) return
        if (lastReport != 0L) {
            val avg = if (gpsFixes > 0) (accSum / gpsFixes).toInt() else 0
            DashLog.d("GPS: $gpsFixes leituras do GPS (precisão média $avg m, pior ${accMax.toInt()} m) " +
                "e $fusedFixes do fused nos últimos 30 s; ${NavEngine.ignored} ignoradas pela navegação")
        }
        NavEngine.ignored = 0
        accSum = 0f
        accMax = 0f
        gpsFixes = 0
        fusedFixes = 0
        lastReport = now
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun acquire(ctx: Context): Boolean {
        users++
        Radars.init(ctx)
        Net.start(ctx)
        initData(ctx)
        if (lm != null) return true
        val m = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            providers.add(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            users-- // sem permissão ainda
            return false
        }
        if (Build.VERSION.SDK_INT >= 31 && m.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            runCatching {
                m.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000L, 0f, fusedListener, Looper.getMainLooper())
                providers.add(LocationManager.FUSED_PROVIDER)
            }.onFailure { DashLog.d("fused indisponível: ${it.message}") }
        }
        lm = m
        CrashDetector.start(ctx) // acelerômetro junto com o GPS (só se a detecção estiver ligada)
        DashLog.d("localização ligada: ${providers.joinToString()}")
        return true
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) {
            lm?.removeUpdates(gpsListener)
            lm?.removeUpdates(fusedListener)
            lm = null
            providers.clear()
            CrashDetector.stop()
            DashLog.d("localização desligada")
        }
    }
}
