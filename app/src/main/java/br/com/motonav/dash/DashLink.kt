package br.com.motonav.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * ETAPA 3a: conecta ao painel exatamente na ordem que o Carbit usa (visto no log):
 *  1. Wi-Fi Direct com o MAC do QR -> painel vira "dono do grupo" (192.168.0.1)
 *  2. abre os servidores 10922 / 10921 / 10920 no celular
 *  3. manda o "wakeup" para 192.168.0.1:10930 com o nome do NOSSO pacote
 *  4. o painel conecta de volta; respondemos o CLIENT_INFO e registramos tudo no DashLog
 * Vídeo: RvServer (mapa do painel). Se a conexão cair, volta a procurar sozinho.
 */
class DashLink private constructor(ctx: Context) {

    companion object {
        @Volatile private var instance: DashLink? = null

        /** Ao abrir o app: procura o painel por até 5 min. */
        private const val SEARCH_FIRST_MS = 5 * 60_000L
        /** Depois que a conexão cai (moto desligada): mais 3 min, e então para tudo. */
        private const val SEARCH_AFTER_LOST_MS = 3 * 60_000L

        /** Uma única conexão para o app inteiro (sobrevive a fechar/reabrir a tela). */
        fun get(ctx: Context): DashLink = instance ?: synchronized(this) {
            instance ?: DashLink(ctx.applicationContext).also { instance = it }
        }
    }

    val isRunning: Boolean get() = running.get()

    /** A busca parou sozinha por tempo esgotado (não foi o usuário que tocou em Parar). */
    @Volatile var stoppedIdle = false
        private set
    @Volatile private var searchUntil = 0L

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val p2p = app.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var dash: DashPairing.Dash? = null

    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val sessionStarted = AtomicBoolean(false)
    private val servers = Collections.synchronizedList(mutableListOf<ServerSocket>())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private var receiverRegistered = false
    private val videoConns = AtomicInteger(0)
    @Volatile private var sessionGen = 0

    @Volatile private var huid: String = ""
    private val rv = RvServer(app)

    var onStateChanged: ((String) -> Unit)? = null
    private fun state(s: String) {
        DashLog.d("== $s")
        main.post { onStateChanged?.invoke(s) }
    }

    // ------------------------------------------------------------------ início / fim

    @SuppressLint("MissingPermission")
    fun start() {
        val d = DashPairing.load(app)
        if (d == null) { state("Painel não pareado. Escaneie o QR code primeiro."); return }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) { state("Ligue o Wi-Fi do celular (não precisa estar conectado a nenhuma rede)."); return }
        if (running.get()) {
            DashLog.d("reiniciando a conexão")
            stop()
        }
        running.set(true)
        stoppedIdle = false
        searchUntil = SystemClock.elapsedRealtime() + SEARCH_FIRST_MS
        DashService.start(app)

        dash = d
        connecting.set(false)
        sessionStarted.set(false)
        DashLog.d("Painel pareado: ${d.name} (${d.mac}) | app: ${app.packageName}")

        val ch = p2p.initialize(app, Looper.getMainLooper()) { DashLog.d("canal Wi-Fi Direct perdido") }
        channel = ch
        registerReceiver()
        startServers()

        // Se o celular já estiver no grupo do painel, segue direto.
        p2p.requestConnectionInfo(ch) { info -> handleConnectionInfo(info, fromStart = true) }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        main.removeCallbacksAndMessages(null)
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        rv.stop()
        DashService.stop(app)
        synchronized(servers) { servers.forEach { runCatching { it.close() } }; servers.clear() }
        if (receiverRegistered) { runCatching { app.unregisterReceiver(receiver) }; receiverRegistered = false }
        channel?.let { ch ->
            runCatching { p2p.stopPeerDiscovery(ch, null) }
            runCatching { p2p.removeGroup(ch, null) }
        }
        state("Desconectado.")
    }

    // ------------------------------------------------------------------ Wi-Fi Direct

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            if (!running.get()) return
            val ch = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    p2p.requestPeers(ch) { list -> onPeers(list.deviceList) }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    p2p.requestConnectionInfo(ch) { info -> handleConnectionInfo(info, fromStart = false) }
            }
        }
    }

    private fun registerReceiver() {
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        if (!running.get() || sessionStarted.get()) return
        val ch = channel ?: return
        if (SystemClock.elapsedRealtime() > searchUntil) {
            // Moto desligada (ou longe): para tudo para não gastar bateria. Ao abrir o app de
            // novo (ou tocar em Conectar) a busca recomeça.
            stoppedIdle = true
            stop()
            state("Painel não encontrado (moto desligada?). Busca parada para poupar bateria. " +
                "Abra o app ou toque em Conectar para procurar de novo.")
            return
        }
        state("Procurando o painel ${dash?.name} (ligue a moto)...")
        p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = DashLog.d("busca Wi-Fi Direct iniciada")
            override fun onFailure(reason: Int) = DashLog.d("busca falhou, motivo=$reason (2=ocupado, 0=erro)")
        })
        // A busca do Android expira sozinha: repete enquanto não conectar.
        scheduleDiscover(10_000)
    }

    // Um único agendamento de busca por vez (antes, depois de reconectar, ficavam 2 buscas em paralelo).
    private val discoverTick = Runnable { if (!connecting.get()) discover() }

    private fun scheduleDiscover(delayMs: Long) {
        main.removeCallbacks(discoverTick)
        main.postDelayed(discoverTick, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun onPeers(devices: Collection<WifiP2pDevice>) {
        val target = dash ?: return
        val ch = channel ?: return
        val dev = devices.firstOrNull { it.deviceAddress.equals(target.mac, ignoreCase = true) } ?: return
        if (connecting.getAndSet(true)) return
        DashLog.d("Painel encontrado: ${dev.deviceName} ${dev.deviceAddress} status=${dev.status}")
        state("Painel encontrado, conectando por Wi-Fi Direct...")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // o painel é sempre o dono do grupo
        }
        p2p.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = DashLog.d("pedido de conexão aceito pelo Android")
            override fun onFailure(reason: Int) {
                DashLog.d("conexão falhou, motivo=$reason; tentando de novo")
                connecting.set(false)
                scheduleDiscover(2000)
            }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo?, fromStart: Boolean) {
        if (!running.get()) return
        if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
            if (sessionStarted.getAndSet(true)) return
            sessionGen++
            connecting.set(true)
            val go = info.groupOwnerAddress
            state("Wi-Fi Direct conectado. Painel em ${go.hostAddress}")
            thread(name = "dash-wakeup") { sendWakeup(go) }
        } else if (fromStart) {
            discover()
        } else if (sessionStarted.get() && info?.groupFormed != true) {
            // Antes só voltava a procurar e deixava o vídeo e o mapa rodando à toa.
            DashLog.d("Wi-Fi Direct caiu (moto desligada?)")
            onSessionLost()
        }
    }

    /** O canal de controle caiu (moto desligada, fora de alcance...): limpa e volta a procurar. */
    @SuppressLint("MissingPermission")
    private fun onSessionLost() {
        if (!running.get() || !sessionStarted.getAndSet(false)) return
        connecting.set(false)
        searchUntil = SystemClock.elapsedRealtime() + SEARCH_AFTER_LOST_MS
        state("Conexão com o painel caiu. Procurando de novo por ${SEARCH_AFTER_LOST_MS / 60_000} min...")
        rv.stop()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        main.post {
            channel?.let { ch -> runCatching { p2p.removeGroup(ch, null) } }
            scheduleDiscover(3000)
        }
    }

    // ------------------------------------------------------------------ wakeup (10930)

    private fun localP2pAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("p2p") && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
    }.getOrNull()

    private fun sendWakeup(go: InetAddress) {
        // O painel recém-ligado demora alguns segundos para abrir a 10930 (ECONNREFUSED).
        for (attempt in 1..20) {
            if (!running.get()) return
            try {
                Socket().use { s ->
                    localP2pAddress()?.let {
                        s.bind(InetSocketAddress(it, 0))
                        DashLog.d("IP do celular no Wi-Fi Direct: ${it.hostAddress}")
                    }
                    s.connect(InetSocketAddress(go, Pxc.PORT_WAKEUP), 3000)
                    s.soTimeout = 5000
                    val body = JSONObject()
                        .put("phoneType", "Android")
                        .put("packageName", app.packageName)
                        .toString().toByteArray()
                    Pxc.write(s.getOutputStream(), Pxc.CMD_WAKEUP, body)
                    DashLog.d("10930 -> wakeup enviado (${app.packageName})")
                    val resp = Pxc.read(s.getInputStream())
                    DashLog.d("10930 <- ${Pxc.hex(resp.cmd)} ${Pxc.preview(resp.payload)}")
                    val ok = runCatching { JSONObject(resp.text()).optString("status") }.getOrNull()
                    state(if (ok.equals("true", true))
                        "Painel respondeu status=true. Aguardando ele conectar de volta..."
                    else "Painel respondeu status=$ok (pode ter recusado o app). Veja o log.")
                }
                return
            } catch (e: Exception) {
                if (attempt <= 3 || attempt % 5 == 0) DashLog.d("wakeup tentativa $attempt falhou: ${e.message}")
                Thread.sleep(1000)
            }
        }
        state("Não consegui falar com o painel na porta 10930. Tentando de novo...")
        onSessionLost()
    }

    // ------------------------------------------------------------------ servidores

    private fun startServers() {
        listen(Pxc.PORT_PXC) { s -> handlePxc(s) }
        listen(Pxc.PORT_RV_CTRL) { s ->
            state("Painel abriu o controle de vídeo (10921).")
            rv.handleCtrl(s)
        }
        listen(Pxc.PORT_RV_DATA) { s ->
            videoConns.incrementAndGet()
            try {
                state("Painel pediu o vídeo (10920). Enviando o mapa...")
                rv.handleData(s)
            } finally {
                if (videoConns.decrementAndGet() == 0) watchVideo()
            }
        }
    }

    /**
     * O painel às vezes fecha o vídeo (10920/10921) e mantém só o controle (C2P) vivo, e então
     * o mapa nunca mais volta. Mas o piloto também pode sair da projeção pelo botão do guidão
     * (teste de 08/11: o painel fechou o vídeo e reabriu sozinho 2 s depois, ao voltar). Por isso
     * espera 60 s antes de refazer a conexão.
     */
    private fun watchVideo() {
        val gen = sessionGen
        DashLog.d("painel fechou o vídeo (saiu da projeção?): aguardando voltar")
        main.postDelayed({
            if (running.get() && sessionStarted.get() && gen == sessionGen && videoConns.get() == 0) {
                DashLog.d("painel não reabriu o vídeo em 60 s: refazendo a conexão")
                onSessionLost()
            }
        }, 60_000)
    }

    private fun listen(port: Int, handler: (Socket) -> Unit) {
        thread(name = "dash-srv-$port") {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), 50)
                }
                servers.add(ss)
                DashLog.d("ouvindo na porta $port")
                while (running.get()) {
                    val s = ss.accept()
                    sockets.add(s)
                    s.tcpNoDelay = true
                    DashLog.d("porta $port <- conexão de ${s.inetAddress.hostAddress}:${s.port}")
                    thread(name = "dash-$port-${s.port}") {
                        try { handler(s) }
                        catch (e: Exception) { DashLog.d("porta $port (${s.port}) fechou: ${e.message}") }
                        finally { runCatching { s.close() }; sockets.remove(s) }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) DashLog.d("servidor $port erro: ${e.message}")
            }
        }
    }

    /** 10922: o 1º pacote diz o tipo do canal; respondemos tipo+1 (igual ao Carbit, h0.c). */
    private fun handlePxc(s: Socket) {
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val first = Pxc.read(input)
        DashLog.d("10922 <- canal ${Pxc.hex(first.cmd)}")
        Pxc.write(out, first.cmd + 1)
        when (first.cmd) {
            Pxc.CONN_C2P -> {
                // O painel manda sinal de vida a cada ~2-3 s. Se a moto for desligada de repente o
                // TCP não fecha: sem nada por 12 s, consideramos a moto desligada.
                s.soTimeout = 12_000
                try { c2pLoop(input, s) } finally { onSessionLost() }
            }
            Pxc.CONN_P2C -> p2cLoop(input, s)
            else -> logLoop(input, "10922/${Pxc.hex(first.cmd)}")
        }
    }

    /** Canal painel -> celular: o painel pergunta, a gente responde. */
    private fun c2pLoop(input: InputStream, s: Socket) {
        val out = s.getOutputStream()
        state("Painel abriu o canal de controle (C2P).")
        while (running.get()) {
            val p = Pxc.read(input)
            val c = p.cmd
            DashLog.d("C2P <- ${Pxc.hex(c)} ${Pxc.preview(p.payload)}")
            if (c % 2 != 0) continue // o Carbit descarta comandos ímpares
            when {
                c == Pxc.CMD_CLIENT_INFO -> {
                    val hu = runCatching { JSONObject(p.text()) }.getOrDefault(JSONObject())
                    huid = hu.optString("HUID", "")
                    val reply = clientInfoReply(hu)
                    Pxc.write(out, c + 1, reply.toString().toByteArray())
                    DashLog.d("C2P -> CLIENT_INFO respondido como ${app.packageName}")
                    state("Painel ${hu.optString("HUName")} se apresentou. Tela ${hu.optInt("screenType")}, " +
                        "mirrorMode ${hu.optInt("mirrorMode")}.")
                }
                Pxc.expectsNoReply(c) -> Unit
                // botão do guidão (no Carbit: km.z)
                c == 0x00010500 -> {
                    Pxc.write(out, c + 1)
                    val j = runCatching { JSONObject(p.text()) }.getOrDefault(JSONObject())
                    DashKeys.onKey(app, j.optInt("actionId", -1), j.optInt("keyCode", -1), p.text())
                }
                // modo noturno vindo do painel (no Carbit: km.c)
                c == 0x00010200 -> {
                    Pxc.write(out, c + 1)
                    val j = runCatching { JSONObject(p.text()) }.getOrDefault(JSONObject())
                    if (j.has("night")) br.com.motonav.NightMode.fromPanel(j.optBoolean("night") || j.optInt("night", 0) == 1)
                }
                // Comandos que o Carbit respondeu com sucesso no log (rst 0x0)
                c == Pxc.CMD_KEEPALIVE || c == Pxc.CMD_CHECK_SN || c == 0x00010450 ||
                    c == 0x00010040 || c == 0x000104A0 || c == 0x00010430 || c == 0x00010630 ->
                    Pxc.write(out, c + 1)
                else -> {
                    Pxc.write(out, Pxc.CMD_UNSUPPORTED)
                    DashLog.d("C2P -> não suportado ${Pxc.hex(c)}")
                }
            }
        }
    }

    /** Canal celular -> painel: mandamos as infos da tela periodicamente (o Carbit faz ~1x/s). */
    private fun p2cLoop(input: InputStream, s: Socket) {
        val out = s.getOutputStream()
        state("Painel abriu o canal celular->painel (P2C).")
        val dm = app.resources.displayMetrics
        val display = JSONObject()
            .put("displayRotation", 0)
            .put("width", dm.widthPixels)
            .put("height", dm.heightPixels)
            .put("enableAccessibility", false)
            .put("enableAOAHid", false)
            .put("mode", 2)
            .toString().toByteArray()
        thread(name = "dash-p2c-send") {
            try {
                while (running.get() && !s.isClosed) {
                    Pxc.write(out, Pxc.CMD_P2C_DISPLAY, display)
                    Thread.sleep(1000)
                }
            } catch (e: Exception) {
                DashLog.d("P2C envio parou: ${e.message}")
            }
        }
        var lastLogged = 0
        while (running.get()) {
            val p = Pxc.read(input)
            // as respostas ao 0x20020 chegam toda hora; registra só a cada 10
            if (p.cmd != Pxc.CMD_P2C_DISPLAY + 1 || lastLogged++ % 10 == 0) {
                DashLog.d("P2C <- ${Pxc.hex(p.cmd)} ${Pxc.preview(p.payload)}")
            }
        }
    }

    /** 10921/10920: ainda não respondemos; só registramos o que o painel manda. */
    private fun handleRaw(s: Socket, label: String) {
        state("Painel abriu $label (canal de vídeo).")
        logLoop(s.getInputStream(), label)
    }

    private fun logLoop(input: InputStream, label: String) {
        var count = 0
        while (running.get()) {
            try {
                val p = Pxc.read(input)
                if (count++ < 40) DashLog.d("$label <- ${Pxc.hex(p.cmd)} ${Pxc.preview(p.payload)}")
            } catch (m: Pxc.MagicException) {
                DashLog.d("$label <- bytes fora do formato PXC: " +
                    m.header.joinToString(" ") { String.format("%02X", it) })
                val buf = ByteArray(64)
                val n = input.read(buf)
                if (n <= 0) return
                DashLog.d("$label <- +$n bytes: " + buf.take(n).joinToString(" ") { String.format("%02X", it) })
            }
        }
    }

    private fun clientInfoReply(hu: JSONObject): JSONObject {
        val huid = hu.optString("HUID", "")
        val btName = runCatching {
            Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull() ?: Build.MODEL
        return JSONObject()
            .put("pxcVersion", "1.0.2")
            .put("phoneUUID", RsaIdentity.phoneUuid(app))
            .put("phoneBrand", Build.BRAND)
            .put("phoneModel", Build.MODEL)
            .put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
            .put("phoneOs", "Android")
            .put("package", app.packageName)
            .put("versionCode", 1)
            .put("token", 0)
            .put("pubkey", RsaIdentity.publicKey(app))
            .put("encryptedHUID", RsaIdentity.encryptHuid(app, huid))
            .put("bluetoothName", btName)
            .put("supportH264IFrame", true)
            .put("supportFunction", hu.optInt("supportFunction", 0))
    }
}
