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
    val bajoPx = with(LocalDensity.current) { abajo.toPx().toInt() }
    val vista = remember {
        // TextureView: respeta las esquinas redondeadas y las animaciones de Mochy (un SurfaceView las ignora).
        MapView(ctx, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).apply {
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
                iconImage(Expression.step(Expression.zoom(), Expression.get("lejos"), Expression.stop(14.6, Expression.get("cerca")))),
                iconAnchor(Expression.step(Expression.zoom(), Expression.get("anclaLejos"), Expression.stop(14.6, Expression.literal("bottom")))),
                iconAllowOverlap(true), iconIgnorePlacement(true), symbolSortKey(Expression.get("orden")),
            ))
            s.addLayer(SymbolLayer("buses-flecha", "buses").withProperties(
                iconImage(Expression.get("flecha")), iconRotate(Expression.get("rumbo")), iconRotationAlignment("map"), iconAllowOverlap(true), iconIgnorePlacement(true),
            ).withFilter(Expression.has("rumbo")))
            s.addLayer(SymbolLayer("buses", "buses").withProperties(iconImage(Expression.get("icono")), iconAllowOverlap(true), iconIgnorePlacement(true)))
            s.addLayer(SymbolLayer("buses-codigo", "buses").withProperties(
                iconImage(Expression.get("etiqueta")), iconAnchor("top"), iconOffset(arrayOf(0f, 14f)), iconAllowOverlap(true), iconIgnorePlacement(true),
            ))
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
    LaunchedEffect(estilo, buses) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("buses")?.setGeoJson(FeatureCollection.fromFeatures(buses.map { b ->
            val color = colorSistema(b.system).toArgb()
            val hex = String.format("%06X", 0xFFFFFF and color)
            val codigo = b.code.orEmpty()
            if (s.getImage("bus-$hex") == null) { s.addImage("bus-$hex", IconosMapa.busCirculo(ctx, color)); s.addImage("flecha-$hex", IconosMapa.flecha(ctx, color)) }
            if (s.getImage("et-$codigo-$hex") == null) s.addImage("et-$codigo-$hex", IconosMapa.etiqueta(ctx, codigo, color))
            Feature.fromGeometry(Point.fromLngLat(b.lon, b.lat)).apply {
                addStringProperty("id", b.id); addStringProperty("icono", "bus-$hex"); addStringProperty("flecha", "flecha-$hex"); addStringProperty("etiqueta", "et-$codigo-$hex")
                b.bearing?.let { addNumberProperty("rumbo", it) }
            }
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
    val iconosBus = remember(buses) { buses.map { b -> val c = colorSistema(b.system).toArgb(); Triple(IconosMapa.busCirculo(ctx, c).asImageBitmap(), IconosMapa.etiqueta(ctx, b.code.orEmpty(), c).asImageBitmap(), IconosMapa.flecha(ctx, c).asImageBitmap()) } }
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
            val o = pos(b.lon, b.lat); val (c, e, f) = iconosBus[i]
            b.bearing?.let { r -> rotate(r.toFloat(), o) { drawImage(f, androidx.compose.ui.geometry.Offset(o.x - f.width / 2f, o.y - f.height / 2f)) } }
            drawImage(c, androidx.compose.ui.geometry.Offset(o.x - c.width / 2f, o.y - c.height / 2f))
            drawImage(e, androidx.compose.ui.geometry.Offset(o.x - e.width / 2f, o.y + 14 * density))
        }
    }
}
