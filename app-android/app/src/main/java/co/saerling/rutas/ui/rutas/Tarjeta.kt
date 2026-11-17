package co.saerling.rutas.ui.rutas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.saerling.rutas.data.PASAJE_COP
import co.saerling.rutas.data.TuLlave
import co.saerling.rutas.ui.*

/** El último saldo leído, para verlo en Cerca sin acercar la tarjeta. Solo en el teléfono; el número no se guarda. */
object UltimoSaldo {
    var saldo by mutableStateOf<Int?>(null); private set
    var cuando by mutableLongStateOf(0L); private set
    fun iniciar(c: Context) {
        val p = c.getSharedPreferences("tullave", Context.MODE_PRIVATE)
        saldo = p.getInt("saldo", -1).takeIf { it >= 0 }; cuando = p.getLong("cuando", 0)
    }
    fun guardar(c: Context, valor: Int) {
        saldo = valor; cuando = System.currentTimeMillis()
        c.getSharedPreferences("tullave", Context.MODE_PRIVATE).edit().putInt("saldo", valor).putLong("cuando", cuando).apply()
    }
    fun hace(): String {
        val m = ((System.currentTimeMillis() - cuando) / 60_000).toInt()
        return when { m < 1 -> "ahora"; m < 60 -> "hace $m min"; m < 1440 -> "hace ${m / 60} h"; else -> "hace ${m / 1440} d" }
    }
}

/** Solo para las capturas de prueba: una tarjeta ya leída. */
object TarjetaPrueba { var dato: TuLlave.Tarjeta? = null }

/**
 * TuLlave, como en Paradero: se lee sola al abrir, muestra el saldo grande con la hora de lectura, si quedó debiendo,
 * el número y los movimientos por mes. Además: cuántos pasajes te alcanzan, el resumen del mes y la tarjeta dibujada.
 */
@Composable
fun TarjetaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val actividad = ctx as? Activity
    var intento by remember { mutableIntStateOf(0) }
    var estado by remember { mutableStateOf<TuLlave.Lectura?>(null) }
    var leidaA by remember { mutableStateOf<String?>(null) }
    var sinTarjeta by remember { mutableStateOf(false) }
    val conNfc = actividad != null && TuLlave.tieneNfc(actividad)
    // El NFC se puede encender desde los ajustes rápidos sin salir: se revisa cada segundo.
    var activo by remember { mutableStateOf(actividad != null && TuLlave.disponible(actividad)) }
    LaunchedEffect(Unit) { while (true) { activo = actividad != null && TuLlave.disponible(actividad); kotlinx.coroutines.delay(1000) } }
    LaunchedEffect(intento, activo) {
        if (actividad == null || !activo) return@LaunchedEffect
        estado = null; sinTarjeta = false
        val r = kotlinx.coroutines.withTimeoutOrNull(60_000) { TuLlave.leer(actividad) }
        if (r == null) { sinTarjeta = true; return@LaunchedEffect }
        estado = r
        when (r) {
            is TuLlave.Lectura.Ok -> {
                Tacto.alternar(true); UltimoSaldo.guardar(ctx, r.t.saldo)
                leidaA = java.time.LocalTime.now().let { "%d:%02d".format(it.hour, it.minute) }
            }
            else -> Tacto.rechazar()
        }
    }
    TarjetaPrueba.dato?.let { d -> LaunchedEffect(d) { estado = TuLlave.Lectura.Ok(d); leidaA = "7:45" } }
    // Por tipo, nunca por el nombre de la clase: en el APK final R8 renombra las clases y el nombre deja de ser «Ok».
    val clave = when {
        estado is TuLlave.Lectura.Ok -> "Ok"
        !conNfc -> "sin"; !activo -> "apagado"; sinTarjeta -> "Tiempo"
        estado is TuLlave.Lectura.NoEsTuLlave -> "NoEsTuLlave"
        estado is TuLlave.Lectura.SeSolto -> "SeSolto"
        else -> "esperando"
    }

    LazyColumn(Modifier.fillMaxSize().background(LectorColors.Bg), contentPadding = PaddingValues(bottom = 48.dp)) {
        item("barra") {
            Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(SuaveShape(15.dp)).background(LectorColors.Card).pressable(onBack), Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", tint = LectorColors.Text)
                }
            }
        }
        item("titulo") {
            Text("Tu TuLlave", style = LectorType.Display, color = LectorColors.Text, modifier = Modifier.padding(horizontal = Margen).padding(top = 4.dp, bottom = 18.dp))
        }
        item("tarjeta") {
            AnimatedContent(
                targetState = clave,
                transitionSpec = { (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.94f)) togetherWith fadeOut(tween(160)) },
                label = "tullave",
            ) { k ->
                Column(Modifier.padding(horizontal = Margen)) {
                    when (k) {
                        "sin" -> Fallo("Este teléfono no lee tarjetas", "Necesitas un teléfono con NFC.", null, null)
                        "apagado" -> Tarjeta(radio = 28.dp) {
                            Icon(Icons.Rounded.Nfc, null, tint = LectorColors.Text, modifier = Modifier.size(30.dp))
                            Text("Activa el NFC", style = LectorType.Hero, color = LectorColors.Text, modifier = Modifier.padding(top = 10.dp))
                            Text("Está en los ajustes rápidos del celular. Apenas lo enciendas, empezamos a leer.", style = LectorType.Body, color = LectorColors.Muted)
                            Spacer(Modifier.height(16.dp))
                            BotonExpresivo("Abrir ajustes de NFC", Icons.Rounded.Settings, { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, estilo = EstiloBoton.RELLENO)
                        }
                        "Ok" -> (estado as? TuLlave.Lectura.Ok)?.let { Resultado(it.t, leidaA) { intento++ } }
                        "NoEsTuLlave" -> Fallo("No es una TuLlave", "Esa tarjeta no respondió como una TuLlave. Prueba con tu tarjeta de TransMilenio.", "Leer otra vez") { intento++ }
                        "SeSolto" -> Fallo("Se movió la tarjeta", "Déjala quieta contra el teléfono hasta que vibre, y vuelve a intentar.", "Leer otra vez") { intento++ }
                        "Tiempo" -> Fallo("No encontramos la tarjeta", "Acércala más y déjala quieta. Si tiene funda, sácala.", "Leer otra vez") { intento++ }
                        else -> Esperando()
                    }
                }
            }
        }
        item("privado") {
            Row(Modifier.padding(horizontal = Margen).padding(top = 22.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, null, tint = LectorColors.Muted, modifier = Modifier.size(15.dp).padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text("La lectura pasa en tu teléfono. El número de la tarjeta no sale de él y no lo guardamos.", style = LectorType.Label, color = LectorColors.Muted)
            }
        }
    }
}

@Composable
private fun Fallo(titulo: String, cuerpo: String, accion: String?, alAccion: (() -> Unit)?) {
    Column {
        Column(Modifier.fillMaxWidth().clip(SuaveShape(24.dp)).background(LectorColors.Card).padding(18.dp)) {
            Text(titulo, style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text)
            Text(cuerpo, style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (accion != null && alAccion != null) {
            Spacer(Modifier.height(14.dp))
            BotonExpresivo(accion, Icons.Rounded.Contactless, alAccion, Modifier.fillMaxWidth(), estilo = EstiloBoton.RELLENO, alto = 54.dp)
        }
    }
}

/** Anillos que laten desde la tarjeta mientras espera que la acerques, como las ondas del NFC. */
@Composable
private fun Esperando() {
    val acento = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "nfc")
    val onda by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "onda")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(260.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (!Mov.reducido) for (i in 0..2) {
                    val f = (onda + i / 3f) % 1f
                    drawCircle(acento.copy(alpha = (1f - f) * 0.5f), radius = 70.dp.toPx() + f * 70.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
            DibujoTarjeta(Modifier.size(170.dp, 108.dp), null)
        }
        Text("Mira el saldo de tu TuLlave", style = LectorType.Hero, color = LectorColors.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Acerca la tarjeta a la parte de atrás del teléfono y déjala quieta hasta que vibre.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** La tarjeta dibujada: degradado del acento, chip y el número cuando se conoce. */
@Composable
private fun DibujoTarjeta(modifier: Modifier, numero: String?) {
    val acento = MaterialTheme.colorScheme.primary
    Box(modifier.clip(SuaveShape(18.dp)).background(Brush.linearGradient(listOf(acento, acento.copy(alpha = 0.55f), Color(0xFF1B1D22)))).padding(14.dp)) {
        Box(Modifier.size(30.dp, 22.dp).clip(SuaveShape(5.dp)).background(Color(0xFFE8C77A)))
        Text("TuLlave", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd))
        if (numero != null) Text(TuLlave.agrupar(numero), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Resultado(t: TuLlave.Tarjeta, leidaA: String?, otraVez: () -> Unit) {
    val acento = MaterialTheme.colorScheme.primary
    val ctx = LocalContext.current
    Column {
        DibujoTarjeta(Modifier.fillMaxWidth().aspectRatio(1.586f), t.numero.ifBlank { null })
        Spacer(Modifier.height(22.dp))
        Text("SALDO EN LA TARJETA", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = LectorColors.Muted)
        Text(if (t.saldo < 0) "−${pesos(-t.saldo)}" else pesos(t.saldo), style = LectorType.Display.copy(fontSize = 46.sp, fontWeight = FontWeight.Bold), color = if (t.saldo < 0) Color(0xFFE5484D) else LectorColors.Text)
        if (leidaA != null) Text("Leído a las $leidaA", style = LectorType.Label, color = LectorColors.Muted)
        Spacer(Modifier.height(12.dp))
        val viajes = (t.saldo / PASAJE_COP).coerceAtLeast(0)
        Row(Modifier.clip(SuaveShape(14.dp)).background(if (viajes == 0) Color(0xFFE5484D).copy(alpha = 0.14f) else acento.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DirectionsBus, null, tint = if (viajes == 0) Color(0xFFE5484D) else acento, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (viajes == 0) "No te alcanza para un pasaje (${pesos(PASAJE_COP)})" else "Te alcanza para $viajes pasaje${if (viajes > 1) "s" else ""}", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text)
        }
        if (t.saldo < 0) Nota(Icons.Rounded.Warning, "La tarjeta quedó debiendo ${pesos(-t.saldo)}. Se descuenta de tu próxima recarga.")
        Nota(Icons.Rounded.Info, "Una recarga en línea aparece aquí cuando la cargas en un validador o un punto de recarga.")

        // Resumen del mes en curso, con lo que guarda la tarjeta (sus últimos movimientos).
        val mes = java.time.LocalDate.now().toString().take(7)
        val delMes = t.movimientos.filter { it.fecha?.startsWith(mes) == true }
        if (delMes.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dato("Viajes este mes", "${delMes.count { it.tipo == TuLlave.Tipo.VIAJE }}", Modifier.weight(1f))
                Dato("Gastado", pesos(delMes.filter { it.tipo == TuLlave.Tipo.VIAJE }.sumOf { it.valor }), Modifier.weight(1f))
                Dato("Recargado", pesos(delMes.filter { it.tipo == TuLlave.Tipo.RECARGA }.sumOf { it.valor }), Modifier.weight(1f))
            }
        }

        if (t.numero.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text("NÚMERO DE LA TARJETA", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = LectorColors.Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TuLlave.agrupar(t.numero), style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = LectorColors.Text, modifier = Modifier.weight(1f))
                Box(Modifier.size(38.dp).clip(SuaveShape(12.dp)).background(LectorColors.PillFill).pressable {
                    val cb = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cb?.setPrimaryClip(android.content.ClipData.newPlainText("TuLlave", t.numero.uppercase()))
                    Tacto.tic(); Avisos.mostrar("Número copiado")
                }, Alignment.Center) { Icon(Icons.Rounded.ContentCopy, "Copiar número", tint = LectorColors.Text, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        BotonExpresivo("Leer otra vez", Icons.Rounded.Contactless, otraVez, Modifier.fillMaxWidth(), estilo = EstiloBoton.TONAL, alto = 50.dp)

        Spacer(Modifier.height(10.dp))
        Text("ÚLTIMOS MOVIMIENTOS", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = LectorColors.Muted, modifier = Modifier.padding(top = 14.dp))
        if (t.movimientos.isEmpty()) Text("La tarjeta no tiene movimientos guardados.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp))
        // Por mes, en el orden que los guarda la tarjeta (más nuevo primero); los sin fecha al final.
        val (conFecha, sinFecha) = t.movimientos.partition { it.fecha != null }
        (conFecha.groupBy { it.fecha!!.take(7) }.toList() + if (sinFecha.isNotEmpty()) listOf(null to sinFecha) else emptyList()).forEach { (m, lista) ->
            Text(nombreMes(m).replaceFirstChar { it.uppercase() }, style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = LectorColors.Text, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            Column(Modifier.fillMaxWidth().clip(SuaveShape(20.dp)).background(LectorColors.Card).padding(horizontal = 14.dp)) {
                lista.forEachIndexed { k, mv ->
                    if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LectorColors.Linea))
                    FilaMovimiento(mv)
                }
            }
        }
    }
}

@Composable
private fun Nota(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icono, null, tint = LectorColors.Muted, modifier = Modifier.size(15.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(texto, style = LectorType.Label, color = LectorColors.Muted)
    }
}

@Composable
private fun Dato(titulo: String, valor: String, modifier: Modifier) {
    Column(modifier.clip(SuaveShape(18.dp)).background(LectorColors.Card).padding(12.dp)) {
        Text(valor, style = LectorType.Section.copy(fontWeight = FontWeight.Bold), color = LectorColors.Text, maxLines = 1)
        Text(titulo, style = LectorType.Label, color = LectorColors.Muted, maxLines = 1)
    }
}

private val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
private fun nombreMes(ym: String?): String = ym?.let { runCatching { "${MESES[it.substring(5, 7).toInt() - 1]} ${it.take(4)}" }.getOrNull() } ?: "Sin fecha"

@Composable
private fun FilaMovimiento(m: TuLlave.Movimiento) {
    val (icono, texto, signo) = when (m.tipo) {
        TuLlave.Tipo.VIAJE -> Triple(Icons.Rounded.DirectionsBus, "Viaje", "−")
        TuLlave.Tipo.RECARGA -> Triple(Icons.Rounded.AddCard, "Recarga", "+")
        TuLlave.Tipo.RECARGA_ANULADA -> Triple(Icons.Rounded.Undo, "Recarga anulada", "−")
        TuLlave.Tipo.OTRO -> Triple(Icons.Rounded.SwapHoriz, "Movimiento", "")
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(SuaveShape(13.dp)).background(LectorColors.CardHigh), Alignment.Center) { Icon(icono, null, tint = LectorColors.Text, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(texto, style = LectorType.Section, color = LectorColors.Text)
            Text(m.fecha?.let { f -> "${f.substring(8, 10).trimStart('0')} ${MESES[f.substring(5, 7).toInt() - 1].take(3)} · ${f.substring(11, 16)}" } ?: "Sin fecha", style = LectorType.Label, color = LectorColors.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$signo${pesos(m.valor)}", style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = if (signo == "+") Color(0xFF34C759) else LectorColors.Text)
            Text("Quedó en ${pesos(m.saldoDespues)}", style = LectorType.Label, color = LectorColors.Muted)
        }
    }
}
