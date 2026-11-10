package br.com.motonav

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela vermelha da queda: contagem grande e um botão enorme "ESTOU BEM". Abre por cima da tela
 * de bloqueio e liga a tela do celular.
 */
class CrashActivity : AppCompatActivity() {
    private lateinit var count: TextView
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(180, 20, 20))
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = if (CrashAlert.test) "TESTE DO ALARME" else "QUEDA DETECTADA?"
            setTextColor(Color.WHITE); textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        count = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 120f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        root.addView(count)
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            text = if (CrashAlert.test) "Teste: nenhum SMS será enviado."
            else "Se você não responder, um SMS com a sua localização vai para ${CrashDetector.contactName}."
        }
        root.addView(info)
        root.addView(Button(this).apply {
            text = "ESTOU BEM"
            textSize = 30f
            setTextColor(Color.rgb(180, 20, 20))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, (140 * dp).toInt()).apply { topMargin = (40 * dp).toInt() }
            setOnClickListener { CrashAlert.cancel("tela do celular"); finish() }
        })
        setContentView(root)
        show(CrashAlert.secondsLeft)
        CrashAlert.onTick = { s -> runOnUiThread { show(s) } }
    }

    private fun show(s: Int) {
        when {
            s >= 0 -> count.text = "$s"
            s == -2 -> { count.text = "✉"; info.text = "SMS de emergência enviado para ${CrashDetector.contactName}." }
            else -> finish()
        }
    }

    override fun onDestroy() {
        if (CrashAlert.onTick != null) CrashAlert.onTick = null
        super.onDestroy()
    }
}
