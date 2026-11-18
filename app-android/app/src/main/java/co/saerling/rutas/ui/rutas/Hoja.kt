package co.saerling.rutas.ui.rutas

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import co.saerling.rutas.data.Ubicacion
import co.saerling.rutas.ui.*

/**
 * El patrón de Google Maps y Moovit: el mapa a pantalla completa (se mueve y se hace zoom con los dedos) y una hoja
 * que se arrastra encima. Arriba van atrás y las acciones; el botón de tu ubicación flota justo encima de la hoja.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapaConHoja(
    mapa: @Composable (abajo: Dp, pedido: Int) -> Unit,
    contenido: LazyListScope.() -> Unit,
    onBack: (() -> Unit)? = null,
    acciones: (@Composable RowScope.() -> Unit)? = null,
    fijo: (@Composable () -> Unit)? = null,
    sobreMapa: (@Composable BoxScope.() -> Unit)? = null,
    asomo: Float = 0.5f,
    finLista: Dp = 48.dp,
    /** Lo que se ve en la barrita de abajo cuando escondes la hoja para ver el mapa entero. */
    resumen: String? = null,
    /** Espacio libre abajo para la barrita (la barra de pestañas tapa el borde en Cerca). */
    margenAbajo: Dp = 0.dp,
) {
    val alto = LocalConfiguration.current.screenHeightDp.dp
    val asomoDp = alto * asomo
    val estado = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false))
    var pedido by remember { mutableIntStateOf(0) }
    val densidad = LocalDensity.current
    val alcance = rememberCoroutineScope()
    val hoja = estado.bottomSheetState
    val escondida = hoja.targetValue == SheetValue.Hidden
    BottomSheetScaffold(
        scaffoldState = estado,
        sheetPeekHeight = asomoDp,
        sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        sheetContainerColor = LectorColors.Bg,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            // Tocar la manija la baja un escalón: de llena a media, de media a escondida (mapa entero).
            Box(
                Modifier.fillMaxWidth().height(28.dp).pressable {
                    Tacto.tic()
                    alcance.launch { if (hoja.currentValue == SheetValue.Expanded) hoja.partialExpand() else hoja.hide() }
                },
                Alignment.Center,
            ) { Box(Modifier.size(38.dp, 5.dp).clip(SuaveShape(3.dp)).background(LectorColors.PillFill)) }
        },
        containerColor = LectorColors.Bg,
        sheetContent = {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.93f), contentPadding = PaddingValues(bottom = finLista), content = contenido)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val techoBoton = with(densidad) { (maxHeight - 150.dp - margenAbajo).toPx() }
            mapa(if (escondida) 96.dp else asomoDp, pedido)
            sobreMapa?.invoke(this)
            // Botón «mi ubicación»: sigue el borde de la hoja mientras la arrastras.
            if (Ubicacion.actual != null) {
                val y = runCatching { hoja.requireOffset() }.getOrDefault(with(densidad) { (alto - asomoDp).toPx() }).coerceAtMost(techoBoton + 64.dp.value * densidad.density)
                Box(
                    Modifier.offset { IntOffset(0, (y - 64.dp.toPx()).toInt()) }.align(Alignment.TopEnd).padding(end = 16.dp)
                        .size(48.dp).clip(SuaveShape(16.dp)).background(LectorColors.Glass).pressable { Tacto.tic(); pedido++ },
                    Alignment.Center,
                ) { Icon(Icons.Rounded.MyLocation, "Ir a mi ubicación", tint = LectorColors.Text) }
            }
            Column(Modifier.fillMaxWidth()) {
                if (onBack != null || acciones != null) Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) Box(Modifier.size(44.dp).clip(SuaveShape(15.dp)).background(LectorColors.Glass).pressable(onBack), Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", tint = LectorColors.Text)
                    }
                    Spacer(Modifier.weight(1f))
                    acciones?.invoke(this)
                }
                // Sin barra de arriba, la tarjeta fija (bus que tocaste, guía) baja de la barra de estado.
                if (fijo != null) Box(if (onBack == null && acciones == null) Modifier.statusBarsPadding().padding(top = 60.dp) else Modifier) { fijo() }
            }
            // Hoja escondida: una barrita de cristal abajo para volver a abrirla (tocándola o deslizándola hacia arriba).
            AnimatedVisibility(
                escondida, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                Row(
                    Modifier.navigationBarsPadding().padding(bottom = margenAbajo).padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
                        .clip(SuaveShape(22.dp)).background(LectorColors.Glass).border(1.dp, LectorColors.GlassBorder, SuaveShape(22.dp))
                        .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -8f) alcance.launch { hoja.partialExpand() } } }
                        .pressable { Tacto.tic(); alcance.launch { hoja.partialExpand() } }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = LectorColors.Text)
                    Spacer(Modifier.width(10.dp))
                    Text(resumen ?: "Mostrar detalles", style = LectorType.Section, color = LectorColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("Mostrar", style = LectorType.Label, color = LectorColors.Muted)
                }
            }
        }
    }
}
