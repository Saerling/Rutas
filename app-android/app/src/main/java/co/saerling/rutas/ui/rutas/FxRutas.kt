package co.saerling.rutas.ui.rutas

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.saerling.rutas.data.Arrival
import co.saerling.rutas.fx.*
import co.saerling.rutas.ui.*
import kotlinx.coroutines.delay

/** Efectos de la biblioteca que usa Rutas, encendidos de entrada (luego cada uno se apaga o prende en Ajustes). */
val IdsFxRutas = listOf("slidingnumber", "textshimmer", "pulsating", "searchinput", "noise", "toast", "dialogs", "confetti", "tilt", "blurfade")

/** La primera vez se encienden todos los efectos de Rutas; después manda lo que elija cada quien. */
fun encenderFxIniciales(ctx: android.content.Context) {
    val p = ctx.getSharedPreferences("fx", android.content.Context.MODE_PRIVATE)
    if (p.getBoolean("rutas_iniciales", false)) return
    Fx.setAll(IdsFxRutas, true)
    p.edit().putBoolean("rutas_iniciales", true).apply()
}

/** Catálogo de la «Biblioteca de animaciones y funciones», con una vista previa de cada efecto tal como se ve en Rutas. */
fun catalogoRutas(): List<FxItem> {
    fun i(id: String, n: String, o: String, u: String, v: @Composable () -> Unit) = FxItem(id, n, o, u, vista = v)
    val llegada = Arrival(code = "B10", name = "Portal Norte", system = "troncal", minutes = 7, basis = "predicted")
    return listOf(
        i("slidingnumber", "Number Flow", "NumberFlow", "Los minutos de cada bus ruedan dígito a dígito al cambiar") {
            var m by remember { mutableIntStateOf(7) }
            LaunchedEffect(Unit) { while (true) { delay(1600); m = if (m <= 1) 9 else m - 1 } }
            Minutos(llegada.copy(minutes = m), grande = true)
        },
        i("textshimmer", "Text Shimmer", "Motion Primitives", "«Buscando todas las rutas…» brilla mientras el planeador trabaja") {
            TextoBrillo("Buscando todas las rutas…", LectorType.Section)
        },
        i("pulsating", "Pulsating Button", "Magic UI", "El punto verde de los datos en vivo late con un halo") { PuntoVivo(12.dp) },
        i("searchinput", "Border Beam", "Magic UI", "Un haz de luz recorre el borde del buscador mientras escribes") {
            var t by remember { mutableStateOf("") }; CampoBusqueda(t, { t = it }, "Lugar, dirección o estación")
        },
        i("noise", "Noise Texture", "Magic UI", "Grano fino en el cristal de la barra de navegación") { Text("Mira la barra de abajo", style = LectorType.Body, color = LectorColors.Muted) },
        i("toast", "Toast", "coss ui", "Los avisos de arriba (paradero guardado, bájate en la próxima) con grano y haz de luz") {
            BotonExpresivo("Mostrar aviso", Icons.Rounded.Refresh, { Avisos.mostrar("Paradero guardado") })
        },
        i("dialogs", "Dialogs", "coss ui", "La hoja de actualización y los diálogos con el estilo elegido") { Text("Se ve al buscar actualizaciones", style = LectorType.Body, color = LectorColors.Muted) },
        i("confetti", "Confetti", "Magic UI", "Confeti al llegar a tu destino en un viaje guiado") {
            val c = rememberConfeti(); Box(Modifier.fillMaxWidth().height(140.dp)) { Confeti(c, Modifier.matchParentSize()); BotonExpresivo("Llegué", null, { c.disparar() }) }
        },
        i("tilt", "Tilt", "Aceternity", "Las tarjetas de viaje se inclinan en 3D bajo el dedo") {
            Tarjeta(Modifier.inclinar(), onClick = {}) { Text("29 min", style = LectorType.Hero, color = LectorColors.Text) }
        },
        i("blurfade", "Blur Fade", "Magic UI", "Las filas aparecen subiendo con un leve desenfoque") {
            var k by remember { mutableIntStateOf(0) }
            Column { key(k) { listOf("Universidad Nacional", "Calle 57", "Marly").forEachIndexed { n, t -> Text(t, style = LectorType.Section, color = LectorColors.Text, modifier = Modifier.entrada(n).padding(vertical = 4.dp)) } }
                BotonExpresivo("Repetir", Icons.Rounded.Refresh, { k++ }) }
        },
    )
}
