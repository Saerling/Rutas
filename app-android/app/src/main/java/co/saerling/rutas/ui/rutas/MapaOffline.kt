package co.saerling.rutas.ui.rutas

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Descarga los mapas claro y oscuro de Bogotá (zoom 10–16) para verlos sin datos. MapLibre los usa solo cuando no hay red. */
object MapaOffline {
    sealed interface Estado {
        data object Nada : Estado
        data class Bajando(val porcentaje: Int) : Estado
        data class Listo(val bytes: Long) : Estado
        data class Fallo(val motivo: String) : Estado
    }

    var estado by mutableStateOf<Estado>(Estado.Nada); private set
    private val BOGOTA = LatLngBounds.from(4.85, -73.98, 4.45, -74.25)
    private const val NOMBRE = "bogota"

    private fun gestor(c: Context): OfflineManager { MapLibre.getInstance(c.applicationContext); return OfflineManager.getInstance(c.applicationContext) }

    fun revisar(c: Context) {
        if (ModoPrueba.activo) return
        gestor(c).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regiones: Array<OfflineRegion>?) {
                val nuestras = regiones.orEmpty().filter { String(it.metadata).startsWith(NOMBRE) }
                if (nuestras.isEmpty()) { estado = Estado.Nada; return }
                var total = 0L; var completas = 0
                nuestras.forEach { r ->
                    r.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(s: OfflineRegionStatus?) {
                            total += s?.completedResourceSize ?: 0
                            if (s?.isComplete == true) completas++
                            estado = if (completas == nuestras.size) Estado.Listo(total) else Estado.Bajando(((s?.completedResourceCount ?: 0) * 100 / (s?.requiredResourceCount ?: 1).coerceAtLeast(1)).toInt())
                            if (s?.isComplete != true) bajar(r)
                        }
                        override fun onError(error: String?) = Unit
                    })
                }
            }
            override fun onError(error: String) { estado = Estado.Nada }
        })
    }

    fun descargar(c: Context) {
        estado = Estado.Bajando(0)
        gestor(c).setOfflineMapboxTileCountLimit(20_000)
        val progreso = mutableMapOf<String, Int>()
        val densidad = c.resources.displayMetrics.density
        for (url in listOf(EstilosMapa.CLARO, EstilosMapa.OSCURO)) {
            val def = OfflineTilePyramidRegionDefinition(url, BOGOTA, 10.0, 16.0, densidad)
            gestor(c).createOfflineRegion(def, "$NOMBRE:$url".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(r: OfflineRegion) {
                    r.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(s: OfflineRegionStatus) {
                            progreso[url] = if (s.requiredResourceCount > 0) (s.completedResourceCount * 100 / s.requiredResourceCount).toInt() else 0
                            val media = progreso.values.sum() / 2
                            if (s.isComplete) { r.setDownloadState(OfflineRegion.STATE_INACTIVE); if (progreso.size == 2 && progreso.values.all { it >= 100 }) revisar(c) }
                            else estado = Estado.Bajando(media)
                        }
                        override fun onError(e: OfflineRegionError) { estado = Estado.Fallo(e.message) }
                        override fun mapboxTileCountLimitExceeded(limite: Long) { estado = Estado.Fallo("Demasiadas teselas") }
                    })
                    r.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { estado = Estado.Fallo(error) }
            })
        }
    }

    private fun bajar(r: OfflineRegion) = r.setDownloadState(OfflineRegion.STATE_ACTIVE)

    fun borrar(c: Context) {
        gestor(c).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regiones: Array<OfflineRegion>?) {
                regiones.orEmpty().filter { String(it.metadata).startsWith(NOMBRE) }.forEach { it.delete(object : OfflineRegion.OfflineRegionDeleteCallback { override fun onDelete() = Unit; override fun onError(error: String) = Unit }) }
                estado = Estado.Nada
            }
            override fun onError(error: String) = Unit
        })
    }
}
