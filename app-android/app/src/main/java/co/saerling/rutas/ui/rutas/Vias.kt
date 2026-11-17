package co.saerling.rutas.ui.rutas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.saerling.rutas.data.*
import co.saerling.rutas.ui.*
import kotlinx.coroutines.delay

/** Efectos de las novedades que afectan las vías (no los cambios de horario). */
private val VIALES = setOf("detour", "no_service", "significant_delays", "stop_moved", "reduced_service", "modified_service")

/**
 * Estado de las vías, como el tráfico de Waze/Google Maps pero con lo que hay abierto: la velocidad real de los buses
 * SITP en vivo (los troncales van por su carril y no cuentan), por zonas de toda Bogotá, más los desvíos y cierres.
 */
@Composable
fun ViasScreen(abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var dato by remember { mutableStateOf<Trafico?>(null) }
    var error by remember { mutableStateOf(false) }
    var intento by remember { mutableIntStateOf(0) }
    LaunchedEffect(intento) {
        error = false
        // La primera consulta a veces no tiene velocidades aún (se miden entre dos reportes): se vuelve a pedir pronto.
        var espera = 8_000L
        while (true) {
            runCatching { Api.trafico() }.onSuccess { n -> if (n.cells.isNotEmpty() || dato == null || !n.feed.live) dato = n; error = false }.onFailure { if (dato == null) error = true }
            delay(if (dato?.cells.isNullOrEmpty()) espera.also { espera = 20_000L } else 30_000L)
        }
    }
    var novedades by remember { mutableStateOf<List<Alert>?>(null) }
    LaunchedEffect(Unit) { while (true) { runCatching { Api.novedades().alerts }.onSuccess { novedades = it }; delay(120_000) } }
    var foco by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    MapaConHoja(
        onBack = onBack,
        asomo = 0.48f,
        mapa = { abajo, pedido ->
            Mapa(
                Modifier.fillMaxSize(), centro = Ubicacion.BOGOTA, zoom = 11.2,
                zonas = dato?.cells.orEmpty(), yo = Ubicacion.actual,
                encuadrar = listOfNotNull(foco),
                abajo = abajo, pedido = pedido, centrarEn = Ubicacion.actual,
            )
        },
        contenido = {
            val d = dato
            item("t") {
                Column(Modifier.padding(horizontal = Margen)) {
                    Text("Estado de las vías", style = LectorType.Hero, color = LectorColors.Text)
                    Text("Medido con la velocidad de los buses SITP en vivo. Los troncales no cuentan: van por su carril.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            when {
                error && d == null -> item("e") { Vacio("No pudimos medir el tráfico", "Revisa tu conexión.", "Reintentar") { intento++ } }
                d == null -> item("c") { CargandoFilas(3) }
                !d.feed.live -> item("nv") { Vacio("Sin datos en vivo ahora", "TransMilenio no está publicando la posición de los buses.") }
                d.cells.isEmpty() -> item("mid") { Vacio("Midiendo…", "Comparamos dos reportes seguidos de cada bus; en unos segundos aparece.") }
                else -> {
                    val lentas = d.cells.filter { it.level == "lento" }.sortedBy { it.kmh }
                    val moderadas = d.cells.count { it.level == "moderado" }
                    val fluidas = d.cells.count { it.level == "fluido" }
                    item("resumen") {
                        Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 14.dp), radio = 26.dp, color = LectorColors.CardHigh) {
                            val nivel = d.kmh?.let { nivelTrafico(it) }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${d.kmh ?: "—"}", style = LectorType.Display, color = colorTrafico(nivel))
                                Text(" km/h", style = LectorType.Section, color = LectorColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
                                Spacer(Modifier.weight(1f))
                                PuntoVivo(); Spacer(Modifier.width(6.dp))
                                Text("${d.n} buses", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            Text(when (nivel) { "fluido" -> "Bogotá se mueve bien"; "moderado" -> "Bogotá va a media marcha"; "lento" -> "Bogotá está trancada"; else -> "Velocidad promedio de los buses" }, style = LectorType.Section, color = LectorColors.Text)
                            Spacer(Modifier.height(14.dp))
                            BarraNiveles(fluidas, moderadas, lentas.size)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Leyenda("fluido", "$fluidas fluidas"); Leyenda("moderado", "$moderadas a media"); Leyenda("lento", "${lentas.size} trancadas")
                            }
                        }
                    }
                    if (lentas.isNotEmpty()) {
                        item("l-t") { Seccion("Dónde está más lento") }
                        items(lentas.take(12).size) { i ->
                            val z = lentas[i]
                            Row(Modifier.fillMaxWidth().resaltable { foco = z.lat to z.lon }.padding(horizontal = Margen, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(SuaveShape(12.dp)).background(colorTrafico(z.level).copy(alpha = 0.16f)), Alignment.Center) {
                                    Text("${z.kmh}", style = LectorType.Section.copy(fontWeight = FontWeight.Bold), color = colorTrafico(z.level))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(z.near.ifBlank { "Zona sin nombre" }, style = LectorType.Section, color = LectorColors.Text, maxLines = 1)
                                    Text("${z.kmh} km/h · ${z.n} buses medidos", style = LectorType.Label, color = LectorColors.Muted)
                                }
                                Icon(Icons.Rounded.CenterFocusStrong, "Ver en el mapa", tint = LectorColors.Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            val viales = novedades.orEmpty().filter { it.effect in VIALES }
            if (viales.isNotEmpty()) {
                item("n-t") { Seccion("Desvíos y cierres") }
                items(viales.take(15).size) { i ->
                    val a = viales[i]
                    Tarjeta(Modifier.padding(horizontal = Margen).padding(bottom = 8.dp), radio = 20.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (a.effect) { "detour" -> Icons.Rounded.AltRoute; "no_service" -> Icons.Rounded.Block; "stop_moved" -> Icons.Rounded.WrongLocation; else -> Icons.Rounded.Warning }, null, tint = Color(0xFFF2A33D), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(a.title.ifBlank { "Novedad del servicio" }, style = LectorType.Section, color = LectorColors.Text, modifier = Modifier.weight(1f))
                        }
                        if (a.detail.isNotBlank()) Text(a.detail, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp), maxLines = 4)
                    }
                }
                item("n-m") {
                    Text("Ver todas las novedades", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = Margen, vertical = 8.dp).clip(SuaveShape(10.dp)).pressable { abrir(Capa.Novedades) }.padding(vertical = 6.dp))
                }
            }
        },
    )
}

@Composable
private fun BarraNiveles(fluidas: Int, moderadas: Int, lentas: Int) {
    val total = (fluidas + moderadas + lentas).coerceAtLeast(1).toFloat()
    Row(Modifier.fillMaxWidth().height(10.dp).clip(SuaveShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("fluido" to fluidas, "moderado" to moderadas, "lento" to lentas).filter { it.second > 0 }.forEach { (n, v) ->
            Box(Modifier.weight(v / total).fillMaxHeight().background(colorTrafico(n)))
        }
    }
}

@Composable
private fun Leyenda(nivel: String, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(SuaveShape(4.dp)).background(colorTrafico(nivel)))
        Spacer(Modifier.width(5.dp))
        Text(texto, style = LectorType.Label, color = LectorColors.Muted)
    }
}
