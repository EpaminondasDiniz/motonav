package br.com.motonav.dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Painéis novos (Zontes 2025+): o celular não usa Wi-Fi Direct. A sequência é
 *  1. acha o painel no Bluetooth LE (nome "EC" + 8 letras, ou serviço b360) e conecta;
 *  2. CLIENT_INFO (0x30) e BUILD_NET (0x50) até o painel responder status=2 ("crie a rede");
 *  3. o celular cria um hotspot local (LocalOnlyHotspot, sem compartilhar a internet);
 *  4. manda nome/senha/IP do hotspot (0x52); o painel entra e conta o IP dele (0x53); 0x51 fecha.
 * Daí em diante é igual ao Wi-Fi Direct: wakeup na 10930 e o painel conecta de volta na 10922/21/20.
 * O Bluetooth continua conectado durante a sessão (o painel pede a hora por ele).
 *
 * Sequência aprendida com o projeto aberto ZC-Panel (testado na 350R 2026); código próprio.
 */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val dash: DashPairing.Dash, private val alive: () -> Boolean) {

    class Result(val panelIp: InetAddress, val phoneIp: InetAddress)

    companion object {
        val SERVICE: UUID = UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        val SERIAL: UUID = UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 20_000L
        private const val NET_INFO_MS = 40_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var serial: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var closed = false
    private val frames = LinkedBlockingQueue<EcBtp.Frame>()
    private val assembler = EcBtp.Assembler()
    private val events = LinkedBlockingQueue<String>() // "conn:<ok>", "mtu", "svc", "desc", "write"
    private val writeLock = Object()

    /** Chamado se o Bluetooth cair depois de provisionado (moto desligada). */
    @Volatile var onLost: (() -> Unit)? = null
    @Volatile private var provisioned = false

    /** Onde parou na última tentativa (vai no diagnóstico automático). */
    @Volatile var why = ""
    /** O painel apareceu no Bluetooth? */
    @Volatile var found = false

    /** Se a busca foi interrompida (Desconectar, app reiniciado), não registra motivo nenhum:
     *  antes saía "não contou o IP em 40 s" na hora, sem ter esperado nada. */
    private fun fail(msg: String): Result? {
        if (!alive() || closed) { why = ""; return null }
        why = msg; DashLog.d("BLE: $msg"); return null
    }

    /** Quando o Bluetooth caiu (0 = conectado). */
    @Volatile private var lostAt = 0L
    @Volatile private var lostStatus = 0

    /** Faz tudo; devolve os IPs ou null (o motivo vai para o log/estado e em [why]). */
    fun provision(state: (String) -> Unit): Result? {
        val adapter = bt?.adapter
        if (adapter == null || !adapter.isEnabled) { state("Ligue o Bluetooth do celular (a sua moto conecta por ele)."); return fail("Bluetooth do celular desligado") }
        state("Procurando a moto no Bluetooth (ligue a moto e abra no painel a tela do QR)...")
        val dev = findDevice() ?: return fail("painel ${dash.bleName ?: ""} não apareceu no Bluetooth em ${SCAN_MS / 1000} s")
        found = true
        if (!alive()) return null
        state("Moto encontrada no Bluetooth. Conectando...")
        if (!connect(dev)) return fail("achou o painel, mas a conexão Bluetooth (GATT/b362) falhou")
        if (!handshake()) return fail(if (why.isNotEmpty()) why else "painel não respondeu status=2 ao BUILD_NET")
        if (!alive()) return null
        state("Criando o hotspot do celular para o painel...")
        val ap = startHotspot() ?: return fail("o Android não criou o hotspot ($hotspotErr)")
        val phoneIp = hostedAddress() ?: return fail("hotspot criado, mas a interface ficou sem IP")
        DashLog.d("hotspot ativo: \"${ap.ssid}\" (${ap.auth}), celular em ${phoneIp.hostAddress}")
        val apFrame = EcBtp.apInfo(ap.ssid, ap.pwd, ap.auth, phoneIp.hostAddress ?: "")
            ?: return fail("nome/senha do hotspot grandes demais para o quadro BLE")
        frames.clear()
        state("Passando o hotspot para o painel (leva uns 15 s, aguarde)...")
        if (!write(apFrame, "AP_INFO (hotspot, senha oculta)")) return fail("falhou ao mandar o hotspot (0x52) pelo Bluetooth")
        val sentAt = System.currentTimeMillis()
        val ip = waitPanelIp(phoneIp)
            ?: return fail("hotspot enviado (${ap.auth}), mas o painel não entrou nele em ${NET_INFO_MS / 1000} s" +
                (if (lostAt > 0) " (o Bluetooth caiu com status $lostStatus ${(lostAt - sentAt) / 1000} s depois do hotspot e o painel também não apareceu na rede)"
                else " (nem contou o IP pelo Bluetooth, nem apareceu na rede)"))
        if (gatt != null && lostAt == 0L) {
            write(EcBtp.build(EcBtp.BUILD_NET_FINISH), "BUILD_NET_FINISH")
            write(EcBtp.syncTime(), "hora (0x01)") // o relógio do painel conta desde a ignição se ninguém acertar
            startTimeResponder()
        }
        provisioned = true
        return Result(ip, phoneIp)
    }

    /**
     * Espera o painel entrar no hotspot. Dois caminhos ao mesmo tempo:
     *  - o 0x53 pelo Bluetooth (o painel conta o IP que recebeu);
     *  - procurar o painel na rede do hotspot (porta 10930 nos 254 endereços).
     * O segundo existe por causa do celular do 4º/5º log (Xiaomi 22101320G, Android 14): o Bluetooth
     * cai (status 8, sinal perdido) uns 8 s depois do hotspot, justo quando o painel troca o rádio para
     * entrar no Wi-Fi, e o 0x53 nunca chegava. O hotspot é só do app, então quem aparece nele é o painel.
     */
    private fun waitPanelIp(phoneIp: InetAddress): InetAddress? {
        val found = java.util.concurrent.atomic.AtomicReference<InetAddress?>(null)
        val until = System.currentTimeMillis() + NET_INFO_MS
        val prober = Thread({
            Thread.sleep(2500) // o painel leva ~10 s para entrar; antes disso não há ninguém
            var round = 0
            while (found.get() == null && alive() && !closed && System.currentTimeMillis() < until) {
                round++
                probeSubnet(phoneIp, until)?.let { if (found.compareAndSet(null, it)) DashLog.d("hotspot: painel achado na rede em ${it.hostAddress} (busca $round)") }
                if (found.get() == null) Thread.sleep(1000)
            }
        }, "dash-hotspot-probe").apply { isDaemon = true }
        prober.start()
        var lostLogged = false
        try {
            while (alive() && !closed && System.currentTimeMillis() < until) {
                found.get()?.let {
                    if (lostAt > 0) DashLog.d("BLE: o Bluetooth caiu, mas o painel entrou no hotspot: seguindo pelo Wi-Fi")
                    return it
                }
                if (lostAt > 0 && !lostLogged) {
                    lostLogged = true
                    DashLog.d("BLE: Bluetooth caiu depois do hotspot; continuo procurando o painel na rede do hotspot")
                }
                val f = frames.poll(300, TimeUnit.MILLISECONDS) ?: continue
                if (answerTime(f)) continue
                if (f.cmd != EcBtp.CAR_NET_INFO) { DashLog.d("BLE <- $f"); continue }
                val ip = EcBtp.ips(f).firstOrNull()
                if (ip == null) { DashLog.d("BLE: 0x53 sem IP: $f"); continue }
                DashLog.d("BLE <- painel entrou no hotspot com IP $ip")
                found.compareAndSet(null, InetAddress.getByName(ip))
                return found.get()
            }
            return found.get()
        } finally {
            found.compareAndSet(null, phoneIp) // só para o prober parar
        }
    }

    /**
     * Procura o painel na rede do hotspot: tenta a porta 10930 em todos os endereços da /24 do
     * celular, 64 de cada vez. "Conexão recusada" também vale: o aparelho existe e só ainda não
     * abriu a porta (o wakeup tenta de novo por 20 s).
     */
    private fun probeSubnet(phoneIp: InetAddress, until: Long): InetAddress? {
        val b = phoneIp.address
        if (b.size != 4) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        val hit = java.util.concurrent.atomic.AtomicReference<InetAddress?>(null)
        try {
            val jobs = (1..254).filter { it != (b[3].toInt() and 0xFF) }.map { host ->
                pool.submit {
                    if (hit.get() != null || closed || System.currentTimeMillis() > until) return@submit
                    val addr = InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], host.toByte()))
                    try {
                        java.net.Socket().use { s ->
                            s.bind(java.net.InetSocketAddress(phoneIp, 0))
                            s.connect(java.net.InetSocketAddress(addr, Pxc.PORT_WAKEUP), 800)
                        }
                        hit.compareAndSet(null, addr)
                    } catch (e: java.net.ConnectException) {
                        // ECONNREFUSED = alguém respondeu; sem rota/timeout = ninguém nesse endereço
                        if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", true) == true)
                            hit.compareAndSet(null, addr)
                    } catch (_: Exception) {}
                }
            }
            jobs.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        return hit.get()
    }

    fun close() {
        if (closed) return
        closed = true
        provisioned = false
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        reservation?.let { runCatching { it.close() } }
        reservation = null
        DashLog.d("BLE: Bluetooth e hotspot encerrados")
    }

    // ------------------------------------------------------------------ busca

    private fun findDevice(): BluetoothDevice? {
        val scanner = bt?.adapter?.bluetoothLeScanner ?: return null
        val exact = dash.bleMac?.uppercase()
        val name = dash.bleName?.uppercase()
        fun isPanel(d: BluetoothDevice) = (exact != null && d.address.equals(exact, true)) ||
            (name != null && runCatching { d.name }.getOrNull()?.uppercase() == name)
        // Um painel BLE para de se anunciar quando já tem alguém conectado. Se for este celular
        // (pareado nas configurações do Android, ou outro app segurando a conexão), a busca não o vê.
        runCatching { bt?.getConnectedDevices(BluetoothProfile.GATT)?.firstOrNull { isPanel(it) } }.getOrNull()?.let {
            DashLog.d("BLE: o painel já está conectado a este celular (pelo sistema ou outro app): usando essa conexão")
            return it
        }
        runCatching { bt?.adapter?.bondedDevices?.firstOrNull { isPanel(it) } }.getOrNull()?.let {
            DashLog.d("BLE: o painel está PAREADO nas configurações de Bluetooth do Android (não precisa; se não conectar, remova esse pareamento)")
        }
        var best: ScanResult? = null
        var match: BluetoothDevice? = null
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val done = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                seen.add(r.device.address)
                val n = (r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull())?.uppercase()
                val uuids = r.scanRecord?.serviceUuids?.joinToString(" ") { it.uuid.toString() }.orEmpty().lowercase()
                if ((exact != null && r.device.address.equals(exact, true)) || (name != null && n == name)) {
                    if (match == null) DashLog.d("BLE: painel encontrado $n (rssi ${r.rssi})")
                    match = r.device; done.countDown(); return
                }
                val looks = (n?.matches(Regex("EC[0-9A-F]{8}")) == true) || uuids.contains("0000b360") || uuids.contains("00006967")
                if (looks && (best == null || r.rssi > best!!.rssi)) {
                    if (best?.device?.address != r.device.address) DashLog.d("BLE: painel parecido $n (rssi ${r.rssi})")
                    best = r
                }
            }
            override fun onScanFailed(errorCode: Int) { DashLog.d("BLE: busca falhou ($errorCode)"); done.countDown() }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, cb) }.onFailure { DashLog.d("BLE: ${it.message}"); return null }
        // Busca com filtro ao mesmo tempo: alguns Android (Xiaomi/HyperOS) cortam a busca sem filtro
        // com o app em segundo plano ou a tela apagada; com filtro ela continua.
        val filters = buildList {
            exact?.let { runCatching { add(android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(it).build()) } }
            dash.bleName?.let { add(android.bluetooth.le.ScanFilter.Builder().setDeviceName(it).build()) }
            add(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE)).build())
        }
        // outro objeto de retorno: o Android recusa o mesmo callback em duas buscas
        val cb2 = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = cb.onScanResult(type, r)
            override fun onScanFailed(errorCode: Int) { DashLog.d("BLE: busca com filtro falhou ($errorCode)") }
        }
        val filtered = runCatching { scanner.startScan(filters, settings, cb2) }.isSuccess
        val deadline = System.currentTimeMillis() + SCAN_MS
        while (alive() && System.currentTimeMillis() < deadline && !done.await(250, TimeUnit.MILLISECONDS)) {
            // sem nome/endereço conhecido, o 1º painel "parecido" serve depois de 5 s de busca
            if (exact == null && name == null && best != null && System.currentTimeMillis() > deadline - SCAN_MS + 5_000) break
        }
        runCatching { scanner.stopScan(cb) }
        if (filtered) runCatching { scanner.stopScan(cb2) }
        match?.let { return it }
        if (exact == null && name == null) return best?.device
        best?.let { DashLog.d("BLE: o painel do QR (${dash.bleName}) não apareceu; há outro painel por perto (${it.scanRecord?.deviceName})") }
        if (alive()) DashLog.d("BLE: o painel não se anunciou em ${SCAN_MS / 1000} s; a busca viu ${seen.size} aparelhos Bluetooth" +
            (if (seen.isEmpty()) " (a busca do celular não está funcionando: localização desligada?)"
            else " (o painel pode estar conectado a outro celular: o do garupa, outro app ou um celular antigo pareado)") +
            ". Tentando conectar direto pelo endereço do QR")
        // com o endereço Bluetooth do QR dá para tentar conectar direto
        return exact?.let { runCatching { bt?.adapter?.getRemoteDevice(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ GATT

    private fun connect(dev: BluetoothDevice): Boolean {
        for (attempt in 1..4) {
            if (!alive()) return false
            events.clear()
            serial = null
            lostAt = 0L
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            val ev = events.poll(15, TimeUnit.SECONDS)
            var was133 = ev?.endsWith(":133") == true
            if (ev == "conn:true") {
                // alguns celulares (Xiaomi, Android 14, log de 10/11) se perdem se pedirem MTU e
                // serviços logo no instante da conexão: espera um pouco
                Thread.sleep(600)
                // MTU maior: o quadro do hotspot (~120 bytes) cabe numa escrita só
                gatt?.requestMtu(247)
                waitEvent("mtu", 3_000)
                // intervalo curto entre os pacotes de rádio: o link aguenta melhor o painel
                // dividindo o rádio com o Wi-Fi enquanto entra no hotspot
                runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (discover(attempt)) return enableNotify()
            } else DashLog.d("BLE: conexão falhou (tentativa $attempt, $ev)")
            // o Android às vezes falha a conexão (status 133); fechar, esperar e tentar de novo resolve
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            gatt = null
            if (events.any { it.endsWith(":133") }) was133 = true
            Thread.sleep(if (was133) 2500 else 1200)
        }
        return false
    }

    /**
     * Lista os serviços e acha a b362. Se o Android devolver a lista sem o serviço do painel
     * (cópia antiga guardada no celular), limpa essa cópia (BluetoothGatt.refresh, como fazem outros
     * apps de BLE) e pede de novo; se ele nem responder, pede de novo uma vez.
     */
    private fun discover(attempt: Int): Boolean {
        for (round in 1..3) {
            val g = gatt ?: return false
            svcInfo = ""
            if (!g.discoverServices()) DashLog.d("BLE: discoverServices recusado")
            val came = waitEvent("svc", 10_000)
            if (serial != null) return true
            if (!came) {
                DashLog.d("BLE: o celular não listou os serviços em 10 s (tentativa $attempt.$round)")
                if (round >= 2) return false
                continue
            }
            DashLog.d("BLE: serviço b360 não encontrado (tentativa $attempt.$round). Serviços vistos: ${svcInfo.ifEmpty { "nenhum" }}")
            val refreshed = runCatching { g.javaClass.getMethod("refresh").invoke(g) as? Boolean }.getOrNull()
            DashLog.d("BLE: limpando a lista de serviços guardada no celular (refresh=$refreshed) e pedindo de novo")
            Thread.sleep(800)
        }
        return false
    }

    @Volatile private var svcInfo = ""

    private fun enableNotify(): Boolean {
        val g = gatt ?: return false
        val c = serial ?: return false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: run { DashLog.d("BLE: b362 sem CCCD"); return false }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        events.clear()
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
        if (!ok || !waitEvent("desc", 5_000)) { DashLog.d("BLE: não consegui ativar as notificações"); return false }
        DashLog.d("BLE: conectado (MTU $mtu), notificações ativas")
        return true
    }

    private fun handshake(): Boolean {
        frames.clear()
        write(EcBtp.clientInfo("MotoNav"), "CLIENT_INFO")
        val hs = waitFrame(4_000) { it.cmd == EcBtp.CLIENT_INFO || it.cmd == EcBtp.HANDSHAKE_RESPONSE }
        if (hs == null) DashLog.d("BLE: painel não respondeu ao CLIENT_INFO; seguindo")
        write(EcBtp.syncTime(), "hora (0x01)")
        // status -1 = painel ainda "pensando"; o ZC-Panel repete até 6x a cada 1 s
        for (attempt in 1..6) {
            if (!alive()) return false
            if (!write(EcBtp.build(EcBtp.BUILD_NET), "BUILD_NET")) return false
            val r = waitFrame(4_000) { it.cmd == EcBtp.BUILD_NET }
            val status = r?.field("status")?.toIntOrNull()
            DashLog.d("BLE: BUILD_NET tentativa $attempt: status=$status")
            when (status) {
                2 -> return true
                -1, null -> Thread.sleep(1000)
                else -> {
                    why = "BUILD_NET status=$status (só o 2 = hotspot do celular é suportado): ${r?.text?.take(150)}"
                    return false
                }
            }
        }
        return false
    }

    private fun write(frame: ByteArray, label: String): Boolean = synchronized(writeLock) {
        val g = gatt ?: return false
        val c = serial ?: return false
        val canNoResp = frame.size <= mtu - 3 && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (canNoResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        events.remove("write")
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, frame, type) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { c.writeType = type; c.value = frame; g.writeCharacteristic(c) }
        DashLog.d("BLE -> $label (${frame.size} bytes)" + if (ok) "" else " FALHOU")
        if (ok && type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) waitEvent("write", 3_000)
        ok
    }

    private fun waitEvent(name: String, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return false
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: return false
            if (e == name) return true
            if (e.startsWith("conn:false")) return false
        }
    }

    private fun waitFrame(ms: Long, match: (EcBtp.Frame) -> Boolean): EcBtp.Frame? {
        val until = System.currentTimeMillis() + ms
        while (alive()) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return null
            val f = frames.poll(minOf(left, 500), TimeUnit.MILLISECONDS) ?: continue
            if (answerTime(f)) continue
            if (match(f)) return f
            DashLog.d("BLE <- $f")
        }
        return null
    }

    /** O painel pode pedir a hora a qualquer momento (0x01 / 0x55). */
    private fun answerTime(f: EcBtp.Frame): Boolean = when (f.cmd) {
        EcBtp.SYNC_TIME -> { write(EcBtp.syncTime(), "hora (0x01)"); true }
        EcBtp.QUERY_TIME -> { write(EcBtp.queryTime(), "hora em texto (0x55)"); true }
        else -> false
    }

    /** Depois de provisionado: continua respondendo à hora e registra o resto. */
    private fun startTimeResponder() {
        Thread({
            while (!closed) {
                val f = runCatching { frames.poll(1, TimeUnit.SECONDS) }.getOrNull() ?: continue
                if (!answerTime(f)) DashLog.d("BLE <- $f")
            }
        }, "dash-ble-rx").apply { isDaemon = true }.start()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) events.offer("conn:true")
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                DashLog.d("BLE: desconectado (status $status" + (if (status == 8) ", sinal perdido" else "") + ")")
                if (serial != null && lostAt == 0L) { lostAt = System.currentTimeMillis(); lostStatus = status }
                events.offer("conn:false:$status")
                if (provisioned && !closed) onLost?.invoke()
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { mtu = value; events.offer("mtu") }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            serial = g.getService(SERVICE)?.getCharacteristic(SERIAL)
            svcInfo = "status $status: " + g.services.joinToString(" ") {
                val u = it.uuid.toString()
                if (u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u.substring(0, 8)
            }
            events.offer("svc")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.offer("desc") }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.offer("write") }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            assembler.feed(value).forEach { frames.offer(it) }
        }
        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) assembler.feed(c.value ?: return).forEach { frames.offer(it) }
        }
    }

    // ------------------------------------------------------------------ hotspot do celular

    private class Ap(val ssid: String, val pwd: String, val auth: String)
    @Volatile private var hotspotErr = "sem resposta"

    private fun startHotspot(): Ap? {
        for (attempt in 1..2) {
            val latch = CountDownLatch(1)
            var result: Ap? = null
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    if (closed) { res.close(); latch.countDown(); return }
                    reservation = res
                    result = readAp(res)
                    latch.countDown()
                }
                override fun onStopped() { DashLog.d("hotspot encerrado pelo Android") }
                override fun onFailed(reason: Int) {
                    hotspotErr = "código $reason"
                    DashLog.d("hotspot falhou: " + when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "sem canal livre"
                        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "modo incompatível (desligue o roteador/ancoragem do celular)"
                        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "ancoragem bloqueada"
                        else -> "erro $reason"
                    })
                    latch.countDown()
                }
            }
            try {
                wifi.startLocalOnlyHotspot(cb, main)
            } catch (e: Exception) {
                DashLog.d("hotspot recusado: ${e.message}")
                return null
            }
            latch.await(20, TimeUnit.SECONDS)
            result?.let { return it }
            // visto no ZC-Panel: "modo incompatível" na 1ª vez e sucesso logo depois
            Thread.sleep(2500)
        }
        return null
    }

    private fun readAp(res: WifiManager.LocalOnlyHotspotReservation): Ap? {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = res.softApConfiguration
            val ssid = (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.bytes?.toString(Charsets.UTF_8) else @Suppress("DEPRECATION") c.ssid)
                ?.removeSurrounding("\"") ?: return null
            val auth = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> "NONE"
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> { DashLog.d("hotspot só WPA3: o painel pode não aceitar"); "WPA3" }
                else -> "WPA2"
            }
            return Ap(ssid, c.passphrase.orEmpty(), auth)
        }
        @Suppress("DEPRECATION")
        val w = res.wifiConfiguration ?: return null
        @Suppress("DEPRECATION")
        return Ap(w.SSID.removeSurrounding("\""), w.preSharedKey?.removeSurrounding("\"").orEmpty(), "WPA2")
    }

    /** IP do celular na interface do hotspot (não a do Wi-Fi de casa nem a dos dados móveis). */
    private fun hostedAddress(): InetAddress? {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until && alive()) {
            hostedCandidate()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun hostedCandidate(): InetAddress? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val uplinks = cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }
            .flatMap { lp -> lp.linkAddresses.mapNotNull { it.address as? Inet4Address } }.toSet()
        val names = listOf("ap", "swlan", "softap", "wlan1", "wlan2")
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.name.startsWith("p2p") }
            .flatMap { nif -> nif.interfaceAddresses.map { nif to it } }
            .filter { (nif, ia) ->
                val a = ia.address as? Inet4Address ?: return@filter false
                val hosted = names.any { nif.name.lowercase().startsWith(it) }
                a.isSiteLocalAddress && (hosted || a !in uplinks) && ia.networkPrefixLength in 16..30
            }
            .sortedByDescending { (nif, _) -> names.any { nif.name.lowercase().startsWith(it) } }
            .firstOrNull()?.second?.address
    }.getOrNull()
}
