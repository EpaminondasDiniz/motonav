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

    /**
     * Painel novo: coisas do Bluetooth do celular que atrapalham a conexão com o painel
     * (caso do Xiaomi 22101320G de 10–11/11: o painel nunca aparecia na busca e o link caía).
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun bleExtras(ctx: Context, d: DashPairing.Dash): String = runCatching {
        val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val a = bm.adapter
        fun isPanel(x: android.bluetooth.BluetoothDevice) = (d.bleMac != null && x.address.equals(d.bleMac, true)) ||
            (d.bleName != null && runCatching { x.name }.getOrNull().equals(d.bleName, true))
        val bonded = runCatching { a.bondedDevices.any { isPanel(it) } }.getOrNull()
        val connected = runCatching { bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT).any { isPanel(it) } }.getOrNull()
        val audio = runCatching {
            a.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                a.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
        }.getOrNull()
        fun yn(b: Boolean?) = when (b) { true -> "SIM"; false -> "não"; null -> "?" }
        "Bluetooth do painel: pareado no Android ${yn(bonded)} · conectado agora ${yn(connected)} · " +
            "áudio Bluetooth (intercomunicador/fone) ${yn(audio)}"
    }.getOrDefault("Bluetooth do painel: ?")

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
        val bt = runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
        sb.appendLine("Bluetooth ligado: ${when (bt) { true -> "sim"; false -> "NÃO"; null -> "?" }}" +
            (if (Build.VERSION.SDK_INT >= 31) " · permissão: ${perm(Manifest.permission.BLUETOOTH_SCAN)}/${perm(Manifest.permission.BLUETOOTH_CONNECT)}" else ""))
        sb.appendLine("--- Permissões")
        sb.appendLine("Localização precisa: ${perm(Manifest.permission.ACCESS_FINE_LOCATION)} · o tempo todo: ${perm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}")
        if (Build.VERSION.SDK_INT >= 33) {
            sb.appendLine("Dispositivos Wi-Fi próximos: ${perm(Manifest.permission.NEARBY_WIFI_DEVICES)} · notificações: ${perm(Manifest.permission.POST_NOTIFICATIONS)}")
        }
        sb.appendLine("Acesso a notificações (música/mensagens): ${if (MediaInfo.hasPermission(ctx)) "sim" else "não"} · SMS: ${perm(Manifest.permission.SEND_SMS)}")
        sb.appendLine("--- Bateria")
        sb.appendLine("Sem restrição de bateria: ${if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "sim" else "NÃO"}")
        sb.appendLine("Economia de energia: ${if (pm.isPowerSaveMode) "LIGADA (modo GPS ${pm.locationPowerSaveMode})" else "desligada"}")
        sb.appendLine("--- Moto e painel")
        sb.appendLine("Moto: ${DashScreen.bikeModel.ifBlank { "não informada" }}")
        sb.appendLine("Painel: ${Compat.panelModel.ifBlank { "ainda não conectou" }} · canal ${Compat.panelChannel.ifBlank { "?" }} · tela ${Compat.screen.ifBlank { "?" }}")
        sb.appendLine("Pareado: " + (DashPairing.load(ctx)?.let { "${it.name} · " + if (it.isBle) "modelo novo (Bluetooth + hotspot)" else "Wi-Fi Direct" } ?: "não"))
        DashPairing.load(ctx)?.takeIf { it.isBle }?.let { sb.appendLine(bleExtras(ctx, it)) }
        sb.appendLine("Carbit Ride instalado: ${if (installed(ctx, CARBIT_PACKAGE)) "sim (forçar parada antes de usar)" else "não"}")
        sb.appendLine("Tela: ${DashScreen.mode} · estilo Race: ${DashScreen.raceStyle.title}")
        sb.appendLine("Chave TomTom: ${if (TomTom.enabled) (if (TomTom.hasUserKey) "digitada no app" else "do APK") else "não"}")
        sb.appendLine("--- Log")
        sb.append(sanitize(DashLog.text()))
        return sb.toString()
    }

    /**
     * Tira do log o que não deve sair do celular: MAC, identificador e número de série do painel,
     * senhas, e linhas com endereço, destino, rua ou música (o relatório promete não levar localização).
     */
    fun sanitize(log: String): String {
        var s = log
        // MAC (aa:bb:cc:dd:ee:ff, também escrito com %3A)
        s = Regex("(?i)\\b[0-9a-f]{2}((:|%3A)[0-9a-f]{2}){5}\\b").replace(s, "xx:xx:xx:xx:xx:xx")
        // nome Bluetooth e código do painel novo (vêm do MAC) e nome do hotspot do celular
        s = Regex("\\bEC[0-9A-Fa-f]{8}\\b").replace(s, "ECxxxxxxxx")
        s = Regex("CARBIT[0-9A-Fa-f]{12}").replace(s, "CARBITxxxxxxxxxxxx")
        s = Regex("hotspot ativo: \"[^\"]*\"").replace(s, "hotspot ativo: \"(oculto)\"")
        // campos de identificação no JSON do painel
        s = Regex("(?i)\"(HUID|sn|pwd|password|userName|btAddress|btPin|phoneUUID|token)\"\\s*:\\s*\"[^\"]*\"")
            .replace(s) { "\"${it.groupValues[1]}\": \"(oculto)\"" }
        // linhas com lugares, ruas e músicas: mantém só o começo
        val hide = listOf("Calculando rota até", "Chegou ao destino", "Aviso:", "Alerta:", "música:", "QR inválido:",
            "Pouco combustível", "Limite da via")
        return s.lines().joinToString("\n") { line ->
            val k = hide.firstOrNull { line.contains(it) }
            if (k == null) line else line.substring(0, line.indexOf(k) + k.length) + " (oculto)"
        }
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
        val content = "📋 **Relatório de ${clean(who)}**\n" +
            "🏍 ${clean(Compat.summary())}\n" +
            "📱 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v${BuildConfig.VERSION_NAME}" +
            (if (desc.isNotEmpty()) "\n> ${clean(desc).replace("\n", "\n> ")}" else "")
        val err = multipart(content, file.name, file.readBytes())
        if (err == null) { p.edit().putLong("lastSent", now).apply(); DashLog.d("relatório enviado") }
        return err
    }

    // ------------------------------------------------------------------ diagnóstico automático de conexão

    private const val AUTO_OK_MS = 6 * 3_600_000L   // "deu certo": no máximo 1 a cada 6 h por celular
    private const val AUTO_FAIL_MS = 10 * 60_000L   // erro: no máximo 1 a cada 10 min
    private const val AUTO_NOTFOUND_MS = 24 * 3_600_000L // painel novo não apareceu: 1 por dia

    /**
     * Manda sozinho o resultado de uma tentativa de conexão com o painel (o pessoal tinha dificuldade
     * de enviar o relatório à mão). Vai junto o registro já filtrado (sem MAC, ruas, senhas).
     * [kind]: "ok", "erro" ou "nao_achou". Respeita a chave da tela Moto. Se não houver internet,
     * guarda e manda na próxima vez.
     */
    fun connection(ctx: Context, kind: String, step: String) {
        if (!canSend || !Compat.enabled(ctx)) return
        val p = ctx.getSharedPreferences("report", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val (key, gap) = when (kind) {
            "ok" -> "autoOk" to AUTO_OK_MS
            "nao_achou" -> "autoNotFound" to AUTO_NOTFOUND_MS
            else -> "autoFail" to AUTO_FAIL_MS
        }
        if (now - p.getLong(key, 0L) < gap) return
        p.edit().putLong(key, now).apply()
        val pairing = DashPairing.load(ctx)
        val icon = when (kind) { "ok" -> "✅ **Conectou**"; "nao_achou" -> "🔎 **Painel não encontrado**"; else -> "❌ **Erro na conexão**" }
        val content = "$icon · ${clean(DashScreen.riderName.ifBlank { "Sem nome" })}\n" +
            "🏍 ${clean(Compat.summary())} · " + (if (pairing?.isBle == true) "painel novo (Bluetooth)" else "painel antigo (Wi-Fi Direct)") + "\n" +
            "📱 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v${BuildConfig.VERSION_NAME}\n" +
            "> ${clean(step).take(300)}"
        val log = build(ctx).toByteArray()
        Thread {
            val name = "conexao_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".txt"
            if (multipart(content, name, log) == null) {
                DashLog.d("diagnóstico de conexão enviado ($kind)")
                flushPending(ctx)
            } else savePending(ctx, content, name, log)
        }.start()
    }

    private fun pendingDir(ctx: Context) = File(ctx.filesDir, "pendentes").apply { mkdirs() }

    private fun savePending(ctx: Context, content: String, name: String, log: ByteArray) {
        val dir = pendingDir(ctx)
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        files.dropLast(8).forEach { it.delete() } // guarda no máximo uns 5 diagnósticos
        File(dir, "${System.currentTimeMillis()}.json").writeText(
            org.json.JSONObject().put("content", content).put("name", name)
                .put("log", String(log, Charsets.UTF_8)).toString())
        DashLog.d("diagnóstico de conexão guardado para mandar depois (sem internet)")
    }

    /** Manda os diagnósticos que ficaram guardados (chamado ao abrir a conexão e depois de um envio). */
    fun flushPending(ctx: Context) {
        if (!canSend) return
        val files = pendingDir(ctx).listFiles()?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return
        Thread {
            for (f in files) {
                val j = runCatching { org.json.JSONObject(f.readText()) }.getOrNull()
                if (j == null) { f.delete(); continue }
                if (multipart(j.optString("content") + "\n(enviado depois)", j.optString("name"), j.optString("log").toByteArray()) == null) f.delete()
                else break
                Thread.sleep(1500)
            }
        }.start()
    }

    /** POST multipart no Discord: texto + arquivo. null = deu certo. Bloqueia. */
    private fun multipart(content: String, fileName: String, data: ByteArray): String? {
        val now = System.currentTimeMillis()
        // sem menções: ninguém consegue mandar @everyone pelo relatório
        val payload = org.json.JSONObject()
            .put("username", "Relatórios do app")
            .put("content", content.take(1990))
            .put("allowed_mentions", org.json.JSONObject().put("parse", org.json.JSONArray()))
            .put("flags", 4) // sem pré-visualização de links
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
                w("--$boundary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"$fileName\"\r\nContent-Type: text/plain\r\n\r\n")
                out.write(data); w("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code in 200..299) null else "o servidor respondeu $code"
        } catch (e: Exception) {
            "sem conexão (${e.message})"
        } finally {
            conn.disconnect()
        }
    }

    /** Mensagem de texto simples no canal (aviso de nova moto). Bloqueia; true se deu certo. */
    fun post(content: String): Boolean {
        if (!canSend) return false
        val payload = org.json.JSONObject()
            .put("username", "Relatórios do app")
            .put("content", clean(content).take(1990))
            .put("allowed_mentions", org.json.JSONObject().put("parse", org.json.JSONArray()))
            .put("flags", 4)
        val conn = java.net.URL(BuildConfig.REPORT_WEBHOOK).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Tira o que poderia abusar do canal: menções (@everyone), blocos de código e links
     * disfarçados ([texto](endereço)).
     */
    private fun clean(s: String) = s.replace("@", "＠").replace("`", "'").replace("[", "(").replace("]", ")")

    fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
