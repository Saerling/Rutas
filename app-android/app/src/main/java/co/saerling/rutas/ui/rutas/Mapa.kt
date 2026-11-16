package co.saerling.rutas.ui.rutas

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
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

data class PuntoMapa(val id: String, val lat: Double, val lon: Double, val fuerte: Boolean = false)
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
) {
    if (ModoPrueba.activo) { MapaFalso(modifier, puntos, tramos); return }
    val ctx = LocalContext.current
    remember { iniciarMapLibre(ctx) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    var estilo by remember { mutableStateOf<Style?>(null) }
    val url = EstilosMapa.actual()
    val texto = if (TemaActual.oscuro) Color.White else Color(0xFF111114)
    val vista = remember {
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.apply { isCompassEnabled = false; isRotateGesturesEnabled = false; isTiltGesturesEnabled = false; isAttributionEnabled = false; isLogoEnabled = false }
                m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(centro.first, centro.second)).zoom(zoom).build()
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
            s.addLayer(CircleLayer("puntos", "puntos").withProperties(
                circleRadius(Expression.switchCase(Expression.get("fuerte"), Expression.literal(7f), Expression.literal(5f))),
                circleColor(if (TemaActual.oscuro) Color(0xFF141416).toArgb() else Color.White.toArgb()),
                circleStrokeColor(texto.toArgb()), circleStrokeWidth(2.5f),
            ))
            s.addLayer(CircleLayer("buses-halo", "buses").withProperties(circleRadius(13f), circleColor(Expression.get("color")), circleOpacity(0.22f)))
            s.addLayer(CircleLayer("buses", "buses").withProperties(circleRadius(7f), circleColor(Expression.get("color")), circleStrokeColor(Color.White.toArgb()), circleStrokeWidth(2f)))
            s.addLayer(SymbolLayer("buses-codigo", "buses").withProperties(textField(Expression.get("codigo")), textSize(11f), textOffset(arrayOf(0f, 1.6f)), textColor(texto.toArgb()), textHaloColor(if (TemaActual.oscuro) Color.Black.toArgb() else Color.White.toArgb()), textHaloWidth(1.5f), textAllowOverlap(true)))
            s.addLayer(CircleLayer("yo-halo", "yo").withProperties(circleRadius(14f), circleColor(0xFF4C8DFF.toInt()), circleOpacity(0.2f)))
            s.addLayer(CircleLayer("yo", "yo").withProperties(circleRadius(6.5f), circleColor(0xFF4C8DFF.toInt()), circleStrokeColor(Color.White.toArgb()), circleStrokeWidth(2.5f)))
            estilo = s
        }
        m.addOnMapClickListener { ll ->
            val toque = m.projection.toScreenLocation(ll)
            val f = m.queryRenderedFeatures(toque, "puntos").firstOrNull()
            val id = f?.getStringProperty("id")
            if (id != null && alTocarPunto != null) { alTocarPunto(id); true } else false
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
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("id", p.id); addBooleanProperty("fuerte", p.fuerte) }
        }))
    }
    LaunchedEffect(estilo, buses) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("buses")?.setGeoJson(FeatureCollection.fromFeatures(buses.map { b ->
            Feature.fromGeometry(Point.fromLngLat(b.lon, b.lat)).apply {
                addStringProperty("color", String.format("#%06X", 0xFFFFFF and colorSistema(b.system).toArgb())); addStringProperty("codigo", b.code.orEmpty())
            }
        }))
    }
    LaunchedEffect(estilo, yo) {
        val s = estilo ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>("yo")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(yo?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })))
    }
    LaunchedEffect(mapa, encuadrar) {
        val m = mapa ?: return@LaunchedEffect
        val pts = encuadrar.distinct()
        if (pts.size >= 2) {
            val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.first, it.second)) } }.build()
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 60, 140, 60, 120), 650) }
        } else if (pts.size == 1) m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].first, pts[0].second), zoom), 650)
    }

    AndroidView({ vista }, modifier)
}

@Composable
private fun MapaFalso(modifier: Modifier, puntos: List<PuntoMapa>, tramos: List<TramoMapa>) {
    val fondo = if (TemaActual.oscuro) Color(0xFF17181A) else Color(0xFFECEEF1)
    val calle = if (TemaActual.oscuro) Color(0xFF26272A) else Color.White
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(fondo)
        for (i in 0..12) {
            drawLine(calle, androidx.compose.ui.geometry.Offset(size.width * i / 12f, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 12f - 80f, size.height), 6f)
            drawLine(calle, androidx.compose.ui.geometry.Offset(0f, size.height * i / 12f), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 12f + 40f), 4f)
        }
        val todos = tramos.flatMap { it.puntos } + puntos.map { listOf(it.lon, it.lat) }
        if (todos.isEmpty()) return@Canvas
        val minX = todos.minOf { it[0] }; val maxX = todos.maxOf { it[0] }; val minY = todos.minOf { it[1] }; val maxY = todos.maxOf { it[1] }
        fun pos(lon: Double, lat: Double) = androidx.compose.ui.geometry.Offset(
            (60 + (lon - minX) / (maxX - minX).coerceAtLeast(1e-6) * (size.width - 120)).toFloat(),
            (60 + (maxY - lat) / (maxY - minY).coerceAtLeast(1e-6) * (size.height - 120)).toFloat())
        tramos.forEach { t -> t.puntos.zipWithNext { a, b -> drawLine(t.color, pos(a[0], a[1]), pos(b[0], b[1]), 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round) } }
        puntos.forEach { p -> drawCircle(Color.White, 14f, pos(p.lon, p.lat)); drawCircle(Color(0xFF111114), 14f, pos(p.lon, p.lat), style = androidx.compose.ui.graphics.drawscope.Stroke(5f)) }
    }
}
