package co.saerling.rutas.ui.rutas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.saerling.rutas.data.*
import co.saerling.rutas.ui.*
import kotlinx.coroutines.delay

/** Lo que se abre encima de las pestañas. */
sealed interface Capa {
    data class Paradero(val id: String) : Capa
    data class Ruta(val id: String) : Capa
    data class Viaje(val it: Itinerary) : Capa
    data object Personalizacion : Capa
    data object BibliotecaFx : Capa
    /** No es una página: pide ir a la pestaña Ir con el destino ya puesto. */
    data object IrA : Capa
}

/**
 * Detalle como en Moovit/Citymapper: el mapa arriba y una hoja que sube sobre él al hacer scroll. La hoja tiene su
 * propio fondo (el mapa nunca se ve detrás del texto) y, cuando cubre el mapa, aparece una barra superior sólida.
 */
@Composable
private fun DetalleConMapa(
    mapa: @Composable () -> Unit,
    onBack: () -> Unit,
    accion: (@Composable () -> Unit)? = null,
    fijo: (@Composable () -> Unit)? = null,
    contenido: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val estado = androidx.compose.foundation.lazy.rememberLazyListState()
    val densidad = androidx.compose.ui.platform.LocalDensity.current
    val altoMapa = (LocalContext.current.resources.displayMetrics.let { it.heightPixels / it.density } * 0.42f).dp
    val fondo = LectorColors.Bg
    // Cuánto subió la hoja: 0 con el mapa a la vista, 1 cuando la hoja llega arriba.
    val cubierto by remember { derivedStateOf {
        val hoja = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hoja" }
        if (hoja == null) 1f else (1f - hoja.offset / with(densidad) { (altoMapa - 28.dp).toPx() }).coerceIn(0f, 1f)
    } }
    Box(Modifier.fillMaxSize().background(fondo)) {
        Box(Modifier.fillMaxWidth().height(altoMapa).graphicsLayer { translationY = -cubierto * 120f }) { mapa() }
        LazyColumn(
            Modifier.fillMaxSize().drawBehind {
                val hoja = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hoja" }
                val arriba = when { hoja != null -> hoja.offset.toFloat() + 29.dp.toPx(); estado.firstVisibleItemIndex > 1 -> 0f; else -> size.height }
                drawRect(fondo, topLeft = androidx.compose.ui.geometry.Offset(0f, arriba), size = androidx.compose.ui.geometry.Size(size.width, size.height - arriba))
            },
            state = estado,
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item("hueco") { Spacer(Modifier.fillMaxWidth().height(altoMapa - 28.dp)) }
            item("hoja") {
                // La hoja que sube sobre el mapa: esquinas de arriba redondas y el asa de Mochy.
                Box(Modifier.fillMaxWidth().height(30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(fondo), Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(SuaveShape(2.dp)).background(LectorColors.PillFill))
                }
            }
            contenido()
        }
        // Barra superior: transparente sobre el mapa, sólida cuando la hoja la alcanza.
        Column(Modifier.fillMaxWidth().background(fondo.copy(alpha = ((cubierto - 0.85f) / 0.15f).coerceIn(0f, 1f)))) {
            Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(SuaveShape(15.dp)).background(LectorColors.Glass).pressable(onBack), Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", tint = LectorColors.Text)
                }
                Spacer(Modifier.weight(1f))
                accion?.invoke()
            }
            fijo?.invoke()
        }
    }
}

@Composable
private fun BotonEstrella(guardado: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(SuaveShape(15.dp)).background(LectorColors.Glass).pressable { Tacto.alternar(!guardado); onClick() }, Alignment.Center) {
        Icon(if (guardado) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (guardado) "Quitar de guardados" else "Guardar", tint = if (guardado) MaterialTheme.colorScheme.primary else LectorColors.Text)
    }
}

// ───────────────────────────────────────── Paradero ─────────────────────────────────────────

@Composable
fun ParaderoScreen(id: String, abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var d by remember { mutableStateOf<StopDetail?>(null) }
    var error by remember { mutableStateOf(false) }
    var solo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { while (true) { runCatching { Api.paradero(id) }.onSuccess { d = it; error = false }.onFailure { if (d == null) error = true }; delay(10_000) } }
    val det = d
    DetalleConMapa(
        mapa = {
            val s = det?.stop
            if (s != null) Mapa(
                Modifier.fillMaxSize(), centro = s.lat to s.lon, zoom = 15.5,
                puntos = listOf(PuntoMapa(s.id, s.lat, s.lon, true)),
                buses = if (det.feed.live) det.incoming.filter { solo == null || it.code == solo } else emptyList(),
                yo = Ubicacion.actual, encuadrar = listOf(s.lat to s.lon),
            ) else Box(Modifier.fillMaxSize().background(LectorColors.Card))
        },
        onBack = onBack,
        accion = det?.let { x -> { BotonEstrella(Guardados.esParadero(x.stop.id)) { val g = Guardados.alternar(x.stop); Avisos.mostrar(if (g) "Paradero guardado" else "Quitado de guardados") } } },
    ) {
        when {
            error -> item("e") { Vacio("No pudimos cargar el paradero", "Revisa tu conexión.") }
            det == null -> item("c") { CargandoFilas(5) }
            else -> {
                item("titulo") {
                    Column(Modifier.padding(horizontal = Margen)) {
                        Text(det.stop.name, style = LectorType.Display.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)), color = LectorColors.Text)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(if (det.stop.kind == "station") "Estación" else "Paradero SITP", style = LectorType.Body, color = LectorColors.Muted)
                            if (det.stop.accessible) { Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.Accessible, null, tint = LectorColors.Muted, modifier = Modifier.size(16.dp)); Text(" Accesible", style = LectorType.Body, color = LectorColors.Muted) }
                        }
                    }
                }
                val codigos = det.arrivals.map { it.code }.distinct()
                if (codigos.size > 1) item("filtro") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipSel("Todas", solo == null) { solo = null }
                        codigos.forEach { c -> ChipSel(c, solo == c) { solo = if (solo == c) null else c } }
                    }
                }
                val lista = det.arrivals.filter { solo == null || it.code == solo }
                val primero = lista.firstOrNull()
                if (primero == null) item("nada") { Vacio("Sin buses próximos", "Puede que ya no pasen rutas a esta hora.") }
                else item("proximo") {
                    Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 14.dp), radio = 28.dp, color = LectorColors.CardHigh, onClick = { primero.routeId?.let { abrir(Capa.Ruta(it)) } }) {
                        Text("PRÓXIMO", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Muted)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Insignia(primero.code, primero.system, grande = true)
                            Spacer(Modifier.width(12.dp))
                            Text(primero.name, style = LectorType.Section, color = LectorColors.Text, modifier = Modifier.weight(1f), maxLines = 2)
                            Minutos(primero, grande = true)
                        }
                        Text(puntualidad(primero), style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (!det.feed.live) item("feed") { Text("TransMilenio no está publicando en vivo: los tiempos son del horario.", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 10.dp)) }
                det.alerts.forEach { a -> item("al" + a.id) { TarjetaAlerta(a) } }
                if (lista.size > 1) item("luego") { Seccion("Después") }
                items(lista.drop(1).take(12), key = { "${it.code}-${it.at}-${it.vehicle}" }) { a ->
                    Row(Modifier.fillMaxWidth().resaltable { a.routeId?.let { abrir(Capa.Ruta(it)) } }.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.widthIn(min = 64.dp)) { Insignia(a.code, a.system) }
                        Text(a.name, style = LectorType.Body, color = LectorColors.Text, modifier = Modifier.weight(1f), maxLines = 1)
                        Minutos(a)
                    }
                }
                item("rutas-t") { Seccion("Rutas que paran aquí") }
                items(det.stop.routes, key = { "r" + it.id }) { r -> FilaRuta(r) { abrir(Capa.Ruta(r.id)) } }
            }
        }
    }
}

private fun puntualidad(a: Arrival): String = when {
    a.basis == "scheduled" -> "Según horario"
    a.delaySeconds == null -> "Estimado en vivo"
    a.delaySeconds > 90 -> "${a.delaySeconds / 60} min tarde"
    a.delaySeconds < -90 -> "${-a.delaySeconds / 60} min antes"
    else -> "A tiempo · en vivo"
}

@Composable
private fun TarjetaAlerta(a: Alert) {
    Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 10.dp), radio = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = androidx.compose.ui.graphics.Color(0xFFF2A33D), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(a.title.ifBlank { "Novedad del servicio" }, style = LectorType.Section, color = LectorColors.Text)
        }
        if (a.detail.isNotBlank()) Text(a.detail, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

// ───────────────────────────────────────── Ruta ─────────────────────────────────────────

@Composable
fun RutaScreen(id: String, abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var d by remember { mutableStateOf<RouteDetail?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(id) { while (true) { runCatching { Api.ruta(id) }.onSuccess { d = it; error = false }.onFailure { if (d == null) error = true }; delay(10_000) } }
    val det = d
    DetalleConMapa(
        mapa = {
            if (det != null) {
                val color = colorSistema(det.route.system)
                Mapa(
                    Modifier.fillMaxSize(), centro = det.stops.firstOrNull()?.let { it.lat to it.lon } ?: Ubicacion.BOGOTA, zoom = 12.0,
                    puntos = det.stops.map { PuntoMapa(it.id, it.lat, it.lon) }, tramos = listOf(TramoMapa(det.path, color)),
                    buses = if (det.feed.live) det.buses.map { it.copy(code = det.route.code, system = det.route.system) } else emptyList(),
                    yo = Ubicacion.actual, encuadrar = det.path.map { it[1] to it[0] }.let { p -> if (p.size > 2) listOf(p.first(), p[p.size / 2], p.last()) else p },
                    alTocarPunto = { abrir(Capa.Paradero(it)) },
                )
            } else Box(Modifier.fillMaxSize().background(LectorColors.Card))
        },
        onBack = onBack,
        accion = det?.let { x -> { BotonEstrella(Guardados.esRuta(x.route.id)) { val g = Guardados.alternar(x.route); Avisos.mostrar(if (g) "Ruta guardada" else "Quitada de guardados") } } },
    ) {
        when {
            error -> item("e") { Vacio("No pudimos cargar la ruta", "Revisa tu conexión.") }
            det == null -> item("c") { CargandoFilas(6) }
            else -> {
                item("t") {
                    Column(Modifier.padding(horizontal = Margen)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Insignia(det.route.code, det.route.system, grande = true)
                            Spacer(Modifier.width(10.dp))
                            Text(nombreSistema(det.route.system), style = LectorType.Body, color = LectorColors.Muted)
                        }
                        Text(det.route.name, style = LectorType.Hero, color = LectorColors.Text, modifier = Modifier.padding(top = 10.dp))
                        val horario = listOfNotNull(det.route.firstDeparture?.let { "Primer bus $it" }, det.route.lastDeparture?.let { "último $it" }).joinToString(" · ")
                        if (horario.isNotBlank()) Text(horario, style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (det.feed.live) { PuntoVivo(); Spacer(Modifier.width(6.dp)) }
                            Text(if (det.feed.live) "${det.buses.size} buses en vía, en vivo" else "Sin datos en vivo ahora", style = LectorType.Label, color = LectorColors.Text)
                        }
                    }
                }
                det.alerts.forEach { a -> item("al" + a.id) { TarjetaAlerta(a) } }
                item("pt") { Seccion("Paradas") }
                val color = colorSistema(det.route.system)
                items(det.stops, key = { it.id }) { s ->
                    val i = det.stops.indexOf(s)
                    Row(Modifier.fillMaxWidth().resaltable { abrir(Capa.Paradero(s.id)) }.padding(horizontal = Margen), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(24.dp).height(48.dp), Alignment.Center) {
                            Box(Modifier.width(4.dp).fillMaxHeight(if (i == 0 || i == det.stops.lastIndex) 0.5f else 1f).align(if (i == 0) Alignment.BottomCenter else if (i == det.stops.lastIndex) Alignment.TopCenter else Alignment.Center).background(color))
                            Box(Modifier.size(12.dp).clip(SuaveShape(6.dp)).background(LectorColors.Bg).padding(2.dp).clip(SuaveShape(4.dp)).background(color))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(s.name, style = LectorType.Body, color = LectorColors.Text, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────── Viaje ─────────────────────────────────────────

private sealed interface Guia {
    data object Esperando : Guia
    data class Caminando(val tramo: Int, val metros: Int) : Guia
    data class EnBus(val tramo: Int, val faltan: Int, val bajarYa: Boolean) : Guia
    data object Llegaste : Guia
}

private fun guiar(it: Itinerary, yo: Pair<Double, Double>): Guia {
    val ultimo = it.legs.last()
    if (Ubicacion.metros(yo, ultimo.to.lat to ultimo.to.lon) < 120) return Guia.Llegaste
    val i = it.legs.indexOfFirst { l -> Ubicacion.metros(yo, l.to.lat to l.to.lon) > 120 }.let { x -> if (x < 0) it.legs.lastIndex else x }
    val l = it.legs[i]
    if (l.mode != "transit") return Guia.Caminando(i, Ubicacion.metros(yo, l.to.lat to l.to.lon).toInt())
    val todas = l.stops + l.to
    val cercana = todas.indices.minByOrNull { k -> Ubicacion.metros(yo, todas[k].lat to todas[k].lon) } ?: 0
    val pasada = if (Ubicacion.metros(yo, todas[cercana].lat to todas[cercana].lon) < 120) cercana + 1 else cercana
    val faltan = (todas.size - pasada).coerceAtLeast(0)
    return Guia.EnBus(i, faltan, faltan <= 1)
}

@Composable
fun ViajeScreen(it: Itinerary, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var guiando by remember { mutableStateOf(false) }
    val yo = Ubicacion.actual
    val guia = if (guiando && yo != null) guiar(it, yo) else Guia.Esperando
    val avisados = remember { mutableSetOf<Int>() }
    val vista = LocalView.current
    DisposableEffect(guiando) { vista.keepScreenOn = guiando; onDispose { vista.keepScreenOn = false } }
    LaunchedEffect(guia) {
        if (guia is Guia.EnBus && guia.bajarYa && avisados.add(guia.tramo)) {
            Tacto.rechazar(); Avisos.mostrar("Bájate en la próxima: ${it.legs[guia.tramo].to.name}")
        }
    }
    LaunchedEffect(Unit) { Ubicacion.empezar(ctx) }
    val confeti = co.saerling.rutas.fx.rememberConfeti()
    LaunchedEffect(guia) { if (guia is Guia.Llegaste && co.saerling.rutas.fx.Fx.on("confetti")) { Tacto.alternar(true); confeti.disparar() } }
    Box { DetalleConMapa(
        mapa = {
            Mapa(
                Modifier.fillMaxSize(), centro = it.legs.first().from.let { s -> s.lat to s.lon }, zoom = 13.0,
                puntos = it.legs.filter { l -> l.mode == "transit" }.flatMapIndexed { k, l -> listOf(PuntoMapa("a$k", l.from.lat, l.from.lon, true), PuntoMapa("b$k", l.to.lat, l.to.lon, true)) },
                tramos = it.legs.map { l -> TramoMapa(l.path, if (l.mode == "transit") colorSistema(l.system) else LectorColors.Muted, discontinuo = l.mode != "transit") },
                yo = yo, encuadrar = if (guiando && yo != null) listOf(yo) else it.legs.flatMap { l -> listOf(l.from.lat to l.from.lon, l.to.lat to l.to.lon) },
            )
        },
        onBack = onBack,
        // Mientras viajas, la guía queda fija arriba, como en Moovit: siempre a la vista aunque bajes por los pasos.
        fijo = if (guiando) { { TarjetaGuia(it, guia) { Tacto.alternar(false); guiando = false } } } else null,
    ) {
        item("t") {
            Column(Modifier.padding(horizontal = Margen)) {
                Text(duracion(it.minutes), style = LectorType.Display, color = LectorColors.Text)
                Text("${hora(it.start)} – ${hora(it.end)} · ${if (it.transfers == 0) "directo" else "${it.transfers} transbordo${if (it.transfers > 1) "s" else ""}"} · ${it.walkMetres} m a pie", style = LectorType.Body, color = LectorColors.Muted)
                val p = it.pasaje()
                if (p > 0) Text("Pasaje aproximado ${pesos(p)} (sin descuentos de transbordo)", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                if (!guiando) BotonExpresivo("Empezar viaje", Icons.Rounded.Navigation, {
                    Tacto.alternar(true); guiando = true
                    Avisos.mostrar("Te avisamos cuando debas bajarte. Deja la app abierta.")
                }, Modifier.fillMaxWidth(), estilo = EstiloBoton.RELLENO, alto = 54.dp)
            }
        }
        item("pasos") { Seccion("Paso a paso") }
        items(it.legs.size, key = { i -> "paso$i" }) { i ->
            val l = it.legs[i]
            val actual = guia.let { g -> (g is Guia.EnBus && g.tramo == i) || (g is Guia.Caminando && g.tramo == i) }
            PasoViaje(l, actual, Modifier.revelar("paso" + it.id + i, i * 40))
        }
        item("fin") {
            Row(Modifier.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(64.dp), Alignment.Center) { Icon(Icons.Rounded.Flag, null, tint = LectorColors.Text) }
                Spacer(Modifier.width(10.dp))
                Text("Llegas a ${it.legs.last().to.name}", style = LectorType.Section, color = LectorColors.Text)
            }
        }
    }
    co.saerling.rutas.fx.Confeti(confeti, Modifier.matchParentSize()) }
}

@Composable
private fun TarjetaGuia(it: Itinerary, guia: Guia, terminar: () -> Unit) {
    val (titulo, detalle, urgente) = when (guia) {
        Guia.Esperando -> Triple("Buscando tu ubicación…", "Activa la ubicación para que te avisemos.", false)
        Guia.Llegaste -> Triple("Llegaste", "¡Buen viaje!", false)
        is Guia.Caminando -> Triple("Camina hasta ${it.legs[guia.tramo].to.name}", "Faltan ${guia.metros} m", false)
        is Guia.EnBus -> {
            val l = it.legs[guia.tramo]
            if (guia.bajarYa) Triple("Bájate en la próxima", l.to.name, true)
            else Triple("${guia.faltan} paradas", "En el ${codigoVisible(l.code, l.headsign)} · bájate en ${l.to.name}", false)
        }
    }
    val acento = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(SuaveShape(24.dp))
            .background(if (urgente) acento else LectorColors.CardHigh).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PuntoVivo()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = LectorType.Hero, color = if (urgente) MaterialTheme.colorScheme.onPrimary else LectorColors.Text)
            Text(detalle, style = LectorType.Body, color = if (urgente) MaterialTheme.colorScheme.onPrimary else LectorColors.Muted, maxLines = 2)
        }
        Box(Modifier.size(42.dp).clip(SuaveShape(14.dp)).background(LectorColors.PillFill).pressable(terminar), Alignment.Center) {
            Icon(Icons.Rounded.Close, "Terminar viaje", tint = if (urgente) MaterialTheme.colorScheme.onPrimary else LectorColors.Text)
        }
    }
}

private val GIROS = mapOf(
    "CONTINUE" to "Sigue derecho", "LEFT" to "Gira a la izquierda", "RIGHT" to "Gira a la derecha",
    "SLIGHTLY_LEFT" to "Ve un poco a la izquierda", "SLIGHTLY_RIGHT" to "Ve un poco a la derecha",
    "HARD_LEFT" to "Gira cerrado a la izquierda", "HARD_RIGHT" to "Gira cerrado a la derecha",
    "UTURN_LEFT" to "Da la vuelta", "UTURN_RIGHT" to "Da la vuelta", "CIRCLE_CLOCKWISE" to "Toma la glorieta",
    "CIRCLE_COUNTERCLOCKWISE" to "Toma la glorieta", "ELEVATOR" to "Toma el ascensor", "STAIRS" to "Toma las escaleras",
)

@Composable
private fun PasoViaje(l: Leg, actual: Boolean, modifier: Modifier) {
    val color = if (l.mode == "transit") colorSistema(l.system) else LectorColors.Muted
    var abierto by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = Margen, vertical = 8.dp).height(IntrinsicSize.Min)) {
        Column(Modifier.width(64.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (l.mode == "transit") Insignia(codigoVisible(l.code, l.headsign), l.system) else Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = LectorColors.Muted)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(if (l.mode == "transit") 4.dp else 3.dp).weight(1f).clip(SuaveShape(2.dp)).background(color.copy(alpha = if (l.mode == "transit") 1f else 0.35f)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clip(SuaveShape(18.dp)).background(if (actual) LectorColors.CardHigh else androidx.compose.ui.graphics.Color.Transparent).padding(if (actual) 10.dp else 0.dp)) {
            if (l.mode == "transit") {
                val destino = destinoVisible(l)
                Text("Toma el ${codigoVisible(l.code, l.headsign)}${destino?.let { " hacia $it" } ?: ""}", style = LectorType.Section, color = LectorColors.Text)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (l.live) { PuntoVivo(7.dp); Spacer(Modifier.width(6.dp)) }
                    Text("En ${l.from.name} · ${hora(l.start)} · ${if (l.live) "en vivo" else "según horario"}", style = LectorType.Label, color = LectorColors.Muted)
                }
                Row(Modifier.padding(top = 6.dp).clip(SuaveShape(10.dp)).pressable { abierto = !abierto }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${l.stops.size + 1} paradas · ${duracion(l.minutes)}", style = LectorType.Label, color = LectorColors.Text)
                    Icon(if (abierto) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (abierto) "Ocultar paradas" else "Ver paradas", tint = LectorColors.Muted, modifier = Modifier.size(18.dp))
                }
                androidx.compose.animation.AnimatedVisibility(abierto) {
                    Column(Modifier.padding(top = 4.dp)) {
                        l.stops.forEach { p -> Text("· ${p.name}", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
                    }
                }
                Text("Bájate en ${l.to.name}", style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("Camina hasta ${l.to.name}", style = LectorType.Section, color = LectorColors.Text)
                Text("${duracion(l.minutes)} · ${(l.metres ?: 0.0).toInt()} m", style = LectorType.Label, color = LectorColors.Muted)
                l.pasosLimpios().take(5).forEach { s ->
                    val giro = GIROS[s.turn] ?: "Sigue derecho"
                    Text(if (s.street.isNotBlank()) "$giro por ${s.street} · ${s.metres} m" else "$giro · ${s.metres} m", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
