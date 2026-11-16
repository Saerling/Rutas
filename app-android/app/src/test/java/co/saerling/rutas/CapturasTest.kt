package co.saerling.rutas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import co.saerling.rutas.data.Api
import co.saerling.rutas.data.Guardados
import co.saerling.rutas.data.PlanResult
import co.saerling.rutas.ui.*
import co.saerling.rutas.ui.rutas.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capturas de cada pantalla con datos reales del servidor (guardados en test/resources/datos). Salen en build/capturas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CapturasTest {
    @get:Rule val rule = createComposeRule()

    private fun dato(n: String) = javaClass.classLoader!!.getResource("datos/$n.json")!!.readText()

    private fun preparar() {
        val ctx = RuntimeEnvironment.getApplication()
        ModoPrueba.activo = true
        Guardados.iniciar(ctx)
        Aspecto.iniciar(ctx)
        co.saerling.rutas.ui.mochy.EstiloApp.iniciar(ctx)
        co.saerling.rutas.fx.Fx.init(ctx)
        Api.simulado = { ruta ->
            when {
                ruta.startsWith("/api/nearby") -> dato("nearby")
                ruta.startsWith("/api/search") -> dato("search")
                ruta.startsWith("/api/health") -> dato("health")
                ruta.startsWith("/api/stops") -> dato("stop")
                ruta.startsWith("/api/routes") -> dato("route")
                ruta.startsWith("/api/plan") -> dato("plan")
                else -> "[]"
            }
        }
    }

    private fun capturar(nombre: String, oscuro: Boolean = true, contenido: @Composable () -> Unit) {
        preparar()
        TemaActual.oscuro = oscuro; TemaActual.puro = true
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LectorTheme(acento = colorDePaleta(Paletas.porDefecto.nombre, oscuro), oscuro = oscuro, puro = true) {
                Box(Modifier.fillMaxSize().background(LectorColors.Bg)) { contenido() }
            }
        }
        // Los datos llegan en otro hilo: se espera y luego se dibuja un fotograma, así la captura ve lo último.
        repeat(24) { Thread.sleep(150); rule.mainClock.advanceTimeBy(250) }
        rule.onRoot().captureRoboImage("build/capturas/$nombre.png")
    }

    private val almacen get() = Almacen.obtener(RuntimeEnvironment.getApplication())

    @Test fun cerca() = capturar("01-cerca") { RutasApp(almacen) }
    @Test fun ir() {
        Planeador.hasta = co.saerling.rutas.data.Place("x", "Universidad Nacional", "Teusaquillo", "stop", 4.638, -74.084)
        Planeador.desde = co.saerling.rutas.data.Place("y", "Calle 57", "Chapinero", "stop", 4.649, -74.063)
        capturar("02-ir") { RutasApp(almacen, Pestana.Ir) }
    }
    @Test fun buscar() = capturar("03-buscar") { RutasApp(almacen, Pestana.Buscar) }
    @Test fun ajustes() = capturar("04-ajustes") { RutasApp(almacen, Pestana.Ajustes) }
    @Test fun paradero() = capturar("05-paradero") { RutasApp(almacen, capaInicial = Capa.Paradero("x")) }
    @Test fun ruta() = capturar("06-ruta") { RutasApp(almacen, capaInicial = Capa.Ruta("x")) }
    @Test fun viaje() {
        val plan = Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString<PlanResult>(dato("plan"))
        capturar("07-viaje") { RutasApp(almacen, capaInicial = Capa.Viaje(plan.itineraries.first())) }
    }
    @Test fun personalizacion() = capturar("08-personalizacion") { RutasApp(almacen, capaInicial = Capa.Personalizacion) }
    @Test fun tarjeta() = capturar("12-tarjeta") { RutasApp(almacen, capaInicial = Capa.Tarjeta) }
    @Test fun bibliotecaFx() = capturar("11-biblioteca-fx") { RutasApp(almacen, capaInicial = Capa.BibliotecaFx) }
    @Test fun cercaClaro() = capturar("09-cerca-claro", oscuro = false) { RutasApp(almacen) }
    @Test fun actualizacion() = capturar("10-actualizacion") {
        co.saerling.rutas.ui.mochy.HojaActualizacion(
            "Nueva versión disponible", "Rutas 0.2.7 (compilación 7) está lista para descargar. Tienes la 0.2.6.",
            "38,2 MB · se instala encima, sin perder nada", "Estética Mochy en toda la app\\nActualizador dentro de la app",
            co.saerling.rutas.ui.bitchord.EstadoDescarga.Descargando(0.42f),
            listOf(co.saerling.rutas.ui.bitchord.AccionAlerta("Descargando…", destacada = false, activa = false) {}), {},
        )
    }
}
