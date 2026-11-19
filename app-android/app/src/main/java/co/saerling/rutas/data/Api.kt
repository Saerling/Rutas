package co.saerling.rutas.data

import co.saerling.rutas.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
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

    /**
     * [vigencia]: cuánto sirve una respuesta ya traída. Varias pantallas piden lo mismo (Cerca, Guardados y el detalle
     * piden el mismo paradero; Cerca y Vías, las mismas novedades): dentro de ese plazo no se vuelve a salir a la red.
     */
    private suspend inline fun <reified T> get(ruta: String, vigencia: Long = 0L): T {
        if (kotlin.coroutines.coroutineContext[ClaveFondo] == null && simulado == null) Visible.en.first { it }
        return json.decodeFromString<T>(texto(ruta, vigencia))
    }

    private class Guardado(val cuerpo: String, val en: Long)
    /** Últimas respuestas (máximo 64, la más vieja sale primero). */
    private val memoria = object : LinkedHashMap<String, Guardado>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Guardado>?) = size > 64
    }
    /** Una petición en curso y cuántas pantallas la esperan: si dos piden lo mismo a la vez, viaja una sola. */
    private class EnCurso(val tarea: kotlinx.coroutines.Deferred<String>, var esperando: Int = 0)
    private val enCurso = HashMap<String, EnCurso>()
    private val candado = kotlinx.coroutines.sync.Mutex()
    private val alcance = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private suspend fun texto(ruta: String, vigencia: Long): String {
        simulado?.invoke(ruta)?.let { return it }
        val viaje = candado.withLock {
            if (vigencia > 0) memoria[ruta]?.takeIf { System.currentTimeMillis() - it.en < vigencia }?.let { return it.cuerpo }
            val v = enCurso.getOrPut(ruta) {
                EnCurso(alcance.async {
                    try {
                        descargar(ruta).also { c -> if (vigencia > 0) candado.withLock { memoria[ruta] = Guardado(c, System.currentTimeMillis()) } }
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { candado.withLock { enCurso.remove(ruta) } }
                    }
                })
            }
            v.esperando++
            v
        }
        try {
            return viaje.tarea.await()
        } finally {
            // Si ya nadie espera esta respuesta (se cerró la pantalla, se siguió escribiendo), se corta la descarga.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                candado.withLock { viaje.esperando--; if (viaje.esperando == 0 && viaje.tarea.isActive) viaje.tarea.cancel() }
            }
        }
    }

    /** La llamada se cancela de verdad si nadie la espera (p. ej. al seguir escribiendo en Buscar): no gasta datos. */
    private suspend fun descargar(ruta: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val llamada = http.newCall(Request.Builder().url(BuildConfig.API + ruta).header("Accept", "application/json").build())
        cont.invokeOnCancellation { llamada.cancel() }
        llamada.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val r = runCatching {
                    response.use { res ->
                        val cuerpo = res.body?.string().orEmpty()
                        if (!res.isSuccessful) {
                            val codigo = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(cuerpo)?.groupValues?.get(1) ?: "http_${res.code}"
                            throw ApiError(codigo, "El servidor respondió ${res.code}")
                        }
                        cuerpo
                    }
                }
                if (cont.isActive) cont.resumeWith(r)
            }
        })
    }

    suspend fun salud(): Health = get("/api/health", 30_000)
    suspend fun cerca(lat: Double, lon: Double): Nearby = get("/api/nearby?lat=${r(lat)}&lon=${r(lon)}", 60_000)
    suspend fun buscar(texto: String): SearchResult = get("/api/search?q=${q(texto)}", 5 * 60_000)
    suspend fun paradero(id: String): StopDetail = get("/api/stops/${q(id)}", 8_000)
    suspend fun ruta(id: String): RouteDetail = get("/api/routes/${q(id)}", 8_000)
    suspend fun novedades(): Novedades = get("/api/alerts", 60_000)
    suspend fun vehiculos(lat: Double, lon: Double, radio: Int = 1500): Vehiculos = get("/api/vehicles?lat=${r(lat)}&lon=${r(lon)}&radius=$radio", 8_000)
    suspend fun trafico(): Trafico = get("/api/traffic", 20_000)
    suspend fun noticias(): Noticias = get("/api/news", 5 * 60_000)
    suspend fun incidentes(): Incidentes = get("/api/incidents", 5 * 60_000)
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
