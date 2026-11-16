package co.saerling.rutas

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ModoTema { SISTEMA, OSCURO_PURO, OSCURO, CLARO }

/** Preferencias de la app (apariencia y lugares), una sola instancia por proceso. Son estado de Compose: al cambiarlas, la app se repinta. */
class Almacen private constructor(contexto: Context) {
    companion object {
        @Volatile private var instancia: Almacen? = null
        fun obtener(c: Context): Almacen = instancia ?: synchronized(this) {
            instancia ?: Almacen(c.applicationContext).also { instancia = it }
        }
    }

    val prefs = contexto.getSharedPreferences("rutas", Context.MODE_PRIVATE)

    var paleta by mutableStateOf(prefs.getString("paleta", Paletas.porDefecto.nombre) ?: Paletas.porDefecto.nombre)
        private set

    fun elegirPaleta(nombre: String) {
        paleta = nombre
        prefs.edit().putString("paleta", nombre).apply()
    }

    var modoTema by prefEnum(prefs, "modo_tema", ModoTema.OSCURO_PURO)
}
