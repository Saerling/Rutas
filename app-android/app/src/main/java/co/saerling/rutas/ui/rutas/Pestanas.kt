package co.saerling.rutas.ui.rutas

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.saerling.rutas.data.*
import co.saerling.rutas.ui.*
import co.saerling.rutas.fx.inclinar
import co.saerling.rutas.ui.mochy.SwipeAction
import co.saerling.rutas.ui.mochy.SwipeActionsBox
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

// ───────────────────────────────────────── Cerca ─────────────────────────────────────────

/** Qué se ve en el mapa de Cerca; sobrevive al cambiar de pestaña. */
object CapasMapa {
    var buses by mutableStateOf(true)
    var trafico by mutableStateOf(true)
}

/** Sistemas que se toman en estación o portal (y sus alimentadores), frente al SITP de la calle. */
fun esTransMilenio(sistema: String?) = sistema == "troncal" || sistema == "dual" || sistema == "alimentador" || sistema == "transmicable"

@Composable
fun CercaScreen(abrir: (Capa) -> Unit) {
    val ctx = LocalContext.current
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Ubicacion.empezar(ctx)
        if (!Ubicacion.tienePermiso(ctx)) Avisos.mostrar("Sin permiso de ubicación: mueve el mapa para ver paraderos")
    }
    LaunchedEffect(Unit) { Ubicacion.empezar(ctx) }
    val yo = Ubicacion.actual
    val punto = yo?.takeIf { Ubicacion.enBogota(it.first, it.second) }
    // Zona elegida moviendo el mapa ("Buscar en esta zona"); si no, donde estás.
    var zona by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var centroMapa by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val base = zona ?: punto
    val clave = base?.let { "%.3f,%.3f".format(java.util.Locale.US, it.first, it.second) }
    var intento by remember { mutableIntStateOf(0) }
    var cerca by remember { mutableStateOf<List<Stop>?>(null) }
    var error by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<Feed?>(null) }
    LaunchedEffect(clave, intento) {
        val p = base ?: return@LaunchedEffect
        error = false
        runCatching { Api.cerca(p.first, p.second).stops }.onSuccess { cerca = it }.onFailure { error = true }
    }
    LaunchedEffect(Unit) { while (true) { feed = runCatching { Api.salud().feed }.getOrNull(); delay(60_000) } }
    var alertasVivo by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var boletines by remember { mutableStateOf<List<Boletin>>(emptyList()) }
    LaunchedEffect(Unit) { while (true) { runCatching { Api.novedades().alerts }.onSuccess { alertasVivo = it }; delay(5 * 60_000) } }
    LaunchedEffect(Unit) { while (true) { runCatching { Api.noticias().bulletins }.onSuccess { boletines = it }; delay(15 * 60_000) } }
    val novedades = alertasVivo.size
    // Lo que toca tus rutas o estaciones: en vivo, y boletines de los últimos 4 días.
    val hace4 = java.time.LocalDate.now().minusDays(4).toString()
    val tuyas = alertasVivo.mapNotNull { a -> MisRutas.afectadas(a.routes).takeIf { it.isNotEmpty() }?.let { Triple("a" + a.id, a.title.ifBlank { "Novedad del servicio" }, it) } } +
        boletines.filter { (it.date ?: "") >= hace4 }.mapNotNull { b ->
            val r = MisRutas.afectadas(b.routes) + MisRutas.estacionesAfectadas(b.stations)
            if (r.isEmpty()) null else Triple("b" + b.id, b.title, r)
        }
    LaunchedEffect(tuyas.map { it.first }) {
        tuyas.forEach { (id, titulo, rutas) -> MisRutas.avisar(ctx, id, "Afecta ${rutas.take(3).joinToString(", ")}", titulo) }
    }
    // Todos los buses en vía alrededor (del centro del mapa si lo mueves), cada 15 s, con su velocidad.
    val centroBuses = centroMapa?.takeIf { Ubicacion.enBogota(it.first, it.second) } ?: base
    val claveBuses = centroBuses?.let { "%.2f,%.2f".format(java.util.Locale.US, it.first, it.second) }
    var vivos by remember { mutableStateOf<Vehiculos?>(null) }
    LaunchedEffect(claveBuses, CapasMapa.buses || CapasMapa.trafico) {
        val c = centroBuses ?: return@LaunchedEffect
        if (!CapasMapa.buses && !CapasMapa.trafico) { vivos = null; return@LaunchedEffect }
        while (true) { runCatching { Api.vehiculos(c.first, c.second, 1800) }.onSuccess { n ->
            // Una instancia nueva del servidor aún no ha medido velocidades: se conserva la última medición.
            val previo = vivos
            vivos = if (n.traffic.level == null && previo?.traffic?.level != null) n.copy(traffic = previo.traffic) else n
        }; delay(20_000) }
    }
    // Tráfico de TomTom (si el servidor tiene la clave): calles coloreadas e incidentes; si no, el de los buses.
    var incid by remember { mutableStateOf<Incidentes?>(null) }
    LaunchedEffect(CapasMapa.trafico) { if (CapasMapa.trafico) while (true) { runCatching { Api.incidentes() }.onSuccess { incid = it }; delay(10 * 60_000) } }
    val tomtom = incid?.source != null
    val incidCerca = base?.let { b -> incid?.incidents.orEmpty().filter { Ubicacion.metros(it.lat to it.lon, b) < 2500 } }.orEmpty()
    var tocado by remember { mutableStateOf<String?>(null) }
    var siguiendo by remember { mutableStateOf(false) }
    val busesMapa = if (CapasMapa.buses && vivos?.feed?.live == true) vivos!!.buses else emptyList()
    val bus = busesMapa.firstOrNull { it.id == tocado }
    LaunchedEffect(bus == null) { if (bus == null) siguiendo = false }
    // Próximas salidas de los paraderos más cercanos, juntas por ruta (como la app Transit).
    var salidas by remember { mutableStateOf<List<Pair<Stop, Arrival>>?>(null) }
    LaunchedEffect(cerca) {
        val lista = cerca?.take(5) ?: return@LaunchedEffect
        salidas = null
        while (true) {
            val todas = kotlinx.coroutines.coroutineScope {
                lista.map { s -> async { runCatching { Api.paradero(s.id) }.getOrNull()?.arrivals.orEmpty().map { s to it } } }.map { it.await() }.flatten()
            }
            salidas = todas.filter { (it.second.minutes ?: 999) <= 60 }.groupBy { it.second.code }.values
                .map { g -> g.minWith(compareBy({ it.second.minutes ?: 999 }, { it.first.distanceM ?: 9e9 })) }
                .sortedWith(compareBy({ (it.first.distanceM ?: 0.0) / 80 + (it.second.minutes ?: 99) }))
            delay(45_000)
        }
    }
    val gpsApagado = Ubicacion.permiso && yo == null && !Ubicacion.gpsEncendido(ctx)
    val saludo = when (LocalTime.now().hour) { in 5..11 -> "Buenos días"; in 12..18 -> "Buenas tardes"; else -> "Buenas noches" }

    MapaConHoja(
        asomo = 0.42f,
        finLista = 120.dp,
        // La barrita de la hoja escondida va encima de la barra de pestañas (y de la del viaje en curso, si hay).
        margenAbajo = if (co.saerling.rutas.data.GuiaViaje.viaje != null) 158.dp else 82.dp,
        resumen = "Paraderos y buses cerca",
        fijo = bus?.let { b -> { TarjetaBus(b, siguiendo, { siguiendo = !siguiendo; Tacto.alternar(siguiendo) }, verRuta = b.routeId?.let { r -> { abrir(Capa.Ruta(r)) } }) { tocado = null } } },
        mapa = { abajo, pedido ->
            Mapa(
                Modifier.fillMaxSize(), centro = base ?: Ubicacion.BOGOTA, zoom = if (base != null) 15.5 else 11.5,
                puntos = cerca.orEmpty().take(25).map { PuntoMapa(it.id, it.lat, it.lon, it.kind == "station") }, yo = punto,
                encuadrar = if (zona != null) emptyList() else listOfNotNull(punto) + cerca.orEmpty().take(6).map { it.lat to it.lon },
                alTocarPunto = { id -> abrir(Capa.Paradero(id)) },
                alMover = { c -> centroMapa = c },
                buses = busesMapa,
                trafico = CapasMapa.trafico && !tomtom && vivos?.feed?.live == true,
                flujo = CapasMapa.trafico && tomtom,
                incidentes = if (CapasMapa.trafico) incid?.incidents.orEmpty() else emptyList(),
                alTocarBus = { tocado = it; Tacto.tic() },
                resaltado = tocado,
                seguir = if (siguiendo) bus?.let { it.lat to it.lon } else null,
                abajo = abajo, pedido = pedido, centrarEn = punto,
            )
        },
        sobreMapa = {
            val c = centroMapa
            val lejos = c != null && Ubicacion.enBogota(c.first, c.second) && (base == null || Ubicacion.metros(c, base) > 350)
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Capas del mapa, como los filtros de Google Maps.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapaChip(Icons.Rounded.DirectionsBus, "Buses en vivo", CapasMapa.buses) { CapasMapa.buses = !CapasMapa.buses }
                    CapaChip(Icons.Rounded.Traffic, "Tráfico", CapasMapa.trafico) { CapasMapa.trafico = !CapasMapa.trafico }
                }
                AnimatedVisibility(lejos, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Row(Modifier.padding(top = 10.dp).clip(SuaveShape(20.dp)).background(LectorColors.Glass).border(1.dp, LectorColors.GlassBorder, SuaveShape(20.dp)).pressable { Tacto.alternar(true); zona = c; cerca = null; centroMapa = null }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = LectorColors.Text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar paraderos en esta zona", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text)
                    }
                }
            }
        },
        contenido = {
            item("saludo") {
                Column(Modifier.padding(horizontal = Margen)) {
                    Text(saludo, style = LectorType.Hero, color = LectorColors.Text)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EstadoDatos(feed)
                        val n = vivos?.buses?.size
                        if (n != null && vivos?.feed?.live == true && n > 0) Pildora(Icons.Rounded.DirectionsBus, "$n buses cerca")
                    }
                }
            }
            if (tuyas.isNotEmpty()) item("tuyas") {
                Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 14.dp), radio = 22.dp, color = Color(0xFFF2A33D).copy(alpha = 0.16f), onClick = { abrir(Capa.Novedades) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = Color(0xFFF2A33D), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Hay cambios en tus rutas", style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted)
                    }
                    tuyas.take(3).forEach { (_, titulo, rutas) ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Insignia(rutas.first(), null)
                            Spacer(Modifier.width(8.dp))
                            Text(titulo, style = LectorType.Label, color = LectorColors.Text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (Vigia.esperas.isNotEmpty()) item("esperas") { EsperasActivas() }
            item("tullave") { AccesoTarjeta { abrir(Capa.Tarjeta) } }
            item("vias") { ResumenVias(vivos?.traffic, novedades, incidCerca, { abrir(Capa.Vias) }) { abrir(Capa.Novedades) } }
            val sal = salidas
            if (base != null && cerca?.isNotEmpty() == true) {
                item("sal-t") { Seccion("Salen cerca de ti") }
                if (sal == null) item("sal-c") { CargandoFilas(3) }
                else if (sal.isEmpty()) item("sal-v") { Text("No hay salidas en la próxima hora en los paraderos cercanos.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen)) }
                else {
                    val (tm, sitp) = sal.partition { esTransMilenio(it.second.system) }
                    if (sitp.isNotEmpty()) item("sal-sitp") { GrupoSalidas("SITP", Color(0xFF2F6FC6), sitp, abrir) }
                    if (tm.isNotEmpty()) item("sal-tm") { GrupoSalidas("TransMilenio y alimentadores", Color(0xFFD7262C), tm, abrir) }
                }
            }
            // Sin nada guardado, lo que más sirve es guardar el paradero donde estás: así sus buses salen aquí al abrir la app.
            val masCerca = cerca?.firstOrNull()
            if (Guardados.paraderos.isEmpty() && masCerca != null && zona == null) item("primero") {
                Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 10.dp), radio = 24.dp) {
                    Text("Guarda tu paradero de siempre", style = LectorType.Section, color = LectorColors.Text)
                    Text("Así ves sus próximos buses apenas abres la app, sin buscarlo.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                    BotonExpresivo("Guardar ${masCerca.name}", Icons.Rounded.Star, {
                        Tacto.alternar(true); Guardados.alternar(masCerca); Avisos.mostrar("${masCerca.name} guardado. Lo ves en Tus paraderos.")
                    }, Modifier.fillMaxWidth(), estilo = EstiloBoton.RELLENO)
                }
            }
            if (Guardados.paraderos.isNotEmpty()) {
                item("guardados-t") { Seccion("Tus paraderos") }
                item("guardados") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Guardados.paraderos.forEach { g -> TarjetaGuardado(g) { abrir(Capa.Paradero(g.id)) } }
                    }
                }
            }
            if (zona != null) item("zona") {
                Row(Modifier.padding(horizontal = Margen).padding(top = 12.dp).clip(SuaveShape(14.dp)).background(LectorColors.Card).pressable { zona = null; cerca = null }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MyLocation, null, tint = LectorColors.Text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Viendo otra zona · volver a donde estoy", style = LectorType.Label, color = LectorColors.Text)
                }
            }
            when {
                base == null && !Ubicacion.permiso -> item("permiso") {
                    Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 16.dp)) {
                        Icon(Icons.Rounded.MyLocation, null, tint = LectorColors.Text, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Paraderos cerca de ti", style = LectorType.Section, color = LectorColors.Text)
                        Text("Usamos tu ubicación solo para esto y sale del teléfono redondeada a una cuadra. También puedes mover el mapa y tocar «Buscar en esta zona».", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        BotonExpresivo("Usar mi ubicación", Icons.Rounded.NearMe, { pedir.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, estilo = EstiloBoton.RELLENO)
                    }
                }
                base == null && gpsApagado -> item("gps") {
                    Vacio("Tu ubicación está apagada", "Enciéndela o mueve el mapa y toca «Buscar en esta zona».", "Abrir ajustes") {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }
                }
                base == null && yo != null -> item("fuera") { Vacio("Estás fuera de Bogotá", "Mueve el mapa a Bogotá o busca en Buscar.") }
                base == null -> item("buscando") { Seccion("Buscando dónde estás…"); CargandoFilas(3) }
                error && cerca == null -> item("error") { Vacio("No pudimos cargar los paraderos", "Revisa tu conexión.", "Reintentar") { intento++ } }
                cerca == null -> item("cargando") { Seccion(if (zona != null) "En esta zona" else "Paraderos cerca"); CargandoFilas() }
                cerca!!.isEmpty() -> item("vacio") { Vacio("No hay paraderos aquí", "Prueba moviendo el mapa.") }
                else -> {
                    item("lista-t") { Seccion(if (zona != null) "Paraderos en esta zona" else "Paraderos cerca") }
                    itemsIndexed(cerca.orEmpty(), key = { i, x -> "c$i-" + x.id }) { _, s -> FilaParadero(s, { abrir(Capa.Paradero(s.id)) }, Modifier.revelar("c" + s.id)) }
                }
            }
        },
    )
}

@Composable
private fun CapaChip(icono: ImageVector, texto: String, activo: Boolean, onClick: () -> Unit) {
    val (relleno, sobre) = rellenoActivo()
    val fondo by androidx.compose.animation.animateColorAsState(if (activo) relleno else LectorColors.Glass, label = "capa")
    Row(
        Modifier.clip(SuaveShape(18.dp)).background(fondo).border(1.dp, if (activo) sobre.copy(alpha = 0.22f) else LectorColors.GlassBorder, SuaveShape(18.dp))
            .pressable { Tacto.alternar(!activo); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (activo) sobre else LectorColors.Muted
        Icon(icono, null, tint = c, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(texto, style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = c)
    }
}

@Composable
private fun Pildora(icono: ImageVector, texto: String) {
    Row(Modifier.clip(SuaveShape(14.dp)).background(LectorColors.Card).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icono, null, tint = LectorColors.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(texto, style = LectorType.Label, color = LectorColors.Text)
    }
}

/** Un grupo de salidas (SITP o TransMilenio): cada ruta una vez, con el paradero donde tomarla y cuánto falta. */
@Composable
private fun GrupoSalidas(titulo: String, color: Color, lista: List<Pair<Stop, Arrival>>, abrir: (Capa) -> Unit) {
    var todas by remember { mutableStateOf(false) }
    Tarjeta(Modifier.padding(horizontal = Margen).padding(bottom = 10.dp), radio = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(SuaveShape(5.dp)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(titulo, style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text, modifier = Modifier.weight(1f))
            Text("${lista.size} ruta${if (lista.size > 1) "s" else ""}", style = LectorType.Label, color = LectorColors.Muted)
        }
        Spacer(Modifier.height(6.dp))
        val vista = if (todas) lista else lista.take(5)
        vista.forEach { (s, a) ->
            Row(Modifier.fillMaxWidth().clip(SuaveShape(14.dp)).pressable { Tacto.tic(); abrir(Capa.Paradero(s.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.widthIn(min = 66.dp)) { Insignia(a.code, a.system) }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(a.name.ifBlank { a.code }, style = LectorType.Body.copy(fontWeight = FontWeight.Medium), color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(s.name, s.distanceM?.let { "${(it / 80).toInt().coerceAtLeast(1)} min a pie" }).joinToString(" · "), style = LectorType.Label, color = LectorColors.Muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Minutos(a)
            }
        }
        if (lista.size > 5) Text(if (todas) "Ver menos" else "Ver las ${lista.size}", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp).clip(SuaveShape(10.dp)).pressable { todas = !todas }.padding(vertical = 6.dp))
    }
}

/** Los buses que estás esperando (campana): cuánto les falta y cómo quitarlos. */
@Composable
fun EsperasActivas() {
    val ctx = LocalContext.current
    Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 14.dp), radio = 22.dp, color = LectorColors.CardHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Te avisamos cuando lleguen", style = LectorType.Section, color = LectorColors.Text)
        }
        Vigia.esperas.toList().forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Insignia(e.codigo, e.sistema)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(e.nombre, style = LectorType.Label, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(Vigia.faltan[e.clave]?.let { "Llega en $it min · avisamos a los ${e.antes}" } ?: "Consultando…", style = LectorType.Label, color = LectorColors.Muted)
                }
                Box(Modifier.size(36.dp).clip(SuaveShape(12.dp)).background(LectorColors.PillFill).pressable { Tacto.alternar(false); Vigia.quitar(ctx, e.clave); Avisos.mostrar("Ya no te avisamos del ${e.codigo}") }, Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsOff, "Ya no me avises del ${e.codigo}", tint = LectorColors.Text, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Cómo están las vías alrededor: tráfico medido con los buses y novedades del servicio. */
@Composable
private fun ResumenVias(t: TraficoZona?, novedades: Int, incidentes: List<Incidente>, abrirVias: () -> Unit, abrirNovedades: () -> Unit) {
    Tarjeta(Modifier.padding(horizontal = Margen).padding(top = 10.dp), radio = 22.dp, onClick = abrirVias) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val nivel = t?.level
            Box(Modifier.size(36.dp).clip(SuaveShape(12.dp)).background(colorTrafico(nivel).copy(alpha = 0.18f)), Alignment.Center) {
                Icon(Icons.Rounded.Traffic, null, tint = colorTrafico(nivel), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when (nivel) { "fluido" -> "Las vías van fluidas"; "moderado" -> "Tráfico moderado cerca"; "lento" -> "Hay trancón cerca"; else -> "Estado de las vías" }, style = LectorType.Section, color = LectorColors.Text)
                Text(
                    if (t?.kmh != null) "Los buses van a ${t.kmh} km/h en promedio" + (t.slow.firstOrNull { it.level == "lento" }?.near?.takeIf { it.isNotBlank() }?.let { " · lento en $it" } ?: "")
                    else "Tráfico de toda Bogotá, medido con los buses en vivo",
                    style = LectorType.Label, color = LectorColors.Muted, maxLines = 2,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted)
        }
        incidentes.take(2).forEach { i ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(SuaveShape(4.dp)).background(colorIncidente(i.kind, i.magnitude)))
                Spacer(Modifier.width(8.dp))
                Text(textoIncidente(i), style = LectorType.Label, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (novedades > 0) Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(SuaveShape(14.dp)).background(LectorColors.PillFill).pressable(abrirNovedades).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, null, tint = Color(0xFFF2A33D), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("$novedades novedad${if (novedades > 1) "es" else ""}: desvíos y cierres", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AccesoTarjeta(onClick: () -> Unit) {
    val saldo = UltimoSaldo.saldo
    Row(
        Modifier.padding(horizontal = Margen).padding(top = 14.dp).fillMaxWidth().clip(SuaveShape(22.dp)).background(LectorColors.Card)
            .pressable(onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Contactless, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Tarjeta TuLlave", style = LectorType.Section, color = LectorColors.Text)
            Text(if (saldo != null) "Último saldo ${pesos(saldo)} · toca para leerla de nuevo" else "Acércala al celular para ver el saldo", style = LectorType.Label, color = LectorColors.Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted)
    }
}

@Composable
private fun EstadoDatos(feed: Feed?) {
    if (feed == null) return
    Row(Modifier.clip(SuaveShape(14.dp)).background(LectorColors.Card).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (feed.live) PuntoVivo() else Icon(Icons.Rounded.Schedule, null, tint = LectorColors.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (feed.live) "TransMilenio en vivo" else "Sin datos en vivo · según horario", style = LectorType.Label, color = LectorColors.Text)
    }
}

@Composable
private fun TarjetaGuardado(g: ParaderoGuardado, onClick: () -> Unit) {
    var detalle by remember { mutableStateOf<StopDetail?>(null) }
    LaunchedEffect(g.id) { while (true) { runCatching { Api.paradero(g.id) }.onSuccess { detalle = it }; delay(30_000) } }
    Tarjeta(Modifier.width(220.dp), onClick = onClick) {
        Text(g.nombre, style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val proximos = detalle?.arrivals.orEmpty().take(2)
        if (proximos.isEmpty()) Text(if (detalle == null) "Cargando…" else "Sin buses próximos", style = LectorType.Label, color = LectorColors.Muted)
        proximos.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Insignia(a.code, a.system); Spacer(Modifier.weight(1f)); Minutos(a)
            }
        }
    }
}

// ───────────────────────────────────────── Ir (planeador) ─────────────────────────────────────────

/** Estado del planeador: sobrevive al cambiar de pestaña. */
object Planeador {
    var desde by mutableStateOf<Place?>(null)
    var hasta by mutableStateOf<Place?>(null)
    var dentroDe by mutableIntStateOf(0)
    /** Franja del día elegida (mañana, tarde, noche); null si se eligió ahora o en X min. */
    var franja by mutableStateOf<String?>(null)
    var llegarA by mutableStateOf(false)
    var claveRes: String? = null
    var accesible by mutableStateOf(false)
    var menosTransbordos by mutableStateOf(false)
    var resultado by mutableStateOf<PlanResult?>(null)
    var orden by mutableIntStateOf(0)
    var cargandoMas by mutableStateOf(false)
    /** 0 todas, 1 solo SITP, 2 con TransMilenio. */
    var tipo by mutableIntStateOf(0)
    /** Páginas extra pedidas solas para esta búsqueda. */
    var paginas = 0
}

@Composable
fun IrScreen(abrir: (Capa) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Ubicacion.empezar(ctx) }
    var editando by remember { mutableStateOf<String?>(if (Planeador.hasta == null) "hasta" else null) }
    var texto by remember { mutableStateOf("") }
    var lugares by remember { mutableStateOf<List<Place>?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val origen = Planeador.desde?.let { it.lat to it.lon } ?: Ubicacion.actual
    val escena = rememberCoroutineScope()

    LaunchedEffect(texto) {
        if (texto.trim().length < 2) { lugares = null; return@LaunchedEffect }
        delay(250)
        lugares = runCatching { Api.lugares(texto.trim(), origen?.first, origen?.second) }.getOrNull()
    }
    val hasta = Planeador.hasta
    var intento by remember { mutableIntStateOf(0) }
    LaunchedEffect(hasta?.id, Planeador.desde?.id) { val k = "${hasta?.id}|${Planeador.desde?.id}"; if (Planeador.claveRes != k) { Planeador.resultado = null; Planeador.claveRes = k; Planeador.paginas = 0 } }
    LaunchedEffect(origen?.let { "%.3f,%.3f".format(it.first, it.second) }, hasta?.id, Planeador.dentroDe, Planeador.llegarA, Planeador.accesible, Planeador.menosTransbordos, editando, intento) {
        if (editando != null || hasta == null || origen == null) return@LaunchedEffect
        cargando = true; error = false
        val hora = if (Planeador.dentroDe > 0) java.time.OffsetDateTime.now().plusMinutes(Planeador.dentroDe.toLong()).toString() else null
        runCatching { Api.plan(origen, hasta.lat to hasta.lon, hora, Planeador.llegarA, Planeador.accesible, Planeador.menosTransbordos) }
            .onSuccess { r -> Planeador.paginas = 0; Planeador.resultado = nombrar(r, Planeador.desde?.name ?: "Tu ubicación", hasta.name) }.onFailure { error = true }
        cargando = false
    }

    fun elegir(p: Place, cual: String) {
        Tacto.tic()
        when (cual) {
            "casa" -> { Guardados.ponerCasa(p); Avisos.mostrar("Guardado como casa") }
            "trabajo" -> { Guardados.ponerTrabajo(p); Avisos.mostrar("Guardado como trabajo") }
            "desde" -> { Guardados.recordar(p); Planeador.desde = p }
            else -> { Guardados.recordar(p); Planeador.hasta = p }
        }
        editando = null; texto = ""
    }

    fun cargarMas() {
        val r = Planeador.resultado ?: return
        val o = origen ?: return
        val h = hasta ?: return
        val cursor = r.next ?: return
        Planeador.cargandoMas = true
        escena.launch {
            runCatching { Api.plan(o, h.lat to h.lon, if (Planeador.dentroDe > 0) java.time.OffsetDateTime.now().plusMinutes(Planeador.dentroDe.toLong()).toString() else null, Planeador.llegarA, cursor = cursor, accesible = Planeador.accesible, menosTransbordos = Planeador.menosTransbordos) }
                .onSuccess { mas -> val n = nombrar(mas, Planeador.desde?.name ?: "Tu ubicación", h.name)
                    val actual = Planeador.resultado ?: r
                    Planeador.resultado = actual.copy(itineraries = (actual.itineraries + n.itineraries).distinctBy { x -> x.id }, next = n.next) }
                .onFailure { Avisos.mostrar("No pudimos cargar más opciones") }
            Planeador.cargandoMas = false
        }
    }

    // Se piden solas hasta 3 páginas más, para ver todas las rutas posibles; después, con el botón.
    LaunchedEffect(Planeador.resultado?.next, editando) {
        if (editando == null && Planeador.resultado?.next != null && Planeador.paginas < 3 && !Planeador.cargandoMas) { Planeador.paginas++; cargarMas() }
    }

    PaginaPestana("Ir", "¿A dónde vas?") {
        item("campos") {
            Tarjeta(Modifier.padding(horizontal = Margen), radio = 26.dp) {
                CampoRuta(Icons.Rounded.MyLocation, "Desde", Planeador.desde?.name ?: "Tu ubicación", editando == "desde") { editando = if (editando == "desde") null else "desde" }
                Box(Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp).fillMaxWidth().height(1.dp).background(LectorColors.Linea))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { CampoRuta(Icons.Rounded.Place, "Hasta", hasta?.name ?: "Elige el destino", editando == "hasta") { editando = if (editando == "hasta") null else "hasta" } }
                    if (hasta != null) Box(Modifier.size(40.dp).clip(SuaveShape(14.dp)).background(LectorColors.PillFill).pressable {
                        Tacto.tic(); val d = Planeador.desde; Planeador.desde = hasta
                        Planeador.hasta = d ?: Ubicacion.actual?.let { Place("yo", "Tu ubicación", null, "place", it.first, it.second) }
                    }, Alignment.Center) { Icon(Icons.Rounded.SwapVert, "Cambiar origen y destino", tint = LectorColors.Text) }
                }
            }
        }
        val e = editando
        if (e != null) {
            item("buscar") {
                Box(Modifier.padding(horizontal = Margen).padding(top = 14.dp)) {
                    CampoBusqueda(texto, { texto = it }, when (e) { "casa" -> "Busca tu casa"; "trabajo" -> "Busca tu trabajo"; else -> "Lugar, dirección o estación" })
                }
            }
            if (texto.trim().length < 2) {
                if (e == "desde" && Ubicacion.actual != null) item("yo") { FilaLugar(Icons.Rounded.MyLocation, "Tu ubicación", null) { Planeador.desde = null; editando = null } }
                if (e == "desde" || e == "hasta") {
                    item("casa") { val c = Guardados.casa; FilaLugar(Icons.Rounded.Home, c?.name ?: "Agrega tu casa", if (c != null) "Casa" else null) { if (c != null) elegir(c, e) else editando = "casa" } }
                    item("trabajo") { val t = Guardados.trabajo; FilaLugar(Icons.Rounded.Work, t?.name ?: "Agrega tu trabajo", if (t != null) "Trabajo" else null) { if (t != null) elegir(t, e) else editando = "trabajo" } }
                }
                if (Guardados.recientes.isNotEmpty()) item("rec-t") { Seccion("Recientes") }
                itemsIndexed(Guardados.recientes, key = { i, x -> "r$i-" + x.id }) { _, p -> FilaLugar(Icons.Rounded.History, p.name, p.area) { elegir(p, e) } }
            } else when (val l = lugares) {
                null -> item("c") { CargandoFilas(3) }
                else -> if (l.isEmpty()) item("n") { Vacio("Nada para «${texto.trim()}»") }
                    else itemsIndexed(l, key = { i, x -> "l$i-" + x.id }) { _, p -> FilaLugar(if (p.kind == "stop") Icons.Rounded.Train else Icons.Rounded.Place, p.name, p.area, Modifier.revelar("l" + p.id)) { elegir(p, e) } }
            }
        } else if (hasta == null) {
            item("atajos") {
                Row(Modifier.padding(horizontal = Margen).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Atajo(Icons.Rounded.Home, Guardados.casa?.let { "Casa" } ?: "Agrega tu casa", Modifier.weight(1f)) { Guardados.casa?.let { Planeador.hasta = it } ?: run { editando = "casa" } }
                    Atajo(Icons.Rounded.Work, Guardados.trabajo?.let { "Trabajo" } ?: "Agrega tu trabajo", Modifier.weight(1f)) { Guardados.trabajo?.let { Planeador.hasta = it } ?: run { editando = "trabajo" } }
                }
            }
        } else {
            item("opciones") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipSel("Ahora", Planeador.dentroDe == 0) { Planeador.dentroDe = 0; Planeador.franja = null; Planeador.llegarA = false }
                    listOf(15, 30, 60).forEach { m -> ChipSel(if (m < 60) "En $m min" else "En 1 h", Planeador.dentroDe == m && Planeador.franja == null) { Planeador.dentroDe = m; Planeador.franja = null } }
                    // Para planear el regreso o la salida de mañana sin abrir un reloj: mañana, tarde y noche.
                    franjas().forEach { f -> ChipSel(f.texto, Planeador.franja == f.id) { Planeador.franja = f.id; Planeador.dentroDe = f.minutos() } }
                    if (Planeador.dentroDe > 0) ChipSel("Llegar a esa hora", Planeador.llegarA) { Planeador.llegarA = !Planeador.llegarA }
                    ChipSel("Máximo 1 transbordo", Planeador.menosTransbordos) { Planeador.menosTransbordos = !Planeador.menosTransbordos }
                    ChipSel("Accesible", Planeador.accesible) { Planeador.accesible = !Planeador.accesible }
                }
            }
            val r = Planeador.resultado
            when {
                origen == null -> item("sin") { Vacio("¿Desde dónde sales?", "Activa tu ubicación o elige el punto de partida.") }
                error -> item("err") { Vacio("No pudimos planear el viaje", "Revisa tu conexión.", "Reintentar") { error = false; intento++ } }
                r == null -> item("carg") {
                    if (co.saerling.rutas.fx.fxOn("textshimmer")) co.saerling.rutas.fx.TextoBrillo("Buscando todas las rutas…", LectorType.Section, Modifier.padding(horizontal = Margen, vertical = 18.dp))
                    else Seccion("Buscando todas las rutas…")
                    CargandoFilas(3)
                }
                r != null && r.itineraries.isEmpty() && r.walk == null -> item("nada") { Vacio("No encontramos cómo llegar", "Prueba con otra hora o un punto más cerca de una estación.") }
                r != null -> {
                    val todas = ordenar(r.itineraries, Planeador.orden)
                    // Arriba, como en Moovit y Transit: el bus más cercano para tomar de cada ruta, SITP y TransMilenio aparte.
                    val porTomar = todas.mapNotNull { x -> x.legs.firstOrNull { l -> l.mode == "transit" }?.let { l -> l to x } }
                        .filter { (l, _) -> minutosDesdeAhora(l.start) >= -1 }
                        .groupBy { (l, _) -> codigoVisible(l.code, l.headsign) }.values
                        .map { g -> g.minBy { (l, _) -> l.start } }
                        .sortedBy { (l, _) -> l.start }
                    val (tm, sitp) = porTomar.partition { (l, _) -> esTransMilenio(l.system) }
                    if (sitp.isNotEmpty()) {
                        item("cerca-sitp-t") { SeccionColor("SITP más cercanos", Color(0xFF2F6FC6)) }
                        item("cerca-sitp") { FilaPorTomar(sitp) { abrir(Capa.Viaje(it)) } }
                    }
                    if (tm.isNotEmpty()) {
                        item("cerca-tm-t") { SeccionColor("TransMilenio y alimentadores más cercanos", Color(0xFFD7262C)) }
                        item("cerca-tm") { FilaPorTomar(tm) { abrir(Capa.Viaje(it)) } }
                    }
                    item("orden") {
                        // Segmented Control deslizante (Motion Primitives) para ordenar, como los filtros de Moovit.
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides LectorColors.Text) {
                            Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 18.dp)) {
                                co.saerling.rutas.fx.SegmentadoDeslizante(
                                    listOf("Mejor", "Más rápido", "Menos a pie", "Menos transbordos"), Planeador.orden, { Tacto.tic(); Planeador.orden = it },
                                    // Gris iluminado en oscuro, nunca la pastilla casi blanca del acento monocromo.
                                    color = rellenoActivo().first,
                                )
                            }
                        }
                    }
                    item("tipo") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipSel("Todas", Planeador.tipo == 0) { Planeador.tipo = 0 }
                            ChipSel("Solo SITP", Planeador.tipo == 1) { Planeador.tipo = 1 }
                            ChipSel("Con TransMilenio", Planeador.tipo == 2) { Planeador.tipo = 2 }
                        }
                    }
                    // Todas las formas de llegar: una tarjeta por combinación de buses, con sus siguientes salidas debajo.
                    val grupos = todas.filter { x ->
                        val sis = x.legs.filter { l -> l.mode == "transit" }.map { l -> l.system }
                        when (Planeador.tipo) { 1 -> sis.isNotEmpty() && sis.none { esTransMilenio(it) }; 2 -> sis.any { esTransMilenio(it) }; else -> true }
                    }.groupBy { cadena(it) }.values.toList()
                    item("res-t") { Seccion(if (cargando) "Actualizando…" else if (grupos.isEmpty()) "Ninguna con ese filtro" else "Todas las rutas · ${grupos.size} forma${if (grupos.size > 1) "s" else ""} de llegar") }
                    val mejor = grupos.firstOrNull()?.first()
                    val etiquetas = fortalezas(grupos.map { g -> g.first() })
                    itemsIndexed(grupos, key = { i, x -> "v$i-" + x.first().id }) { _, g -> TarjetaViaje(g.first(), g.first() == mejor, Modifier.revelar("v" + g.first().id), siguientes = g.drop(1).sortedBy { x -> x.start }.take(3), etiquetas = etiquetas[g.first().id].orEmpty()) { abrir(Capa.Viaje(g.first())) } }
                    r.walk?.let { w -> item("pie") { TarjetaViaje(w, false) { abrir(Capa.Viaje(w)) } } }
                    if (r.next != null) item("mas") {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.Center) {
                            if (Planeador.cargandoMas) co.saerling.rutas.fx.Girador(color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                            else BotonExpresivo("Ver más opciones", Icons.Rounded.ExpandMore, { cargarMas() }, estilo = EstiloBoton.TONAL)
                        }
                    }
                    item("credito") { Text("Rutas planeadas con Transitous y datos abiertos de TransMilenio.", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 14.dp)) }
                }
            }
        }
    }
}

/** Orden como en Moovit. «Mejor» pesa lo que tarda, lo que caminas y los transbordos; las otras ordenan por una sola cosa. */
private fun ordenar(lista: List<Itinerary>, modo: Int): List<Itinerary> = when (modo) {
    1 -> lista.sortedBy { it.end }
    2 -> lista.sortedWith(compareBy({ it.walkMetres }, { it.end }))
    3 -> lista.sortedWith(compareBy({ it.transfers }, { it.end }))
    else -> lista.sortedBy { it.minutes + it.walkMetres / 120 + it.transfers * 6 + runCatching { java.time.Duration.between(java.time.OffsetDateTime.now(), java.time.OffsetDateTime.parse(it.start)).toMinutes().coerceAtLeast(0) / 2 }.getOrDefault(0L) }
}

/** Una parte del día para planear: hoy si todavía no llega, si no mañana. */
private class Franja(val id: String, val texto: String, val a: java.time.ZonedDateTime) {
    fun minutos(): Int = java.time.Duration.between(java.time.ZonedDateTime.now(a.zone), a).toMinutes().toInt().coerceAtLeast(1)
}

private fun franjas(): List<Franja> {
    val zona = java.time.ZoneId.of("America/Bogota")
    val ahora = java.time.ZonedDateTime.now(zona)
    return listOf(Triple("manana", 7, "mañana"), Triple("tarde", 13, "tarde"), Triple("noche", 18, "noche")).map { (id, h, nombre) ->
        var a = ahora.withHour(h).withMinute(0).withSecond(0).withNano(0)
        val manana = !a.isAfter(ahora.plusMinutes(15))
        if (manana) a = a.plusDays(1)
        val parte = if (manana) when (id) { "manana" -> "Mañana temprano"; else -> "Mañana en la $nombre" } else when (id) { "manana" -> "Esta mañana"; else -> "Esta $nombre" }
        Franja(id, "$parte · $h:00", a)
    }
}

/**
 * En qué es mejor cada opción frente a las demás, como las etiquetas de Vía: solo una diferencia real gana etiqueta
 * (si todas empatan o dos comparten el primer puesto, nadie la lleva).
 */
private fun fortalezas(lista: List<Itinerary>): Map<String, List<String>> {
    val out = lista.associate { it.id to mutableListOf<String>() }
    if (lista.size < 2) return out
    fun mejor(etiqueta: String, valor: (Itinerary) -> Long) {
        val valores = lista.map(valor)
        val min = valores.min()
        if (valores.all { it == min }) return
        lista.filter { valor(it) == min }.singleOrNull()?.let { out.getValue(it.id).add(etiqueta) }
    }
    mejor("Más rápida") { runCatching { java.time.OffsetDateTime.parse(it.end).toEpochSecond() }.getOrDefault(Long.MAX_VALUE) }
    mejor("Menos transbordos") { it.transfers.toLong() }
    mejor("Menos caminata") { (it.walkMetres / 100).toLong() }
    return out
}

/** La combinación de buses de un viaje (K309 › 1), para agrupar las salidas de la misma ruta. */
private fun cadena(i: Itinerary) = i.legs.filter { it.mode == "transit" }.joinToString(">") { codigoVisible(it.code, it.headsign) }.ifEmpty { "pie" }

private fun nombrar(r: PlanResult, desde: String, hasta: String): PlanResult {
    fun n(s: String) = when (s) { "START" -> desde; "END" -> hasta; else -> s }
    fun f(i: Itinerary) = i.copy(legs = i.legs.map { l -> l.copy(from = l.from.copy(name = n(l.from.name)), to = l.to.copy(name = n(l.to.name))) })
    return PlanResult(r.itineraries.map(::f), r.walk?.let(::f), r.next)
}

@Composable
private fun CampoRuta(icono: ImageVector, etiqueta: String, valor: String, activo: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(SuaveShape(14.dp)).pressable(onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icono, null, tint = if (activo) androidx.compose.material3.MaterialTheme.colorScheme.primary else LectorColors.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(etiqueta, style = LectorType.Label, color = LectorColors.Muted)
            Text(valor, style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FilaLugar(icono: ImageVector, nombre: String, zona: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().resaltable(onClick = onClick).padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(SuaveShape(13.dp)).background(LectorColors.CardHigh), Alignment.Center) { Icon(icono, null, tint = LectorColors.Text, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (zona != null) Text(zona, style = LectorType.Label, color = LectorColors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun Atajo(icono: ImageVector, texto: String, modifier: Modifier, onClick: () -> Unit) {
    Tarjeta(modifier, onClick = onClick) {
        Icon(icono, null, tint = LectorColors.Text, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Text(texto, style = LectorType.Section, color = LectorColors.Text)
    }
}

fun minutosDesdeAhora(iso: String): Long = runCatching { java.time.Duration.between(java.time.OffsetDateTime.now(), java.time.OffsetDateTime.parse(iso)).toMinutes() }.getOrDefault(0L)

@Composable
private fun SeccionColor(texto: String, color: Color) {
    Row(Modifier.padding(horizontal = Margen).padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(SuaveShape(4.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(texto.uppercase(), style = LectorType.Label.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.SemiBold), color = LectorColors.Muted)
    }
}

/** Tarjetas en fila con el próximo bus de cada ruta: cuándo sale, desde dónde y a qué hora llegas. */
@Composable
private fun FilaPorTomar(lista: List<Pair<Leg, Itinerary>>, abrir: (Itinerary) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        lista.take(10).forEach { (l, x) ->
            val faltan = minutosHasta(l.start)
            val caminar = x.legs.takeWhile { it.mode != "transit" }.sumOf { it.minutes }
            Tarjeta(Modifier.width(170.dp), radio = 22.dp, onClick = { abrir(x) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Insignia(codigoVisible(l.code, l.headsign), l.system, grande = true)
                    Spacer(Modifier.weight(1f))
                    if (l.live) PuntoVivo(7.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(if (faltan <= 0) "Sale ya" else if (faltan < 60) "En $faltan min" else hora(l.start), style = LectorType.Hero.copy(fontSize = 22.sp), color = LectorColors.Text)
                Text(l.from.name, style = LectorType.Label, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(listOfNotNull(if (caminar > 0) "$caminar min a pie" else null, "llegas ${hora(x.end)}").joinToString(" · "), style = LectorType.Label, color = LectorColors.Muted, maxLines = 1)
                if (caminar > 0 && faltan in 0 until caminar) Text("Puede que no alcances", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFE5484D))
            }
        }
    }
}

/** La barra del viaje (como Citymapper y Google Maps): cada tramo del largo de lo que tarda, en su color. */
@Composable
fun BarraViaje(it: Itinerary, modifier: Modifier = Modifier) {
    val total = it.legs.sumOf { l -> l.minutes.coerceAtLeast(1) }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        it.legs.forEach { l ->
            val pie = l.mode != "transit"
            Box(Modifier.weight(l.minutes.coerceAtLeast(1).toFloat() / total).fillMaxHeight().clip(SuaveShape(3.dp))
                .background(if (pie) LectorColors.PillFill else colorSistema(l.system)))
        }
    }
}

/** Minutos hasta una hora ISO, con un reloj que avanza solo cada 30 s. */
@Composable
fun minutosHasta(iso: String): Int {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); ahora = System.currentTimeMillis() } }
    return runCatching { ((java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() - ahora) / 60_000).toInt() }.getOrDefault(0)
}

/** Una forma de llegar, como en Moovit: cuánto tarda, la cadena de buses, la hora y el pasaje. */
@Composable
fun TarjetaViaje(it: Itinerary, rapida: Boolean, modifier: Modifier = Modifier, siguientes: List<Itinerary> = emptyList(), etiquetas: List<String> = emptyList(), onClick: () -> Unit) {
    val primero = it.legs.firstOrNull { l -> l.mode == "transit" }
    Tarjeta(modifier.padding(horizontal = Margen, vertical = 5.dp).then(if (co.saerling.rutas.fx.fxOn("tilt")) Modifier.inclinar(4f) else Modifier), radio = 26.dp, color = if (rapida) LectorColors.CardHigh else LectorColors.Card, onClick = onClick) {
        if (etiquetas.isNotEmpty()) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                etiquetas.forEach { e ->
                    val (fondo, tinta) = if (e == "Más rápida") rellenoActivo() else LectorColors.PillFill to LectorColors.Text
                    Text(e, style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = tinta, modifier = Modifier.clip(SuaveShape(10.dp)).background(fondo).padding(horizontal = 9.dp, vertical = 4.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(duracion(it.minutes), style = LectorType.Hero, color = LectorColors.Text)
            Spacer(Modifier.weight(1f))
            Text("${hora(it.start)} – ${hora(it.end)}", style = LectorType.Label, color = LectorColors.Muted)
        }
        Spacer(Modifier.height(10.dp))
        BarraViaje(it)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            it.legs.forEachIndexed { i, l ->
                if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted, modifier = Modifier.size(16.dp))
                if (l.mode == "transit") Insignia(codigoVisible(l.code, l.headsign), l.system)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = LectorColors.Muted, modifier = Modifier.size(18.dp))
                    Text("${l.minutes}", style = LectorType.Label, color = LectorColors.Muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (primero != null) {
                if (it.live) { PuntoVivo(7.dp); Spacer(Modifier.width(6.dp)) }
                val faltan = minutosHasta(primero.start)
                Text((if (faltan <= 0) "Sale ya" else if (faltan < 60) "Sale en $faltan min" else "Sale ${hora(primero.start)}") + " · ${if (it.live) "en vivo" else "según horario"}", style = LectorType.Label, color = LectorColors.Text)
            } else Text("Caminando", style = LectorType.Label, color = LectorColors.Text)
            Spacer(Modifier.weight(1f))
            val p = it.pasaje()
            Text(listOfNotNull(if (p > 0) "≈ ${pesos(p)}" else null, "${it.walkMetres} m a pie").joinToString(" · "), style = LectorType.Label, color = LectorColors.Muted)
        }
        if (siguientes.isNotEmpty()) {
            val primeros = siguientes.mapNotNull { x -> x.legs.firstOrNull { l -> l.mode == "transit" }?.start?.let { hora(it) } }
            if (primeros.isNotEmpty()) Text("También sale a las ${primeros.joinToString(" · ")}", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ───────────────────────────────────────── Buscar ─────────────────────────────────────────

@Composable
fun BuscarScreen(abrir: (Capa) -> Unit) {
    var texto by rememberSaveable { mutableStateOf("") }
    var res by remember { mutableStateOf<SearchResult?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(texto) {
        if (texto.trim().length < 2) { res = null; return@LaunchedEffect }
        delay(250); error = false
        runCatching { Api.buscar(texto.trim()) }.onSuccess { res = it }.onFailure { error = true }
    }
    PaginaPestana("Buscar", "Rutas, paraderos y estaciones", arriba = {
        Box(Modifier.padding(horizontal = Margen)) { CampoBusqueda(texto, { texto = it }, "K635, B10, Portal Norte…") }
    }) {
        val r = res
        when {
            texto.trim().length < 2 -> item("ej") {
                if (Guardados.busquedas.isNotEmpty()) {
                    Seccion("Recientes")
                    Guardados.busquedas.forEachIndexed { i, busqueda ->
                        Row(Modifier.fillMaxWidth().resaltable { Tacto.tic(); texto = busqueda }.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = LectorColors.Muted)
                            Spacer(Modifier.width(12.dp))
                            Text(busqueda, style = LectorType.Body, color = LectorColors.Text)
                        }
                    }
                    BotonExpresivo("Borrar recientes", null, { Guardados.borrarBusquedas() }, Modifier.padding(horizontal = Margen), estilo = EstiloBoton.TEXTO)
                }
                Seccion("Prueba con")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("K635", "B10", "Portal Norte", "Calle 72", "H710", "TransMiCable").forEach { ChipSel(it, false) { texto = it } }
                }
                Text("Las rutas SITP en pareja (como K635 y H635) aparecen con el código que lleva el bus.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 18.dp))
            }
            error -> item("e") { Vacio("No pudimos buscar", "Revisa tu conexión.") }
            r == null -> item("c") { CargandoFilas(4) }
            r.routes.isEmpty() && r.stops.isEmpty() -> item("n") { Vacio("Nada para «${texto.trim()}»", "Prueba con el código del bus o el nombre de la estación.") }
            else -> {
                val mejorRuta = r.routes.firstOrNull()
                val mejorParadero = if (mejorRuta == null) r.stops.firstOrNull() else null
                item("mejor") {
                    Seccion("Mejor resultado")
                    if (mejorRuta != null) co.saerling.rutas.ui.mochy.search.TopResultCard(
                        artwork = { Insignia(mejorRuta.code, mejorRuta.system, grande = true) },
                        title = mejorRuta.name,
                        detail = nombreSistema(mejorRuta.system) + (mejorRuta.variant?.let { " · $it" } ?: ""),
                        onClick = { Guardados.recordarBusqueda(texto); abrir(Capa.Ruta(mejorRuta.id)) },
                        actionIcon = Icons.Rounded.ChevronRight,
                        actionDescription = "Abrir ruta ${mejorRuta.code}",
                    ) else if (mejorParadero != null) co.saerling.rutas.ui.mochy.search.TopResultCard(
                        artwork = { Box(Modifier.size(44.dp).clip(SuaveShape(14.dp)).background(LectorColors.CardHigh), Alignment.Center) { Icon(if (mejorParadero.kind == "station") Icons.Rounded.Train else Icons.Rounded.DirectionsBus, null, tint = LectorColors.Text, modifier = Modifier.size(22.dp)) } },
                        title = mejorParadero.name,
                        detail = listOfNotNull(if (mejorParadero.kind == "station") "Estación" else "Paradero SITP", mejorParadero.distanceM?.let { distancia(it) }).joinToString(" · "),
                        onClick = { Guardados.recordarBusqueda(texto); abrir(Capa.Paradero(mejorParadero.id)) },
                        actionIcon = Icons.Rounded.ChevronRight,
                        actionDescription = "Abrir ${mejorParadero.name}",
                    )
                }
                val rutasRestantes = r.routes.drop(if (mejorRuta != null) 1 else 0)
                val paraderosRestantes = r.stops.drop(if (mejorParadero != null) 1 else 0)
                if (rutasRestantes.isNotEmpty()) item("rt") { Seccion("Rutas") }
                itemsIndexed(rutasRestantes, key = { i, x -> "r$i-" + x.id }) { _, ro -> FilaRuta(ro, Modifier.revelar("br" + ro.id)) { Guardados.recordarBusqueda(texto); abrir(Capa.Ruta(ro.id)) } }
                if (paraderosRestantes.isNotEmpty()) item("st") { Seccion("Paraderos y estaciones") }
                itemsIndexed(paraderosRestantes, key = { i, x -> "s$i-" + x.id }) { _, s -> FilaParadero(s, { Guardados.recordarBusqueda(texto); abrir(Capa.Paradero(s.id)) }, Modifier.revelar("bs" + s.id)) }
            }
        }
    }
}

@Composable
fun FilaRuta(r: RouteRef, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().resaltable { Tacto.tic(); onClick() }.padding(horizontal = Margen, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 64.dp)) { Insignia(r.code, r.system, grande = true) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = LectorType.Section, color = LectorColors.Text, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(nombreSistema(r.system) + (r.variant?.let { " · $it" } ?: ""), style = LectorType.Label, color = LectorColors.Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LectorColors.Muted)
    }
}

fun nombreSistema(s: String?) = when (s) {
    "troncal" -> "Troncal"; "dual" -> "Dual"; "zonal_urbano" -> "Zonal"; "alimentador" -> "Alimentador"
    "zonal_complementario" -> "Complementario"; "zonal_especial" -> "Especial"; "transmicable" -> "TransMiCable"; else -> "Ruta"
}

// ───────────────────────────────────────── Guardados ─────────────────────────────────────────

@Composable
fun GuardadosScreen(abrir: (Capa) -> Unit) {
    PaginaPestana("Guardados") {
        val hayGuardados = Guardados.paraderos.isNotEmpty() || Guardados.rutas.isNotEmpty() || Guardados.casa != null || Guardados.trabajo != null
        if (Guardados.paraderos.isEmpty() && Guardados.rutas.isEmpty() && Guardados.casa == null && Guardados.trabajo == null) {
            item("vacio") { Vacio("Aún no guardas nada", "Toca la estrella en un paradero o una ruta y aparecerá aquí, con sus próximos buses.") }
        }
        if (Guardados.casa != null || Guardados.trabajo != null) {
            item("lt") { Seccion("Lugares") }
            Guardados.casa?.let { c -> item("casa") { FilaQuitable(Icons.Rounded.Home, "Casa", c.name, { Planeador.hasta = c; abrir(Capa.IrA) }) { Guardados.ponerCasa(null); Avisos.mostrar("Casa quitada", "Deshacer") { Guardados.ponerCasa(c) } } } }
            Guardados.trabajo?.let { t -> item("trab") { FilaQuitable(Icons.Rounded.Work, "Trabajo", t.name, { Planeador.hasta = t; abrir(Capa.IrA) }) { Guardados.ponerTrabajo(null); Avisos.mostrar("Trabajo quitado", "Deshacer") { Guardados.ponerTrabajo(t) } } } }
        }
        if (Guardados.paraderos.isNotEmpty()) item("pt") { Seccion("Paraderos · próximos buses") }
        itemsIndexed(Guardados.paraderos, key = { i, x -> "p$i-" + x.id }) { _, g ->
            ParaderoConBuses(g, Modifier.revelar("gp" + g.id), { abrir(Capa.Paradero(g.id)) }) {
                Guardados.quitarParadero(g.id); Avisos.mostrar("Quitado de guardados", "Deshacer") { Guardados.devolver(g) }
            }
        }
        if (Guardados.rutas.isNotEmpty()) item("rt") { Seccion("Rutas") }
        itemsIndexed(Guardados.rutas, key = { i, x -> "r$i-" + x.id }) { _, g ->
            Quitable(alQuitar = { Guardados.quitarRuta(g.id); Avisos.mostrar("Ruta quitada", "Deshacer") { Guardados.devolver(g) } }, modifier = Modifier.revelar("gr" + g.id)) {
                FilaRuta(RouteRef(g.id, code = g.codigo, name = g.nombre, system = g.sistema)) { abrir(Capa.Ruta(g.id)) }
            }
        }
        if (hayGuardados) item("deslizar-ayuda") {
            Text("Desliza una fila a la izquierda para quitarla.", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 14.dp))
        }
    }
}

@Composable
private fun Quitable(alQuitar: () -> Unit, modifier: Modifier = Modifier, slab: Boolean = true, contenido: @Composable () -> Unit) {
    SwipeActionsBox(
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction("Quitar") { alQuitar(); true }) },
        end = { SwipeAction(Icons.Rounded.Delete, Color(0xFFFF453A), "Quitar", alQuitar) },
        slab = slab,
        content = contenido,
    )
}

@Composable
private fun FilaQuitable(icono: ImageVector, titulo: String, detalle: String, onClick: () -> Unit, alQuitar: () -> Unit) {
    Quitable(alQuitar = alQuitar) {
        FilaLugar(icono, titulo, detalle, onClick = onClick)
    }
}

/** Paradero guardado con sus próximos buses en vivo (se actualiza cada 30 s mientras se ve). */
@Composable
private fun ParaderoConBuses(g: ParaderoGuardado, modifier: Modifier, onClick: () -> Unit, alQuitar: () -> Unit) {
    var detalle by remember { mutableStateOf<StopDetail?>(null) }
    LaunchedEffect(g.id) { while (true) { runCatching { Api.paradero(g.id) }.onSuccess { detalle = it }; delay(30_000) } }
    Quitable(alQuitar = alQuitar, modifier = modifier.padding(horizontal = Margen).padding(bottom = 10.dp), slab = false) {
        Tarjeta(Modifier, onClick = onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (g.estacion) Icons.Rounded.Train else Icons.Rounded.DirectionsBus, null, tint = LectorColors.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(g.nombre, style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val proximos = detalle?.arrivals.orEmpty().distinctBy { it.code }.take(4)
            if (proximos.isEmpty()) Text(if (detalle == null) "Cargando…" else "Sin buses próximos", style = LectorType.Label, color = LectorColors.Muted)
            proximos.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.widthIn(min = 52.dp)) { Insignia(a.code, a.system) }
                    Spacer(Modifier.width(8.dp))
                    Text(a.name, style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.weight(1f), maxLines = 1)
                    Minutos(a)
                }
            }
        }
    }
}

@Composable
fun AjustesScreen(abrir: (Capa) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MapaOffline.revisar(ctx) }
    var q by remember { mutableStateOf("") }
    // Sin tildes ni mayúsculas: «ubicacion» encuentra «ubicación».
    fun plano(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
    val buscado = plano(q.trim())
    fun ve(vararg palabras: String) = buscado.isEmpty() || palabras.any { plano(it).contains(buscado) }
    val verTransporte = ve("Transporte", "Tarjeta TuLlave saldo movimientos NFC", "Estado de las vías tráfico", "Novedades del sistema desvíos cierres")
    val verApariencia = ve("Apariencia", "Personalización tema acento cristal esquinas animaciones", "Biblioteca de animaciones y funciones")
    val verOffline = ve("Mapa sin conexión descargar túneles datos")
    val verApp = ve("La app", "Buscar actualizaciones versión", "Código fuente GitHub")
    PaginaPestana("Ajustes") {
        item("buscar") { Box(Modifier.padding(horizontal = Margen).padding(bottom = 6.dp)) { CampoBusqueda(q, { q = it }, "Buscar en ajustes") } }
        if (!verTransporte && !verApariencia && !verOffline && !verApp) item("nada") { Vacio("Ningún ajuste con «${q.trim()}»") }
        if (verTransporte) item("tr") { Seccion("Transporte") }
        if (verTransporte) item("transporte") {
            GrupoAjustes(listOf(
                itemAjuste(Icons.Rounded.Contactless, "Tarjeta TuLlave", "Leer saldo y movimientos con NFC") { abrir(Capa.Tarjeta) },
                itemAjuste(Icons.Rounded.Traffic, "Estado de las vías", "Tráfico de Bogotá medido con los buses en vivo") { abrir(Capa.Vias) },
                itemAjuste(Icons.Rounded.Campaign, "Novedades del sistema", "Desvíos, cierres y cambios de TransMilenio") { abrir(Capa.Novedades) },
            ))
        }
        if (verApariencia) item("p") { Seccion("Apariencia") }
        if (verApariencia) item("apariencia") {
            GrupoAjustes(listOf(
                itemAjuste(Icons.Rounded.Palette, "Personalización", "Tema, acento, cristal, esquinas, animaciones y más") { abrir(Capa.Personalizacion) },
                itemAjuste(Icons.Rounded.AutoAwesome, "Biblioteca de animaciones y funciones", "${co.saerling.rutas.fx.Fx.count(IdsFxRutas)} de ${IdsFxRutas.size} activas · Magic UI, NumberFlow, coss ui, Motion Primitives") { abrir(Capa.BibliotecaFx) },
            ))
        }
        if (verOffline) item("m") { Seccion("Mapa sin conexión") }
        if (verOffline) item("offline") {
            Tarjeta(Modifier.padding(horizontal = Margen), radio = 24.dp) {
                Text("Descarga el mapa de Bogotá para verlo sin datos: en túneles, estaciones o cuando se acaba el plan.", style = LectorType.Body, color = LectorColors.Muted)
                Spacer(Modifier.height(14.dp))
                when (val e = MapaOffline.estado) {
                    is MapaOffline.Estado.Nada -> BotonExpresivo("Descargar mapa de Bogotá", Icons.Rounded.Download, { MapaOffline.descargar(ctx) }, estilo = EstiloBoton.RELLENO)
                    is MapaOffline.Estado.Bajando -> {
                        DeslizadorProgreso(e.porcentaje)
                        Text("Descargando… ${e.porcentaje} %", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    }
                    is MapaOffline.Estado.Listo -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Listo · ${(e.bytes / 1_048_576).coerceAtLeast(1)} MB", style = LectorType.Section, color = LectorColors.Text, modifier = Modifier.weight(1f))
                        BotonExpresivo("Borrar", null, { MapaOffline.borrar(ctx) }, estilo = EstiloBoton.TEXTO)
                    }
                    is MapaOffline.Estado.Fallo -> {
                        Text("No se pudo descargar: ${e.motivo}", style = LectorType.Label, color = LectorColors.Error)
                        Spacer(Modifier.height(10.dp))
                        BotonExpresivo("Reintentar", Icons.Rounded.Refresh, { MapaOffline.descargar(ctx) })
                    }
                }
            }
        }
        if (verApp) item("a") { Seccion("La app") }
        if (verApp) item("app") {
            GrupoAjustes(listOf(
                itemAjuste(Icons.Rounded.SystemUpdate, "Buscar actualizaciones", "Tienes la ${co.saerling.rutas.NuevaVersion.nombreActual(ctx)}") {
                    co.saerling.rutas.NuevaVersion.buscarAhora(ctx)
                },
                itemAjuste(Icons.Rounded.Code, "Código fuente", "github.com/Saerling/Rutas") {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Saerling/Rutas")))
                },
            ))
        }
        item("cred") {
            Text(
                "Basada en Paradero de Croma (MIT) y en el diseño de Mochy Music (GPL-3.0). Datos de TransMilenio, rutas con Transitous, mapas © OpenStreetMap y CARTO.",
                style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.padding(horizontal = Margen, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun DeslizadorProgreso(p: Int) {
    val acento = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(8.dp).clip(SuaveShape(4.dp)).background(LectorColors.PillFill)) {
        Box(Modifier.fillMaxWidth(p.coerceIn(2, 100) / 100f).fillMaxHeight().clip(SuaveShape(4.dp)).background(acento))
    }
}

/** «Trancón en Av. Boyacá · 12 min», en palabras de calle. */
fun textoIncidente(i: Incidente): String {
    val tipo = when (i.kind) { "trancon" -> "Trancón"; "accidente" -> "Accidente"; "cierre" -> "Vía cerrada"; "obra" -> "Obras"; "peligro" -> "Peligro en la vía"; else -> i.title.ifBlank { "Incidente" } }
    val donde = listOfNotNull(i.from, i.to).filter { it.isNotBlank() }.distinct().joinToString(" → ")
    return listOfNotNull(tipo + if (donde.isNotBlank()) " en $donde" else "", i.delay?.takeIf { it >= 60 }?.let { "+${it / 60} min" }).joinToString(" · ")
}
