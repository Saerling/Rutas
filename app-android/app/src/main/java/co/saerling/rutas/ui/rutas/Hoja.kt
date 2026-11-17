package co.saerling.rutas.ui.rutas

import androidx.compose.foundation.background
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
) {
    val alto = LocalConfiguration.current.screenHeightDp.dp
    val asomoDp = alto * asomo
    val estado = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded))
    var pedido by remember { mutableIntStateOf(0) }
    val densidad = LocalDensity.current
    BottomSheetScaffold(
        scaffoldState = estado,
        sheetPeekHeight = asomoDp,
        sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        sheetContainerColor = LectorColors.Bg,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(38.dp, 5.dp).clip(SuaveShape(3.dp)).background(LectorColors.PillFill))
        },
        containerColor = LectorColors.Bg,
        sheetContent = {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.93f), contentPadding = PaddingValues(bottom = finLista), content = contenido)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            mapa(asomoDp, pedido)
            sobreMapa?.invoke(this)
            // Botón «mi ubicación»: sigue el borde de la hoja mientras la arrastras.
            if (Ubicacion.actual != null) {
                val y = runCatching { estado.bottomSheetState.requireOffset() }.getOrDefault(with(densidad) { (alto - asomoDp).toPx() })
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
        }
    }
}
