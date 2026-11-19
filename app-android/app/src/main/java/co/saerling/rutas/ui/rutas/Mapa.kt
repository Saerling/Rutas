package co.saerling.rutas.ui.rutas

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import co.saerling.rutas.data.Bus
import co.saerling.rutas.ui.TemaActual
import co.saerling.rutas.ui.LectorColors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Mapas de CARTO sobre OpenStreetMap: los mismos que guarda la descarga sin conexión. */
object EstilosMapa {
    const val CLARO = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
    const val OSCURO = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
    fun actual() = if (TemaActual.oscuro) OSCURO else CLARO
}

/** Colores de cada parte del sistema, como en las señales de TransMilenio. */
fun colorSistema(s: String?): Color = when (s) {
    "troncal", "dual" -> Color(0xFFD7262C)
    "zonal_urbano" -> Color(0xFF2F6FC6)
    "alimentador" -> Color(0xFF3FA34D)
    "zonal_complementario" -> Color(0xFFF08A24)
    "zonal_especial" -> Color(0xFF8B5CB8)
    "transmicable" -> Color(0xFF0FA3A8)
    else -> Color(0xFF8E8E93)
}

data class PuntoMapa(val id: String, val lat: Double, val lon: Double, val fuerte: Boolean = false, val estacion: Boolean = fuerte)
data class TramoMapa(val puntos: List<List<Double>>, val color: Color, val discontinuo: Boolean = false)

/** En las capturas de prueba (Robolectric) no hay motor de mapas: se pinta un mapa de mentira. */
object ModoPrueba { var activo = false }

private fun iniciarMapLibre(c: Context) = MapLibre.getInstance(c.applicationContext)

/**
 * Mapa con paraderos, tramos de recorrido, buses y tu ubicación. Se encuadra solo en lo que muestra.
 * Los datos se pasan a fuentes GeoJSON: cambiar un bus no recrea el mapa.
 */
@Composable
fun Mapa(
    modifier: Modifier = Modifier,
    centro: Pair<Double, Double>,
    zoom: Double = 14.0,
    puntos: List<PuntoMapa> = emptyList(),
    tramos: List<TramoMapa> = emptyList(),
    buses: List<Bus> = emptyList(),
    yo: Pair<Double, Double>? = null,
    encuadrar: List<Pair<Double, Double>> = emptyList(),
    alTocarPunto: ((String) -> Unit)? = null,
    /** Espacio que tapa la hoja de abajo: el encuadre y el logo de la cámara quedan por encima de ella. */
    abajo: androidx.compose.ui.unit.Dp = 0.dp,
    /** Cambia este número para volar a [centrarEn] (botón «mi ubicación»). */
    pedido: Int = 0,
    centrarEn: Pair<Double, Double>? = null,
    /** Cuando el mapa se queda quieto tras moverlo el usuario: su centro. */
    alMover: ((Pair<Double, Double>) -> Unit)? = null,
    /** Tocar un bus: su id. */
    alTocarBus: ((String) -> Unit)? = null,
    /** Mientras no sea null, la cámara sigue este punto (el bus que sigues). */
    seguir: Pair<Double, Double>? = null,
    /** Pinta el tramo que acaba de recorrer cada bus SITP del color de su velocidad (verde, naranja, rojo). */
    trafico: Boolean = false,
    /** Zonas de tráfico de toda la ciudad (manchas de color). */
    zonas: List<co.saerling.rutas.data.CeldaTrafico> = emptyList(),
    /** El bus resaltado (el que tocaste): se dibuja más grande. */
    resaltado: String? = null,
    /** Mosaicos de flujo de tráfico de TomTom (calles verdes, naranja y rojas), si el servidor los tiene. */
    flujo: Boolean = false,
    /** Incidentes viales (TomTom): trancones, accidentes, cierres y obras, con su trazo. */
    incidentes: List<co.saerling.rutas.data.Incidente> = emptyList(),
) {
    if (ModoPrueba.activo) { MapaFalso(modifier, puntos, tramos, buses); return }
    val ctx = LocalContext.current
    remember { iniciarMapLibre(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    var estilo by remember { mutableStateOf<Style?>(null) }
    val url = EstilosMapa.actual()
    val texto = if (TemaActual.oscuro) Color.White else Color(0xFF111114)
    val movidoPorUsuario = remember { booleanArrayOf(false) }
    val alMoverActual = rememberUpdatedState(alMover)
    val alTocarActual = rememberUpdatedState(alTocarPunto)
    val alTocarBusActual = rememberUpdatedState(alTocarBus)
    val flujoActual = rememberUpdatedState(flujo)
    val bajoPx = with(LocalDensity.current) { abajo.toPx().toInt() }
    val vista = remember {
        // TextureView: respeta las esquinas redondeadas y las animaciones de Mochy (un SurfaceView las ignora).
        MapView(ctx, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
            // Mientras carga, el fondo del tema (antes salía un gris claro que destellaba en modo oscuro).
            .foregroundLoadColor(LectorColors.Bg.toArgb())).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.apply { isCompassEnabled = false; isRotateGesturesEnabled = false; isTiltGesturesEnabled = false; isAttributionEnabled = false; isLogoEnabled = false }
                m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(centro.first, centro.second)).zoom(zoom).build()
                // Escuchas una sola vez por mapa (no en cada cambio de estilo), con la acción más reciente.
                m.addOnCameraMoveStartedListener { razon -> if (razon == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) movidoPorUsuario[0] = true }
                m.addOnCameraIdleListener {
                    if (movidoPorUsuario[0]) { movidoPorUsuario[0] = false; m.cameraPosition.target?.let { t -> alMoverActual.value?.invoke(t.latitude to t.longitude) } }
                }
                m.addOnMapClickListener { ll ->
                    val pt = m.projection.toScreenLocation(ll)
                    val bus = m.queryRenderedFeatures(android.graphics.RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24), "buses", "buses-codigo").firstOrNull()?.getStringProperty("id")
                    val accionBus = alTocarBusActual.value
                    if (bus != null && accionBus != null) { accionBus(bus); return@addOnMapClickListener true }
                    val f = m.queryRenderedFeatures(pt, "puntos").firstOrNull()
                    val id = f?.getStringProperty("id")
                    val accion = alTocarActual.value
                    if (id != null && accion != null) { accion(id); true } else false
                }
                mapa = m
            }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vista.onStart()
                Lifecycle.Event.ON_RESUME -> vista.onResume()
                Lifecycle.Event.ON_PAUSE -> vista.onPause()
                Lifecycle.Event.ON_STOP -> vista.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vista.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vista.onResume()
        onDispose { lifecycle.removeObserver(obs); vista.onPause(); vista.onStop(); vista.onDestroy() }
    }

    // Estilo según el tema; al cambiar, las capas se vuelven a poner.
    LaunchedEffect(mapa, url) {
        val m = mapa ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            s.addSource(GeoJsonSource("tramos"))
            s.addSource(GeoJsonSource("puntos"))
            s.addSource(GeoJsonSource("buses"))
            s.addSource(GeoJsonSource("yo"))
            s.addSource(GeoJsonSource("trafico"))
            s.addSource(GeoJsonSource("zonas"))
            s.addSource(GeoJsonSource("incidentes"))
            // Flujo de TomTom debajo de todo lo nuestro (encima de las calles del mapa base).
            s.addSource(org.maplibre.android.style.sources.RasterSource("flujo",
                org.maplibre.android.style.sources.TileSet("2.2.0", co.saerling.rutas.data.Api.mosaicosTrafico(TemaActual.oscuro)).apply { minZoom = 10f; maxZoom = 18f }, 256))
            s.addLayer(org.maplibre.android.style.layers.RasterLayer("flujo", "flujo").withProperties(rasterOpacity(0.7f), visibility(if (flujoActual.value) "visible" else "none")))
            s.addLayer(LineLayer("incidentes-linea", "incidentes").withProperties(
                lineColor(Expression.get("color")), lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(11, 2.5f), Expression.stop(16, 7f))),
                lineOpacity(0.9f), lineCap("round"), lineJoin("round"),
            ))
            s.addLayer(CircleLayer("zonas", "zonas").withProperties(
                circleColor(Expression.get("color")), circleOpacity(0.38f), circleBlur(0.85f),
                circleRadius(Expression.interpolate(Expression.exponential(2f), Expression.zoom(), Expression.stop(10, 14f), Expression.stop(13, 60f), Expression.stop(16, 420f))),
            ))
            s.addLayer(LineLayer("tramos-borde", "tramos").withProperties(lineColor(Expression.get("color")), lineWidth(9f), lineOpacity(0.18f), lineCap("round"), lineJoin("round"), lineBlur(4f)))
            s.addLayer(LineLayer("tramos-linea", "tramos").withProperties(lineColor(Expression.get("color")), lineWidth(4.5f), lineCap("round"), lineJoin("round")).withFilter(Expression.eq(Expression.get("punteado"), false)))
            s.addLayer(LineLayer("tramos-pie", "tramos").withProperties(lineColor(Expression.get("color")), lineWidth(3.5f), lineCap("round"), lineDasharray(arrayOf(0.1f, 2f))).withFilter(Expression.eq(Expression.get("punteado"), true)))
            val oscuro = TemaActual.oscuro
            for (e in listOf(true, false)) {
                val k = if (e) "e" else "p"
                s.addImage("placa-$k", IconosMapa.placa(ctx, e, false, oscuro))
                s.addImage("placa-$k-g", IconosMapa.placa(ctx, e, true, oscuro))
                s.addImage("punto-$k", IconosMapa.punto(ctx, e, oscuro))
            }
            // Lejos, puntos de color; de cerca, la placa con el bus (como las señales de la calle).
            s.addLayer(SymbolLayer("puntos", "puntos").withProperties(
                // Las estaciones (orden 1, más espaciadas) pasan a placa en 14.6; los paraderos SITP, cada 200 m, esperan
                // a 15.6: antes, en la vista de una ruta, sus placas se montaban unas sobre otras.
                iconImage(Expression.step(Expression.zoom(), Expression.get("lejos"),
                    Expression.stop(14.6, Expression.switchCase(Expression.eq(Expression.get("orden"), Expression.literal(1)), Expression.get("cerca"), Expression.get("lejos"))),
                    Expression.stop(15.6, Expression.get("cerca")))),
                iconAnchor(Expression.step(Expression.zoom(), Expression.get("anclaLejos"),
                    Expression.stop(14.6, Expression.switchCase(Expression.eq(Expression.get("orden"), Expression.literal(1)), Expression.literal("bottom"), Expression.get("anclaLejos"))),
                    Expression.stop(15.6, Expression.literal("bottom")))),
                iconAllowOverlap(true), iconIgnorePlacement(true), symbolSortKey(Expression.get("orden")),
            ))
            // Tramo recorrido por cada bus en tráfico mixto, del color de su velocidad (debajo de los buses).
            s.addLayer(LineLayer("trafico", "trafico").withProperties(
                lineColor(Expression.get("color")), lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 3f), Expression.stop(17, 8f))),
                lineOpacity(0.85f), lineCap("round"), lineJoin("round"),
            ))
            // Buses vistos desde arriba, girados hacia donde van (alineados al mapa) y más grandes al acercarse.
            s.addLayer(SymbolLayer("buses", "buses").withProperties(
                iconImage(Expression.get("icono")), iconRotate(Expression.get("rumbo")), iconRotationAlignment("map"), iconPitchAlignment("map"),
                iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                    Expression.stop(11, Expression.product(Expression.literal(0.45f), Expression.get("escala"))),
                    Expression.stop(15, Expression.product(Expression.literal(0.85f), Expression.get("escala"))),
                    Expression.stop(18, Expression.product(Expression.literal(1.25f), Expression.get("escala"))))),
                iconAllowOverlap(true), iconIgnorePlacement(true), symbolSortKey(Expression.get("orden")),
            ))
            s.addLayer(SymbolLayer("buses-codigo", "buses").withProperties(
                // Las etiquetas que chocan se ocultan (con 80 buses cerca era una sopa de códigos); el bus resaltado
                // se coloca primero (clave más baja) y nunca pierde la suya.
                iconImage(Expression.get("etiqueta")), iconAnchor("top"), iconOffset(arrayOf(0f, 22f)), iconAllowOverlap(false), iconIgnorePlacement(false),
                iconPadding(2f), symbolSortKey(Expression.product(Expression.literal(-1), Expression.get("orden"))),
            ).apply { minZoom = 14f })
            s.addLayer(CircleLayer("incidentes-punto", "incidentes").withProperties(
                circleRadius(6.5f), circleColor(Expression.get("color")), circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(2f),
            ).withFilter(Expression.eq(Expression.get("punto"), true)))
            s.addLayer(CircleLayer("yo-halo", "yo").withProperties(circleRadius(14f), circleColor(0xFF4C8DFF.toInt()), circleOpacity(0.2f)))
            s.addLayer(CircleLayer("yo", "yo").withProperties(circleRadius(6.5f), circleColor(0xFF4C8DFF.toInt()), circleStrokeColor(Color.White.toArgb()), circleStrokeWidth(2.5f)))
            estilo = s
        }

    }

    LaunchedEffect(estilo, tramos) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("tramos")?.setGeoJson(FeatureCollection.fromFeatures(tramos.filter { it.puntos.size > 1 }.map { t ->
            Feature.fromGeometry(LineString.fromLngLats(t.puntos.map { Point.fromLngLat(it[0], it[1]) })).apply {
                addStringProperty("color", String.format("#%06X", 0xFFFFFF and t.color.toArgb())); addBooleanProperty("punteado", t.discontinuo)
            }
        }))
    }
    LaunchedEffect(estilo, puntos) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("puntos")?.setGeoJson(FeatureCollection.fromFeatures(puntos.map { p ->
            val k = if (p.estacion) "e" else "p"
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("id", p.id)
                addStringProperty("cerca", if (p.fuerte) "placa-$k-g" else "placa-$k")
                addStringProperty("lejos", if (p.fuerte) "placa-$k-g" else "punto-$k")
                addStringProperty("anclaLejos", if (p.fuerte) "bottom" else "center")
                addNumberProperty("orden", if (p.fuerte) 2 else if (p.estacion) 1 else 0)
            }
        }))
    }
    // Cada bus avanza por la calle de donde estaba a donde está (1,2 s), como los carros de Uber, en vez de saltar.
    // Si el servidor manda el tramo recorrido, el bus lo sigue (dobla las esquinas); si no, va en línea recta.
    val enPantalla = remember { HashMap<String, Pair<Double, Double>>() }
    val rumbos = remember { HashMap<String, Double>() }
    LaunchedEffect(estilo, buses, resaltado) {
        val s = estilo ?: return@LaunchedEffect
        val fuente = s.getSourceAs<GeoJsonSource>("buses") ?: return@LaunchedEffect
        class Mov(val b: Bus, val camino: List<Pair<Double, Double>>, val icono: String, val etiqueta: String, val rumbo: Double)
        val datos = buses.map { b ->
            val color = colorSistema(b.system).toArgb()
            val hex = String.format("%06X", 0xFFFFFF and color)
            val art = b.system == "troncal" || b.system == "dual"
            val icono = "bus-$hex-${if (art) "a" else "n"}"
            val codigo = b.code.orEmpty()
            if (s.getImage(icono) == null) s.addImage(icono, IconosMapa.busArriba(ctx, color, art))
            if (s.getImage("et-$codigo-$hex") == null) s.addImage("et-$codigo-$hex", IconosMapa.etiqueta(ctx, codigo, color))
            val destino = b.lat to b.lon
            val antes = enPantalla[b.id]?.takeIf { p -> metrosEntre(p, destino) in 2.0..900.0 }
            val tramo = b.trail?.filter { it.size >= 2 }?.map { it[1] to it[0] }.orEmpty()
            val camino = when {
                antes == null -> listOf(destino)
                // El tramo arranca cerca de donde lo vimos: se sigue la calle.
                tramo.size >= 2 && metrosEntre(tramo.first(), antes) < 120 -> listOf(antes) + tramo.drop(1).dropLast(1) + destino
                else -> listOf(antes, destino)
            }
            // Sin rumbo nuevo, el bus conserva el último (no se voltea al norte).
            val rumbo = b.bearing ?: rumbos[b.id] ?: 0.0
            rumbos[b.id] = rumbo
            Mov(b, camino, icono, "et-$codigo-$hex", rumbo)
        }
        fun enCamino(c: List<Pair<Double, Double>>, t: Float): Pair<Pair<Double, Double>, Double?> {
            if (c.size == 1) return c[0] to null
            val largos = c.zipWithNext { a, b -> metrosEntre(a, b) }
            var falta = largos.sum() * t
            for (k in largos.indices) {
                val l = largos[k]
                if (falta <= l || k == largos.lastIndex) {
                    val f = if (l > 0) (falta / l).coerceIn(0.0, 1.0) else 1.0
                    val a = c[k]; val b = c[k + 1]
                    val rumbo = if (l > 3) Math.toDegrees(Math.atan2((b.second - a.second) * Math.cos(Math.toRadians(a.first)), b.first - a.first)).let { (it + 360) % 360 } else null
                    return (a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f) to rumbo
                }
                falta -= l
            }
            return c.last() to null
        }
        fun pintar(t: Float) {
            fuente.setGeoJson(FeatureCollection.fromFeatures(datos.map { m ->
                val (p, rumboTramo) = enCamino(m.camino, t)
                enPantalla[m.b.id] = p
                // Mientras dobla una esquina, apunta hacia donde va en ese momento; al llegar, al rumbo de la calle.
                val rumbo = if (t < 1f && m.camino.size > 2 && rumboTramo != null) rumboTramo else m.rumbo
                Feature.fromGeometry(Point.fromLngLat(p.second, p.first)).apply {
                    addStringProperty("id", m.b.id); addStringProperty("icono", m.icono); addStringProperty("etiqueta", m.etiqueta)
                    addNumberProperty("rumbo", rumbo)
                    addNumberProperty("escala", if (m.b.id == resaltado) 1.35 else 1.0)
                    addNumberProperty("orden", if (m.b.id == resaltado) 2 else 1)
                }
            }))
        }
        // Con muchos buses en pantalla no se anima (cada cuadro reescribe todos): se ahorra batería.
        val mueve = !co.saerling.rutas.ui.Mov.reducido && datos.size <= 120 && datos.any { it.camino.size > 1 }
        if (mueve) {
            val t0 = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val f = ((androidx.compose.runtime.withFrameNanos { it } - t0) / 1.4e9).toFloat().coerceIn(0f, 1f)
                pintar(1f - (1f - f) * (1f - f))
                if (f >= 1f) break
                kotlinx.coroutines.delay(48)
            }
        } else pintar(1f)
        val vivos = buses.map { it.id }.toSet()
        enPantalla.keys.retainAll(vivos); rumbos.keys.retainAll(vivos)
    }
    LaunchedEffect(estilo, buses, trafico) {
        val s = estilo ?: return@LaunchedEffect
        val mixto = setOf("zonal_urbano", "zonal_complementario", "zonal_especial", "alimentador")
        s.getSourceAs<GeoJsonSource>("trafico")?.setGeoJson(FeatureCollection.fromFeatures(if (!trafico) emptyList() else buses.mapNotNull { b ->
            // Solo el tramo que siguió por la calle: una recta entre dos reportes cruza manzanas.
            val tramo = b.trail?.filter { it.size >= 2 } ?: return@mapNotNull null
            val k = b.kmh ?: return@mapNotNull null
            if (b.system !in mixto || tramo.size < 2) return@mapNotNull null
            Feature.fromGeometry(LineString.fromLngLats(tramo.map { Point.fromLngLat(it[0], it[1]) })).apply {
                addStringProperty("color", hexTrafico(nivelTrafico(k.toInt())))
            }
        }))
    }
    LaunchedEffect(estilo, flujo) {
        estilo?.getLayer("flujo")?.setProperties(visibility(if (flujo) "visible" else "none"))
    }
    LaunchedEffect(estilo, incidentes) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("incidentes")?.setGeoJson(FeatureCollection.fromFeatures(incidentes.flatMap { i ->
            val color = String.format("#%06X", 0xFFFFFF and colorIncidente(i.kind, i.magnitude).toArgb())
            val linea = i.line.filter { it.size >= 2 }
            listOfNotNull(
                if (linea.size >= 2) Feature.fromGeometry(LineString.fromLngLats(linea.map { Point.fromLngLat(it[0], it[1]) })).apply { addStringProperty("color", color); addBooleanProperty("punto", false) } else null,
                Feature.fromGeometry(Point.fromLngLat(i.lon, i.lat)).apply { addStringProperty("color", color); addBooleanProperty("punto", true) },
            )
        }))
    }
    LaunchedEffect(estilo, zonas) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("zonas")?.setGeoJson(FeatureCollection.fromFeatures(zonas.map { z ->
            Feature.fromGeometry(Point.fromLngLat(z.lon, z.lat)).apply { addStringProperty("color", hexTrafico(z.level)) }
        }))
    }
    LaunchedEffect(estilo, yo) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("yo")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(yo?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })))
    }
    // Se encuadra una vez por cada conjunto nuevo (otro paradero, otra ruta); moverse con el GPS no mueve la cámara.
    val claveEncuadre = encuadrar.map { "%.4f,%.4f".format(it.first, it.second) }.distinct().sorted().joinToString("|")
    LaunchedEffect(mapa, claveEncuadre) {
        val m = mapa ?: return@LaunchedEffect
        val pts = encuadrar.distinct()
        if (pts.size >= 2) {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.first, it.second)) } }.build()
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 90, 260, 90, bajoPx + 90), 650) }
        } else if (pts.size == 1) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].first, pts[0].second), zoom), 650)
    }
    LaunchedEffect(mapa, bajoPx) { mapa?.setPadding(0, 0, 0, bajoPx) }
    LaunchedEffect(mapa, seguir) {
        val m = mapa ?: return@LaunchedEffect
        val p = seguir ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.first, p.second), maxOf(m.cameraPosition.zoom, 15.5)), 900)
    }
    LaunchedEffect(pedido) {
        val m = mapa ?: return@LaunchedEffect
        val c = centrarEn ?: return@LaunchedEffect
        if (pedido > 0) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.first, c.second), 16.0), 600)
    }

    AndroidView({ vista }, modifier)
}

@Composable
private fun MapaFalso(modifier: Modifier, puntos: List<PuntoMapa>, tramos: List<TramoMapa>, buses: List<Bus>) {
    val fondo = if (TemaActual.oscuro) Color(0xFF17181A) else Color(0xFFECEEF1)
    val calle = if (TemaActual.oscuro) Color(0xFF26272A) else Color.White
    val ctx = LocalContext.current
    val oscuro = TemaActual.oscuro
    val iconos = remember(puntos, oscuro) { puntos.associate { it.id to IconosMapa.placa(ctx, it.estacion, it.fuerte, oscuro).asImageBitmap() } }
    val iconosBus = remember(buses) { buses.map { b -> val c = colorSistema(b.system).toArgb(); IconosMapa.busArriba(ctx, c, b.system == "troncal" || b.system == "dual").asImageBitmap() to IconosMapa.etiqueta(ctx, b.code.orEmpty(), c).asImageBitmap() } }
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(fondo)
        for (i in 0..12) {
            drawLine(calle, androidx.compose.ui.geometry.Offset(size.width * i / 12f, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 12f - 80f, size.height), 6f)
            drawLine(calle, androidx.compose.ui.geometry.Offset(0f, size.height * i / 12f), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 12f + 40f), 4f)
        }
        val todos = tramos.flatMap { it.puntos } + puntos.map { listOf(it.lon, it.lat) } + buses.map { listOf(it.lon, it.lat) }
        if (todos.isEmpty()) return@Canvas
        val minX = todos.minOf { it[0] }; val maxX = todos.maxOf { it[0] }; val minY = todos.minOf { it[1] }; val maxY = todos.maxOf { it[1] }
        fun pos(lon: Double, lat: Double) = androidx.compose.ui.geometry.Offset(
            (60 + (lon - minX) / (maxX - minX).coerceAtLeast(1e-6) * (size.width - 120)).toFloat(),
            (60 + (maxY - lat) / (maxY - minY).coerceAtLeast(1e-6) * (size.height - 120)).toFloat())
        tramos.forEach { t -> t.puntos.zipWithNext { a, b -> drawLine(t.color, pos(a[0], a[1]), pos(b[0], b[1]), 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round) } }
        puntos.forEach { p -> iconos[p.id]?.let { im -> val o = pos(p.lon, p.lat); drawImage(im, androidx.compose.ui.geometry.Offset(o.x - im.width / 2f, o.y - im.height)) } }
        buses.forEachIndexed { i, b ->
            val o = pos(b.lon, b.lat); val (c, e) = iconosBus[i]
            rotate((b.bearing ?: 0.0).toFloat(), o) { drawImage(c, androidx.compose.ui.geometry.Offset(o.x - c.width / 2f, o.y - c.height / 2f)) }
            drawImage(e, androidx.compose.ui.geometry.Offset(o.x - e.width / 2f, o.y + 22 * density))
        }
    }
}

/** Cómo va el tráfico según la velocidad de los buses en tráfico mixto (las paradas incluidas). */
fun nivelTrafico(kmh: Int) = when { kmh >= 15 -> "fluido"; kmh >= 8 -> "moderado"; else -> "lento" }
fun colorTrafico(nivel: String?): Color = when (nivel) { "fluido" -> Color(0xFF30B45A); "moderado" -> Color(0xFFF2A33D); "lento" -> Color(0xFFE5484D); else -> Color(0xFF8E8E93) }
private fun hexTrafico(nivel: String?) = String.format("#%06X", 0xFFFFFF and colorTrafico(nivel).toArgb())

/** Distancia en metros entre dos puntos (lat, lon), aproximada para distancias cortas. */
fun metrosEntre(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val dy = (a.first - b.first) * 111_320.0
    val dx = (a.second - b.second) * 111_320.0 * Math.cos(Math.toRadians(a.first))
    return Math.hypot(dx, dy)
}

/** Color de un incidente vial: cierres y accidentes en rojo oscuro, trancones según qué tan graves. */
fun colorIncidente(tipo: String, magnitud: Int): Color = when (tipo) {
    "cierre" -> Color(0xFF9B1C1C); "accidente" -> Color(0xFFD7262C); "obra" -> Color(0xFFF08A24)
    else -> when (magnitud) { 3, 4 -> Color(0xFFB4232A); 2 -> Color(0xFFE5484D); else -> Color(0xFFF2A33D) }
}
