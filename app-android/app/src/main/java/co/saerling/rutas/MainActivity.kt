package co.saerling.rutas

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import co.saerling.rutas.data.Guardados
import co.saerling.rutas.data.Ubicacion
import co.saerling.rutas.ui.*
import co.saerling.rutas.ui.rutas.RutasApp

class MainActivity : ComponentActivity() {
    private lateinit var almacen: Almacen

    override fun onResume() {
        super.onResume()
        MovimientoReducido.actualizar()
        if (Ubicacion.tienePermiso(this)) Ubicacion.empezar(this)
    }

    override fun onPause() {
        super.onPause()
        // Con un viaje guiado, el servicio sigue la ubicación; si no, se apaga al salir de la app.
        Ubicacion.parar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        co.saerling.rutas.fx.Fx.init(this)
        co.saerling.rutas.ui.rutas.encenderFxIniciales(this)
        MovimientoReducido.actualizar()
        Tacto.iniciar(window.decorView)
        Aspecto.iniciar(applicationContext)
        co.saerling.rutas.ui.mochy.EstiloApp.iniciar(applicationContext)
        Guardados.iniciar(applicationContext)
        co.saerling.rutas.ui.rutas.UltimoSaldo.iniciar(applicationContext)
        co.saerling.rutas.data.GuiaServicio.crearCanales(applicationContext)
        co.saerling.rutas.data.Vigia.iniciar(applicationContext)
        co.saerling.rutas.data.MisRutas.iniciar(applicationContext)
        almacen = Almacen.obtener(applicationContext)
        aplicarBarras(oscuro = almacen.modoTema != ModoTema.CLARO)
        if (savedInstanceState == null) { NuevaVersion.alArrancar(applicationContext); co.saerling.rutas.ui.rutas.Atajo.pedir(intent) }
        MovimientoApp.iniciar(applicationContext)
        MovimientoReducido.actualizar()
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val recompositor = window.decorView.createLifecycleAwareWindowRecomposer(MovimientoApp.escala, lifecycle)
        setContent(parent = recompositor) { App(almacen) { aplicarBarras(it) } }
    }

    override fun onStart() { super.onStart(); co.saerling.rutas.data.Visible.en.value = true }
    override fun onStop() { co.saerling.rutas.data.Visible.en.value = false; super.onStop() }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        co.saerling.rutas.ui.rutas.Atajo.pedir(intent)
    }

    private fun aplicarBarras(oscuro: Boolean) {
        val estilo = if (oscuro) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = estilo, navigationBarStyle = estilo)
    }
}

@Composable
fun App(almacen: Almacen, alCambiarTema: (Boolean) -> Unit) {
    var arranque by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(ArranqueRevelado.repetir) { if (ArranqueRevelado.repetir > 0) { ArranqueRevelado.valor = 0f; arranque = true } }
    val sistemaOscuro = isSystemInDarkTheme()
    val oscuro = when (almacen.modoTema) { ModoTema.SISTEMA -> sistemaOscuro; ModoTema.CLARO -> false; else -> true }
    val puro = almacen.modoTema != ModoTema.OSCURO
    SideEffect { TemaActual.oscuro = oscuro; TemaActual.puro = puro }
    LaunchedEffect(oscuro) { alCambiarTema(oscuro) }
    LectorTheme(acento = colorDePaleta(almacen.paleta, oscuro), oscuro = oscuro, puro = puro) {
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        CompositionLocalProvider(
            androidx.compose.material3.LocalRippleConfiguration provides null,
            androidx.compose.foundation.LocalIndication provides SinIndicacion,
        ) {
            Box(Modifier.fillMaxSize().background(LectorColors.Bg)) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    if (arranque) { val e = 1.08f - 0.08f * ArranqueRevelado.valor; scaleX = e; scaleY = e }
                    val alejar = Hojas.presencia
                    if (alejar > 0f) {
                        val e = 1f - co.saerling.rutas.ui.mochy.sheet.BackdropRecede * alejar
                        scaleX = e; scaleY = e
                        shape = androidx.compose.foundation.shape.RoundedCornerShape((28f * alejar).dp)
                        clip = true
                    }
                }) { RutasApp(almacen) }
                co.saerling.rutas.ui.mochy.sheet.PopupSheetBackdrop()
                if (arranque) ArranqueOverlay { arranque = false }
                DialogoActualizacion()
            }
        }
    }
}

private val Float.dp get() = androidx.compose.ui.unit.Dp(this)

/** Indicación vacía: los clickable sin indicación propia no pintan nada (ni ripple ni velo). */
private object SinIndicacion : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : androidx.compose.ui.Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}
