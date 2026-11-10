package br.com.motonav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import br.com.motonav.dash.DashLog
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Garagem": dados da moto medidos pelo GPS (o painel não manda telemetria).
 *  - hodômetro: km pelo GPS + um ajuste para bater com o hodômetro da moto (o usuário informa);
 *  - combustível: tanque (L) x consumo (km/L) - km desde o último "Abasteci" = autonomia;
 *    abaixo de [LOW_FUEL_KM] avisa no painel e procura o posto mais próximo (OpenStreetMap);
 *  - manutenção: itens com intervalo em km; avisa quando faltam [DUE_KM] ou menos.
 */
object Garage {
    /**
     * Item de manutenção. Vence pelo que vier primeiro: [intervalKm] (0 = não usa km) ou
     * [intervalMonths] (0 = não usa tempo). [lastOdo] e [lastDateMs] = quando foi feito por último.
     */
    data class Item(
        val name: String,
        val intervalKm: Int,
        val lastOdo: Double,
        val intervalMonths: Int = 0,
        val lastDateMs: Long = 0L
    )
    data class Station(val name: String, val pos: LatLng, val distanceM: Double)

    const val LOW_FUEL_KM = 50.0
    const val DUE_KM = 200.0
    private const val PREFS = "garage"
    private const val DAY_MS = 86_400_000L
    private const val DUE_DAYS = 15
    private val DEFAULT_ITEMS = listOf(
        Item("Troca de óleo", 5000, 0.0, 6),
        Item("Filtro de óleo", 5000, 0.0, 6),
        Item("Filtro de ar", 10000, 0.0, 12),
        Item("Revisão geral", 6000, 0.0, 12),
        Item("Lubrificar a corrente", 600, 0.0, 0),
        Item("Fluido de freio", 0, 0.0, 24),
        Item("Velas de ignição", 10000, 0.0, 0)
    )
    private var ctx: Context? = null
    @Volatile var gpsKm = 0.0           // km medidos pelo GPS desde que o app foi instalado
        private set
    @Volatile var odoOffset = 0.0       // hodômetro da moto - gpsKm
        private set
    @Volatile var tankL = 15f
        private set
    @Volatile var kmPerL = 28f
        private set
    @Volatile private var fuelAtGpsKm = 0.0
    @Volatile var items: List<Item> = DEFAULT_ITEMS
        private set
    @Volatile var station: Station? = null
        private set

    private var savedAt = 0.0
    private val io = Executors.newSingleThreadExecutor()
    private var stationFetchAt = 0L
    private var stationFetchKm = -100.0
    private var fuelLogged = false

    @Synchronized
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        gpsKm = p.getFloat("gpsKm", 0f).toDouble()
        odoOffset = p.getFloat("odoOffset", 0f).toDouble()
        tankL = p.getFloat("tankL", 15f)
        kmPerL = p.getFloat("kmPerL", 28f)
        fuelAtGpsKm = p.getFloat("fuelAt", 0f).toDouble()
        savedAt = gpsKm
        items = runCatching {
            val a = JSONArray(p.getString("items", null) ?: throw IllegalStateException())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Item(o.getString("name"), o.getInt("interval"), o.getDouble("last"),
                    o.optInt("months", 0), o.optLong("date", 0L))
            }
        }.getOrDefault(DEFAULT_ITEMS)
        // versão 2: lista padrão maior; acrescenta os itens novos sem mexer nos que já existiam
        if (p.getInt("itemsV", 1) < 2) {
            val have = items.map { it.name }.toSet()
            items = items.map { old -> DEFAULT_ITEMS.firstOrNull { it.name == old.name && old.intervalMonths == 0 }
                ?.let { old.copy(intervalMonths = it.intervalMonths) } ?: old } +
                DEFAULT_ITEMS.filter { it.name !in have }.map { it.copy(lastOdo = odometer()) }
            p.edit().putInt("itemsV", 2).apply()
            save()
        }
    }

    private fun save() {
        val c = ctx ?: return
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("name", it.name).put("interval", it.intervalKm).put("last", it.lastOdo)
                .put("months", it.intervalMonths).put("date", it.lastDateMs))
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("gpsKm", gpsKm.toFloat())
            .putFloat("odoOffset", odoOffset.toFloat())
            .putFloat("tankL", tankL)
            .putFloat("kmPerL", kmPerL)
            .putFloat("fuelAt", fuelAtGpsKm.toFloat())
            .putString("items", a.toString())
            .apply()
        savedAt = gpsKm
    }

    fun odometer(): Double = gpsKm + odoOffset

    /** Km rodados (vem do diário de viagens, já filtrado). */
    fun addKm(km: Double) {
        gpsKm += km
        if (gpsKm - savedAt > 0.3) save()
    }

    // ------------------------------------------------------------ hodômetro e manutenção

    /** Acerta o hodômetro sem mudar quanto falta para cada manutenção. */
    fun setOdometer(km: Double) {
        val delta = km - odometer()
        odoOffset = km - gpsKm
        items = items.map { it.copy(lastOdo = it.lastOdo + delta) }
        save()
    }

    /** Km que faltam (null se o item não vence por km). */
    fun kmLeft(it: Item): Double? = if (it.intervalKm > 0) it.lastOdo + it.intervalKm - odometer() else null

    /** Dias que faltam (null se não vence por tempo ou se a data da última vez não foi informada). */
    fun daysLeft(it: Item): Int? {
        if (it.intervalMonths <= 0 || it.lastDateMs <= 0L) return null
        val due = it.lastDateMs + (it.intervalMonths * 30.44 * DAY_MS).toLong()
        return ((due - System.currentTimeMillis()) / DAY_MS).toInt()
    }

    /** 0..1: quanto do intervalo já foi usado (o maior entre km e tempo). */
    fun used(it: Item): Float {
        val byKm = kmLeft(it)?.let { left -> (1 - left / it.intervalKm).toFloat() } ?: 0f
        val byDays = daysLeft(it)?.let { left -> 1f - left / (it.intervalMonths * 30.44f) } ?: 0f
        return maxOf(byKm, byDays).coerceIn(0f, 1f)
    }

    fun isDue(it: Item): Boolean =
        (kmLeft(it)?.let { k -> k <= DUE_KM } ?: false) || (daysLeft(it)?.let { d -> d <= DUE_DAYS } ?: false)

    /** "faltam 1.234 km · 3 meses" / "vencida há 120 km". */
    fun statusText(it: Item): String {
        val parts = mutableListOf<String>()
        kmLeft(it)?.let { k -> parts += if (k < 0) "vencida há ${fmtKm(-k)} km" else "faltam ${fmtKm(k)} km" }
        daysLeft(it)?.let { d ->
            parts += when {
                d < 0 -> "vencida há ${-d} dias"
                d < 60 -> "faltam $d dias"
                else -> "faltam ${(d / 30.44).toInt()} meses"
            }
        }
        if (it.intervalMonths > 0 && it.lastDateMs <= 0L) parts += "informe a data da última vez"
        return parts.joinToString("  ·  ").ifEmpty { "sem intervalo definido" }
    }

    fun intervalText(it: Item): String = listOfNotNull(
        it.intervalKm.takeIf { k -> k > 0 }?.let { k -> "${fmtKm(k.toDouble())} km" },
        it.intervalMonths.takeIf { m -> m > 0 }?.let { m -> if (m == 1) "1 mês" else "$m meses" }
    ).joinToString(" ou ").let { s -> if (s.isEmpty()) "" else "a cada $s" }

    fun fmtKm(v: Double): String = String.format(Locale("pt", "BR"), "%,d", v.toLong())

    fun markDone(index: Int) {
        items = items.mapIndexed { i, it -> if (i == index) it.copy(lastOdo = odometer(), lastDateMs = System.currentTimeMillis()) else it }
        save()
    }

    fun update(index: Int, item: Item) {
        items = items.mapIndexed { i, it -> if (i == index) item else it }
        save()
    }

    fun add(item: Item) { items = items + item; save() }

    fun remove(index: Int) { items = items.filterIndexed { i, _ -> i != index }; save() }

    /** O item mais perto de vencer ("Troca de óleo: faltam 120 km"), se estiver perto. */
    fun dueText(): String? {
        val due = items.filter { isDue(it) }.maxByOrNull { used(it) } ?: return null
        return "${due.name}: ${statusText(due)}"
    }

    /** O próximo item a vencer, mesmo longe (para a tela inicial). */
    fun nextText(): String? {
        val next = items.filter { it.intervalKm > 0 || (it.intervalMonths > 0 && it.lastDateMs > 0) }.maxByOrNull { used(it) } ?: return null
        return "${next.name}: ${statusText(next)}"
    }
    // ------------------------------------------------------------ combustível

    fun setFuel(tank: Float, consumption: Float) { tankL = tank; kmPerL = consumption; save() }

    fun refuel() {
        fuelAtGpsKm = gpsKm
        fuelLogged = false
        NavState.fuel = null
        save()
        DashLog.d("Abasteceu: autonomia ${rangeKm().toInt()} km")
    }

    fun rangeKm(): Double = tankL * kmPerL - (gpsKm - fuelAtGpsKm)

    /** A cada leitura do GPS: aviso de pouca gasolina e posto mais próximo. */
    fun onLocation(loc: Location) {
        val range = rangeKm()
        if (range > LOW_FUEL_KM) { NavState.fuel = null; return }
        if (!fuelLogged) { fuelLogged = true; DashLog.d("Pouco combustível: ~${range.toInt()} km de autonomia") }
        val now = SystemClock.elapsedRealtime()
        if (Net.online && (gpsKm - stationFetchKm > 5 || now - stationFetchAt > 10 * 60_000) && now - stationFetchAt > 60_000) {
            stationFetchAt = now
            stationFetchKm = gpsKm
            io.execute { runCatching { station = nearestStation(loc.latitude, loc.longitude) } }
        }
        val st = station?.let { s ->
            val d = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, s.pos.latitude, s.pos.longitude, d)
            " · ${s.name} ${km(d[0].toDouble())}"
        } ?: ""
        NavState.fuel = "⛽ ~${range.toInt().coerceAtLeast(0)} km de gasolina$st"
    }

    /** Posto mais próximo (OpenStreetMap, até 8 km). Roda fora da thread principal. */
    fun nearestStation(lat: Double, lon: Double): Station? {
        val q = "[out:json][timeout:25];nwr[\"amenity\"=\"fuel\"](around:8000,$lat,$lon);out center;"
        val arr = JSONObject(Radars.query(q)).getJSONArray("elements")
        var best: Station? = null
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
            val tags = e.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("brand")?.takeIf { it.isNotBlank() } ?: "Posto"
            val d = FloatArray(1)
            Location.distanceBetween(lat, lon, la, lo, d)
            if (best == null || d[0] < best.distanceM) best = Station(name, LatLng(la, lo), d[0].toDouble())
        }
        station = best
        return best
    }

    fun km(m: Double): String =
        if (m >= 1000) String.format(Locale("pt", "BR"), "%.1f km", m / 1000) else "${(m / 10).toInt() * 10} m"
}
