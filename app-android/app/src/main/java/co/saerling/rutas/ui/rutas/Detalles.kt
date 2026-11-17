package co.saerling.rutas.ui.rutas

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    data object Tarjeta : Capa
    data object Novedades : Capa
    data object Vias : Capa
    /** No es una página: pide ir a la pestaña Ir con el destino ya puesto. */
    data object IrA : Capa
}

@Composable
private fun BotonEstrella(guardado: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(SuaveShape(15.dp)).background(LectorColors.Glass).pressable { Tacto.alternar(!guardado); onClick() }, Alignment.Center) {
        Icon(if (guardado) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (guardado) "Quitar de guardados" else "Guardar", tint = if (guardado) MaterialTheme.colorScheme.primary else LectorColors.Text)
    }
}

/** Pide datos cada [cadaMs] mientras la pantalla está abierta; [intento] reinicia (botón Reintentar). */
@Composable
private fun <T> rememberSondeo(clave: Any, intento: Int, cadaMs: Long, cargar: suspend () -> T): Pair<T?, Boolean> {
    var dato by remember(clave) { mutableStateOf<T?>(null) }
    var error by remember(clave) { mutableStateOf(false) }
    LaunchedEffect(clave, intento) {
        error = false
        while (true) {
            runCatching { cargar() }.onSuccess { dato = it; error = false }.onFailure { if (dato == null) error = true }
            delay(cadaMs)
        }
    }
    return dato to error
}

// ───────────────────────────────────────── Paradero ─────────────────────────────────────────

@Composable
fun ParaderoScreen(id: String, abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var intento by remember { mutableIntStateOf(0) }
    val (det, error) = rememberSondeo(id, intento, 10_000) { Api.paradero(id) }
    var solo by remember { mutableStateOf<String?>(null) }
    var tocado by remember { mutableStateOf<String?>(null) }
    var siguiendo by remember { mutableStateOf(false) }
    val vienen = if (det?.feed?.live == true) det.incoming.filter { solo == null || it.code == solo } else emptyList()
    val bus = vienen.firstOrNull { it.id == tocado }
    LaunchedEffect(bus == null) { if (bus == null) siguiendo = false }
    MapaConHoja(
        onBack = onBack,
        fijo = bus?.let { b -> { TarjetaBus(b, siguiendo, { siguiendo = !siguiendo; Tacto.alternar(siguiendo) }, verRuta = det?.arrivals?.firstOrNull { it.code == b.code }?.routeId?.let { r -> { abrir(Capa.Ruta(r)) } }) { tocado = null } } },
        acciones = { det?.let { x -> BotonEstrella(Guardados.esParadero(x.stop.id)) { val g = Guardados.alternar(x.stop); Avisos.mostrar(if (g) "Paradero guardado" else "Quitado de guardados") } } },
        mapa = { abajo, pedido ->
            val s = det?.stop
            Mapa(
                Modifier.fillMaxSize(), centro = s?.let { it.lat to it.lon } ?: Ubicacion.BOGOTA, zoom = 16.0,
                puntos = listOfNotNull(s?.let { PuntoMapa(it.id, it.lat, it.lon, true, estacion = it.kind == "station") }),
                buses = vienen,
                yo = Ubicacion.actual, encuadrar = listOfNotNull(s?.let { it.lat to it.lon }),
                alTocarBus = { tocado = it; Tacto.tic() },
                resaltado = tocado,
                seguir = if (siguiendo) bus?.let { it.lat to it.lon } else null,
                abajo = abajo, pedido = pedido, centrarEn = Ubicacion.actual,
            )
        },
        contenido = {
            when {
                error && det == null -> item("e") { Vacio("No pudimos cargar el paradero", "Revisa tu conexión.", "Reintentar") { intento++ } }
                det == null -> item("c") { CargandoFilas(5) }
                else -> {
                    item("titulo") {
                        Column(Modifier.padding(horizontal = Margen)) {
                            Text(det.stop.name, style = LectorType.Hero, color = LectorColors.Text)
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
                                Campana(primero, det.stop.id, det.stop.name)
                            }
                            Text(puntualidad(primero), style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (!det.feed.live) item("feed") { Text("TransMilenio no está publicando en vivo: los tiempos son del horario.", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 10.dp)) }
                    det.alerts.forEachIndexed { i, a -> item("al$i") { TarjetaAlerta(a) } }
                    if (lista.size > 1) item("luego") { Seccion("Después") }
                    itemsIndexed(lista.drop(1).take(12), key = { i, a -> "lleg$i-${a.code}" }) { _, a ->
                        Row(Modifier.fillMaxWidth().resaltable { a.routeId?.let { abrir(Capa.Ruta(it)) } }.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.widthIn(min = 72.dp)) { Insignia(a.code, a.system) }
                            Text(a.name, style = LectorType.Body, color = LectorColors.Text, modifier = Modifier.weight(1f), maxLines = 1)
                            Minutos(a)
                            Campana(a, det.stop.id, det.stop.name)
                        }
                    }
                    item("rutas-t") { Seccion("Rutas que paran aquí") }
                    itemsIndexed(det.stop.routes, key = { i, r -> "r$i-${r.id}" }) { _, r -> FilaRuta(r) { abrir(Capa.Ruta(r.id)) } }
                }
            }
        },
    )
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
            Icon(Icons.Rounded.Warning, null, tint = Color(0xFFF2A33D), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(a.title.ifBlank { "Novedad del servicio" }, style = LectorType.Section, color = LectorColors.Text)
        }
        if (a.detail.isNotBlank()) Text(a.detail, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}

// ───────────────────────────────────────── Ruta ─────────────────────────────────────────

@Composable
fun RutaScreen(id: String, abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var intento by remember { mutableIntStateOf(0) }
    val (det, error) = rememberSondeo(id, intento, 10_000) { Api.ruta(id) }
    var sentido by remember(id) { mutableIntStateOf(0) }
    var tocado by remember { mutableStateOf<String?>(null) }
    var siguiendo by remember { mutableStateOf(false) }
    val variante = det?.variants?.getOrNull(sentido)
    val paradas = variante?.stops ?: det?.stops.orEmpty()
    val trazo = variante?.path ?: det?.path.orEmpty()
    // Con varios sentidos, solo los buses que van hacia el elegido.
    val buses = if (det?.feed?.live == true) det.buses.filter { b -> det.variants.size < 2 || b.toward == null || b.toward == variante?.toward || det.variants.none { v -> v.toward == b.toward } }.map { it.copy(code = det.route.code, system = det.route.system) } else emptyList()
    val bus = buses.firstOrNull { it.id == tocado }
    LaunchedEffect(bus == null) { if (bus == null) siguiendo = false }
    MapaConHoja(
        onBack = onBack,
        asomo = 0.45f,
        acciones = { det?.let { x -> BotonEstrella(Guardados.esRuta(x.route.id)) { val g = Guardados.alternar(x.route); Avisos.mostrar(if (g) "Ruta guardada" else "Quitada de guardados") } } },
        fijo = bus?.let { b -> { TarjetaBus(b, siguiendo, { siguiendo = !siguiendo; Tacto.alternar(siguiendo) }) { tocado = null } } },
        mapa = { abajo, pedido ->
            val color = colorSistema(det?.route?.system)
            val troncal = det?.route?.system == "troncal" || det?.route?.system == "dual"
            Mapa(
                Modifier.fillMaxSize(), centro = paradas.firstOrNull()?.let { it.lat to it.lon } ?: Ubicacion.BOGOTA, zoom = 12.0,
                puntos = paradas.map { PuntoMapa(it.id, it.lat, it.lon, estacion = troncal) },
                tramos = listOfNotNull(det?.let { TramoMapa(trazo, color) }),
                buses = buses,
                yo = Ubicacion.actual,
                encuadrar = trazo.map { it[1] to it[0] }.let { p -> if (p.size > 2) listOf(p.first(), p[p.size / 4], p[p.size / 2], p[p.size * 3 / 4], p.last()) else p },
                alTocarPunto = { abrir(Capa.Paradero(it)) },
                alTocarBus = { tocado = it; Tacto.tic() },
                resaltado = tocado,
                seguir = if (siguiendo) bus?.let { it.lat to it.lon } else null,
                abajo = abajo, pedido = pedido, centrarEn = Ubicacion.actual,
            )
        },
        contenido = {
            when {
                error && det == null -> item("e") { Vacio("No pudimos cargar la ruta", "Revisa tu conexión.", "Reintentar") { intento++ } }
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
                                Text(if (det.feed.live) "${buses.size} buses en vía, en vivo · toca uno para seguirlo" else "Sin datos en vivo ahora", style = LectorType.Label, color = LectorColors.Text)
                            }
                        }
                    }
                    if (det.variants.size > 1) item("sentidos") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            det.variants.forEachIndexed { i, v -> ChipSel("Hacia ${v.toward}", sentido == i) { sentido = i; tocado = null } }
                        }
                    }
                    det.alerts.forEachIndexed { i, a -> item("al$i") { TarjetaAlerta(a) } }
                    val salidas = variante?.departures.orEmpty()
                    if (salidas.isNotEmpty()) {
                        item("sal-t") { Seccion("Salidas de hoy desde ${paradas.firstOrNull()?.name ?: "la primera parada"}") }
                        item("sal") { Salidas(salidas) }
                    }
                    item("pt") { Seccion("Paradas · toca una para ver sus buses") }
                    val color = colorSistema(det.route.system)
                    // Índice en la clave: las rutas circulares pasan dos veces por la misma parada.
                    itemsIndexed(paradas, key = { i, s -> "p$sentido-$i-${s.id}" }) { i, s ->
                        val aqui = buses.count { it.nextStop == s.name }
                        Row(Modifier.fillMaxWidth().resaltable { abrir(Capa.Paradero(s.id)) }.padding(horizontal = Margen), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(24.dp).height(48.dp), Alignment.Center) {
                                Box(Modifier.width(4.dp).fillMaxHeight(if (i == 0 || i == paradas.lastIndex) 0.5f else 1f).align(if (i == 0) Alignment.BottomCenter else if (i == paradas.lastIndex) Alignment.TopCenter else Alignment.Center).background(color))
                                Box(Modifier.size(12.dp).clip(SuaveShape(6.dp)).background(LectorColors.Bg).padding(2.dp).clip(SuaveShape(4.dp)).background(color))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(s.name, style = LectorType.Body, color = LectorColors.Text, modifier = Modifier.weight(1f))
                            if (aqui > 0) {
                                Row(Modifier.clip(SuaveShape(10.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.DirectionsBus, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Text(if (aqui > 1) " $aqui llegando" else " llegando", style = LectorType.Label, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Horas de salida en una cuadrícula; las que ya pasaron, apagadas, y la próxima resaltada. */
@Composable
private fun Salidas(horas: List<String>) {
    val ahora = java.time.LocalTime.now(java.time.ZoneId.of("America/Bogota")).let { it.hour * 60 + it.minute }
    fun min(h: String) = h.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
    val proxima = horas.indexOfFirst { min(it) >= ahora }
    var todas by remember { mutableStateOf(false) }
    val desde = if (todas || proxima < 0) 0 else (proxima - 2).coerceAtLeast(0)
    val vista = horas.drop(desde).let { if (todas) it else it.take(16) }
    Column(Modifier.padding(horizontal = Margen)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            vista.forEachIndexed { k, h ->
                val i = desde + k
                val pasada = proxima >= 0 && i < proxima || proxima < 0
                Text(
                    h, style = LectorType.Label.copy(fontWeight = if (i == proxima) FontWeight.Bold else FontWeight.Normal),
                    color = if (i == proxima) MaterialTheme.colorScheme.onPrimary else if (pasada) LectorColors.Muted else LectorColors.Text,
                    modifier = Modifier.clip(SuaveShape(10.dp)).background(if (i == proxima) MaterialTheme.colorScheme.primary else LectorColors.Card).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (horas.size > 16) Text(
            if (todas) "Ver menos" else "Ver todas (${horas.size})", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).clip(SuaveShape(10.dp)).pressable { todas = !todas }.padding(vertical = 6.dp),
        )
    }
}

/** El bus que tocaste en el mapa: su ruta, hacia dónde va, su próxima parada, a qué velocidad va y el botón para seguirlo. */
@Composable
fun TarjetaBus(b: Bus, siguiendo: Boolean, alSeguir: () -> Unit, verRuta: (() -> Unit)? = null, cerrar: () -> Unit) {
    val color = colorSistema(b.system)
    val ctx = LocalContext.current
    val icono = remember(b.system) { IconosMapa.busArriba(ctx, android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()), b.system == "troncal" || b.system == "dual").asImageBitmap() }
    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(SuaveShape(24.dp)).background(LectorColors.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(SuaveShape(14.dp)).background(color.copy(alpha = 0.14f)), Alignment.Center) {
                androidx.compose.foundation.Image(icono, null, Modifier.size(18.dp, 38.dp).graphicsLayer { rotationZ = 35f })
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    b.code?.let { Insignia(it, b.system) ; Spacer(Modifier.width(8.dp)) }
                    Text(b.toward?.let { "hacia $it" } ?: "En vía", style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text(listOfNotNull(b.nextStop?.let { "Próxima: $it" }, b.kmh?.let { "${it.toInt()} km/h" }, b.plate).joinToString(" · "), style = LectorType.Label, color = LectorColors.Muted, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
            }
            Box(Modifier.size(36.dp).clip(SuaveShape(12.dp)).pressable(cerrar), Alignment.Center) { Icon(Icons.Rounded.Close, "Cerrar", tint = LectorColors.Muted) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(SuaveShape(14.dp)).background(if (siguiendo) MaterialTheme.colorScheme.primary else LectorColors.PillFill).pressable(alSeguir).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CenterFocusStrong, null, tint = if (siguiendo) MaterialTheme.colorScheme.onPrimary else LectorColors.Text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (siguiendo) "Siguiendo" else "Seguir en el mapa", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = if (siguiendo) MaterialTheme.colorScheme.onPrimary else LectorColors.Text)
            }
            if (verRuta != null) Row(Modifier.clip(SuaveShape(14.dp)).background(LectorColors.PillFill).pressable(verRuta).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Route, null, tint = LectorColors.Text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ver ruta", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text)
            }
        }
    }
}

// ───────────────────────────────────────── Viaje ─────────────────────────────────────────

@Composable
fun ViajeScreen(it: Itinerary, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val guiando = GuiaViaje.guiando(it)
    val guia = if (guiando) GuiaViaje.guia else Guia.Esperando
    val vista = LocalView.current
    DisposableEffect(guiando) { vista.keepScreenOn = guiando; onDispose { vista.keepScreenOn = false } }
    val confeti = co.saerling.rutas.fx.rememberConfeti()
    LaunchedEffect(guia) { if (guia is Guia.Llegaste && co.saerling.rutas.fx.Fx.on("confetti")) { Tacto.alternar(true); confeti.disparar() } }
    LaunchedEffect(Unit) { Ubicacion.empezar(ctx) }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (Ubicacion.tienePermiso(ctx)) { GuiaViaje.empezar(ctx, it); Avisos.mostrar("Te avisamos cuando debas bajarte, aunque bloquees el celular.") }
        else Avisos.mostrar("Sin tu ubicación no podemos avisarte cuándo bajarte")
    }
    fun empezar() {
        Tacto.alternar(true)
        val pedir = buildList {
            if (!Ubicacion.tienePermiso(ctx)) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (pedir.isEmpty()) { GuiaViaje.empezar(ctx, it); Avisos.mostrar("Te avisamos cuando debas bajarte, aunque bloquees el celular.") }
        else permisos.launch(pedir.toTypedArray())
    }
    Box {
        MapaConHoja(
            onBack = onBack,
            asomo = 0.55f,
            mapa = { abajo, pedido ->
                val yo = Ubicacion.actual
                Mapa(
                    Modifier.fillMaxSize(), centro = it.legs.first().from.let { s -> s.lat to s.lon }, zoom = 13.0,
                    puntos = it.legs.filter { l -> l.mode == "transit" }.flatMapIndexed { k, l -> val e = l.system == "troncal" || l.system == "dual"; listOf(PuntoMapa("a$k", l.from.lat, l.from.lon, true, e), PuntoMapa("b$k", l.to.lat, l.to.lon, true, e)) },
                    tramos = it.legs.map { l -> TramoMapa(l.path, if (l.mode == "transit") colorSistema(l.system) else LectorColors.Muted, discontinuo = l.mode != "transit") },
                    yo = yo, encuadrar = it.legs.flatMap { l -> listOf(l.from.lat to l.from.lon, l.to.lat to l.to.lon) },
                    abajo = abajo, pedido = pedido, centrarEn = yo,
                )
            },
            // Mientras viajas, la guía queda fija arriba, como en Moovit.
            fijo = if (guiando) { { TarjetaGuia(it, guia) { Tacto.alternar(false); GuiaViaje.terminar(ctx) } } } else null,
            contenido = {
                item("t") {
                    Column(Modifier.padding(horizontal = Margen)) {
                        Text(duracion(it.minutes), style = LectorType.Display, color = LectorColors.Text)
                        Text("${hora(it.start)} – ${hora(it.end)} · ${if (it.transfers == 0) "directo" else "${it.transfers} transbordo${if (it.transfers > 1) "s" else ""}"} · ${it.walkMetres} m a pie", style = LectorType.Body, color = LectorColors.Muted)
                        val p = it.pasaje()
                        if (p > 0) Text("Pasaje aproximado ${pesos(p)} (sin descuentos de transbordo)", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(16.dp))
                        if (!guiando) BotonExpresivo("Empezar viaje", Icons.Rounded.Navigation, { empezar() }, Modifier.fillMaxWidth(), estilo = EstiloBoton.RELLENO, alto = 54.dp)
                        else BotonExpresivo("Terminar viaje", Icons.Rounded.Stop, { GuiaViaje.terminar(ctx) }, Modifier.fillMaxWidth(), estilo = EstiloBoton.TONAL, alto = 54.dp)
                        val salida = runCatching { java.time.OffsetDateTime.parse(it.start).toInstant().toEpochMilli() }.getOrNull()
                        if (!guiando && salida != null && salida - System.currentTimeMillis() > 6 * 60_000) {
                            Spacer(Modifier.height(10.dp))
                            val puesto = Recordatorio.programados[it.id]
                            BotonExpresivo(if (puesto != null) "Aviso a las ${hora(java.time.Instant.ofEpochMilli(puesto.second).atOffset(java.time.ZoneOffset.ofHours(-5)).toString())} · quitar" else "Avísame 5 min antes de salir",
                                if (puesto != null) Icons.Rounded.AlarmOff else Icons.Rounded.Alarm, {
                                if (puesto != null) { Recordatorio.quitar(ctx, it.id); Tacto.alternar(false); Avisos.mostrar("Aviso quitado") }
                                else {
                                    val primero = it.legs.firstOrNull { l -> l.mode == "transit" }
                                    val cuando = Recordatorio.programar(ctx, salida, "Sal ahora para alcanzar el ${codigoVisible(primero?.code, primero?.headsign)}", it.id)
                                    Tacto.alternar(cuando != null)
                                    Avisos.mostrar(if (cuando != null) "Te avisamos a las ${hora(java.time.Instant.ofEpochMilli(cuando).atOffset(java.time.ZoneOffset.ofHours(-5)).toString())}" else "Ya casi sale: mejor empieza el viaje")
                                }
                            }, Modifier.fillMaxWidth(), estilo = EstiloBoton.TONAL)
                        }
                    }
                }
                item("pasos") { Seccion("Paso a paso") }
                items(it.legs.size, key = { i -> "paso$i" }) { i ->
                    val actual = guia.let { g -> (g is Guia.EnBus && g.tramo == i) || (g is Guia.Caminando && g.tramo == i) }
                    PasoViaje(it.legs[i], actual, Modifier.revelar("paso" + it.id + i, i * 40))
                }
                item("fin") {
                    Row(Modifier.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(64.dp), Alignment.Center) { Icon(Icons.Rounded.Flag, null, tint = LectorColors.Text) }
                        Spacer(Modifier.width(10.dp))
                        Text("Llegas a ${it.legs.last().to.name}", style = LectorType.Section, color = LectorColors.Text)
                    }
                }
            },
        )
        co.saerling.rutas.fx.Confeti(confeti, Modifier.matchParentSize())
    }
}

@Composable
private fun TarjetaGuia(it: Itinerary, guia: Guia, terminar: () -> Unit) {
    val (titulo, detalle) = GuiaViaje.texto(it, guia)
    val urgente = guia is Guia.EnBus && guia.bajarYa
    val acento = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(SuaveShape(24.dp))
            .background(if (urgente) acento else LectorColors.Card).padding(16.dp),
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
        Column(Modifier.weight(1f).clip(SuaveShape(18.dp)).background(if (actual) LectorColors.CardHigh else Color.Transparent).padding(if (actual) 10.dp else 0.dp)) {
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
                AnimatedVisibility(abierto) {
                    Column(Modifier.padding(top = 4.dp)) { l.stops.forEach { p -> Text("· ${p.name}", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) } }
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

// ───────────────────────────────────────── Novedades ─────────────────────────────────────────

/** Desvíos, cierres y cambios de servicio vigentes en toda la red, como en la app oficial. */
@Composable
fun NovedadesScreen(abrir: (Capa) -> Unit, onBack: () -> Unit) {
    var intento by remember { mutableIntStateOf(0) }
    val (dato, error) = rememberSondeo("novedades", intento, 120_000) { Api.novedades() }
    var filtro by remember { mutableStateOf("") }
    PaginaPestana("Novedades", "Desvíos y cambios en el servicio", atras = onBack) {
        when {
            error && dato == null -> item("e") { Vacio("No pudimos cargar las novedades", "Revisa tu conexión.", "Reintentar") { intento++ } }
            dato == null -> item("c") { CargandoFilas(4) }
            dato.alerts.isEmpty() -> item("v") { Vacio("Todo en orden", "TransMilenio no reporta desvíos ni cierres en este momento.") }
            else -> {
                item("f") { Box(Modifier.padding(horizontal = Margen).padding(bottom = 8.dp)) { CampoBusqueda(filtro, { filtro = it }, "Filtrar por ruta o lugar") } }
                val q = filtro.trim().lowercase()
                val lista = dato.alerts.filter { a -> q.isEmpty() || a.routes.any { it.lowercase().contains(q) } || a.title.lowercase().contains(q) || a.detail.lowercase().contains(q) }
                items(lista.size, key = { "n" + lista[it].id }) { i ->
                    val a = lista[i]
                    Column {
                        TarjetaAlerta(a)
                        if (a.routes.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen + 4.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.routes.take(12).forEach { c -> Insignia(c, null) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Campana de Moovit, en vivo: el Vigía consulta cada 20 s cuánto le falta a esa ruta en este paradero y avisa
 * cuando está a 3 min. Tocarla otra vez la quita. El estado sale del Vigía, así no se pierde cuando la lista se refresca.
 */
@Composable
private fun Campana(a: Arrival, paraderoId: String, paradero: String) {
    val ctx = LocalContext.current
    val puesta = Vigia.esperando(paraderoId, a.code)
    val m = a.minutes
    if (!puesta && (m == null || m <= 3)) { Spacer(Modifier.width(42.dp)); return }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Box(Modifier.padding(start = 6.dp).size(36.dp).clip(SuaveShape(12.dp)).background(if (puesta) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent).pressable {
        if (Build.VERSION.SDK_INT >= 33 && !puesta) permiso.launch(Manifest.permission.POST_NOTIFICATIONS)
        val ahora = Vigia.alternar(ctx, Espera(paraderoId, paradero, a.code, a.system))
        Tacto.alternar(ahora)
        Avisos.mostrar(if (ahora) "Te avisamos cuando al ${a.code} le falten 3 min" else "Ya no te avisamos del ${a.code}")
    }, Alignment.Center) {
        Icon(if (puesta) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationAdd, if (puesta) "Quitar aviso del ${a.code}" else "Avísame cuando llegue el ${a.code}",
            tint = if (puesta) MaterialTheme.colorScheme.primary else LectorColors.Muted, modifier = Modifier.size(20.dp))
    }
}
