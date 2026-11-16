package co.saerling.rutas.ui.rutas

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.saerling.rutas.data.*
import co.saerling.rutas.ui.*
import co.saerling.rutas.fx.inclinar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

// ───────────────────────────────────────── Cerca ─────────────────────────────────────────

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
    val gpsApagado = Ubicacion.permiso && yo == null && !Ubicacion.gpsEncendido(ctx)
    val saludo = when (LocalTime.now().hour) { in 5..11 -> "Buenos días"; in 12..18 -> "Buenas tardes"; else -> "Buenas noches" }

    MapaConHoja(
        asomo = 0.42f,
        finLista = 120.dp,
        mapa = { abajo, pedido ->
            Mapa(
                Modifier.fillMaxSize(), centro = base ?: Ubicacion.BOGOTA, zoom = if (base != null) 15.5 else 11.5,
                puntos = cerca.orEmpty().take(25).map { PuntoMapa(it.id, it.lat, it.lon, it.kind == "station") }, yo = punto,
                encuadrar = if (zona != null) emptyList() else listOfNotNull(punto) + cerca.orEmpty().take(6).map { it.lat to it.lon },
                alTocarPunto = { id -> abrir(Capa.Paradero(id)) },
                alMover = { c -> centroMapa = c },
                abajo = abajo, pedido = pedido, centrarEn = punto,
            )
        },
        sobreMapa = {
            val c = centroMapa
            val lejos = c != null && Ubicacion.enBogota(c.first, c.second) && (base == null || Ubicacion.metros(c, base) > 350)
            AnimatedVisibility(lejos, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.clip(SuaveShape(20.dp)).background(LectorColors.Card).pressable { Tacto.alternar(true); zona = c; cerca = null; centroMapa = null }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = LectorColors.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Buscar en esta zona", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text)
                }
            }
        },
        contenido = {
            item("saludo") {
                Column(Modifier.padding(horizontal = Margen)) {
                    Text(saludo, style = LectorType.Hero, color = LectorColors.Text)
                    Spacer(Modifier.height(8.dp))
                    EstadoDatos(feed)
                }
            }
            item("tullave") { AccesoTarjeta { abrir(Capa.Tarjeta) } }
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
                cerca == null -> item("cargando") { Seccion(if (zona != null) "En esta zona" else "Cerca de ti"); CargandoFilas() }
                cerca!!.isEmpty() -> item("vacio") { Vacio("No hay paraderos aquí", "Prueba moviendo el mapa.") }
                else -> {
                    item("lista-t") { Seccion(if (zona != null) "En esta zona" else "Cerca de ti") }
                    items(cerca.orEmpty(), key = { "c" + it.id }) { s -> FilaParadero(s, { abrir(Capa.Paradero(s.id)) }, Modifier.revelar("c" + s.id)) }
                }
            }
        },
    )
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
    Row(Modifier.padding(horizontal = Margen).clip(SuaveShape(14.dp)).background(LectorColors.Card).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
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
    var llegarA by mutableStateOf(false)
    var claveRes: String? = null
    var accesible by mutableStateOf(false)
    var menosTransbordos by mutableStateOf(false)
    var resultado by mutableStateOf<PlanResult?>(null)
    var orden by mutableIntStateOf(0)
    var cargandoMas by mutableStateOf(false)
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
    LaunchedEffect(hasta?.id, Planeador.desde?.id) { val k = "${hasta?.id}|${Planeador.desde?.id}"; if (Planeador.claveRes != k) { Planeador.resultado = null; Planeador.claveRes = k } }
    LaunchedEffect(origen?.let { "%.3f,%.3f".format(it.first, it.second) }, hasta?.id, Planeador.dentroDe, Planeador.llegarA, Planeador.accesible, Planeador.menosTransbordos, editando, intento) {
        if (editando != null || hasta == null || origen == null) return@LaunchedEffect
        cargando = true; error = false
        val hora = if (Planeador.dentroDe > 0) java.time.OffsetDateTime.now().plusMinutes(Planeador.dentroDe.toLong()).toString() else null
        runCatching { Api.plan(origen, hasta.lat to hasta.lon, hora, Planeador.llegarA, Planeador.accesible, Planeador.menosTransbordos) }
            .onSuccess { r -> Planeador.resultado = nombrar(r, Planeador.desde?.name ?: "Tu ubicación", hasta.name) }.onFailure { error = true }
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
                items(Guardados.recientes, key = { "r" + it.id }) { p -> FilaLugar(Icons.Rounded.History, p.name, p.area) { elegir(p, e) } }
            } else when (val l = lugares) {
                null -> item("c") { CargandoFilas(3) }
                else -> if (l.isEmpty()) item("n") { Vacio("Nada para «${texto.trim()}»") }
                    else items(l, key = { "l" + it.id }) { p -> FilaLugar(if (p.kind == "stop") Icons.Rounded.Train else Icons.Rounded.Place, p.name, p.area, Modifier.revelar("l" + p.id)) { elegir(p, e) } }
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
                    ChipSel("Ahora", Planeador.dentroDe == 0) { Planeador.dentroDe = 0; Planeador.llegarA = false }
                    listOf(15, 30, 60).forEach { m -> ChipSel(if (m < 60) "En $m min" else "En 1 h", Planeador.dentroDe == m) { Planeador.dentroDe = m } }
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
                    item("orden") {
                        // Segmented Control deslizante (Motion Primitives) para ordenar, como los filtros de Moovit.
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides LectorColors.Text) {
                            Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Margen).padding(top = 16.dp)) {
                                co.saerling.rutas.fx.SegmentadoDeslizante(
                                    listOf("Mejor", "Más rápido", "Menos a pie", "Menos transbordos"), Planeador.orden, { Tacto.tic(); Planeador.orden = it },
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    // Como Moovit: una tarjeta por combinación de buses, con sus siguientes salidas debajo.
                    val grupos = ordenar(r.itineraries, Planeador.orden).groupBy { cadena(it) }.values.toList()
                    item("res-t") { Seccion(if (cargando) "Actualizando…" else "${grupos.size} formas de llegar") }
                    val mejor = grupos.firstOrNull()?.first()
                    items(grupos, key = { it.first().id }) { g -> TarjetaViaje(g.first(), g.first() == mejor, Modifier.revelar("v" + g.first().id), siguientes = g.drop(1).sortedBy { x -> x.start }.take(3)) { abrir(Capa.Viaje(g.first())) } }
                    r.walk?.let { w -> item("pie") { TarjetaViaje(w, false) { abrir(Capa.Viaje(w)) } } }
                    if (r.next != null) item("mas") {
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp), Alignment.Center) {
                            if (Planeador.cargandoMas) co.saerling.rutas.fx.Girador(color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                            else BotonExpresivo("Ver más opciones", Icons.Rounded.ExpandMore, {
                                Planeador.cargandoMas = true
                                escena.launch {
                                    val o = origen
                                    runCatching { Api.plan(o, hasta.lat to hasta.lon, if (Planeador.dentroDe > 0) java.time.OffsetDateTime.now().plusMinutes(Planeador.dentroDe.toLong()).toString() else null, Planeador.llegarA, cursor = r.next, accesible = Planeador.accesible, menosTransbordos = Planeador.menosTransbordos) }
                                        .onSuccess { mas -> val n = nombrar(mas, Planeador.desde?.name ?: "Tu ubicación", hasta.name)
                                            Planeador.resultado = r.copy(itineraries = (r.itineraries + n.itineraries).distinctBy { x -> x.id }, next = n.next) }
                                        .onFailure { Avisos.mostrar("No pudimos cargar más opciones") }
                                    Planeador.cargandoMas = false
                                }
                            }, estilo = EstiloBoton.TONAL)
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

/** Minutos hasta una hora ISO, con un reloj que avanza solo cada 30 s. */
@Composable
fun minutosHasta(iso: String): Int {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); ahora = System.currentTimeMillis() } }
    return runCatching { ((java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() - ahora) / 60_000).toInt() }.getOrDefault(0)
}

/** Una forma de llegar, como en Moovit: cuánto tarda, la cadena de buses, la hora y el pasaje. */
@Composable
fun TarjetaViaje(it: Itinerary, rapida: Boolean, modifier: Modifier = Modifier, siguientes: List<Itinerary> = emptyList(), onClick: () -> Unit) {
    val primero = it.legs.firstOrNull { l -> l.mode == "transit" }
    Tarjeta(modifier.padding(horizontal = Margen, vertical = 5.dp).then(if (co.saerling.rutas.fx.fxOn("tilt")) Modifier.inclinar(4f) else Modifier), radio = 26.dp, color = if (rapida) LectorColors.CardHigh else LectorColors.Card, onClick = onClick) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(duracion(it.minutes), style = LectorType.Hero, color = LectorColors.Text)
            Spacer(Modifier.weight(1f))
            Text("${hora(it.start)} – ${hora(it.end)}", style = LectorType.Label, color = LectorColors.Muted)
        }
        Spacer(Modifier.height(12.dp))
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
                if (r.routes.isNotEmpty()) item("rt") { Seccion("Rutas") }
                items(r.routes, key = { "r" + it.id }) { ro -> FilaRuta(ro, Modifier.revelar("br" + ro.id)) { abrir(Capa.Ruta(ro.id)) } }
                if (r.stops.isNotEmpty()) item("st") { Seccion("Paraderos y estaciones") }
                items(r.stops, key = { "s" + it.id }) { s -> FilaParadero(s, { abrir(Capa.Paradero(s.id)) }, Modifier.revelar("bs" + s.id)) }
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
        if (Guardados.paraderos.isEmpty() && Guardados.rutas.isEmpty()) {
            item("vacio") { Vacio("Aún no guardas nada", "Toca la estrella en un paradero o una ruta y aparecerá aquí, con sus próximos buses.") }
        }
        if (Guardados.paraderos.isNotEmpty()) item("pt") { Seccion("Paraderos") }
        items(Guardados.paraderos, key = { "p" + it.id }) { g ->
            FilaParadero(Stop(g.id, g.nombre, if (g.estacion) "station" else "stop", g.lat, g.lon, routes = g.codigos.map { RouteRef(it, code = it) }), { abrir(Capa.Paradero(g.id)) }, Modifier.revelar("gp" + g.id))
        }
        if (Guardados.rutas.isNotEmpty()) item("rt") { Seccion("Rutas") }
        items(Guardados.rutas, key = { "r" + it.id }) { g -> FilaRuta(RouteRef(g.id, code = g.codigo, name = g.nombre, system = g.sistema), Modifier.revelar("gr" + g.id)) { abrir(Capa.Ruta(g.id)) } }
        if (Guardados.casa != null || Guardados.trabajo != null) {
            item("lt") { Seccion("Lugares") }
            Guardados.casa?.let { c -> item("casa") { FilaLugar(Icons.Rounded.Home, c.name, "Casa") { Planeador.hasta = c; abrir(Capa.IrA) } } }
            Guardados.trabajo?.let { t -> item("trab") { FilaLugar(Icons.Rounded.Work, t.name, "Trabajo") { Planeador.hasta = t; abrir(Capa.IrA) } } }
        }
    }
}

// ───────────────────────────────────────── Ajustes ─────────────────────────────────────────

@Composable
fun AjustesScreen(abrir: (Capa) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MapaOffline.revisar(ctx) }
    PaginaPestana("Ajustes") {
        item("p") { Seccion("Apariencia") }
        item("pers") { FilaAjuste(Icons.Rounded.Palette, "Personalización", "Tema, acento, cristal, esquinas, animaciones y más") { abrir(Capa.Personalizacion) } }
        item("fx") { FilaAjuste(Icons.Rounded.AutoAwesome, "Biblioteca de animaciones y funciones", "${co.saerling.rutas.fx.Fx.count(IdsFxRutas)} de ${IdsFxRutas.size} activas · Magic UI, NumberFlow, coss ui, Motion Primitives") { abrir(Capa.BibliotecaFx) } }
        item("m") { Seccion("Mapa sin conexión") }
        item("offline") {
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
        item("a") { Seccion("La app") }
        item("upd") {
            FilaAjuste(Icons.Rounded.SystemUpdate, "Buscar actualizaciones", "Tienes la ${co.saerling.rutas.NuevaVersion.nombreActual(ctx)}") {
                co.saerling.rutas.NuevaVersion.buscarAhora(ctx)
            }
        }
        item("code") {
            FilaAjuste(Icons.Rounded.Code, "Código fuente", "github.com/Saerling/Rutas") {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Saerling/Rutas")))
            }
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
