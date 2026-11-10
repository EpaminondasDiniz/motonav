package br.com.motonav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.motonav.dash.DashLog

/**
 * Tela de pilotagem: tela do celular ACESA, toda preta e no brilho mínimo.
 *
 * Por quê: com a Economia de energia do Samsung ligada, a política é "GPS desligado com a tela
 * APAGADA" (location_mode=1). Com a tela acesa o GPS continua, e numa tela AMOLED o preto não
 * acende nenhum pixel, então o gasto extra é pequeno. Abre sozinha quando a tela apaga com o
 * painel conectado e a Economia ligada (ver [RideScreenGuard]). Segurar 1 s em qualquer lugar sai.
 */
class RideScreen : AppCompatActivity() {

    companion object {
        @Volatile var visible = false
            private set
        private var current: java.lang.ref.WeakReference<RideScreen>? = null
        /** Fecha a tela (o painel desconectou). */
        fun closeAll() { current?.get()?.let { if (!it.isFinishing) it.finish() } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hint: TextView
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(600).start() }
    private val exit = Runnable { DashLog.d("tela de pilotagem: fechada pelo piloto"); finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // brilho mínimo só nesta janela (a configuração do celular não muda)
        window.attributes = window.attributes.apply { screenBrightness = 0.0f }
        val dp = resources.displayMetrics.density
        hint = TextView(this).apply {
            text = "🏍 Tela de pilotagem\n\nA Economia de energia desliga o GPS com a tela apagada, " +
                "então o app deixa a tela acesa, toda preta.\n\nSegure o dedo 1 segundo para sair."
            setTextColor(Color.rgb(110, 110, 110))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((32 * dp).toInt(), 0, (32 * dp).toInt(), 0)
            alpha = 0f
        }
        val root = object : FrameLayout(this) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(hideHint)
                        hint.animate().alpha(1f).setDuration(200).start()
                        handler.postDelayed(exit, 1000)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(exit)
                        handler.postDelayed(hideHint, 3000)
                    }
                }
                return true
            }
        }.apply { setBackgroundColor(Color.BLACK) }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        current = java.lang.ref.WeakReference(this)
        hideBars()
        getSystemService(NotificationManager::class.java)?.cancel(RideScreenGuard.NOTIF_ID)
        DashLog.d("tela de pilotagem aberta (tela acesa e preta para o GPS continuar com a Economia de energia)")
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() { super.onResume(); visible = true; RideScreenGuard.onShown() }
    override fun onPause() { super.onPause(); visible = false }

    // Apagou (botão de ligar): fecha, para a próxima tela cheia criar uma nova e acender a tela
    // (uma tela já aberta, parada atrás, não acende de novo).
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/**
 * Vigia do serviço do painel: com a Economia de energia cortando o GPS, quando a tela apaga
 * (tempo esgotado ou botão de ligar), acende de novo na [RideScreen].
 */
object RideScreenGuard {
    const val NOTIF_ID = 4471
    private const val CHANNEL = "ride_screen"
    private const val PREFS = "ride_screen"

    private var registered = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    /** A Economia de energia está cortando o GPS com a tela apagada? */
    fun saverCutsGps(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode && pm.locationPowerSaveMode in setOf(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF)
    }

    /** Android 14+: abrir tela cheia por cima do bloqueio precisa de permissão. */
    fun canShow(ctx: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun permissionIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            android.net.Uri.parse("package:${ctx.packageName}"))
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> main.postDelayed({ show(c, "a tela apagou") }, 400)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                    DashLog.d("Economia de energia ${if (saverCutsGps(c)) "LIGADA: a tela de pilotagem entra quando a tela apagar" else "desligada"}")
            }
        }
    }

    /** O serviço do painel começou (conectado). */
    fun start(ctx: Context) {
        if (registered) return
        app = ctx.applicationContext
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        ContextCompat.registerReceiver(ctx.applicationContext, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        if (saverCutsGps(ctx) && enabled(ctx)) {
            DashLog.d("Economia de energia ligada: ao apagar a tela, o app acende a tela de pilotagem (preta) para o GPS continuar" +
                if (canShow(ctx)) "" else " — FALTA a permissão de tela cheia (Moto → Tela de pilotagem)")
        }
    }

    fun stop(ctx: Context) {
        if (!registered) return
        runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
        registered = false
        main.removeCallbacksAndMessages(null)
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        RideScreen.closeAll()
    }

    fun onShown() { app?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }

    @SuppressLint("WakelockTimeout")
    private fun show(ctx: Context, why: String) {
        if (!registered || !enabled(ctx) || !saverCutsGps(ctx) || RideScreen.visible) return
        // só com o painel conectado (durante a busca o GPS não faz falta); "test" = teste pelo adb
        val test = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("test", false)
        if (!br.com.motonav.dash.DashLink.get(ctx).sessionActive && !test) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        DashLog.d("tela de pilotagem: $why com a Economia de energia; acendendo para o GPS não parar")
        val open = Intent(ctx, RideScreen::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tela de pilotagem", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Acende a tela preta para o GPS continuar com a Economia de energia"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moto)
            .setContentTitle("Tela de pilotagem")
            .setContentText("GPS ativo com a Economia de energia")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
        if (!canShow(ctx)) DashLog.d("tela de pilotagem: sem permissão de tela cheia, o Android pode não abrir")
        // Reforço, só se a tela cheia não abriu: acende a tela e tenta abrir direto. (Acender antes
        // faria o Android mostrar só um aviso pequeno no lugar da tela cheia.)
        main.postDelayed({
            if (RideScreen.visible || !registered) return@postDelayed
            DashLog.d("tela de pilotagem: tela cheia não abriu em 1,5 s; acendendo a tela")
            runCatching {
                @Suppress("DEPRECATION")
                pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "MotoNav:ride").acquire(3000)
            }
            runCatching { ctx.startActivity(open) }
        }, 1500)
    }
}
