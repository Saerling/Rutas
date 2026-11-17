package co.saerling.rutas.data

import co.saerling.rutas.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class ApiError(val codigo: String, mensaje: String) : Exception(mensaje)

/**
 * Si la app está a la vista. Las consultas de las pantallas esperan aquí mientras está en segundo plano o con la
 * pantalla apagada: ningún sondeo gasta datos ni batería sin que lo veas. Los servicios (avisos, guía de viaje)
 * consultan dentro de [EnFondo] y no esperan.
 */
object Visible {
    val en = kotlinx.coroutines.flow.MutableStateFlow(true)
}

/** Marca una consulta hecha a propósito en segundo plano (servicio de avisos). */
object EnFondo : kotlin.coroutines.AbstractCoroutineContextElement(ClaveFondo)
/** Clave aparte: un objeto no puede ser su propia clave (se pasaría a sí mismo sin terminar de crearse). */
object ClaveFondo : kotlin.coroutines.CoroutineContext.Key<EnFondo>

/** Cliente del servidor de Rutas. Las coordenadas salen del teléfono redondeadas a una cuadra (3 decimales). */
object Api {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun r(n: Double) = String.format(Locale.US, "%.3f", n)
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Solo para las capturas de prueba: devuelve el JSON de cada ruta sin salir a la red. */
    var simulado: ((String) -> String?)? = null

    private suspend inline fun <reified T> get(ruta: String): T {
        if (kotlin.coroutines.coroutineContext[ClaveFondo] == null && simulado == null) Visible.en.first { it }
        return pedir(ruta)
    }

    private suspend inline fun <reified T> pedir(ruta: String): T = withContext(Dispatchers.IO) {
        simulado?.invoke(ruta)?.let { return@withContext json.decodeFromString<T>(it) }
        http.newCall(Request.Builder().url(BuildConfig.API + ruta).header("Accept", "application/json").build()).execute().use { res ->
            val cuerpo = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val codigo = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(cuerpo)?.groupValues?.get(1) ?: "http_${res.code}"
                throw ApiError(codigo, "El servidor respondió ${res.code}")
            }
            json.decodeFromString<T>(cuerpo)
        }
    }

    suspend fun salud(): Health = get("/api/health")
    suspend fun cerca(lat: Double, lon: Double): Nearby = get("/api/nearby?lat=${r(lat)}&lon=${r(lon)}")
    suspend fun buscar(texto: String): SearchResult = get("/api/search?q=${q(texto)}")
    suspend fun paradero(id: String): StopDetail = get("/api/stops/${q(id)}")
    suspend fun ruta(id: String): RouteDetail = get("/api/routes/${q(id)}")
    suspend fun novedades(): Novedades = get("/api/alerts")
    suspend fun vehiculos(lat: Double, lon: Double, radio: Int = 1500): Vehiculos = get("/api/vehicles?lat=${r(lat)}&lon=${r(lon)}&radius=$radio")
    suspend fun trafico(): Trafico = get("/api/traffic")
    suspend fun noticias(): Noticias = get("/api/news")
    suspend fun incidentes(): Incidentes = get("/api/incidents")
    /** Plantilla de los mosaicos de flujo de TomTom (pasan por el servidor para no poner la clave en la app). */
    fun mosaicosTrafico(oscuro: Boolean) = BuildConfig.API + "/api/tiles?z={z}&x={x}&y={y}" + if (oscuro) "&dark=1" else ""
    suspend fun lugares(texto: String, lat: Double?, lon: Double?): List<Place> =
        get("/api/places?q=${q(texto)}" + if (lat != null && lon != null) "&lat=${r(lat)}&lon=${r(lon)}" else "")

    suspend fun plan(
        desde: Pair<Double, Double>, hasta: Pair<Double, Double>, horaIso: String? = null,
        llegarA: Boolean = false, accesible: Boolean = false, menosTransbordos: Boolean = false, cursor: String? = null,
    ): PlanResult {
        val p = buildString {
            append("/api/plan?fromLat=${r(desde.first)}&fromLon=${r(desde.second)}&toLat=${r(hasta.first)}&toLon=${r(hasta.second)}")
            if (horaIso != null) append("&time=${q(horaIso)}")
            if (llegarA) append("&arriveBy=1")
            if (accesible) append("&wheelchair=1")
            if (menosTransbordos) append("&maxTransfers=1")
            if (cursor != null) append("&cursor=${q(cursor)}")
        }
        return get(p)
    }
}
