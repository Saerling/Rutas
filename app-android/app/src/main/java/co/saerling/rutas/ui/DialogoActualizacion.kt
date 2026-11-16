package co.saerling.rutas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.saerling.rutas.NuevaVersion
import co.saerling.rutas.NuevaVersion.Paso
import co.saerling.rutas.ui.bitchord.AccionAlerta
import co.saerling.rutas.ui.bitchord.EstadoDescarga
import co.saerling.rutas.ui.bitchord.UpdateAvailableDialog

/** Aviso de versión nueva de la app (hoja de Mochy o ventana de BitChord, según Personalización): novedades, descarga con su avance e instalación encima de la actual. */
@Composable
fun DialogoActualizacion() {
    val v = NuevaVersion.disponible
    if (!NuevaVersion.verDialogo || v == null) return
    val ctx = LocalContext.current
    val paso = NuevaVersion.paso
    val cerrar = { if (NuevaVersion.paso != Paso.DESCARGANDO) NuevaVersion.cerrar() }
    val actualizar = { NuevaVersion.descargarEInstalar(ctx) }
    // Personalización → Estilo → Ventana de actualización: la hoja de Mochy (por defecto) o la alerta de BitChord.
    val titulo = "Nueva versión disponible"
    val cuerpo = when (paso) {
        Paso.DESCARGANDO -> "Descargando Rutas ${v.nombre}… ${NuevaVersion.porcentaje} %"
        Paso.INSTALANDO -> "Confirma la instalación en la ventana de Android. La app se cerrará y se abrirá ya actualizada."
        Paso.ERROR -> "No se pudo instalar Rutas ${v.nombre}."
        Paso.LISTO -> "Rutas ${v.nombre} (compilación ${v.codigo}) está lista para descargar. Tienes la ${NuevaVersion.nombreActual(ctx)}."
    }
    val detalle = if (v.tamano > 0) "${"%.1f".format(v.tamano / 1048576f)} MB · se instala encima, sin perder nada" else null
    val estado = when (paso) {
        Paso.DESCARGANDO -> EstadoDescarga.Descargando(NuevaVersion.porcentaje / 100f)
        Paso.INSTALANDO -> EstadoDescarga.Instalando
        Paso.ERROR -> EstadoDescarga.Fallo(NuevaVersion.error ?: "No se pudo actualizar")
        Paso.LISTO -> EstadoDescarga.Disponible
    }
    val acciones = when (paso) {
        Paso.DESCARGANDO -> listOf(AccionAlerta("Descargando…", destacada = false, activa = false) {})
        Paso.LISTO -> listOf(AccionAlerta("Actualizar", true, alPulsar = actualizar), AccionAlerta("Más tarde", false, alPulsar = cerrar))
        Paso.INSTALANDO, Paso.ERROR ->
            listOf(AccionAlerta("Reintentar", true, alPulsar = actualizar), AccionAlerta("Más tarde", false, alPulsar = cerrar))
    }
    if (co.saerling.rutas.ui.mochy.EstiloApp.ventanaActualizacion == co.saerling.rutas.ui.mochy.EstiloApp.VentanaActualizacion.MOCHY) {
        co.saerling.rutas.ui.mochy.HojaActualizacion(titulo, cuerpo, detalle, v.notas, estado, acciones, cerrar)
        return
    }
    Dialog(
        onDismissRequest = cerrar,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        UpdateAvailableDialog(titulo, cuerpo, detalle, v.notas, estado, acciones, onDismiss = cerrar)
    }
}
