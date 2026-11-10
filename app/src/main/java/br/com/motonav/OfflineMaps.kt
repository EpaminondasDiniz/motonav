package br.com.motonav

import android.content.Context
import br.com.motonav.dash.DashLog
import br.com.motonav.dash.DashMapSource
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.ConcurrentHashMap

/**
 * Mapas guardados no celular (MapLibre offline). Ficam no mesmo banco do cache do mapa: sem
 * internet o MapLibre usa esses pedaços sozinho, nos dois mapas (celular e painel).
 *  - "regiao": ~50 x 50 km em volta de onde a pessoa estava ao baixar ("minha região");
 *  - "viagem:<id>": o corredor ao longo da rota de uma viagem + a cidade de destino.
 * Os tiles do OpenFreeMap vão até o zoom 14; acima disso o mapa amplia os mesmos dados.
 */
object OfflineMaps {
    const val ISLAND = "regiao"
    private const val REGION_HALF_DEG = 0.23 // ~25 km para cada lado
    private const val MIN_ZOOM = 10.0
    private const val MAX_ZOOM = 16.0
    private const val PIXEL_RATIO = 2f

    /** Progresso legível para a tela (nome da região -> texto). */
    val progress = ConcurrentHashMap<String, String>()
    private val complete = ConcurrentHashMap.newKeySet<String>()
    private val active = ConcurrentHashMap<String, OfflineRegion>() // mantém a referência viva durante o download
    @Volatile var onChange: (() -> Unit)? = null

    fun isComplete(name: String) = name in complete

    private fun nameOf(r: OfflineRegion): String =
        runCatching { JSONObject(String(r.metadata)).optString("name") }.getOrDefault("")

    /** Ao abrir o app: descobre o que já está baixado e retoma downloads interrompidos. */
    fun init(ctx: Context, autoIsland: Boolean) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty()
                var hasIsland = false
                for (r in regions) {
                    val name = nameOf(r)
                    if (name == ISLAND) hasIsland = true
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status?.isComplete == true) {
                                complete.add(name)
                                progress[name] = "baixado (${status.completedResourceSize / 1_048_576} MB)"
                                onChange?.invoke()
                            } else {
                                watch(name, r) // ficou pela metade: continua
                            }
                        }
                        override fun onError(error: String?) {}
                    })
                }
                if (!hasIsland && autoIsland && Net.online) downloadIsland(ctx)
            }
            override fun onError(error: String) = DashLog.d("mapa offline: erro ao listar ($error)")
        })
    }

    /** Baixa "minha região" em volta da posição atual; false se ainda não há GPS. */
    fun downloadIsland(ctx: Context): Boolean {
        if (ISLAND in complete || active.containsKey(ISLAND)) return true
        val loc = NavState.lastLocation ?: return false
        val d = REGION_HALF_DEG
        val bounds = LatLngBounds.from(loc.latitude + d, loc.longitude + d, loc.latitude - d, loc.longitude - d)
        create(ctx, ISLAND, OfflineTilePyramidRegionDefinition(DashMapSource.STYLE_URL, bounds, MIN_ZOOM, MAX_ZOOM, PIXEL_RATIO))
        return true
    }

    /** Viagem: tiles ao longo da linha da rota + um quadrado de ~8 km em volta do destino. */
    fun downloadTrip(ctx: Context, id: String, points: List<LatLng>, dest: LatLng) {
        if (points.size >= 2) {
            val line = LineString.fromLngLats(simplify(points).map { Point.fromLngLat(it.longitude, it.latitude) })
            create(ctx, "viagem:$id:rota",
                OfflineGeometryRegionDefinition(DashMapSource.STYLE_URL, line, MIN_ZOOM, MAX_ZOOM, PIXEL_RATIO))
        }
        val d = 0.04
        create(ctx, "viagem:$id:cidade", OfflineTilePyramidRegionDefinition(DashMapSource.STYLE_URL,
            LatLngBounds.from(dest.latitude + d, dest.longitude + d, dest.latitude - d, dest.longitude - d),
            MIN_ZOOM, MAX_ZOOM, PIXEL_RATIO))
    }

    fun tripComplete(id: String) = isComplete("viagem:$id:rota") && isComplete("viagem:$id:cidade")

    fun tripProgress(id: String): String? =
        progress["viagem:$id:rota"] ?: progress["viagem:$id:cidade"]

    /** Apaga os mapas de uma viagem. */
    fun deleteTrip(ctx: Context, id: String) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().filter { nameOf(it).startsWith("viagem:$id:") }.forEach { r ->
                    val n = nameOf(r)
                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { complete.remove(n); progress.remove(n); active.remove(n) }
                        override fun onError(error: String) {}
                    })
                }
            }
            override fun onError(error: String) {}
        })
    }

    private fun create(ctx: Context, name: String, def: OfflineRegionDefinition) {
        val meta = JSONObject().put("name", name).toString().toByteArray()
        progress[name] = "começando..."
        onChange?.invoke()
        OfflineManager.getInstance(ctx).createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) = watch(name, offlineRegion)
            override fun onError(error: String) {
                progress[name] = "erro: $error"
                DashLog.d("mapa offline '$name': $error")
                onChange?.invoke()
            }
        })
    }

    private fun watch(name: String, r: OfflineRegion) {
        active[name] = r
        var lastPct = -1
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val req = status.requiredResourceCount.coerceAtLeast(1)
                val pct = (100 * status.completedResourceCount / req).toInt()
                val mb = status.completedResourceSize / 1_048_576
                if (status.isComplete) {
                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    active.remove(name)
                    complete.add(name)
                    progress[name] = "baixado ($mb MB)"
                    DashLog.d("mapa offline '$name' baixado: $mb MB")
                    onChange?.invoke()
                } else if (pct / 5 != lastPct / 5) {
                    lastPct = pct
                    progress[name] = "baixando $pct% ($mb MB)"
                    if (pct % 25 == 0) DashLog.d("mapa offline '$name': $pct%")
                    onChange?.invoke()
                }
            }
            override fun onError(error: OfflineRegionError) {
                // falha de rede: o MapLibre tenta de novo sozinho quando a conexão volta
                if (error.reason != OfflineRegionError.REASON_CONNECTION) {
                    DashLog.d("mapa offline '$name': ${error.reason} ${error.message}")
                }
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                DashLog.d("mapa offline '$name': limite de $limit tiles")
            }
        })
        r.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /** Menos pontos na linha (um a cada ~300 m): a área baixada é a mesma, o pedido fica leve. */
    private fun simplify(pts: List<LatLng>): List<LatLng> {
        val out = ArrayList<LatLng>()
        val r = FloatArray(1)
        var last: LatLng? = null
        for (p in pts) {
            val l = last
            if (l == null) { out.add(p); last = p; continue }
            android.location.Location.distanceBetween(l.latitude, l.longitude, p.latitude, p.longitude, r)
            if (r[0] >= 300f) { out.add(p); last = p }
        }
        if (out.last() != pts.last()) out.add(pts.last())
        return out
    }
}
