package co.saerling.rutas.ui.rutas

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import co.saerling.rutas.Almacen
import co.saerling.rutas.ui.*
import co.saerling.rutas.ui.mochy.AppNavigationBar
import co.saerling.rutas.ui.mochy.NavItem

/** Las cinco pestañas. Ajustes queda en la esquina, como en Shiori. */
enum class Pestana(val label: String, val activo: ImageVector, val apagado: ImageVector) {
    Cerca("Cerca", Icons.Rounded.NearMe, Icons.Outlined.NearMe),
    Ir("Ir", Icons.Rounded.Directions, Icons.Outlined.Directions),
    Buscar("Buscar", Icons.Rounded.Search, Icons.Outlined.Search),
    Guardados("Guardados", Icons.Rounded.Star, Icons.Outlined.StarOutline),
    Ajustes("Ajustes", Icons.Rounded.Settings, Icons.Outlined.Settings),
}

/**
 * El armazón de Shiori/Mochy: pestañas que se deslizan un octavo de pantalla con fundido, la de debajo retrocede
 * (se encoge y se apaga) cuando se abre una página encima, y la barra flotante de cristal sobre todo.
 */
@Composable
fun RutasApp(almacen: Almacen, inicial: Pestana = Pestana.Cerca, capaInicial: Capa? = null) {
    var pestana by rememberSaveable { mutableStateOf(inicial) }
    val pila = remember { mutableStateListOf<Capa>().apply { capaInicial?.let { add(it) } } }
    val abrir: (Capa) -> Unit = { c -> if (c == Capa.IrA) { pila.clear(); pestana = Pestana.Ir } else pila.add(c) }
    val volver: () -> Unit = { if (pila.isNotEmpty()) pila.removeAt(pila.lastIndex) }
    BackHandler(pila.isNotEmpty()) { volver() }
    BackHandler(pila.isEmpty() && pestana != Pestana.Cerca) { pestana = Pestana.Cerca }

    val abierta = pila.isNotEmpty()
    val retroceso = remember { Animatable(0f) }
    LaunchedEffect(abierta) {
        if (abierta) retroceso.animateTo(1f, spring(dampingRatio = 0.88f, stiffness = 150f, visibilityThreshold = 0.001f))
        else retroceso.animateTo(0f, tween(ContenedorCierreMs, easing = CierreEasing))
    }
    val estados = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize().background(LectorColors.Bg)) {
        Box(Modifier.fillMaxSize().raizCristal(CristalApp)) {
            FondoVivo(Aspecto.fondo, Modifier.fillMaxSize(), acento = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            AnimatedContent(
                targetState = pestana, label = "pestanas",
                modifier = Modifier.graphicsLayer {
                    val r = retroceso.value
                    if (r > 0f) { val e = 1f - 0.08f * r; scaleX = e; scaleY = e; alpha = 1f - 0.55f * r }
                },
                transitionSpec = {
                    val derecha = targetState.ordinal >= initialState.ordinal
                    if (Mov.reducido) ContentTransform(fadeIn(tween(120)), fadeOut(tween(100))) else ContentTransform(
                        slideInHorizontally { if (derecha) it / 8 else -it / 8 } + fadeIn(tween(200)),
                        slideOutHorizontally { if (derecha) -it / 8 else it / 8 } + fadeOut(tween(200)),
                    )
                },
            ) { p ->
                estados.SaveableStateProvider(p.name) {
                    when (p) {
                        Pestana.Cerca -> CercaScreen(abrir)
                        Pestana.Ir -> IrScreen(abrir)
                        Pestana.Buscar -> BuscarScreen(abrir)
                        Pestana.Guardados -> GuardadosScreen(abrir)
                        Pestana.Ajustes -> AjustesScreen(abrir)
                    }
                }
            }
        }

        // Páginas encima: crecen desde donde tocaste y, al volver, se encogen hacia arriba (las rutas «de portada» de Mochy).
        val capa = pila.lastOrNull()
        AnimatedContent(
            targetState = capa, label = "capas",
            transitionSpec = {
                val vuelta = targetState == null || (initialState != null && pila.size < 2 && targetState != initialState && initialState !in pila)
                if (Mov.reducido) ContentTransform(fadeIn(tween(120)), fadeOut(tween(100)))
                else if (!vuelta) ContentTransform(
                    fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 260f), initialScale = 0.9f, transformOrigin = OrigenToque.origen()),
                    fadeOut(tween(180)),
                ) else ContentTransform(
                    fadeIn(tween(220)),
                    scaleOut(tween(260), targetScale = 0.86f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.22f)) + fadeOut(tween(180)),
                )
            },
        ) { c ->
            Box(Modifier.fillMaxSize().gestoAtras(c != null && c == capa)) {
                when (c) {
                    null -> Unit
                    is Capa.Paradero -> ParaderoScreen(c.id, abrir, volver)
                    is Capa.Ruta -> RutaScreen(c.id, abrir, volver)
                    is Capa.Viaje -> ViajeScreen(c.it, volver)
                    Capa.Personalizacion -> PersonalizacionScreen(almacen, volver)
                    Capa.IrA -> Unit
                }
            }
        }

        AnimatedVisibility(
            visible = !abierta,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260, easing = EnfasisEasing)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            val items = remember { Pestana.entries.map { NavItem(it.label, it.activo, it.apagado, 24.dp) } }
            AppNavigationBar(
                navigationItems = items,
                selectedIndex = pestana.ordinal,
                onItemClick = { i, _ -> Tacto.tic(); pestana = Pestana.entries[i] },
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 8.dp).widthIn(max = 520.dp),
            )
        }
        AvisosHost(Modifier.align(Alignment.TopCenter))
    }
}
