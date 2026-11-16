package co.saerling.rutas.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class ParaderoGuardado(val id: String, val nombre: String, val lat: Double, val lon: Double, val codigos: List<String> = emptyList(), val estacion: Boolean = false)
@Serializable data class RutaGuardada(val id: String, val codigo: String, val nombre: String, val sistema: String)
@Serializable private data class Datos(
    val paraderos: List<ParaderoGuardado> = emptyList(), val rutas: List<RutaGuardada> = emptyList(),
    val casa: Place? = null, val trabajo: Place? = null, val recientes: List<Place> = emptyList(),
)

/** Paraderos, rutas, casa, trabajo y lugares recientes. Solo en el teléfono. Es estado de Compose. */
object Guardados {
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: android.content.SharedPreferences? = null
    private var datos by mutableStateOf(Datos())

    fun iniciar(c: Context) {
        if (prefs != null) return
        prefs = c.getSharedPreferences("guardados", Context.MODE_PRIVATE)
        datos = runCatching { json.decodeFromString<Datos>(prefs!!.getString("v1", null) ?: "{}") }.getOrDefault(Datos())
    }

    private fun guardar(nuevo: Datos) { datos = nuevo; prefs?.edit()?.putString("v1", json.encodeToString(nuevo))?.apply() }

    val paraderos get() = datos.paraderos
    val rutas get() = datos.rutas
    val casa get() = datos.casa
    val trabajo get() = datos.trabajo
    val recientes get() = datos.recientes

    fun esParadero(id: String) = datos.paraderos.any { it.id == id }
    fun esRuta(id: String) = datos.rutas.any { it.id == id }

    fun alternar(s: Stop): Boolean {
        val ya = esParadero(s.id)
        guardar(datos.copy(paraderos = if (ya) datos.paraderos.filterNot { it.id == s.id } else datos.paraderos + ParaderoGuardado(s.id, s.name, s.lat, s.lon, s.routes.take(6).map { it.code }, s.kind == "station")))
        return !ya
    }

    fun alternar(r: RouteInfo): Boolean {
        val ya = esRuta(r.id)
        guardar(datos.copy(rutas = if (ya) datos.rutas.filterNot { it.id == r.id } else datos.rutas + RutaGuardada(r.id, r.code, r.name, r.system)))
        return !ya
    }

    fun quitarParadero(id: String) = guardar(datos.copy(paraderos = datos.paraderos.filterNot { it.id == id }))
    fun quitarRuta(id: String) = guardar(datos.copy(rutas = datos.rutas.filterNot { it.id == id }))
    fun ponerCasa(p: Place?) = guardar(datos.copy(casa = p))
    fun ponerTrabajo(p: Place?) = guardar(datos.copy(trabajo = p))
    fun recordar(p: Place) = guardar(datos.copy(recientes = (listOf(p) + datos.recientes.filterNot { it.id == p.id }).take(8)))
}
