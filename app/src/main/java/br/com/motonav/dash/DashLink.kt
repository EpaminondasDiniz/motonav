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

    /** Conectado ao painel agora (não só procurando). */
    val sessionActive: Boolean get() = running.get() && sessionStarted.get()

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
    @Volatile private var lastState = ""
    private fun state(s: String) {
        lastState = s
        DashLog.d("== $s")
        main.post { onStateChanged?.invoke(s) }
    }

    // ------------------------------------------------------------------ início / fim

    @SuppressLint("MissingPermission")
    fun start() {
        val d = DashPairing.load(app)
        if (d == null) { state("Painel não pareado. Escaneie o QR code primeiro."); return }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!d.isBle && !wifi.isWifiEnabled) { state("Ligue o Wi-Fi do celular (não precisa estar conectado a nenhuma rede)."); return }
        if (running.get()) {
            DashLog.d("reiniciando a conexão")
            stop()
        }
        running.set(true)
        stoppedIdle = false
        searchUntil = SystemClock.elapsedRealtime() + SEARCH_FIRST_MS
        DashService.start(app)
        br.com.motonav.Report.flushPending(app)

        dash = d
        connecting.set(false)
        sessionStarted.set(false)
        bleMode = d.isBle
        rv.extended = d.isBle
        DashLog.d("Painel pareado: ${d.name} (${d.mac}) | ${if (d.isBle) "Bluetooth + hotspot" else "Wi-Fi Direct"} | app: ${app.packageName}")

        if (d.isBle) {
            startServers()
            startBle()
            return
        }

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
        panelAddr = null
        localLinkAddr = null
        ble?.close(); ble = null
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
            if (!everOk()) diag("nao_achou", "Wi-Fi Direct: o painel ${dash?.name} não apareceu na busca em ${SEARCH_FIRST_MS / 60_000} min")
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
            panelAddr = go // só esse endereço pode conectar nas portas 10920-10922
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
        // diagnóstico 2 s depois, para o log já ter o motivo da queda (que é registrado logo em seguida)
        val step = lastState
        val videoSecs = if (videoStartAt > 0) (SystemClock.elapsedRealtime() - videoStartAt) / 1000 else -1
        if (okGen != sessionGen) main.postDelayed({ diag("erro", "a conexão caiu antes do vídeo. Último passo: $step") }, 2000)
        else if (bleMode && videoSecs in 0..600) // painel novo: queda logo depois de funcionar é suspeita
            main.postDelayed({ diag("erro", "conectou, mas caiu depois de $videoSecs s de vídeo. Último passo: $step") }, 2000)
        videoStartAt = 0
        connecting.set(false)
        panelAddr = null
        localLinkAddr = null
        searchUntil = SystemClock.elapsedRealtime() + SEARCH_AFTER_LOST_MS
        state("Conexão com o painel caiu. Procurando de novo por ${SEARCH_AFTER_LOST_MS / 60_000} min...")
        rv.stop()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        if (bleMode) {
            ble?.close(); ble = null
            main.postDelayed({ if (running.get() && !sessionStarted.get()) startBle() }, 3000)
            return
        }
        main.post {
            channel?.let { ch -> runCatching { p2p.removeGroup(ch, null) } }
            scheduleDiscover(3000)
        }
    }

    // ------------------------------------------------------------------ painéis novos (Bluetooth + hotspot)

    /** Painel pareado pelo QR novo (sem Wi-Fi Direct). */
    @Volatile private var bleMode = false
    @Volatile private var ble: BleLink? = null
    private val bleBusy = AtomicBoolean(false)
    /** IP do celular na rede onde o painel está (hotspot); no Wi-Fi Direct fica null e vale a p2p. */
    @Volatile private var localLinkAddr: InetAddress? = null
    @Volatile private var c2pOpenedGen = -1

    private fun startBle() {
        val d = dash ?: return
        if (!bleBusy.compareAndSet(false, true)) return
        thread(name = "dash-ble") {
            try {
                while (running.get() && !sessionStarted.get()) {
                    if (SystemClock.elapsedRealtime() > searchUntil) {
                        stoppedIdle = true
                        main.post { stop() }
                        state("Painel não encontrado (moto desligada?). Busca parada para poupar bateria. " +
                            "Abra o app ou toque em Conectar para procurar de novo.")
                        return@thread
                    }
                    val link = BleLink(app, d) { running.get() }
                    ble = link
                    val r = link.provision { state(it) }
                    if (r != null && running.get()) {
                        link.onLost = {
                            // Depois que o painel está no hotspot, o Bluetooth só serve para acertar a hora.
                            // Em alguns celulares ele cai sozinho (status 8) com tudo funcionando pelo Wi-Fi:
                            // quem decide se a moto desligou é o Wi-Fi (C2P/P2C/vídeo param em ~12 s).
                            val now = System.currentTimeMillis()
                            // (antes do vídeo: o wakeup/C2P já têm prazos próprios e chamam onSessionLost)
                            if (now - rv.lastPollAt < 5_000 || now - lastP2cRxAt < 6_000 || videoStartAt == 0L)
                                DashLog.d("Bluetooth do painel caiu, mas o Wi-Fi com o painel continua: mantendo a conexão")
                            else { DashLog.d("Bluetooth do painel caiu e o Wi-Fi está parado (moto desligada?)"); onSessionLost() }
                        }
                        sessionGen++
                        sessionStarted.set(true)
                        connecting.set(true)
                        panelAddr = r.panelIp
                        localLinkAddr = r.phoneIp
                        state("Painel entrou no hotspot do celular (${r.panelIp.hostAddress}).")
                        sendWakeup(r.panelIp)
                        return@thread
                    }
                    if (running.get() && (link.found || !everOk())) diag(if (link.found) "erro" else "nao_achou", "Bluetooth: ${link.why}")
                    link.close()
                    if (ble === link) ble = null
                    if (running.get()) Thread.sleep(4000)
                }
            } finally {
                bleBusy.set(false)
            }
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
                    (localLinkAddr ?: localP2pAddress())?.let {
                        s.bind(InetSocketAddress(it, 0))
                        DashLog.d("IP do celular na rede do painel: ${it.hostAddress}")
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
                    if (!ok.equals("true", true)) diag("erro", "o painel respondeu ao wakeup (10930) com status=$ok: ${Pxc.preview(resp.payload, 200)}")
                }
                if (bleMode) reprobeIfSilent(go)
                return
            } catch (e: Exception) {
                if (attempt <= 3 || attempt % 5 == 0) DashLog.d("wakeup tentativa $attempt falhou: ${e.message}")
                Thread.sleep(1000)
            }
        }
        diag("erro", "rede com o painel OK, mas a porta 10930 dele não respondeu em 20 tentativas")
        state("Não consegui falar com o painel na porta 10930. Tentando de novo...")
        onSessionLost()
    }

    /**
     * Visto pelo ZC-Panel na 350R: às vezes o painel aceita o wakeup e não conecta de volta.
     * Se o C2P não abrir em 8 s, manda o wakeup de novo (até 3 vezes).
     */
    private fun reprobeIfSilent(go: InetAddress) {
        val gen = sessionGen
        for (i in 1..3) {
            Thread.sleep(8000)
            if (!running.get() || gen != sessionGen || c2pOpenedGen == gen) return
            DashLog.d("painel não conectou de volta em 8 s; wakeup de novo ($i/3)")
            runCatching {
                Socket().use { s ->
                    localLinkAddr?.let { s.bind(InetSocketAddress(it, 0)) }
                    s.connect(InetSocketAddress(go, Pxc.PORT_WAKEUP), 3000)
                    s.soTimeout = 5000
                    val body = JSONObject().put("phoneType", "Android").put("packageName", app.packageName)
                        .toString().toByteArray()
                    Pxc.write(s.getOutputStream(), Pxc.CMD_WAKEUP, body)
                    DashLog.d("10930 <- " + Pxc.preview(Pxc.read(s.getInputStream()).payload))
                }
            }.onFailure { DashLog.d("wakeup de novo falhou: ${it.message}") }
        }
        if (running.get() && gen == sessionGen && c2pOpenedGen != gen) {
            diag("erro", "o painel aceitou o wakeup, mas não conectou de volta na 10922 em 32 s")
            state("O painel aceitou o app mas não abriu a conexão. Tentando de novo...")
            onSessionLost()
        }
    }

    /** Hora no formato do Carbit, para o 0x10450 (painéis novos zeram o relógio com resposta vazia). */
    private fun timeJson(): ByteArray {
        val now = System.currentTimeMillis()
        val f = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.US)
        val dt = f.format(java.util.Date(now)) + ":%03d".format(java.util.Locale.US, now % 1000)
        return JSONObject().put("time", now).put("dateTime", dt).toString().toByteArray()
    }

    /** Resposta ao 0x10600 (sincronização de hora): mesmo cabeçalho de 16 bytes + "yyyy-MM-dd HH:mm:ss.SSS000000". */
    private fun timeSyncReply(req: ByteArray): ByteArray {
        val out = ByteArray(45)
        if (req.size >= 16) System.arraycopy(req, 0, out, 0, 16)
        val bike = if (req.size > 16) String(req, 16, minOf(29, req.size - 16), Charsets.US_ASCII).trimEnd('\u0000', ' ') else ""
        val stamp = if ((bike.take(4).toIntOrNull() ?: 0) in 2020..2099) bike
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + "000000"
        val a = stamp.toByteArray(Charsets.US_ASCII)
        System.arraycopy(a, 0, out, 16, minOf(29, a.size))
        return out
    }

    // ------------------------------------------------------------------ diagnóstico automático

    /** Sessão em que o vídeo já rodou (e o "deu certo" já foi avisado). */
    @Volatile private var okGen = -1
    /** Última resposta do painel no P2C (sinal de vida do painel novo). */
    @Volatile private var lastP2cRxAt = 0L
    /** Quando o painel abriu o vídeo nesta sessão (0 = ainda não). */
    @Volatile private var videoStartAt = 0L

    private fun everOk() = app.getSharedPreferences("report", Context.MODE_PRIVATE).getBoolean("everOk", false)

    private fun diag(kind: String, step: String) = runCatching { br.com.motonav.Report.connection(app, kind, step) }

    /** 15 s depois de o painel abrir o vídeo: se continua aberto, a conexão deu certo. */
    private fun checkVideoOk() {
        val gen = sessionGen
        main.postDelayed({
            if (running.get() && gen == sessionGen && okGen != gen && videoConns.get() > 0) {
                okGen = gen
                app.getSharedPreferences("report", Context.MODE_PRIVATE).edit().putBoolean("everOk", true).apply()
                diag("ok", "vídeo do mapa rodando no painel há 15 s (" + (if (bleMode) "Bluetooth + hotspot" else "Wi-Fi Direct") + ")")
            }
        }, 15_000)
    }

    /** Painéis novos fecham a sessão se o celular não mandar sinal de vida nas conexões 10922. */
    private fun startPhoneHeartbeat(s: Socket) {
        val out = s.getOutputStream()
        thread(name = "dash-hb-${s.port}") {
            try {
                while (running.get() && !s.isClosed) {
                    Thread.sleep(2000)
                    Pxc.write(out, Pxc.CMD_KEEPALIVE)
                }
            } catch (_: Exception) { }
        }
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
            if (videoStartAt == 0L) videoStartAt = SystemClock.elapsedRealtime()
            try {
                state("Painel pediu o vídeo (10920). Enviando o mapa...")
                br.com.motonav.Compat.onConnected(app) // 1ª vez com este painel: avisa a lista de compatíveis
                checkVideoOk()
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

    /** Endereço do painel na sessão atual (dono do grupo Wi-Fi Direct); null = ninguém pode conectar. */
    @Volatile private var panelAddr: InetAddress? = null

    /** A conexão veio do painel, pela interface do Wi-Fi Direct? */
    private fun fromPanel(s: Socket): Boolean {
        val expected = panelAddr ?: return false
        if (s.inetAddress != expected) return false
        // chegou pela interface p2p (se o aparelho não tiver uma com nome "p2p", vale só o endereço)
        val local = localLinkAddr ?: localP2pAddress() ?: return true
        return s.localAddress == local
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
                    // Segurança: o servidor escuta em todas as redes, mas só o painel (pelo Wi-Fi
                    // Direct) pode conversar. Sem isso, alguém no mesmo Wi-Fi do celular poderia
                    // pedir o vídeo do mapa (a localização ao vivo) ou mandar comandos falsos.
                    if (!fromPanel(s)) {
                        DashLog.d("porta $port: conexão recusada de ${s.inetAddress.hostAddress} (não é o painel)")
                        runCatching { s.close() }
                        continue
                    }
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
        if (bleMode) startPhoneHeartbeat(s)
        when (first.cmd) {
            Pxc.CONN_C2P -> {
                c2pOpenedGen = sessionGen
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
        var silentLogged = false
        while (running.get()) {
            val p = try {
                Pxc.read(input)
            } catch (e: java.net.SocketTimeoutException) {
                // Painel novo (teste de 10/11 na 350R): parou de mandar sinal de vida no C2P ~3 s
                // depois do vídeo, mas continuou pedindo quadros. Enquanto o vídeo andar, ele está vivo.
                // (testes de 10/11: as duas quedas foram exatamente 12 s depois do último sinal no C2P)
                // Também vale a resposta do painel ao nosso sinal de vida no P2C (a cada 2 s): assim o
                // piloto pode sair da projeção pelo guidão sem a conexão cair.
                val now = System.currentTimeMillis()
                if (bleMode && (now - rv.lastPollAt < 5_000 || now - lastP2cRxAt < 6_000)) {
                    if (!silentLogged) { DashLog.d("C2P mudo há 12 s, mas o painel continua respondendo (vídeo/P2C): mantendo a conexão"); silentLogged = true }
                    continue
                }
                throw e
            }
            val c = p.cmd
            // 0x104A0 traz uma conta de atualização do fabricante: não vai para o log (nem para relatórios)
            DashLog.d("C2P <- ${Pxc.hex(c)} " + if (c == 0x000104A0) "(dados de atualização do fabricante, ocultos)" else Pxc.preview(p.payload))
            if (c % 2 != 0) continue // o Carbit descarta comandos ímpares
            when {
                c == Pxc.CMD_CLIENT_INFO -> {
                    val hu = runCatching { JSONObject(p.text()) }.getOrDefault(JSONObject())
                    huid = hu.optString("HUID", "")
                    br.com.motonav.Compat.onPanelInfo(app, hu.optString("HUName"), hu.optString("channel"), huid)
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
                // painéis novos: a hora (resposta vazia zera o relógio da moto)
                bleMode && c == 0x00010450 -> Pxc.write(out, c + 1, timeJson())
                bleMode && c == 0x00010600 -> Pxc.write(out, c + 1, timeSyncReply(p.payload))
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
        // Painéis novos: o ZC-Panel (que funciona neles) não manda o 0x20020, só o sinal de vida.
        if (!bleMode) thread(name = "dash-p2c-send") {
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
            lastP2cRxAt = System.currentTimeMillis()
            // as respostas ao 0x20020 (ou ao nosso sinal de vida) chegam toda hora; registra só a cada 10
            val routine = p.cmd == Pxc.CMD_P2C_DISPLAY + 1 || p.cmd == Pxc.CMD_KEEPALIVE + 1
            if (!routine || lastLogged++ % 10 == 0) {
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
            .apply { if (bleMode) put("supportSyncCorrectTime", false) } // senão alguns painéis zeram o relógio
    }
}
