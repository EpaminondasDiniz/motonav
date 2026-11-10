package br.com.motonav

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Button
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import br.com.motonav.dash.DashLink
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashPairing
import br.com.motonav.dash.DashScreen
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MotoNav"
        // Mapa vetorial gratuito, sem chave. Outros estilos: "bright", "positron", "dark".
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private val DEFAULT_POS = LatLng(-15.7939, -47.8828) // Brasília, só até o GPS responder

        /** Bloco rápido / atalho "Conectar moto": procura o painel e volta para segundo plano. */
        const val ACTION_CONNECT = "br.com.motonav.CONNECT"

        private const val SRC_ROUTE = "route-src"
        private const val SRC_DEST = "dest-src"
    }

    private lateinit var mapView: MapView
    private lateinit var etDestination: EditText
    private lateinit var tvStatus: TextView
    private lateinit var fabClear: FloatingActionButton

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val io = Executors.newSingleThreadExecutor()

    // ---- painel da moto (etapa 3a) ----
    private lateinit var dashLink: DashLink
    private lateinit var dashPanel: View
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvDashState: TextView
    @Volatile private var logRefreshPending = false

    private val nearbyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val nearbyOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        if (nearbyOk) dashLink.start()
        else DashLog.d("Sem permissão de dispositivos Wi-Fi próximos: não dá para achar o painel.")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) enableLocation()
        else setStatus("Sem permissão de localização: a rota precisa do GPS.")
    }

    /** Permissões pedidas pelo assistente (localização, Wi-Fi próximos, notificações) de uma vez. */
    private val wizardPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableLocation()
        }
        if (::wizard.isInitialized) wizard.refresh()
    }

    private lateinit var wizard: SetupWizard

    private fun setupWizard() {
        wizard = SetupWizard(this, findViewById(R.id.setupScreen), object : SetupWizard.Actions {
            override fun requestPermissions() {
                val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) {
                    list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    list.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                wizardPermLauncher.launch(list.toTypedArray())
            }
            override fun scanQr() = this@MainActivity.scanQr()
            override fun connect() = connectDash()
            override fun connectionText(): String = dashStatus.title
            override fun connected(): Boolean = dashStatus == DashStatus.ONLINE
            override fun finished() { showScreen(Screen.HOME); checkUpdates(force = false) }
        })
        // primeira vez: assistente. Quem já tem painel pareado (já usava o app) não precisa.
        if (!SetupWizard.isDone(this)) {
            if (DashPairing.load(this) != null) SetupWizard.markDone(this) else wizard.show()
        }
    }

    // ---------- Atualização e relatório ----------

    /** Procura versão nova no GitHub (1x por dia, ou na hora com [force]). */
    private fun checkUpdates(force: Boolean) {
        if (!Updater.enabled) { if (force) toast("Esta versão não procura atualizações"); return }
        if (force) toast("Procurando atualização…")
        io.execute {
            val r = runCatching { Updater.check(this, force) }.getOrNull()
            runOnUiThread {
                if (r == null) { if (force) toast("Você já está na versão mais nova (${BuildConfig.VERSION_NAME})"); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle("Versão nova: ${r.version}")
                    .setMessage((r.notes.ifBlank { "Correções e melhorias." }).take(1200) +
                        "\n\nVocê está na ${BuildConfig.VERSION_NAME}. Ao baixar, abra o arquivo e toque em Instalar (os seus dados continuam).")
                    .setPositiveButton("Baixar") { _, _ ->
                        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r.apkUrl ?: r.pageUrl))) }
                    }
                    .setNeutralButton("Pular esta") { _, _ -> Updater.skip(this, r.version) }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }
    }

    /** Relatório (aparelho, permissões, bateria e log) para mandar pelo WhatsApp a quem dá suporte. */
    private fun sendReport() {
        val f = runCatching { Report.writeFile(this) }.getOrElse { toast("Falha ao gerar o relatório"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_TEXT, "Relatório do app de navegação (${Build.MANUFACTURER} ${Build.MODEL}, versão ${BuildConfig.VERSION_NAME})")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(send, "Enviar relatório"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        etDestination = findViewById(R.id.etDestination)
        tvStatus = findViewById(R.id.tvStatus)
        fabClear = findViewById(R.id.fabClear)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
        val fabRecenter = findViewById<FloatingActionButton>(R.id.fabRecenter)

        applyInsets()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(DEFAULT_POS).zoom(13.0).build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                style = s
                addRouteLayers(s)      // antes do GPS, para a bolinha de posição ficar por cima
                requestLocationPermission()
            }
            m.addOnMapClickListener { hideSuggestions(); etDestination.clearFocus(); hideKeyboard(); false }
            m.addOnMapLongClickListener { point ->
                routeTo(point, "Ponto escolhido no mapa")
                true
            }
        }

        btnSearch.setOnClickListener { searchAddress() }
        setupSearchSuggestions()
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchAddress(); true } else false
        }
        fabRecenter.setOnClickListener { recenter() }
        fabClear.setOnClickListener { clearRoute() }
        findViewById<FloatingActionButton>(R.id.fabRoutes).setOnClickListener { showRoutesDialog() }
        setupScreenToggle()
        if (BuildConfig.DEBUG) io.execute { saveRacePreviewImage() }

        // Rotas salvas e mapas offline ("minha região" é baixada pelo botão ★ Minhas rotas)
        Net.start(this)
        SavedRoutes.init(this)
        OfflineMaps.onChange = { runOnUiThread { showDownloadProgress() } }
        OfflineMaps.init(this, autoIsland = false)

        LocationHub.initData(this)
        setupDashPanel()
        setupWizard()
        if (!wizard.showing) {
            Garage.dueText()?.let { toast("🔧 $it") }
            checkUpdates(force = false)
        }
        handleConnectIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleConnectIntent(intent)
    }

    private fun handleConnectIntent(i: android.content.Intent?) {
        if (i?.action != ACTION_CONNECT) return
        i.action = null
        if (DashPairing.load(this) == null) { toast("Pareie o painel primeiro (QR)"); return }
        val missing = missingDashPermissions()
        if (missing.isNotEmpty()) { nearbyLauncher.launch(missing.toTypedArray()); return }
        if (!dashLink.isRunning) dashLink.start()
        toast("Procurando a moto…")
        // a busca continua no serviço: o app volta para segundo plano
        mapView.postDelayed({ moveTaskToBack(true) }, 700)
    }

    // ---------- Painel da moto: parear, conectar, ver o log ----------

    private fun setupDashPanel() {
        dashLink = DashLink.get(this)
        dashPanel = findViewById(R.id.dashPanel)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        tvDashState = findViewById(R.id.tvDashState)

        DashLog.listener = {
            if (!logRefreshPending) {
                logRefreshPending = true
                tvLog.postDelayed({
                    logRefreshPending = false
                    tvLog.text = DashLog.text()
                    svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
                }, 200)
            }
        }
        dashLink.onStateChanged = { st ->
            tvDashState.text = st
            dashStatus = statusFrom(st)
            refreshDashChip(st)
        }
        refreshPairedLabel()
        if (!dashLink.isRunning && DashPairing.load(this) != null && missingDashPermissions().isEmpty()) {
            dashLink.start() // conexão automática: já procura o painel ao abrir o app
        }
        dashStatus = when {
            DashPairing.load(this) == null -> DashStatus.UNPAIRED
            dashLink.isRunning -> DashStatus.SEARCHING
            else -> DashStatus.OFF
        }
        refreshDashChip(null)

        // selo da moto: toque abre a tela "Moto"; segurar abre direto o diagnóstico
        val chip = findViewById<TextView>(R.id.tvDashChip)
        chip.setOnClickListener { showMotoSheet() }
        chip.setOnLongClickListener { toggleDiagnostics(); true }
        findViewById<FloatingActionButton>(R.id.fabDash).setOnClickListener { toggleDiagnostics() }
        findViewById<ImageButton>(R.id.btnClosePanel).setOnClickListener { dashPanel.visibility = View.GONE }
        findViewById<Button>(R.id.btnPair).setOnClickListener { scanQr() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectDash() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { dashLink.stop() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MotoNav log", DashLog.text()))
            toast("Log copiado. Cole no chat.")
        }
    }

    // ---------- Tela "Moto": conexão, nome do piloto, animação de entrada ----------

    private enum class DashStatus(val chip: String, val title: String) {
        UNPAIRED("🏍 Parear", "Moto não pareada"),
        OFF("⚪ Desligada", "Moto desconectada"),
        SEARCHING("🔍 Procurando…", "Procurando a moto…"),
        CONNECTING("🟡 Conectando…", "Conectando ao painel…"),
        ONLINE("🟢 No painel", "Conectado ao painel")
    }

    private var dashStatus = DashStatus.OFF
    private var motoSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null
    private var sheetRefresh: ((String?) -> Unit)? = null

    private fun statusFrom(st: String): DashStatus = when {
        st.contains("não pareado") -> DashStatus.UNPAIRED
        st.startsWith("Painel pediu o vídeo") -> DashStatus.ONLINE
        st.startsWith("Procurando") || st.startsWith("Conexão com o painel caiu") -> DashStatus.SEARCHING
        st.startsWith("Desconectado") || st.startsWith("Painel não encontrado") ||
            st.startsWith("Ligue o Wi-Fi") -> DashStatus.OFF
        dashStatus == DashStatus.ONLINE -> DashStatus.ONLINE // mensagens de canal com o vídeo já rodando
        else -> DashStatus.CONNECTING
    }

    private fun refreshDashChip(detail: String?) {
        // com o painel conectado, o mapa do celular a 30 fps basta (economiza bateria)
        if (::mapView.isInitialized) mapView.setMaximumFps(if (dashStatus == DashStatus.ONLINE) 30 else 60)
        val chip = "🏍 MOTO · ${dashStatus.chip.substringAfter(' ').removeSuffix("…")}"
        findViewById<TextView>(R.id.tvDashChip).text = chip
        findViewById<TextView>(R.id.raceMoto)?.text = chip
        findViewById<TextView>(R.id.homeConnTitle)?.text = dashStatus.title
        findViewById<TextView>(R.id.homeConnDetail)?.text = when (dashStatus) {
            DashStatus.UNPAIRED -> "Leia o QR do painel uma vez"
            DashStatus.OFF -> "Ligue a moto e toque em Conectar"
            DashStatus.SEARCHING -> "Ligue a moto…"
            DashStatus.CONNECTING -> "Abrindo o vídeo do painel…"
            DashStatus.ONLINE -> "Painel ${DashPairing.load(this)?.name ?: ""} ao vivo"
        }
        findViewById<Button>(R.id.homeConnect)?.text = when (dashStatus) {
            DashStatus.UNPAIRED -> "Parear"
            DashStatus.OFF -> "Conectar"
            else -> "Parar"
        }
        sheetRefresh?.invoke(detail)
    }

    private fun toggleDiagnostics() {
        dashPanel.visibility = if (dashPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        dashPanel.bringToFront() // por cima da tela inicial e do Race
        tvLog.text = DashLog.text()
        backToHome.isEnabled = screen != Screen.HOME || dashPanel.visibility == View.VISIBLE
    }

    private fun showMotoSheet() {
        val v = layoutInflater.inflate(R.layout.sheet_moto, null)
        val state = v.findViewById<TextView>(R.id.sheetState)
        val detail = v.findViewById<TextView>(R.id.sheetDetail)
        val connect = v.findViewById<Button>(R.id.sheetConnect)
        val rider = v.findViewById<EditText>(R.id.sheetRider)
        val intro = v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sheetIntro)

        val refresh: (String?) -> Unit = { st ->
            state.text = dashStatus.title
            val pairing = DashPairing.load(this)
            detail.text = st ?: when (dashStatus) {
                DashStatus.UNPAIRED -> "Leia o QR code do painel uma vez em Diagnóstico e pareamento."
                DashStatus.OFF -> "Painel ${pairing?.name ?: ""}. Ligue a moto e toque em Procurar."
                else -> "Painel ${pairing?.name ?: ""}"
            }
            connect.text = when (dashStatus) {
                DashStatus.UNPAIRED -> "Ler o QR do painel"
                DashStatus.OFF -> "Procurar a moto"
                else -> "Desconectar"
            }
        }
        sheetRefresh = refresh
        refresh(null)

        connect.setOnClickListener {
            when (dashStatus) {
                DashStatus.UNPAIRED -> scanQr()
                DashStatus.OFF -> connectDash()
                else -> { dashLink.stop(); dashStatus = DashStatus.OFF; refreshDashChip(null) }
            }
        }
        rider.setText(DashScreen.riderName)
        intro.isChecked = DashScreen.introEnabled
        intro.setOnCheckedChangeListener { _, on -> DashScreen.setIntro(this, on) }
        v.findViewById<Button>(R.id.sheetPreview).setOnClickListener {
            DashScreen.setRider(this, rider.text.toString())
            showIntroPreview()
        }
        v.findViewById<Button>(R.id.sheetRacePreview).setOnClickListener { showRacePreview() }
        v.findViewById<Button>(R.id.sheetResetTrip).setOnClickListener { TripStats.reset(); toast("Viagem zerada") }
        v.findViewById<Button>(R.id.sheetDiag).setOnClickListener {
            motoSheet?.dismiss()
            toggleDiagnostics()
        }

        // ajuda: relatório, assistente, atualização
        v.findViewById<Button>(R.id.sheetReport).setOnClickListener { motoSheet?.dismiss(); sendReport() }
        v.findViewById<Button>(R.id.sheetWizard).setOnClickListener { motoSheet?.dismiss(); wizard.show() }
        v.findViewById<Button>(R.id.sheetUpdate).apply {
            text = "🔄 Procurar atualização (versão ${BuildConfig.VERSION_NAME})"
            setOnClickListener { motoSheet?.dismiss(); checkUpdates(force = true) }
        }

        // chave TomTom (cada pessoa usa a sua, gratuita)
        v.findViewById<Button>(R.id.sheetTomTom).apply {
            text = "🗝 Chave TomTom: " + when {
                TomTom.hasUserKey -> "digitada no app"
                TomTom.enabled -> "do arquivo local.properties"
                else -> "nenhuma (sem trânsito)"
            }
            setOnClickListener { motoSheet?.dismiss(); tomTomKeyDialog() }
        }

        // mensagens no painel e detecção de queda
        Messages.load(this)
        v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.sheetMessages).apply {
            isChecked = Messages.enabled
            setOnCheckedChangeListener { _, on ->
                Messages.setEnabled(this@MainActivity, on)
                if (on && !MediaInfo.hasPermission(this@MainActivity)) {
                    toast("Ligue o acesso a notificações para o MotoNav")
                    MediaInfo.openPermissionSettings(this@MainActivity)
                }
            }
        }
        v.findViewById<Button>(R.id.sheetCrash).apply {
            CrashDetector.load(this@MainActivity)
            text = if (CrashDetector.ready()) "🆘 Detecção de queda: ligada (${CrashDetector.contactName})" else "🆘 Detecção de queda: desligada"
            setOnClickListener { motoSheet?.dismiss(); crashSettings() }
        }

        // música no painel (precisa do "acesso a notificações")
        v.findViewById<Button>(R.id.sheetMusic).apply {
            val on = MediaInfo.hasPermission(this@MainActivity)
            text = if (on) "🎵 Música no painel: ligada (${MediaInfo.text() ?: "nada tocando"})"
            else "🎵 Mostrar a música (Spotify) no painel"
            setOnClickListener {
                if (MediaInfo.hasPermission(this@MainActivity)) {
                    MediaInfo.start(this@MainActivity)
                    toast(MediaInfo.text() ?: "Ligada. Toque uma música no Spotify.")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Música no painel")
                        .setMessage("Para mostrar a música que está tocando (Spotify, YouTube Music...), o Android " +
                            "pede o \"acesso a notificações\".\n\nNa próxima tela, ligue o MotoNav. O app só lê " +
                            "o nome da música e do artista; não lê suas mensagens.")
                        .setPositiveButton("Abrir configuração") { _, _ -> MediaInfo.openPermissionSettings(this@MainActivity) }
                        .setNegativeButton("Agora não", null).show()
                }
            }
        }

        // combustível
        val fuel = v.findViewById<TextView>(R.id.sheetFuel)
        val showFuel = {
            fuel.text = "Autonomia ≈ ${Garage.rangeKm().toInt().coerceAtLeast(0)} km  ·  tanque ${fmt1(Garage.tankL)} L × ${fmt1(Garage.kmPerL)} km/L"
        }
        showFuel()
        v.findViewById<Button>(R.id.sheetRefuel).setOnClickListener {
            Garage.refuel(); showFuel(); toast("Tanque cheio: ≈ ${Garage.rangeKm().toInt()} km")
        }
        v.findViewById<Button>(R.id.sheetFuelCfg).setOnClickListener { fuelSettings(showFuel) }
        v.findViewById<Button>(R.id.sheetStation).setOnClickListener { motoSheet?.dismiss(); goToStation() }

        // manutenção
        val maint = v.findViewById<TextView>(R.id.sheetMaint)
        val showMaint = { maint.text = maintenanceSummary() }
        showMaint()
        v.findViewById<Button>(R.id.sheetMaintBtn).setOnClickListener { maintenanceDialog(showMaint) }

        // viagens
        v.findViewById<Button>(R.id.sheetTrips).apply {
            text = "📒 Minhas viagens (${TripLog.all().size})"
            setOnClickListener { tripsDialog() }
        }

        // mapa do painel à noite
        val night = v.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.sheetNight)
        night.check(when (NightMode.pref) {
            NightMode.Pref.AUTO -> R.id.nightAuto
            NightMode.Pref.DAY -> R.id.nightDay
            NightMode.Pref.NIGHT -> R.id.nightNight
        })
        night.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            NightMode.setPref(this, when (id) {
                R.id.nightDay -> NightMode.Pref.DAY
                R.id.nightNight -> NightMode.Pref.NIGHT
                else -> NightMode.Pref.AUTO
            })
        }

        motoSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
            setContentView(v)
            setOnDismissListener {
                DashScreen.setRider(this@MainActivity, rider.text.toString())
                sheetRefresh = null
                motoSheet = null
            }
            show()
        }
    }

    private fun fmt1(v: Float) = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale("pt", "BR"), "%.1f", v)

    private fun numberField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun parseNum(s: String): Double? = s.replace(',', '.').trim().toDoubleOrNull()

    private fun formBox(vararg rows: View) = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        rows.forEach { addView(it) }
    }

    private fun label(t: String) = TextView(this).apply { text = t; alpha = 0.7f; setPadding(0, 16, 0, 0) }

    private fun fuelSettings(after: () -> Unit) {
        val tank = numberField("Tanque (litros)", fmt1(Garage.tankL))
        val cons = numberField("Consumo (km por litro)", fmt1(Garage.kmPerL))
        AlertDialog.Builder(this)
            .setTitle("Combustível")
            .setView(formBox(label("Tanque (litros)"), tank, label("Consumo médio (km/L)"), cons))
            .setPositiveButton("Salvar") { _, _ ->
                val t = parseNum(tank.text.toString())?.toFloat()
                val c = parseNum(cons.text.toString())?.toFloat()
                if (t != null && c != null && t in 3f..40f && c in 5f..80f) { Garage.setFuel(t, c); after() }
                else toast("Valores inválidos")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Rota até o posto mais próximo (OpenStreetMap). */
    private fun goToStation() {
        val here = currentPosition() ?: run { toast("Aguardando GPS..."); return }
        if (!Net.online) { toast("Sem internet para procurar postos"); return }
        setStatus("Procurando o posto mais próximo...")
        io.execute {
            val st = runCatching { Garage.nearestStation(here.latitude, here.longitude) }.getOrNull()
            runOnUiThread {
                if (st == null) setStatus("Nenhum posto encontrado a até 8 km.")
                else { showScreen(Screen.MAP); routeTo(st.pos, "⛽ ${st.name}") }
            }
        }
    }

    private fun maintenanceSummary(): String {
        val next = Garage.dueText() ?: Garage.nextText() ?: "sem itens"
        return "Km da moto ≈ ${Garage.fmtKm(Garage.odometer())} km\n$next"
    }

    /** Abre a tela de Manutenção (substitui a janela antiga). */
    @Suppress("UNUSED_PARAMETER")
    private fun maintenanceDialog(after: () -> Unit) {
        motoSheet?.dismiss()
        showScreen(Screen.MAINT)
    }

    // ---------- Diário de viagens ----------

    private fun tripTitle(t: TripLog.Trip): String {
        val df = java.text.SimpleDateFormat("dd/MM  HH:mm", Locale("pt", "BR"))
        return "${df.format(java.util.Date(t.startMs))}  ·  ${String.format(Locale("pt", "BR"), "%.1f", t.distanceM / 1000)} km  ·  " +
            "${t.movingMs / 60_000} min  ·  máx ${t.maxKmh.toInt()}"
    }

    private fun tripsDialog() {
        val trips = TripLog.all()
        if (trips.isEmpty()) { toast("Nenhuma viagem ainda: elas são gravadas sozinhas quando você anda."); return }
        AlertDialog.Builder(this)
            .setTitle("Minhas viagens")
            .setItems(trips.map { tripTitle(it) }.toTypedArray()) { _, which -> showTrip(trips[which]) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Imagem da viagem (mapa com o trajeto + números), pronta para compartilhar. */
    private fun showTrip(t: TripLog.Trip) {
        if (t.points.size < 2) { toast("Trajeto curto demais"); return }
        val img = android.widget.ImageView(this).apply { adjustViewBounds = true }
        var file: java.io.File? = null
        val dlg = AlertDialog.Builder(this)
            .setTitle(tripTitle(t))
            .setView(img)
            .setPositiveButton("Imagem") { _, _ -> file?.let { shareImage(it) } }
            .setNeutralButton("GPX") { _, _ -> shareGpx(t) }
            .setNegativeButton("Excluir") { _, _ ->
                AlertDialog.Builder(this).setMessage("Excluir esta viagem?")
                    .setPositiveButton("Excluir") { _, _ -> TripLog.delete(t.id); toast("Viagem excluída") }
                    .setNegativeButton("Cancelar", null).show()
            }
            .show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        renderTripImage(t) { bmp, f ->
            img.setImageBitmap(bmp)
            file = f
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = f != null
        }
    }

    private var tripSnap: org.maplibre.android.snapshotter.MapSnapshotter? = null

    private fun renderTripImage(t: TripLog.Trip, done: (android.graphics.Bitmap, java.io.File?) -> Unit) {
        val W = 1080
        val mapH = 760
        var n = -90.0; var s = 90.0; var e = -180.0; var w = 180.0
        t.points.forEach { n = maxOf(n, it.latitude); s = minOf(s, it.latitude); e = maxOf(e, it.longitude); w = minOf(w, it.longitude) }
        val padLat = ((n - s) * 0.18).coerceAtLeast(0.004)
        val padLon = ((e - w) * 0.18).coerceAtLeast(0.004)
        val bounds = LatLngBounds.from(n + padLat, e + padLon, s - padLat, w - padLon)
        val snap = org.maplibre.android.snapshotter.MapSnapshotter(this,
            org.maplibre.android.snapshotter.MapSnapshotter.Options(W, mapH)
                .withPixelRatio(1f)
                .withStyle(STYLE_URL)
                .withRegion(bounds)
                .withLogo(false))
        tripSnap = snap
        snap.start({ shot ->
            val out = android.graphics.Bitmap.createBitmap(W, 1350, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(android.graphics.Color.rgb(12, 12, 16))
            c.drawBitmap(shot.bitmap, null, android.graphics.Rect(0, 0, W, mapH), null)
            val kx = W.toFloat() / shot.bitmap.width.coerceAtLeast(1) // pixelRatio 1: ponto = pixel
            val path = android.graphics.Path()
            t.points.forEachIndexed { i, p ->
                val q = shot.pixelForLatLng(p)
                if (i == 0) path.moveTo(q.x * kx, q.y * kx) else path.lineTo(q.x * kx, q.y * kx)
            }
            val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeJoin = android.graphics.Paint.Join.ROUND
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            stroke.color = android.graphics.Color.WHITE; stroke.strokeWidth = 16f; c.drawPath(path, stroke)
            stroke.color = android.graphics.Color.rgb(255, 122, 0); stroke.strokeWidth = 10f; c.drawPath(path, stroke)
            drawTripCard(c, t, W, mapH)
            val f = runCatching {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                java.io.File(dir, "viagem_${t.id}.png").also { ff ->
                    ff.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }.getOrNull()
            done(out, f)
        }, { err -> toast("Mapa da viagem falhou: $err") })
    }

    private fun drawTripCard(c: android.graphics.Canvas, t: TripLog.Trip, W: Int, top: Int) {
        val orange = android.graphics.Color.rgb(255, 122, 0)
        val black = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL), 900, true)
        val bold = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = orange
        c.drawRect(0f, top.toFloat(), W.toFloat(), top + 10f, p)
        p.typeface = black; p.textSize = 64f; p.color = android.graphics.Color.WHITE
        c.drawText("MOTO", 60f, top + 100f, p)
        val zw = p.measureText("MOTO")
        p.color = orange; p.textSize = 30f; p.typeface = bold; p.letterSpacing = 0.3f
        c.drawText("NAV", 60f + zw + 16f, top + 100f, p)
        p.letterSpacing = 0f
        val df = java.text.SimpleDateFormat("EEEE, dd 'de' MMMM · HH:mm", Locale("pt", "BR"))
        p.color = android.graphics.Color.rgb(170, 175, 185); p.textSize = 34f
        c.drawText(df.format(java.util.Date(t.startMs)), 60f, top + 150f, p)
        val stats = listOf(
            "DISTÂNCIA" to (String.format(Locale("pt", "BR"), "%.1f", t.distanceM / 1000) to "km"),
            "TEMPO" to ("${t.movingMs / 60_000}" to "min"),
            "MÉDIA" to ("${t.avgKmh.toInt()}" to "km/h"),
            "MÁXIMA" to ("${t.maxKmh.toInt()}" to "km/h")
        )
        val tw = (W - 120f - 3 * 24f) / 4
        for ((i, st) in stats.withIndex()) {
            val x = 60f + i * (tw + 24f)
            val y = top + 200f
            p.color = android.graphics.Color.rgb(28, 30, 38)
            c.drawRoundRect(android.graphics.RectF(x, y, x + tw, y + 300f), 24f, 24f, p)
            p.color = orange
            c.drawRect(x, y, x + tw, y + 8f, p)
            p.textAlign = android.graphics.Paint.Align.CENTER
            p.typeface = bold; p.textSize = 30f; p.color = android.graphics.Color.rgb(150, 155, 165)
            c.drawText(st.first, x + tw / 2, y + 70f, p)
            p.typeface = black; p.textSize = 96f; p.color = android.graphics.Color.WHITE
            c.drawText(st.second.first, x + tw / 2, y + 190f, p)
            p.typeface = bold; p.textSize = 32f; p.color = orange
            c.drawText(st.second.second, x + tw / 2, y + 250f, p)
            p.textAlign = android.graphics.Paint.Align.LEFT
        }
        p.color = android.graphics.Color.rgb(90, 95, 105); p.textSize = 24f; p.typeface = bold
        c.drawText("MotoNav  ·  mapa © OpenStreetMap", 60f, 1350f - 40f, p)
    }

    /** Trajeto em GPX (Strava, Google Earth, Wikiloc...). */
    private fun shareGpx(t: TripLog.Trip) {
        val f = runCatching {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val name = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(java.util.Date(t.startMs))
            java.io.File(dir, "viagem_$name.gpx").apply { writeText(TripLog.gpx(t)) }
        }.getOrElse { toast("Falha ao gerar o GPX: ${it.message}"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(send, "Exportar GPX"))
    }

    private fun tomTomKeyDialog() {
        val f = EditText(this).apply {
            hint = "Cole aqui a sua chave"
            setText(if (TomTom.hasUserKey) TomTom.KEY else "")
            isSingleLine = true
        }
        val explain = TextView(this).apply {
            alpha = 0.75f
            setPadding(0, 0, 0, 12)
            text = "Trânsito, incidentes e rota com trânsito usam a TomTom. A chave é gratuita: crie uma " +
                "conta em developer.tomtom.com e marque Traffic API e Routing API. A chave fica guardada " +
                "só neste celular. Sem chave, o app usa rotas gratuitas sem trânsito."
        }
        AlertDialog.Builder(this)
            .setTitle("Chave TomTom")
            .setView(formBox(explain, f))
            .setPositiveButton("Salvar") { _, _ -> TomTom.setKey(this, f.text.toString()); toast("Chave salva") }
            .setNeutralButton("Apagar") { _, _ -> TomTom.setKey(this, ""); toast("Chave do app apagada") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Detecção de queda ----------

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast("Sem permissão de SMS: o aviso de queda não consegue mandar mensagem")
    }

    private fun crashSettings() {
        CrashDetector.load(this)
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Detectar queda e avisar o contato"
            isChecked = CrashDetector.enabled
        }
        val name = EditText(this).apply { hint = "Nome do contato"; setText(CrashDetector.contactName) }
        val phone = EditText(this).apply {
            hint = "Celular com DDD, ex.: 98 99999-9999"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(CrashDetector.contactPhone)
        }
        val explain = TextView(this).apply {
            alpha = 0.75f
            setPadding(0, 0, 0, 12)
            text = "Funciona com o app aberto ou o painel conectado. Se o celular sentir um impacto forte " +
                "andando e depois a moto ficar parada, toca um alarme por 30 s. Se ninguém tocar em " +
                "\"ESTOU BEM\", o contato recebe um SMS com a sua localização."
        }
        AlertDialog.Builder(this)
            .setTitle("🆘 Detecção de queda")
            .setView(android.widget.ScrollView(this).apply { addView(formBox(explain, sw, label("Contato de emergência"), name, phone)) })
            .setPositiveButton("Salvar") { _, _ ->
                val on = sw.isChecked
                if (on && phone.text.count { it.isDigit() } < 8) { toast("Informe um celular válido"); return@setPositiveButton }
                CrashDetector.save(this, on, name.text.toString(), phone.text.toString())
                if (on && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    smsLauncher.launch(Manifest.permission.SEND_SMS)
                }
                toast(if (on) "Detecção de queda ligada" else "Detecção de queda desligada")
            }
            .setNeutralButton("Testar alarme") { _, _ -> CrashAlert.begin(this, test = true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun shareImage(f: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(send, "Compartilhar viagem"))
    }

    /** A animação de entrada do painel, tocando no celular (por baixo dela, o Race de demonstração). */
    private fun showIntroPreview() {
        val w = 800
        val h = 480
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        var intro = br.com.motonav.dash.IntroDash(w, h, DashScreen.riderName)
        val race = br.com.motonav.dash.RaceDash(w, h, demo = true)
        val img = android.widget.ImageView(this).apply { adjustViewBounds = true; setImageBitmap(bmp) }
        val handler = android.os.Handler(mainLooper)
        var start = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val t = (android.os.SystemClock.uptimeMillis() - start) / 1000f
                if (t < br.com.motonav.dash.IntroDash.DURATION_S) {
                    intro.draw(canvas, t) { c ->
                        if (DashScreen.mode == DashScreen.Mode.RACE) race.draw(c)
                        else { c.drawColor(android.graphics.Color.rgb(222, 226, 214)) }
                    }
                } else if (t < br.com.motonav.dash.IntroDash.DURATION_S + 1.5f) {
                    if (DashScreen.mode == DashScreen.Mode.RACE) race.draw(canvas)
                } else {
                    start = android.os.SystemClock.uptimeMillis() // repete
                    intro = br.com.motonav.dash.IntroDash(w, h, DashScreen.riderName)
                }
                img.invalidate()
                handler.postDelayed(this, 33)
            }
        }
        AlertDialog.Builder(this)
            .setView(img)
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { handler.removeCallbacks(tick) }
            .show()
        handler.post(tick)
    }

    private fun refreshPairedLabel() {
        val d = DashPairing.load(this)
        tvDashState.text = if (d == null) "Nenhum painel pareado. Toque em QR."
        else "Pareado: ${d.name} (${d.mac}). Ligue a moto e toque em Conectar."
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> savePairing(code.rawValue ?: "") }
            .addOnFailureListener { e ->
                DashLog.d("leitor de QR falhou: ${e.message}")
                askQrLink()
            }
    }

    /** Alternativa ao leitor: colar o link do QR. */
    private fun askQrLink() {
        val input = EditText(this).apply { hint = "Cole aqui o link do QR do painel" }
        AlertDialog.Builder(this)
            .setTitle("Parear painel")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ -> savePairing(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun savePairing(raw: String) {
        val d = DashPairing.parseQr(raw)
        if (d == null) {
            toast("Esse QR não é o de conexão do painel. Procure o outro QR na tela da moto.")
            DashLog.d("QR inválido: $raw")
            return
        }
        DashPairing.save(this, d)
        DashLog.d("Painel pareado: ${d.name} ${d.mac}")
        toast("Painel ${d.name} pareado ✓")
        refreshPairedLabel()
        dashStatus = DashStatus.OFF
        refreshDashChip(null)
        if (::wizard.isInitialized) wizard.refresh()
    }

    private fun missingDashPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun connectDash() {
        val missing = missingDashPermissions()
        if (missing.isEmpty()) dashLink.start() else nearbyLauncher.launch(missing.toTypedArray())
    }

    // ---------- Layout: não ficar embaixo da barra de status / navegação ----------

    private fun applyInsets() {
        val top = findViewById<View>(R.id.topPanel)
        val bottom = findViewById<View>(R.id.bottomPanel)
        val baseTop = top.paddingTop
        val homeContent = findViewById<View>(R.id.homeContent)
        val race = findViewById<View>(R.id.raceScreen)
        val homeTop = homeContent.paddingTop
        val homeBottom = homeContent.paddingBottom
        val raceTop = race.paddingTop
        val raceBottom = race.paddingBottom
        val maint = findViewById<View>(R.id.maintScreen)
        val maintTop = maint.paddingTop
        val maintBottom = maint.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.updatePadding(top = baseTop + bars.top)
            homeContent.updatePadding(top = homeTop + bars.top, bottom = homeBottom + bars.bottom)
            race.updatePadding(top = raceTop + bars.top, bottom = raceBottom + bars.bottom)
            maint.updatePadding(top = maintTop + bars.top, bottom = maintBottom + bars.bottom)
            val margin = (16 * resources.displayMetrics.density).toInt()
            for (v in listOf(bottom, findViewById<View>(R.id.fabDash))) {
                (v.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                    it.bottomMargin = bars.bottom + margin
                    v.layoutParams = it
                }
            }
            findViewById<View>(R.id.dashPanel).updatePadding(bottom = bars.bottom + margin / 2)
            findViewById<View>(R.id.setupScreen).updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    // ---------- GPS ----------

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            enableLocation()
        } else if (::wizard.isInitialized && wizard.showing) {
            return // o assistente pede as permissões no passo 2
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val m = map ?: return
        val s = style ?: return
        val lc = m.locationComponent
        val options = LocationComponentActivationOptions.builder(this, s)
            .useDefaultLocationEngine(true)
            .locationEngineRequest(
                LocationEngineRequest.Builder(1000)          // posição a cada 1 s
                    .setFastestInterval(500)
                    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                    .build()
            )
            .build()
        lc.activateLocationComponent(options)
        if (!holdsGps) holdsGps = LocationHub.acquire(this)
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        lc.cameraMode = CameraMode.TRACKING_GPS
        lc.zoomWhileTracking(16.0)
        setStatus("GPS ativo. Digite um destino ou segure o dedo no mapa.")
    }

    @SuppressLint("MissingPermission")
    private fun currentPosition(): LatLng? {
        val m = map ?: return null
        if (!m.locationComponent.isLocationComponentActivated) return null
        val loc = m.locationComponent.lastKnownLocation ?: return null
        return LatLng(loc.latitude, loc.longitude)
    }

    @SuppressLint("MissingPermission")
    private fun recenter() {
        val m = map ?: return
        if (!m.locationComponent.isLocationComponentActivated) {
            requestLocationPermission(); return
        }
        m.locationComponent.cameraMode = CameraMode.TRACKING_GPS
        m.locationComponent.zoomWhileTracking(16.0)
    }

    // ---------- Busca de endereço ----------

    private val suggestExec = Executors.newSingleThreadExecutor()
    private val suggestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var suggestGen = 0
    private var suggestions: List<Places.Place> = emptyList()
    private var ignoreTextChange = false

    /** Sugestões enquanto digita (TomTom + OpenStreetMap) e destinos recentes com o campo vazio. */
    private fun setupSearchSuggestions() {
        etDestination.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (ignoreTextChange) return
                val q = s?.toString()?.trim().orEmpty()
                suggestHandler.removeCallbacksAndMessages(null)
                if (q.length < 3) { showRecents(); return }
                suggestHandler.postDelayed({ fetchSuggestions(q) }, 350)
            }
        })
        // a lista some ao escolher uma sugestão, tocar no mapa ou apagar o texto (não ao perder o foco)
        etDestination.setOnFocusChangeListener { _, has ->
            if (has && etDestination.text.isNullOrBlank()) showRecents()
        }
    }

    private fun fetchSuggestions(q: String) {
        val gen = ++suggestGen
        val near = currentPosition() ?: NavState.lastLocation?.let { LatLng(it.latitude, it.longitude) }
        showSuggestionRows(emptyList(), "Procurando \"$q\"…")
        suggestExec.execute {
            val list = runCatching { Places.search(this, q, near) }.getOrDefault(emptyList())
            runOnUiThread {
                if (gen != suggestGen) return@runOnUiThread // já digitou outra coisa
                suggestions = list
                showSuggestionRows(list, if (list.isEmpty()) "Nada encontrado. Tente o nome do lugar, rua ou bairro." else null)
            }
        }
    }

    private fun showRecents() {
        suggestGen++
        val rec = Places.recents(this)
        suggestions = rec
        if (rec.isEmpty() || !etDestination.hasFocus()) { hideSuggestions(); return }
        showSuggestionRows(rec, "Recentes")
    }

    private fun hideSuggestions() {
        findViewById<View>(R.id.suggestCard).visibility = View.GONE
    }

    /** Desenha a lista: ícone, nome, endereço e distância. [header] é uma linha de texto acima. */
    private fun showSuggestionRows(list: List<Places.Place>, header: String?) {
        val card = findViewById<View>(R.id.suggestCard)
        val box = findViewById<android.widget.LinearLayout>(R.id.suggestList)
        box.removeAllViews()
        val dp = resources.displayMetrics.density
        header?.let {
            box.addView(TextView(this).apply {
                text = it; textSize = 12f; alpha = 0.6f
                setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), (4 * dp).toInt())
            })
        }
        for (p in list) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                isClickable = true
                background = android.util.TypedValue().let { tv ->
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ContextCompat.getDrawable(this@MainActivity, tv.resourceId)
                }
                setOnClickListener { choosePlace(p) }
            }
            row.addView(TextView(this).apply {
                text = when { p.recent -> "🕘"; p.poi -> "⭐"; else -> "📍" }
                textSize = 18f
                setPadding(0, 0, (12 * dp).toInt(), 0)
            })
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(this).apply {
                text = p.title; textSize = 15f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (p.subtitle.isNotBlank()) col.addView(TextView(this).apply {
                text = p.subtitle; textSize = 12f; alpha = 0.65f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col)
            Places.distanceText(p.distanceM).takeIf { it.isNotEmpty() }?.let { d ->
                row.addView(TextView(this).apply { text = d; textSize = 12f; alpha = 0.6f; setPadding((8 * dp).toInt(), 0, 0, 0) })
            }
            box.addView(row)
        }
        card.visibility = if (box.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun choosePlace(p: Places.Place) {
        hideKeyboard()
        hideSuggestions()
        etDestination.clearFocus()
        ignoreTextChange = true
        etDestination.setText(p.title)
        ignoreTextChange = false
        Places.addRecent(this, p.copy(recent = false, distanceM = null))
        routeTo(p.pos, if (p.subtitle.isNotBlank()) "${p.title} · ${p.subtitle}" else p.title)
    }

    private fun searchAddress() {
        val query = etDestination.text.toString().trim()
        if (query.isEmpty()) {
            toast("Digite um endereço ou segure o dedo num ponto do mapa")
            return
        }
        // já tem sugestões na tela: "Buscar" escolhe a primeira
        suggestions.firstOrNull()?.takeIf { findViewById<View>(R.id.suggestCard).visibility == View.VISIBLE && !it.recent }
            ?.let { choosePlace(it); return }
        hideKeyboard()
        hideSuggestions()
        if (!Geocoder.isPresent()) {
            toast("Busca de endereço indisponível neste aparelho. Use o toque longo no mapa.")
            return
        }
        setStatus("Buscando \"$query\"...")
        val near = currentPosition() ?: DEFAULT_POS
        io.execute {
            val address = geocode(query, near)
            runOnUiThread {
                if (address == null) {
                    setStatus("Endereço não encontrado. Tente com bairro/cidade, ou segure o dedo no mapa.")
                } else {
                    routeTo(LatLng(address.latitude, address.longitude), address.getAddressLine(0) ?: query)
                }
            }
        }
    }

    /** Procura primeiro perto de onde você está (~110 km), depois em qualquer lugar. */
    @Suppress("DEPRECATION")
    private fun geocode(query: String, near: LatLng): Address? {
        return try {
            val g = Geocoder(this, Locale("pt", "BR"))
            val d = 1.0
            g.getFromLocationName(query, 1,
                near.latitude - d, near.longitude - d, near.latitude + d, near.longitude + d
            )?.firstOrNull() ?: g.getFromLocationName(query, 1)?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Erro no geocoder", e)
            null
        }
    }

    // ---------- Rota ----------

    private fun routeTo(dest: LatLng, label: String) {
        val origin = currentPosition()
        if (origin == null) {
            setStatus("Ainda sem posição do GPS. Espere alguns segundos ao ar livre e tente de novo.")
            return
        }
        showDestination(dest)
        setStatus("Calculando rota até: $label")
        io.execute {
            try {
                val route = RoutingClient.route(origin, dest)
                runOnUiThread { showRoute(route, label, dest) }
            } catch (e: Exception) {
                Log.e(TAG, "Erro na rota", e)
                runOnUiThread { setStatus("Não consegui calcular a rota: ${e.message}") }
            }
        }
    }

    private fun showRoute(route: RoutingClient.Route, label: String, dest: LatLng) {
        currentRoute = route
        currentDest = dest
        currentLabel = label
        val s = style ?: return
        val line = LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(Feature.fromGeometry(line))
        fabClear.visibility = View.VISIBLE

        val km = String.format(Locale("pt", "BR"), "%.1f", route.distanceKm)
        val min = route.durationMin.toInt()
        val tempo = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
        setStatus("$km km · $tempo · $label\n(${route.provider})")
        NavEngine.setRoute(route, dest, label)

        // Mostra a rota inteira; o botão de posição volta a seguir o GPS.
        val m = map ?: return
        if (route.points.size >= 2) {
            m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
            val bounds = LatLngBounds.Builder().includes(route.points).build()
            m.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160), 800)
        }
    }

    private fun showDestination(dest: LatLng) {
        style?.getSourceAs<GeoJsonSource>(SRC_DEST)
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(dest.longitude, dest.latitude)))
    }

    private fun clearRoute() {
        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
        style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(empty)
        style?.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(empty)
        fabClear.visibility = View.GONE
        currentRoute = null
        currentDest = null
        NavEngine.clear()
        etDestination.setText("")
        setStatus("Rota removida.")
        recenter()
    }

    // ---------- Tela do painel: Mapa ou Race ----------

    // ---------- Telas do app: Início, Navegação (mapa) e Race ----------

    private enum class Screen { HOME, MAP, RACE, MAINT }

    private var screen = Screen.HOME
    private lateinit var homeView: View
    private lateinit var raceScreen: View
    private lateinit var maintScreen: View
    private val raceHandler by lazy { android.os.Handler(mainLooper) }
    private var raceBmp: android.graphics.Bitmap? = null
    private var raceOnPhone: br.com.motonav.dash.RaceDash? = null

    private val backToHome = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::wizard.isInitialized && wizard.showing) { wizard.hide(); showScreen(Screen.HOME); return }
            if (::dashPanel.isInitialized && dashPanel.visibility == View.VISIBLE) dashPanel.visibility = View.GONE
            else showScreen(Screen.HOME)
        }
    }

    private fun setupScreenToggle() {
        DashScreen.load(this)
        homeView = findViewById(R.id.home)
        raceScreen = findViewById(R.id.raceScreen)
        maintScreen = findViewById(R.id.maintScreen)
        onBackPressedDispatcher.addCallback(this, backToHome)

        // início
        findViewById<View>(R.id.homeMapCard).setOnClickListener { showScreen(Screen.MAP) }
        findViewById<View>(R.id.homeRaceCard).setOnClickListener { raceStylePicker { showScreen(Screen.RACE) } }
        findViewById<View>(R.id.homeDue).setOnClickListener { showScreen(Screen.MAINT) }
        findViewById<Button>(R.id.raceStyle).setOnClickListener { raceStylePicker { updateRaceTitle() } }

        // manutenção
        findViewById<Button>(R.id.maintHome).setOnClickListener { showScreen(Screen.HOME) }
        findViewById<Button>(R.id.maintOdoEdit).setOnClickListener { editOdometer() }
        findViewById<Button>(R.id.maintAdd).setOnClickListener { editMaintItem(-1) }
        findViewById<View>(R.id.homeRaceCard).setOnLongClickListener { showRacePreview(); true }
        findViewById<Button>(R.id.homeConnect).setOnClickListener { connectButton() }
        findViewById<View>(R.id.homeConnCard).setOnClickListener { showMotoSheet() }
        findViewById<Button>(R.id.homeRoutes).setOnClickListener { showScreen(Screen.MAP); showRoutesDialog() }
        findViewById<Button>(R.id.homeTrips).setOnClickListener { tripsDialog() }
        findViewById<Button>(R.id.homeGarage).setOnClickListener { showScreen(Screen.MAINT) }
        findViewById<Button>(R.id.homeSettings).setOnClickListener { showMotoSheet() }

        // navegação: barra do topo
        findViewById<Button>(R.id.mapHome).setOnClickListener { showScreen(Screen.HOME) }
        findViewById<Button>(R.id.mapToRace).setOnClickListener { showScreen(Screen.RACE) }

        // race
        findViewById<Button>(R.id.raceHome).setOnClickListener { showScreen(Screen.HOME) }
        findViewById<Button>(R.id.raceToMap).setOnClickListener { showScreen(Screen.MAP) }
        findViewById<Button>(R.id.raceBigMap).setOnClickListener { showScreen(Screen.MAP) }
        findViewById<Button>(R.id.raceMoto).setOnClickListener { showMotoSheet() }
        findViewById<Button>(R.id.raceMoto).setOnLongClickListener { toggleDiagnostics(); true }
        findViewById<Button>(R.id.raceReset).setOnClickListener { TripStats.reset(); toast("Viagem zerada") }

        showScreen(Screen.HOME)
    }

    /** Troca a tela do app; Navegação e Race também mandam a mesma tela para o painel da moto. */
    private fun showScreen(s: Screen) {
        screen = s
        homeView.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        raceScreen.visibility = if (s == Screen.RACE) View.VISIBLE else View.GONE
        maintScreen.visibility = if (s == Screen.MAINT) View.VISIBLE else View.GONE
        // mapa do celular escondido atrás das outras telas: não precisa ficar desenhando
        mapView.visibility = if (s == Screen.MAP) View.VISIBLE else View.INVISIBLE
        backToHome.isEnabled = s != Screen.HOME
        when (s) {
            Screen.MAP -> DashScreen.set(this, DashScreen.Mode.MAP)
            Screen.RACE -> { DashScreen.set(this, DashScreen.Mode.RACE); updateRaceTitle() }
            Screen.HOME -> refreshHome()
            Screen.MAINT -> refreshMaint()
        }
        if (s == Screen.RACE) startRaceOnPhone() else stopRaceOnPhone()
        if (s != Screen.HOME) hideKeyboard()
    }

    private fun refreshHome() {
        if (!::homeView.isInitialized) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        findViewById<TextView>(R.id.homeGreeting).text = when (hour) {
            in 5..11 -> "BOM DIA"
            in 12..17 -> "BOA TARDE"
            else -> "BOA NOITE"
        }
        findViewById<TextView>(R.id.homeHello).text =
            if (DashScreen.riderName.isBlank()) "Olá!" else "Olá, ${DashScreen.riderName}"
        val race = DashScreen.mode == DashScreen.Mode.RACE
        val orange = android.graphics.Color.rgb(255, 122, 0)
        val idle = android.graphics.Color.rgb(42, 44, 54)
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.homeMapCard).strokeColor = if (race) idle else orange
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.homeRaceCard).strokeColor = if (race) orange else idle
        findViewById<TextView>(R.id.homePanelNow).text = "No painel agora: ${if (race) "Race" else "Navegação"}"
        findViewById<TextView>(R.id.homeFuel).text = "⛽\n≈ ${Garage.rangeKm().toInt().coerceAtLeast(0)} km"
        findViewById<TextView>(R.id.homeOdo).text = "📟\n${Garage.odometer().toInt()} km"
        findViewById<TextView>(R.id.homeWeather).text =
            if (NavState.weather != null) "🌧\nchuva" else "🌡\n${NavState.temperature?.let { "$it°C" } ?: "—"}"
        val due = findViewById<TextView>(R.id.homeDue)
        val dueText = Garage.dueText()
        val next = dueText ?: Garage.nextText()
        due.visibility = if (next != null) View.VISIBLE else View.GONE
        due.text = (if (dueText != null) "⚠ " else "🔧 Próxima: ") + next + "  ›"
        due.setTextColor(if (dueText != null) android.graphics.Color.rgb(255, 176, 102) else android.graphics.Color.rgb(170, 175, 185))
        // otimização de bateria ativa: o Android pode pausar o app com a tela apagada
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        findViewById<TextView>(R.id.homeBattery).apply {
            visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
            setOnClickListener {
                toast("Toque em MotoNav e escolha \"Sem restrições\"")
                runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }
        refreshDashChip(null)
    }

    // ---------- Estilos do Race ----------

    private fun updateRaceTitle() {
        findViewById<TextView>(R.id.raceTitle)?.text = "RACE · ${DashScreen.raceStyle.title.uppercase(Locale("pt", "BR"))}"
    }

    /** Escolher o estilo do Race (prévia de cada um); [then] roda depois da escolha. */
    private fun raceStylePicker(then: () -> Unit) {
        val dp = resources.displayMetrics.density
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        list.addView(TextView(this).apply {
            text = "Escolha o estilo do Race"
            textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (10 * dp).toInt())
        })
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val images = HashMap<br.com.motonav.dash.RaceStyle, android.widget.ImageView>()
        for (s in br.com.motonav.dash.RaceStyle.values()) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16 * dp
                strokeWidth = (2 * dp).toInt()
                strokeColor = if (s == DashScreen.raceStyle) android.graphics.Color.rgb(255, 122, 0) else android.graphics.Color.rgb(60, 62, 72)
                setCardBackgroundColor(android.graphics.Color.rgb(20, 21, 27))
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * dp).toInt() }
                setOnClickListener {
                    DashScreen.setRaceStyle(this@MainActivity, s)
                    sheet.dismiss()
                    then()
                }
            }
            val inner = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            val img = android.widget.ImageView(this).apply {
                adjustViewBounds = true
                setBackgroundColor(android.graphics.Color.BLACK)
                minimumHeight = (180 * dp).toInt()
            }
            images[s] = img
            inner.addView(img)
            inner.addView(TextView(this).apply {
                text = (if (s == DashScreen.raceStyle) "✔ " else "") + s.title
                textSize = 17f; setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), 0)
            })
            inner.addView(TextView(this).apply {
                text = s.subtitle
                textSize = 13f; setTextColor(android.graphics.Color.rgb(154, 160, 172))
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), (12 * dp).toInt())
            })
            card.addView(inner)
            list.addView(card)
        }
        sheet.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(list) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
        // prévias desenhadas fora da thread principal
        io.execute {
            for (s in br.com.motonav.dash.RaceStyle.values()) {
                val bmp = renderRaceSample(s)
                runOnUiThread { images[s]?.setImageBitmap(bmp) }
            }
        }
    }

    /** Um quadro de demonstração do estilo (com o gráfico já preenchido). */
    private fun renderRaceSample(s: br.com.motonav.dash.RaceStyle): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(800, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val r = br.com.motonav.dash.RaceDash(800, 480, demo = true, fixedStyle = s)
        repeat(70) { r.demoAdvance(0.5f); r.draw(cv) }
        return bmp
    }

    // ---------- Manutenção ----------

    private fun refreshMaint() {
        findViewById<TextView>(R.id.maintOdo).text = "${Garage.fmtKm(Garage.odometer())} km"
        val list = findViewById<android.widget.LinearLayout>(R.id.maintList)
        list.removeAllViews()
        val dp = resources.displayMetrics.density
        Garage.items.forEachIndexed { i, item ->
            val due = Garage.isDue(item)
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16 * dp
                strokeWidth = (1 * dp).toInt()
                strokeColor = if (due) android.graphics.Color.rgb(255, 122, 0) else android.graphics.Color.rgb(42, 44, 54)
                setCardBackgroundColor(android.graphics.Color.rgb(23, 24, 31))
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
                setOnClickListener { editMaintItem(i) }
            }
            val col = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            }
            val top = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = (if (due) "⚠ " else "") + item.name
                textSize = 17f; setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            top.addView(Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = "✔ Fiz hoje"
                setTextColor(android.graphics.Color.rgb(255, 122, 0))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Registrar \"${item.name}\" feito hoje, com ${Garage.fmtKm(Garage.odometer())} km?")
                        .setPositiveButton("Registrar") { _, _ -> Garage.markDone(i); refreshMaint() }
                        .setNegativeButton("Cancelar", null).show()
                }
            })
            col.addView(top)
            col.addView(TextView(this).apply {
                text = Garage.intervalText(item)
                textSize = 12f; setTextColor(android.graphics.Color.rgb(120, 126, 140))
            })
            col.addView(com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
                max = 1000
                progress = (Garage.used(item) * 1000).toInt()
                trackCornerRadius = (3 * dp).toInt()
                trackThickness = (6 * dp).toInt()
                setIndicatorColor(if (due) android.graphics.Color.rgb(255, 122, 0) else android.graphics.Color.rgb(0, 200, 120))
                trackColor = android.graphics.Color.rgb(40, 42, 52)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() }
            })
            col.addView(TextView(this).apply {
                text = Garage.statusText(item)
                textSize = 14f
                setTextColor(if (due) android.graphics.Color.rgb(255, 176, 102) else android.graphics.Color.rgb(200, 204, 212))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            val last = listOfNotNull(
                item.lastDateMs.takeIf { it > 0 }?.let { java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(java.util.Date(it)) },
                if (item.intervalKm > 0) "${Garage.fmtKm(item.lastOdo)} km" else null
            ).joinToString(" · ")
            if (last.isNotEmpty()) col.addView(TextView(this).apply {
                text = "Última vez: $last"
                textSize = 12f; setTextColor(android.graphics.Color.rgb(107, 113, 128))
            })
            card.addView(col)
            list.addView(card)
        }
    }

    private fun editOdometer() {
        val f = numberField("Km do hodômetro da moto", "${Garage.odometer().toLong()}")
        AlertDialog.Builder(this).setTitle("Km atual da moto")
            .setMessage("Digite o km que aparece no painel da moto. Depois disso o app soma sozinho pelo GPS.")
            .setView(formBox(f))
            .setPositiveButton("Salvar") { _, _ ->
                parseNum(f.text.toString())?.let { Garage.setOdometer(it); refreshMaint(); refreshHome() }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Editar (ou criar, com index -1) um item: nome, a cada km, a cada meses, última vez (km e data). */
    private fun editMaintItem(index: Int) {
        val old = Garage.items.getOrNull(index)
        val df = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).apply { isLenient = false }
        val name = EditText(this).apply { hint = "Ex.: Troca de óleo"; setText(old?.name ?: "") }
        val km = numberField("0 = não vence por km", (old?.intervalKm ?: 5000).toString())
        val months = numberField("0 = não vence por tempo", (old?.intervalMonths ?: 6).toString())
        val lastKm = numberField("km na última vez", (old?.lastOdo ?: Garage.odometer()).toLong().toString())
        val lastDate = EditText(this).apply {
            hint = "dd/mm/aaaa"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE
            setText(old?.lastDateMs?.takeIf { it > 0 }?.let { df.format(java.util.Date(it)) } ?: df.format(java.util.Date()))
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (old == null) "Novo item" else old.name)
            .setView(android.widget.ScrollView(this).apply {
                addView(formBox(label("Nome"), name, label("Fazer a cada (km)"), km, label("Ou a cada (meses)"), months,
                    label("Última vez: km da moto"), lastKm, label("Última vez: data"), lastDate))
            })
            .setPositiveButton("Salvar") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { old?.name ?: "Item" }
                val date = runCatching { df.parse(lastDate.text.toString().trim())?.time }.getOrNull() ?: 0L
                val item = Garage.Item(
                    n,
                    parseNum(km.text.toString())?.toInt()?.coerceAtLeast(0) ?: 0,
                    parseNum(lastKm.text.toString()) ?: Garage.odometer(),
                    parseNum(months.text.toString())?.toInt()?.coerceAtLeast(0) ?: 0,
                    date
                )
                if (old == null) Garage.add(item) else Garage.update(index, item)
                refreshMaint()
            }
            .setNegativeButton("Cancelar", null)
        if (old != null) b.setNeutralButton("Excluir") { _, _ ->
            AlertDialog.Builder(this).setMessage("Excluir \"${old.name}\"?")
                .setPositiveButton("Excluir") { _, _ -> Garage.remove(index); refreshMaint() }
                .setNegativeButton("Cancelar", null).show()
        }
        b.show()
    }

    /** Botão principal de conexão (início e tela Moto). */
    private fun connectButton() {
        when (dashStatus) {
            DashStatus.UNPAIRED -> scanQr()
            DashStatus.OFF -> connectDash()
            else -> { dashLink.stop(); dashStatus = DashStatus.OFF; refreshDashChip(null) }
        }
    }

    /** O painel Race ao vivo na tela do celular (30 quadros/s enquanto a tela Race estiver aberta). */
    private val raceTick = object : Runnable {
        override fun run() {
            val b = raceBmp ?: return
            raceOnPhone?.draw(android.graphics.Canvas(b))
            findViewById<android.widget.ImageView>(R.id.raceImage).invalidate()
            raceHandler.postDelayed(this, 33)
        }
    }
    private var raceInfoAt = 0L

    private fun startRaceOnPhone() {
        if (raceBmp == null) {
            raceBmp = android.graphics.Bitmap.createBitmap(800, 480, android.graphics.Bitmap.Config.ARGB_8888)
            findViewById<android.widget.ImageView>(R.id.raceImage).setImageBitmap(raceBmp)
        }
        if (raceOnPhone == null) raceOnPhone = br.com.motonav.dash.RaceDash(800, 480)
        raceHandler.removeCallbacks(raceTick)
        raceHandler.post(raceTick)
        raceHandler.removeCallbacks(raceInfoTick)
        raceHandler.post(raceInfoTick)
    }

    private fun stopRaceOnPhone() {
        raceHandler.removeCallbacks(raceTick)
        raceHandler.removeCallbacks(raceInfoTick)
    }

    /** Texto abaixo do Race: combustível, limite, clima, manutenção (1x/s). */
    private val raceInfoTick = object : Runnable {
        override fun run() {
            val lines = mutableListOf<String>()
            lines += "⛽  Autonomia ≈ ${Garage.rangeKm().toInt().coerceAtLeast(0)} km"
            NavState.speedLimit?.let { lines += "🚦  Limite da via: $it km/h" }
            lines += NavState.weather ?: "🌡  ${NavState.temperature?.let { "$it°C, sem chuva no caminho" } ?: "Clima: aguardando"}"
            NavState.route?.let { lines += "🗺  Rota: ${it.summary}" }
            Garage.dueText()?.let { lines += "🔧  $it" }
            lines += "📟  Hodômetro ≈ ${Garage.odometer().toInt()} km"
            findViewById<TextView>(R.id.raceInfo).text = lines.joinToString("\n")
            raceHandler.postDelayed(this, 1000)
        }
    }

    /** Só na versão de teste: um quadro da demonstração em cache/race_preview.png (para conferir o visual). */
    private fun saveRacePreviewImage() {
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(800, 480, android.graphics.Bitmap.Config.ARGB_8888)
            val race = br.com.motonav.dash.RaceDash(800, 480, demo = true)
            race.demoAdvance(2.6f)
            race.draw(android.graphics.Canvas(bmp))
            java.io.File(cacheDir, "race_preview.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            // painel do mapa com manobra + música (faixa dividida)
            if (MediaInfo.text() == null) {
                val route = NavState.Route(emptyList(), LatLng(0.0, 0.0), "3,5 km · 7 min",
                    NavState.Maneuver(NavState.Turn.LEFT, "Vire à esquerda para a Rua Tuibas", 180.0), remainingMin = 7.0)
                val cv = android.graphics.Canvas(bmp)
                cv.drawColor(android.graphics.Color.rgb(226, 222, 212))
                br.com.motonav.dash.DashOverlay(800, 480).draw(cv, route)
                java.io.File(cacheDir, "nav_preview.png").outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                val undo = MediaInfo.fakeForPreview("Todo Azul Do Mar", "14 Bis")
                cv.drawColor(android.graphics.Color.rgb(226, 222, 212))
                br.com.motonav.dash.DashOverlay(800, 480).draw(cv, route)
                java.io.File(cacheDir, "music_preview.png").outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                undo()
            }
            // um quadro de cada estilo do Race
            for (s in br.com.motonav.dash.RaceStyle.values()) {
                java.io.File(cacheDir, "race_${s.name.lowercase()}.png").outputStream().use {
                    renderRaceSample(s).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // tela de chegada
            run {
                race.draw(android.graphics.Canvas(bmp))
                val a = NavState.Arrival("Praça Central", 18_400.0, 1_980_000L, 33f, 92f)
                br.com.motonav.dash.ArrivalDash(800, 480).draw(android.graphics.Canvas(bmp), a, 3.5f)
                java.io.File(cacheDir, "arrival_preview.png").outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // quadros da animação de entrada
            for (t in listOf(0.9f, 1.5f, 2.2f, 3.8f, 4.9f)) {
                val intro = br.com.motonav.dash.IntroDash(800, 480, DashScreen.riderName)
                intro.draw(android.graphics.Canvas(bmp), t) { c -> race.draw(c) }
                java.io.File(cacheDir, "intro_${(t * 10).toInt()}.png").outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    private fun showRacePreview() {
        val w = 800
        val h = 480
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        // parado: demonstração (moto "andando" de mentira); andando: dados reais do GPS
        val moving = (NavState.lastLocation?.speed ?: 0f) > 1.5f
        val race = br.com.motonav.dash.RaceDash(w, h, demo = !moving)
        val img = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bmp)
        }
        val handler = android.os.Handler(mainLooper)
        val tick = object : Runnable {
            override fun run() {
                race.draw(canvas)
                img.invalidate()
                handler.postDelayed(this, 33)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Prévia do painel Race")
            .setView(img)
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { handler.removeCallbacks(tick) }
            .show()
        handler.post(tick)
    }

    // ---------- Minhas rotas: rotas salvas, viagens e mapa offline ----------

    private var currentRoute: RoutingClient.Route? = null
    private var currentDest: LatLng? = null
    private var currentLabel: String = ""

    private fun showRoutesDialog() {
        val saved = SavedRoutes.all
        val island = OfflineMaps.ISLAND
        val islandTxt = when {
            OfflineMaps.isComplete(island) -> "✓ Mapa da minha região no celular (${OfflineMaps.progress[island] ?: "baixado"})"
            OfflineMaps.progress[island] != null -> "⬇ Mapa da minha região: ${OfflineMaps.progress[island]}"
            else -> "⬇ Baixar mapa da minha região (~50 km em volta, para usar sem internet)"
        }
        val items = listOf(islandTxt) + saved.map { describe(it) }
        AlertDialog.Builder(this)
            .setTitle("Minhas rotas")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    when {
                        OfflineMaps.isComplete(island) -> toast("O mapa da sua região já está no celular")
                        !Net.online -> toast("Precisa de internet para baixar o mapa")
                        else -> { if (OfflineMaps.downloadIsland(this)) toast("Baixando o mapa da sua região...") else toast("Aguardando o GPS para saber a sua região") }
                    }
                } else {
                    savedOptions(saved[which - 1])
                }
            }
            .setPositiveButton("Salvar rota atual") { _, _ -> saveCurrentRoute() }
            .setNeutralButton("Nova viagem") { _, _ -> newTrip() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun describe(s: SavedRoutes.Saved): String {
        val km = String.format(Locale("pt", "BR"), "%.1f km", s.route.distanceKm)
        if (!s.trip) return "★ ${s.name} · $km"
        val map = when {
            OfflineMaps.tripComplete(s.id) -> "mapa ✓"
            else -> OfflineMaps.tripProgress(s.id)?.let { "mapa $it" } ?: "mapa pendente"
        }
        return "✈ ${s.name} · $km · $map · ${s.radars.size} radares"
    }

    private fun savedOptions(s: SavedRoutes.Saved) {
        val opts = mutableListOf("Iniciar")
        if (s.trip && !OfflineMaps.tripComplete(s.id)) opts.add("Baixar mapa do caminho de novo")
        opts.add("Excluir")
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "Iniciar" -> startSaved(s)
                    "Excluir" -> {
                        SavedRoutes.remove(s.id)
                        if (s.trip) OfflineMaps.deleteTrip(this, s.id)
                        toast("\"${s.name}\" excluída")
                    }
                    else -> if (Net.online) OfflineMaps.downloadTrip(this, s.id, s.route.points, s.dest)
                        else toast("Precisa de internet para baixar")
                }
            }
            .show()
    }

    /** Inicia na hora com a rota guardada; com internet, troca por uma atualizada (com trânsito). */
    private fun startSaved(s: SavedRoutes.Saved) {
        etDestination.setText(s.name)
        showDestination(s.dest)
        showRoute(s.route, s.name, s.dest)
        val origin = currentPosition()
        if (!Net.online || origin == null) {
            setStatus("Usando a rota salva \"${s.name}\"" + if (!Net.online) " (sem internet)" else "")
            return
        }
        io.execute {
            try {
                val fresh = RoutingClient.route(origin, s.dest)
                runOnUiThread {
                    if (currentDest == s.dest) {
                        showRoute(fresh, s.name, s.dest)
                        DashLog.d("Rota salva \"${s.name}\" atualizada (${fresh.provider})")
                    }
                }
            } catch (e: Exception) {
                DashLog.d("Rota salva \"${s.name}\": seguindo com a versão guardada (${e.message})")
            }
        }
    }

    private fun saveCurrentRoute() {
        val r = currentRoute
        val dest = currentDest
        if (r == null || dest == null) { toast("Trace uma rota primeiro, depois salve"); return }
        val input = EditText(this).apply {
            setText(currentLabel.substringBefore(",").take(40))
            hint = "Nome (ex.: Trabalho)"
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar rota")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Rota" }
                SavedRoutes.add(name, trip = false, dest = dest, route = r)
                toast("Rota \"$name\" salva no celular")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Viagem: calcula a rota agora (com internet) e baixa o mapa do caminho e os radares. */
    private fun newTrip() {
        if (!Net.online) { toast("Precisa de internet para preparar a viagem"); return }
        val input = EditText(this).apply { hint = "Destino (ex.: São João Batista, MA)" }
        AlertDialog.Builder(this)
            .setTitle("Nova viagem")
            .setMessage("A rota, o mapa do caminho e os radares ficam guardados no celular para usar sem internet.")
            .setView(input)
            .setPositiveButton("Preparar") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) prepareTrip(query)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun prepareTrip(query: String) {
        val origin = currentPosition()
        if (origin == null) { setStatus("Ainda sem posição do GPS. Tente de novo em alguns segundos."); return }
        setStatus("Preparando viagem para \"$query\"...")
        io.execute {
            val address = geocode(query, origin)
            if (address == null) {
                runOnUiThread { setStatus("Não achei \"$query\". Tente com o estado (ex.: Santa Helena, MA).") }
                return@execute
            }
            val dest = LatLng(address.latitude, address.longitude)
            val route = try {
                RoutingClient.route(origin, dest)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Não consegui calcular a rota da viagem: ${e.message}") }
                return@execute
            }
            val name = query.substringBefore(",").trim()
            val saved = SavedRoutes.add(name, trip = true, dest = dest, route = route)
            runOnUiThread {
                OfflineMaps.downloadTrip(this, saved.id, route.points, dest)
                setStatus("Viagem \"$name\" salva (${String.format(Locale("pt", "BR"), "%.0f", route.distanceKm)} km). " +
                    "Baixando o mapa do caminho e os radares...")
            }
            try {
                val rads = Radars.downloadAlong(route.points)
                SavedRoutes.update(saved.copy(radars = rads))
                DashLog.d("Viagem \"$name\": ${rads.size} radares no caminho guardados")
            } catch (e: Exception) {
                DashLog.d("Viagem \"$name\": radares não baixaram (${e.message}); tente \"Baixar de novo\" depois")
            }
        }
    }

    /** Progresso dos downloads no status (só quando não está navegando, para não atrapalhar). */
    private fun showDownloadProgress() {
        if (fabClear.visibility == View.VISIBLE) return
        val (name, txt) = OfflineMaps.progress.entries.firstOrNull { it.value.startsWith("baixando") }
            ?.let { it.key to it.value } ?: return
        val label = if (name == OfflineMaps.ISLAND) "Minha região"
            else SavedRoutes.all.firstOrNull { name.startsWith("viagem:${it.id}") }?.name ?: "viagem"
        setStatus("Mapa offline ($label): $txt")
    }

    private var holdsGps = false

    private val navListener: (NavState.Route?) -> Unit = { r ->
        val st = style
        if (st != null && r != null) {
            val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.longitude, it.latitude) })
            st.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(Feature.fromGeometry(line))
            val m = r.maneuver
            tvStatus.text = when {
                r.rerouting -> "Recalculando rota…\n${r.summaryWithEta}"
                m != null -> "${m.turn.arrow}  ${m.distanceText} · ${m.text}\n${r.summaryWithEta}"
                else -> r.summaryWithEta
            }
        } else if (st != null && fabClear.visibility == View.VISIBLE) {
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            st.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(empty)
            st.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(empty)
            fabClear.visibility = View.GONE
            tvStatus.text = "Rota finalizada."
        }
    }

    private val incidentListener: () -> Unit = { style?.let { TomTom.refreshIncidents(it) } }

    private fun addRouteLayers(s: Style) {
        TomTom.addTrafficLayer(s) // trânsito (cores nas ruas) e incidentes, por baixo da rota
        TomTom.addListener(incidentListener)
        s.addSource(GeoJsonSource(SRC_ROUTE))
        s.addSource(GeoJsonSource(SRC_DEST))
        // contorno branco + linha azul, fica legível em qualquer fundo
        s.addLayer(LineLayer("route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        s.addLayer(LineLayer("route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#2979FF"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        s.addLayer(CircleLayer("dest-layer", SRC_DEST).withProperties(
            PropertyFactory.circleColor("#E53935"),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f)
        ))
    }

    // ---------- utilidades ----------

    private fun setStatus(msg: String) {
        tvStatus.text = msg
        Log.d(TAG, msg)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etDestination.windowToken, 0)
        etDestination.clearFocus()
    }

    // ---------- ciclo de vida do MapView (obrigatório) ----------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        NavState.addListener(navListener)
        if (!holdsGps) holdsGps = LocationHub.acquire(this)
        // A busca tinha parado sozinha (moto desligada): abrir o app de novo volta a procurar.
        if (::dashLink.isInitialized && dashLink.stoppedIdle && !dashLink.isRunning &&
            DashPairing.load(this) != null && missingDashPermissions().isEmpty()) {
            dashLink.start()
        }
    }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (::wizard.isInitialized && wizard.showing) wizard.refresh() else warnBatterySaver()
        if (::homeView.isInitialized && screen == Screen.HOME) refreshHome()
        if (::raceScreen.isInitialized && screen == Screen.RACE) startRaceOnPhone()
        if (::maintScreen.isInitialized && screen == Screen.MAINT) refreshMaint()
        MediaInfo.start(this) // caso o acesso a notificações tenha acabado de ser ligado
    }

    private var batteryDialog: AlertDialog? = null

    /**
     * A Economia de energia do Android pode desligar o GPS de todos os apps com a tela apagada
     * (confirmado num Samsung Galaxy). Não dá para contornar pelo app: só avisar e levar à configuração.
     */
    private fun warnBatterySaver() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val cutsGps = pm.isPowerSaveMode && pm.locationPowerSaveMode in setOf(
            android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY,
            android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF
        )
        if (!cutsGps) { batteryDialog?.dismiss(); batteryDialog = null; return }
        if (batteryDialog?.isShowing == true) return
        batteryDialog = AlertDialog.Builder(this)
            .setTitle("Economia de energia ligada")
            .setMessage(
                "Com a Economia de energia ligada, o Android desliga o GPS quando a tela do " +
                    "celular apaga, e o mapa do painel para de andar.\n\nDesligue a Economia de " +
                    "energia para navegar com o celular bloqueado."
            )
            .setPositiveButton("Abrir configurações") { _, _ ->
                runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
                    .onFailure { toast("Desligue a Economia de energia no painel rápido") }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }
    override fun onPause() { super.onPause(); mapView.onPause(); stopRaceOnPhone() }
    override fun onStop() {
        super.onStop()
        mapView.onStop()
        NavState.removeListener(navListener)
        if (holdsGps) { LocationHub.release(); holdsGps = false }
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        DashLog.listener = null
        dashLink.onStateChanged = null
        TomTom.removeListener(incidentListener)
        OfflineMaps.onChange = null
        io.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
}
