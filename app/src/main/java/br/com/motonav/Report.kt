package br.com.motonav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashPairing
import br.com.motonav.dash.DashScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relatório para suporte ("Enviar relatório"): aparelho, versões, permissões, bateria e o log do
 * painel. Não inclui chave da TomTom (só se existe), MAC do painel, contatos nem posição.
 */
object Report {
    const val CARBIT_PACKAGE = "net.easyconn.easyride.wws"

    fun build(ctx: Context): String {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        fun perm(p: String) = if (ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED) "sim" else "NÃO"
        val sb = StringBuilder()
        sb.appendLine("=== Relatório ${ctx.applicationInfo.loadLabel(ctx.packageManager)} ===")
        sb.appendLine("Data: " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date()))
        sb.appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
        sb.appendLine("Celular: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Wi-Fi ligado: ${if (wifi.isWifiEnabled) "sim" else "NÃO"} · Wi-Fi Direct: " +
            if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) "sim" else "NÃO")
        sb.appendLine("Internet: ${if (Net.online) "sim" else "NÃO"}")
        sb.appendLine("--- Permissões")
        sb.appendLine("Localização precisa: ${perm(Manifest.permission.ACCESS_FINE_LOCATION)} · o tempo todo: ${perm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}")
        if (Build.VERSION.SDK_INT >= 33) {
            sb.appendLine("Dispositivos Wi-Fi próximos: ${perm(Manifest.permission.NEARBY_WIFI_DEVICES)} · notificações: ${perm(Manifest.permission.POST_NOTIFICATIONS)}")
        }
        sb.appendLine("Acesso a notificações (música/mensagens): ${if (MediaInfo.hasPermission(ctx)) "sim" else "não"} · SMS: ${perm(Manifest.permission.SEND_SMS)}")
        sb.appendLine("--- Bateria")
        sb.appendLine("Sem restrição de bateria: ${if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "sim" else "NÃO"}")
        sb.appendLine("Economia de energia: ${if (pm.isPowerSaveMode) "LIGADA (modo GPS ${pm.locationPowerSaveMode})" else "desligada"}")
        sb.appendLine("--- Painel")
        sb.appendLine("Pareado: ${DashPairing.load(ctx)?.name ?: "não"}")
        sb.appendLine("Carbit Ride instalado: ${if (installed(ctx, CARBIT_PACKAGE)) "sim (forçar parada antes de usar)" else "não"}")
        sb.appendLine("Tela: ${DashScreen.mode} · estilo Race: ${DashScreen.raceStyle.title}")
        sb.appendLine("Chave TomTom: ${if (TomTom.enabled) (if (TomTom.hasUserKey) "digitada no app" else "do APK") else "não"}")
        sb.appendLine("--- Log")
        sb.append(DashLog.text())
        return sb.toString()
    }

    /** Grava em cache/share/ para compartilhar como arquivo. */
    fun writeFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val name = "relatorio_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".txt"
        return File(dir, name).apply { writeText(build(ctx)) }
    }

    // ------------------------------------------------------------------ envio para o Discord

    val canSend: Boolean get() = BuildConfig.REPORT_WEBHOOK.startsWith("https://")
    private const val MIN_INTERVAL_MS = 2 * 60_000L

    /**
     * Manda o relatório para o canal do Discord (webhook), com nome, aparelho, versão, a descrição
     * e o arquivo. Bloqueia: chamar fora da thread principal. No máximo 1 envio a cada 2 min.
     * Devolve null se deu certo, ou o motivo do erro.
     */
    fun send(ctx: Context, name: String, what: String): String? {
        if (!canSend) return "envio não configurado"
        val p = ctx.getSharedPreferences("report", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastSent", 0L) < MIN_INTERVAL_MS) return "aguarde 2 minutos entre um relatório e outro"
        val file = writeFile(ctx)
        val who = name.trim().ifEmpty { "Sem nome" }.take(40)
        val desc = what.trim().take(900)
        val content = "📋 **Relatório de ${clean(who)}** · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "v${BuildConfig.VERSION_NAME}" + (if (desc.isNotEmpty()) "\n> ${clean(desc).replace("\n", "\n> ")}" else "")
        // sem menções: ninguém consegue mandar @everyone pelo relatório
        val payload = org.json.JSONObject()
            .put("username", "Relatórios do app")
            .put("content", content.take(1990))
            .put("allowed_mentions", org.json.JSONObject().put("parse", org.json.JSONArray()))
        val boundary = "----motonav" + now
        val conn = java.net.URL(BuildConfig.REPORT_WEBHOOK).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n")
                w(payload.toString()); w("\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"${file.name}\"\r\nContent-Type: text/plain\r\n\r\n")
                out.write(file.readBytes()); w("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code in 200..299) { p.edit().putLong("lastSent", now).apply(); DashLog.d("relatório enviado"); null }
            else "o servidor respondeu $code"
        } catch (e: Exception) {
            "sem conexão (${e.message})"
        } finally {
            conn.disconnect()
        }
    }

    /** Tira formatação do Discord que poderia bagunçar a mensagem. */
    private fun clean(s: String) = s.replace("@", "＠").replace("`", "'")

    fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
