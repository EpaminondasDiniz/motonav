package br.com.motonav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashNotice
import kotlin.math.sqrt

/**
 * Detecção de queda (só funciona com o GPS ligado, ou seja, app aberto ou painel conectado):
 *  1. impacto forte no acelerômetro (> [IMPACT_G] G) com a moto a 20 km/h ou mais nos 5 s antes;
 *  2. depois, a moto fica parada (< 5 km/h ou sem GPS) por 10 s seguidos, dentro de 25 s.
 * Aí começa [CrashAlert]: 30 s de contagem com alarme, tela vermelha e aviso no painel. Se
 * ninguém tocar em "Estou bem", manda SMS com a localização para o contato de emergência.
 * Uma lombada forte não dispara: a moto continua andando depois.
 */
object CrashDetector {
    private const val PREFS = "crash"
    private const val IMPACT_G = 4.5f
    private const val FAST_KMH = 20f
    private const val STOP_KMH = 5f
    private const val STOP_NEEDED_MS = 10_000L
    private const val WINDOW_MS = 25_000L

    @Volatile var enabled = false
        private set
    @Volatile var contactName = ""
        private set
    @Volatile var contactPhone = ""
        private set

    private var sm: SensorManager? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastFastAt = 0L
    @Volatile private var lastFix: Location? = null
    @Volatile private var lastFixAt = 0L
    @Volatile private var impactAt = 0L
    private var stoppedSince = 0L
    private var lastImpactLog = 0L

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        contactName = p.getString("name", "") ?: ""
        contactPhone = p.getString("phone", "") ?: ""
    }

    fun save(ctx: Context, on: Boolean, name: String, phone: String) {
        enabled = on; contactName = name.trim(); contactPhone = phone.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).putString("name", contactName).putString("phone", contactPhone).apply()
        if (on) start(ctx) else stop()
        DashLog.d("detecção de queda: ${if (on) "ligada" else "desligada"}")
    }

    fun ready(): Boolean = enabled && contactPhone.count { it.isDigit() } >= 8

    /** Liga o acelerômetro (chamado junto com o GPS). */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        load(ctx)
        if (!ready() || sm != null) return
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // em lote: o sensor guarda as leituras e entrega a cada ~1 s (o processador dorme no meio)
        m.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME, 1_000_000, main)
        sm = m
        DashLog.d("detecção de queda: acelerômetro ligado (contato: $contactName)")
    }

    fun stop() {
        sm?.unregisterListener(listener)
        sm = null
        main.removeCallbacks(check)
        impactAt = 0L
    }

    fun onLocation(loc: Location) {
        lastFix = loc
        lastFixAt = SystemClock.elapsedRealtime()
        if (loc.hasSpeed() && loc.speed * 3.6f >= FAST_KMH) lastFastAt = lastFixAt
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
            if (g < IMPACT_G) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastFastAt > 5_000L || impactAt != 0L || CrashAlert.active) return
            impactAt = now
            stoppedSince = 0L
            if (now - lastImpactLog > 5_000) {
                lastImpactLog = now
                DashLog.d("queda? impacto de ${"%.1f".format(g)} G; vendo se a moto parou...")
            }
            main.removeCallbacks(check)
            main.postDelayed(check, 1_000)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Depois do impacto, 1x/s: a moto parou? */
    private val check = object : Runnable {
        override fun run() {
            if (impactAt == 0L) return
            val now = SystemClock.elapsedRealtime()
            val fix = lastFix
            val gpsOld = now - lastFixAt > 3_000L
            val kmh = if (fix != null && fix.hasSpeed()) fix.speed * 3.6f else 0f
            when {
                !gpsOld && kmh > 15f -> { // seguiu andando: foi buraco/lombada
                    DashLog.d("queda? não: a moto seguiu a ${kmh.toInt()} km/h")
                    impactAt = 0L; return
                }
                gpsOld || kmh < STOP_KMH -> { if (stoppedSince == 0L) stoppedSince = now }
                else -> stoppedSince = 0L
            }
            if (stoppedSince != 0L && now - stoppedSince >= STOP_NEEDED_MS) {
                impactAt = 0L
                appCtx?.let { CrashAlert.begin(it, test = false) }
                return
            }
            if (now - impactAt > WINDOW_MS) { impactAt = 0L; return }
            main.postDelayed(this, 1_000)
        }
    }
}

/** Contagem regressiva com alarme; no fim, SMS com a localização. */
object CrashAlert {
    const val COUNTDOWN_S = 30
    private const val CHANNEL = "queda"
    private const val NOTIF_ID = 77

    @Volatile var active = false
        private set
    @Volatile var secondsLeft = 0
        private set
    @Volatile var test = false
        private set
    var onTick: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var appCtx: Context? = null

    fun begin(ctx: Context, test: Boolean) {
        if (active) return
        val c = ctx.applicationContext
        appCtx = c
        active = true
        this.test = test
        secondsLeft = if (test) 10 else COUNTDOWN_S
        DashLog.d(if (test) "queda: TESTE do alarme" else "QUEDA DETECTADA: SMS em $secondsLeft s se ninguém cancelar")
        notifyAndOpen(c)
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(c, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        vibrate(c)
        main.post(tick)
    }

    fun cancel(reason: String = "Estou bem") {
        if (!active) return
        active = false
        main.removeCallbacks(tick)
        stopAlarm()
        DashNotice.show("✔  Alarme de queda cancelado", 3_000, DashNotice.Kind.INFO)
        DashLog.d("queda: cancelado ($reason)")
        onTick?.invoke(-1)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val s = secondsLeft
            onTick?.invoke(s)
            DashNotice.show(if (test) "🆘 TESTE · SMS em $s s · cancele no celular" else "🆘 QUEDA? SMS em $s s · cancele no celular",
                1_500, DashNotice.Kind.ALERT)
            if (s <= 0) { finish(); return }
            secondsLeft = s - 1
            main.postDelayed(this, 1_000)
        }
    }

    private fun finish() {
        active = false
        stopAlarm()
        val c = appCtx ?: return
        if (test) {
            DashLog.d("queda: teste terminou (nenhum SMS enviado)")
            DashNotice.show("Teste concluído · nenhum SMS enviado", 4_000)
            onTick?.invoke(-1)
            return
        }
        val sent = sendSms(c)
        DashNotice.show(if (sent) "SMS de emergência enviado para ${CrashDetector.contactName}" else "Falha ao enviar o SMS de emergência",
            15_000, DashNotice.Kind.ALERT)
        onTick?.invoke(-2)
    }

    private fun sendSms(c: Context): Boolean {
        val phone = CrashDetector.contactPhone
        if (phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            DashLog.d("queda: sem permissão de SMS")
            return false
        }
        val loc = NavState.lastLocation
        val rider = br.com.motonav.dash.DashScreen.riderName.ifBlank { "o piloto" }
        val where = loc?.let {
            "https://maps.google.com/?q=${"%.6f".format(java.util.Locale.US, it.latitude)},${"%.6f".format(java.util.Locale.US, it.longitude)}"
        } ?: "localização indisponível"
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        val text = "MotoNav: possível QUEDA de moto de $rider às $time. Local: $where . Ele não respondeu ao alarme. Ligue para ele."
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null)
            DashLog.d("queda: SMS de emergência enviado")
            true
        } catch (e: Exception) {
            DashLog.d("queda: SMS falhou (${e.message})")
            false
        }
    }

    private fun stopAlarm() {
        runCatching { ringtone?.stop() }
        ringtone = null
        appCtx?.let { c ->
            (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
    }

    private fun vibrate(c: Context) {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0)) }
    }

    /** Notificação de tela cheia (abre a tela vermelha mesmo com o celular bloqueado). */
    private fun notifyAndOpen(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarme de queda", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Contagem antes de mandar o SMS de emergência"
                setSound(null, null)
            })
        }
        val open = PendingIntent.getActivity(c, 1, Intent(c, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(c, 2, Intent(c, CrashCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentTitle(if (test) "Teste do alarme de queda" else "Queda detectada?")
            .setContentText("SMS de emergência para ${CrashDetector.contactName} se você não responder")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "ESTOU BEM", cancel)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        runCatching { c.startActivity(Intent(c, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Botão "ESTOU BEM" da notificação. */
class CrashCancelReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = CrashAlert.cancel("botão da notificação")
}
