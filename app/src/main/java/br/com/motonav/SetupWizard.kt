package br.com.motonav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.motonav.dash.DashPairing
import br.com.motonav.dash.DashScreen

/**
 * Assistente de primeiro uso, em tela cheia por cima do app (para quem nunca configurou):
 *  1. boas-vindas, aviso de responsabilidade e nome;  2. permissões;  3. bateria (dicas por marca);
 *  4. Carbit Ride (forçar parada);  5. parear o painel (QR);  6. testar a conexão.
 * Cada passo mostra ✓ quando está resolvido; dá para pular. Reabre em Ajustes.
 */
class SetupWizard(
    private val act: AppCompatActivity,
    private val host: FrameLayout,
    private val actions: Actions
) {
    interface Actions {
        fun requestPermissions()
        fun scanQr()
        fun connect()
        fun connectionText(): String
        fun connected(): Boolean
        fun finished()
    }

    companion object {
        private const val PREFS = "setup"
        fun isDone(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)
        fun markDone(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
    }

    private val orange = Color.rgb(255, 122, 0)
    private val green = Color.rgb(60, 220, 120)
    private val dim = Color.rgb(154, 160, 172)
    private val dp = act.resources.displayMetrics.density
    private var step = 0
    private val steps = 6
    private val ticker = Handler(Looper.getMainLooper())
    private var nameField: EditText? = null
    private var bikeField: EditText? = null

    val showing: Boolean get() = host.visibility == View.VISIBLE

    fun show(from: Int = 0) {
        step = from
        host.visibility = View.VISIBLE
        host.bringToFront()
        render()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 1000)
    }

    fun hide() {
        saveName()
        host.visibility = View.GONE
        ticker.removeCallbacks(tick)
    }

    /** Volta de uma tela do Android (permissões, bateria...): atualiza os ✓. */
    fun refresh() { if (showing) render() }

    /** Passos 5 e 6 mudam sozinhos (pareou, conectou): redesenha 1x/s. */
    private val tick = object : Runnable {
        override fun run() {
            if (!showing) return
            if (step >= 4) render()
            ticker.postDelayed(this, 1000)
        }
    }

    private fun saveName() {
        nameField?.text?.toString()?.trim()?.let { if (it.isNotEmpty()) DashScreen.setRider(act, it) }
        bikeField?.text?.toString()?.trim()?.let { if (it.isNotEmpty()) DashScreen.setBike(act, it) }
    }

    // ------------------------------------------------------------------ desenho

    private fun render() {
        host.removeAllViews()
        host.setBackgroundColor(Color.rgb(11, 11, 15))
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val top = host.paddingTop
            setPadding(px(20), px(24) + top, px(20), px(20))
        }
        // topo: passo X de 6 + barrinhas
        root.addView(text("PASSO ${step + 1} DE $steps", 12f, orange, bold = true).apply { letterSpacing = 0.2f })
        val bars = LinearLayout(act).apply { setPadding(0, px(8), 0, px(16)) }
        for (i in 0 until steps) bars.addView(View(act).apply {
            setBackgroundColor(if (i <= step) orange else Color.rgb(42, 44, 54))
            layoutParams = LinearLayout.LayoutParams(0, px(4), 1f).apply { marginEnd = px(4) }
        })
        root.addView(bars)

        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        when (step) {
            0 -> welcome(body)
            1 -> permissions(body)
            2 -> battery(body)
            3 -> carbit(body)
            4 -> pair(body)
            5 -> test(body)
        }
        root.addView(ScrollView(act).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })

        // rodapé: voltar / próximo
        val nav = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, 0) }
        if (step > 0) nav.addView(button("Voltar", tonal = true) { saveName(); step--; render() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, px(56), 1f).apply { marginEnd = px(8) }
        })
        val nextLabel = when (step) { 0 -> "Li e aceito"; steps - 1 -> "Concluir"; else -> "Próximo" }
        nav.addView(button(nextLabel) {
            saveName()
            if (step < steps - 1) { step++; render() } else { markDone(act); hide(); actions.finished() }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, px(56), 1.4f) })
        root.addView(nav)
        host.addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    private fun welcome(b: LinearLayout) {
        b.addView(title("Bem-vindo! 🏍"))
        b.addView(text("Este app mostra mapa, navegação e um painel esportivo na tela da moto, no lugar do Carbit Ride. " +
            "Vamos configurar em 2 minutos.", 16f, Color.WHITE))
        b.addView(gap(16))
        b.addView(text("Como você quer ser chamado no painel?", 14f, dim))
        nameField = EditText(act).apply {
            hint = "Seu nome ou apelido"
            setText(DashScreen.riderName)
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(100, 104, 116))
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        b.addView(nameField)
        b.addView(gap(12))
        b.addView(text("Qual é a sua moto?", 14f, dim))
        bikeField = EditText(act).apply {
            hint = "Ex.: Zontes T350X"
            setText(DashScreen.bikeModel)
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(100, 104, 116))
            inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        b.addView(bikeField)
        b.addView(gap(16))
        b.addView(card(
            "⚠ Antes de usar",
            "• Não mexa no celular pilotando. Configure tudo parado.\n" +
                "• Navegação, radares e limites vêm de mapas abertos e podem estar errados: respeite a sinalização.\n" +
                "• A detecção de queda é uma ajuda e pode falhar; não substitui cuidado nem socorro.\n" +
                "• Na primeira conexão com o painel, o app avisa quem mantém o projeto qual moto e qual painel conectaram " +
                "(seu nome ou apelido, a moto, o modelo do painel e do celular), para saber com quais motos ele funciona. " +
                "Não vai localização. Dá para desligar em Ajustes.\n" +
                "• Projeto independente e gratuito, sem vínculo com a fabricante da moto nem com a Carbit. Uso por sua conta e risco.",
            orange))
    }

    private fun permissions(b: LinearLayout) {
        b.addView(title("Permissões"))
        b.addView(text("O app precisa delas para achar o painel e saber onde você está.", 15f, dim))
        b.addView(gap(12))
        b.addView(status("Localização precisa", "para o mapa, a rota e a velocidade", has(Manifest.permission.ACCESS_FINE_LOCATION)))
        if (Build.VERSION.SDK_INT >= 33) {
            b.addView(status("Dispositivos Wi-Fi próximos", "para encontrar o painel da moto", has(Manifest.permission.NEARBY_WIFI_DEVICES)))
            b.addView(status("Notificações", "para manter a conexão com o painel", has(Manifest.permission.POST_NOTIFICATIONS)))
        }
        b.addView(gap(12))
        b.addView(button("Permitir") { actions.requestPermissions() })
        b.addView(gap(8))
        b.addView(text("Se o Android não perguntar mais, toque em \"Abrir configurações\" e libere em Permissões.", 13f, dim))
        b.addView(button("Abrir configurações do app", tonal = true) { openAppSettings(act.packageName) })
    }

    private fun battery(b: LinearLayout) {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        val free = pm.isIgnoringBatteryOptimizations(act.packageName)
        b.addView(title("Bateria"))
        b.addView(text("Com a tela do celular apagada, o Android costuma pausar os apps. Para o painel não travar no meio do caminho:", 15f, dim))
        b.addView(gap(12))
        b.addView(status("App liberado (sem restrição de bateria)", if (free) "tudo certo" else "toque em Liberar e confirme", free))
        b.addView(status("Economia de energia desligada", "ela desliga o GPS com a tela apagada", !pm.isPowerSaveMode))
        b.addView(gap(12))
        if (!free) b.addView(button("Liberar bateria") {
            runCatching {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}")))
            }.onFailure { runCatching { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        })
        if (pm.isPowerSaveMode) b.addView(button("Abrir Economia de energia", tonal = true) {
            runCatching { act.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        })
        b.addView(gap(12))
        val brand = Build.MANUFACTURER.lowercase()
        val tip = when {
            brand.contains("samsung") -> "Samsung: Configurações → Bateria → Limites de uso em segundo plano → \"Apps que nunca suspendem\" → adicione este app."
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "Xiaomi/Redmi/POCO: segure o ícone do app → Informações → Economia de bateria → \"Sem restrições\". E ligue \"Início automático\"."
            brand.contains("motorola") -> "Motorola: Configurações → Apps → este app → Bateria → \"Sem restrições\"."
            brand.contains("huawei") || brand.contains("honor") -> "Huawei/Honor: Configurações → Bateria → Inicialização de apps → este app → \"Gerenciar manualmente\" e ligue tudo."
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "OPPO/realme/OnePlus: Configurações → Bateria → este app → \"Permitir atividade em segundo plano\"."
            else -> "Procure nas configurações de bateria do seu celular a opção de não restringir este app."
        }
        b.addView(card("Dica para o seu celular (${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }})", tip, Color.rgb(60, 64, 76)))
    }

    private fun carbit(b: LinearLayout) {
        val has = Report.installed(act, Report.CARBIT_PACKAGE)
        b.addView(title("Carbit Ride"))
        if (!has) {
            b.addView(status("Carbit Ride não está instalado", "nada a fazer aqui", true))
            return
        }
        b.addView(text("O Carbit Ride conecta no painel antes deste app. Sempre que for usar este app, force a parada dele:", 15f, dim))
        b.addView(gap(12))
        b.addView(card("Como fazer",
            "1. Toque no botão abaixo.\n2. Toque em \"Forçar parada\" e confirme.\n3. Volte para cá.\n\n" +
                "Se não for mais usar o Carbit, pode desinstalar: assim nunca atrapalha.", Color.rgb(60, 64, 76)))
        b.addView(gap(12))
        b.addView(button("Abrir o Carbit Ride nas configurações") { openAppSettings(Report.CARBIT_PACKAGE) })
    }

    private fun pair(b: LinearLayout) {
        val d = DashPairing.load(act)
        b.addView(title("Parear o painel"))
        b.addView(text("É feito uma vez só. Com a moto ligada, abra no painel a tela de conexão com o celular (a que mostra o QR code do Carbit Ride) e leia o QR com o botão abaixo.", 15f, dim))
        b.addView(gap(12))
        b.addView(status(if (d != null) "Painel pareado: ${d.name}" else "Nenhum painel pareado ainda",
            if (d != null) "pode seguir" else "leia o QR do painel", d != null))
        b.addView(gap(12))
        b.addView(button(if (d != null) "Ler outro QR" else "Ler QR do painel", tonal = d != null) { actions.scanQr() })
        b.addView(gap(8))
        b.addView(text("Se aparecer \"QR não reconhecido\", procure no painel outro QR: o certo é o que leva para o Carbit Ride com o endereço do painel.", 13f, dim))
    }

    private fun test(b: LinearLayout) {
        val ok = actions.connected()
        b.addView(title("Testar a conexão"))
        b.addView(text("1. Force a parada do Carbit Ride (se tiver).\n2. Ligue a moto.\n3. Toque em Conectar e aguarde até 1 minuto.", 15f, Color.WHITE))
        b.addView(gap(12))
        b.addView(status(if (ok) "Conectado! O mapa já está no painel 🎉" else actions.connectionText(),
            if (ok) "toque em Concluir" else "deixe o Wi-Fi do celular ligado (não precisa estar numa rede)", ok))
        b.addView(gap(12))
        if (!ok) b.addView(button("Conectar") { actions.connect() })
        b.addView(gap(8))
        b.addView(text("Não conectou? Toque em Concluir e use Ajustes → \"Enviar relatório\" para mandar o log a quem te passou o app.", 13f, dim))
    }

    // ------------------------------------------------------------------ peças

    private fun has(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings(pkg: String) {
        runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
    }

    private fun px(v: Int) = (v * dp).toInt()
    private fun gap(h: Int) = View(act).apply { layoutParams = LinearLayout.LayoutParams(1, px(h)) }

    private fun title(s: String) = text(s, 28f, Color.WHITE, bold = true).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        setPadding(0, 0, 0, px(8))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(px(3).toFloat(), 1f)
    }

    private fun status(titleText: String, sub: String, ok: Boolean) = LinearLayout(act).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), px(12), px(14), px(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(14).toFloat(); setColor(Color.rgb(23, 24, 31))
            setStroke(px(1), if (ok) green else Color.rgb(42, 44, 54))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }
        addView(text(if (ok) "✓" else "○", 22f, if (ok) green else dim, bold = true).apply { setPadding(0, 0, px(14), 0) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(titleText, 16f, Color.WHITE, bold = true))
        col.addView(text(sub, 13f, dim))
        addView(col)
    }

    private fun card(t: String, body: String, stroke: Int) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(14), px(16), px(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(16).toFloat(); setColor(Color.rgb(23, 24, 31)); setStroke(px(1), stroke)
        }
        addView(text(t, 15f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, px(6)) })
        addView(text(body, 14f, Color.rgb(200, 204, 212)))
    }

    private fun button(label: String, tonal: Boolean = false, onClick: () -> Unit): Button =
        com.google.android.material.button.MaterialButton(act).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            cornerRadius = px(28)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (tonal) Color.rgb(42, 44, 54) else orange)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, px(56))
            setOnClickListener { onClick() }
        }
}
