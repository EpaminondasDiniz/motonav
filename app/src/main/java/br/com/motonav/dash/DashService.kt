package br.com.motonav.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import br.com.motonav.LocationHub
import br.com.motonav.MainActivity

/**
 * Serviço em primeiro plano enquanto o painel estiver conectado: mantém o app vivo com a tela
 * do celular apagada (GPS, Wi-Fi Direct e codificação de vídeo continuam funcionando).
 */
class DashService : Service() {

    companion object {
        private const val CHANNEL = "motonav_dash"

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DashService::class.java)) }
                .onFailure { DashLog.d("serviço em primeiro plano não iniciou: ${it.message}") }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DashService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var holdsGps = false
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Painel da moto", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("MotoNav")
            .setContentText("Conectado ao painel da moto")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (fine) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(1, n, types)
        } catch (e: Exception) {
            // Sem o tipo "localização" o Android corta o GPS com a tela apagada. Mesmo assim,
            // mantém a conexão com o painel viva em vez de derrubar o serviço.
            DashLog.d("serviço com GPS recusado pelo Android: ${e.message}")
            try {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e2: Exception) {
                DashLog.d("startForeground falhou: ${e2.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        logDiagnostics()

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoNav:dash").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MotoNav:dash").apply { acquire() }
        }
        if (!holdsGps) holdsGps = LocationHub.acquire(this) // GPS continua com a tela apagada
        return START_NOT_STICKY
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Tudo que pode cortar o GPS com a tela apagada, numa linha só do log. */
    private fun logDiagnostics() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val fgsLocation = foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
        val locSaver = when (pm.locationPowerSaveMode) {
            PowerManager.LOCATION_MODE_NO_CHANGE -> "não mexe no GPS"
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF -> "DESLIGA o GPS com a tela apagada"
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF -> "DESLIGA a localização com a tela apagada"
            PowerManager.LOCATION_MODE_FOREGROUND_ONLY -> "só apps em primeiro plano"
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF -> "LIMITA o GPS com a tela apagada"
            else -> "modo ${pm.locationPowerSaveMode}"
        }
        DashLog.d(
            "diagnóstico GPS: serviço com localização=${if (fgsLocation) "SIM" else "NÃO"}; " +
                "permissão precisa=${granted(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
                "o tempo todo=${granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}; " +
                "economia de energia=${if (pm.isPowerSaveMode) "LIGADA ($locSaver)" else "desligada"}; " +
                "otimização de bateria=${if (pm.isIgnoringBatteryOptimizations(packageName)) "sem restrição" else "ATIVA"}"
        )
    }

    override fun onDestroy() {
        if (holdsGps) { LocationHub.release(); holdsGps = false }
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }
}
