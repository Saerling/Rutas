package co.saerling.rutas.ui.rutas

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessible
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.saerling.rutas.data.Arrival
import co.saerling.rutas.data.Stop
import co.saerling.rutas.ui.*
import co.saerling.rutas.ui.mochy.revealOnAppear

/** Margen lateral de las páginas, el de Mochy. */
val Margen = 20.dp
/** Espacio libre al final de cada lista para que la barra flotante no tape la última fila. */
val FinLista = 132.dp

/**
 * Página de pestaña como las de Mochy: título grande que se encoge al hacer scroll, contenido en una lista perezosa
 * y cada fila entra con su revelado (fundido y subida) la primera vez que aparece.
 */
@Composable
fun PaginaPestana(
    titulo: String,
    subtitulo: String? = null,
    estado: LazyListState = rememberLazyListState(),
    arriba: (@Composable () -> Unit)? = null,
    contenido: LazyListScope.() -> Unit,
) {
    val desplazado = estado.firstVisibleItemIndex > 0 || estado.firstVisibleItemScrollOffset > 40
    val escala by animateFloatAsState(if (desplazado) 0.82f else 1f, tween(260, easing = EnfasisEasing), label = "titulo")
    LazyColumn(
        state = estado,
        modifier = Modifier.fillMaxSize().background(LectorColors.Bg),
        contentPadding = PaddingValues(bottom = FinLista),
    ) {
        item("titulo") {
            Column(Modifier.statusBarsPadding().padding(horizontal = Margen).padding(top = 18.dp, bottom = 14.dp)) {
                Text(
                    titulo, style = LectorType.Display, color = LectorColors.Text,
                    modifier = Modifier.graphicsLayer { scaleX = escala; scaleY = escala; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                )
                if (subtitulo != null) Text(subtitulo, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (arriba != null) item("arriba") { arriba() }
        contenido()
    }
}

/** Una tarjeta de Mochy: superelipse, color de campo, se hunde y rebota al tocarla. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    radio: Dp = 24.dp,
    color: Color = LectorColors.Card,
    onClick: (() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    val forma = SuaveShape(radio)
    Column(
        modifier.fillMaxWidth().clip(forma).background(color)
            .then(if (onClick != null) Modifier.pressable { Tacto.tic(); onClick() } else Modifier)
            .padding(16.dp),
        content = contenido,
    )
}

/** Título de sección (como los de Mochy: 13 sp, gris, en mayúsculas pequeñas). */
@Composable
fun Seccion(texto: String, modifier: Modifier = Modifier) {
    Text(texto.uppercase(), style = LectorType.Label.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold), color = LectorColors.Muted,
        modifier = modifier.padding(horizontal = Margen).padding(top = 22.dp, bottom = 10.dp))
}

/** El código de una ruta en su color: rojo troncal, azul zonal, verde alimentador… */
@Composable
fun Insignia(codigo: String, sistema: String?, grande: Boolean = false) {
    val color = colorSistema(sistema)
    Box(
        Modifier.clip(SuaveShape(if (grande) 10.dp else 8.dp)).background(color)
            .padding(horizontal = if (grande) 10.dp else 7.dp, vertical = if (grande) 5.dp else 3.dp),
    ) {
        Text(codigo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (grande) 17.sp else 13.sp, maxLines = 1)
    }
}

/** Minutos que faltan, grandes y con el punto vivo si el dato es en vivo. */
@Composable
fun Minutos(a: Arrival, grande: Boolean = false) {
    val vivo = a.basis != "scheduled"
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (vivo) PuntoVivo()
        Spacer(Modifier.width(6.dp))
        AnimatedContent(a.minutes, transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(160)))
        }, label = "min") { m ->
            Text(
                when { m == null -> "—"; m <= 0 -> "Ya"; else -> "$m min" },
                style = if (grande) LectorType.Hero else LectorType.Section, color = LectorColors.Text, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** El punto verde que respira cuando el dato es en vivo. */
@Composable
fun PuntoVivo(tam: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "vivo")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(Modifier.size(tam).graphicsLayer { alpha = if (Mov.reducido) 1f else a }.clip(CircleShape).background(Color(0xFF34C759)))
}

/** Fila de paradero o estación: icono, nombre, distancia y las rutas que pasan. */
@Composable
fun FilaParadero(s: Stop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().resaltable { Tacto.tic(); onClick() }.padding(horizontal = Margen, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(SuaveShape(14.dp)).background(LectorColors.CardHigh), Alignment.Center) {
            Icon(if (s.kind == "station") Icons.Rounded.Train else Icons.Rounded.DirectionsBus, null, tint = LectorColors.Text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = LectorType.Section, color = LectorColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(if (s.kind == "station") "Estación" else "Paradero SITP", s.distanceM?.let { distancia(it) }).joinToString(" · "),
                    style = LectorType.Label, color = LectorColors.Muted,
                )
                if (s.accessible) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Accessible, "Accesible", tint = LectorColors.Muted, modifier = Modifier.size(14.dp)) }
            }
            if (s.routes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.routes.take(5).forEach { Insignia(it.code, it.system) }
                    if (s.routes.size > 5) Text("+${s.routes.size - 5}", style = LectorType.Label, color = LectorColors.Muted, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

fun distancia(m: Double): String = if (m < 1000) "${m.toInt()} m · ${(m / 80).toInt().coerceAtLeast(1)} min a pie" else "%.1f km".format(m / 1000)

fun hora(iso: String?): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.of("America/Bogota")).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }
}.getOrDefault("")

fun duracion(min: Int) = if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
fun pesos(cop: Int) = "$" + "%,d".format(cop).replace(',', '.')

/** Estado vacío o de error, con una acción opcional. */
@Composable
fun Vacio(titulo: String, cuerpo: String? = null, accion: String? = null, alAccion: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Margen, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(titulo, style = LectorType.Section, color = LectorColors.Text)
        if (cuerpo != null) Text(cuerpo, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (accion != null && alAccion != null) { Spacer(Modifier.height(16.dp)); BotonExpresivo(accion, null, alAccion) }
    }
}

/** Esqueleto de carga de Mochy (los «huesos» que brillan) con forma de filas. */
@Composable
fun CargandoFilas(filas: Int = 5) {
    BonesHost(Modifier.padding(horizontal = Margen)) {
        repeat(filas) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Bone(44.dp, 44.dp, forma = SuaveShape(14.dp))
                Spacer(Modifier.width(14.dp))
                Column { Bone(180.dp, 16.dp); Spacer(Modifier.height(8.dp)); Bone(110.dp, 12.dp) }
            }
        }
    }
}

/** Revelado de Mochy para cada fila de una lista (solo la primera vez que se ve). */
@Composable
fun Modifier.revelar(clave: String, retraso: Int = 0): Modifier = this.revealOnAppear(clave, Vistos, enabled = !Mov.reducido, delayMillis = retraso)

/** Filas ya reveladas en esta sesión: al volver con scroll no se animan otra vez. */
private val Vistos = co.saerling.rutas.ui.mochy.RevealSeenState()
