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

@Composable
fun TarjetaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val actividad = ctx as? Activity
    var intento by remember { mutableIntStateOf(0) }
    var estado by remember { mutableStateOf<TuLlave.Lectura?>(null) }
    val conNfc = actividad != null && TuLlave.tieneNfc(actividad)
    val activo = actividad != null && TuLlave.disponible(actividad)
    LaunchedEffect(intento, activo) {
        if (actividad == null || !activo) return@LaunchedEffect
        estado = null
        val r = TuLlave.leer(actividad)
        estado = r
        when (r) {
            is TuLlave.Lectura.Ok -> { Tacto.alternar(true); UltimoSaldo.guardar(ctx, r.t.saldo) }
            else -> Tacto.rechazar()
        }
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
            Column(Modifier.padding(horizontal = Margen).padding(top = 4.dp, bottom = 18.dp)) {
                Text("TuLlave", style = LectorType.Display, color = LectorColors.Text)
                Text("Saldo y últimos movimientos, leídos por NFC. El número no sale del teléfono.", style = LectorType.Body, color = LectorColors.Muted)
            }
        }
        item("tarjeta") {
            AnimatedContent(
                targetState = when { !conNfc -> "sin"; !activo -> "apagado"; else -> estado?.let { it::class.simpleName } ?: "esperando" },
                transitionSpec = { (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 300f), initialScale = 0.94f)) togetherWith fadeOut(tween(160)) },
                label = "tullave",
            ) { k ->
                Column(Modifier.padding(horizontal = Margen)) {
                    when (k) {
                        "sin" -> Vacio("Este celular no tiene NFC", "Para ver el saldo necesitas un teléfono con NFC.")
                        "apagado" -> Tarjeta(radio = 28.dp) {
                            Icon(Icons.Rounded.Nfc, null, tint = LectorColors.Text, modifier = Modifier.size(30.dp))
                            Text("Activa el NFC", style = LectorType.Hero, color = LectorColors.Text, modifier = Modifier.padding(top = 10.dp))
                            Text("Está en los ajustes rápidos del celular.", style = LectorType.Body, color = LectorColors.Muted)
                            Spacer(Modifier.height(16.dp))
                            BotonExpresivo("Abrir ajustes de NFC", Icons.Rounded.Settings, { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, estilo = EstiloBoton.RELLENO)
                        }
                        "Ok" -> (estado as? TuLlave.Lectura.Ok)?.let { Resultado(it.t) { intento++ } }
                        "NoEsTuLlave" -> Vacio("Esa no es una TuLlave", "Acerca una tarjeta TuLlave personalizada o básica.", "Intentar otra vez") { intento++ }
                        "SeSolto" -> Vacio("Se soltó la tarjeta", "Déjala quieta contra la parte de atrás hasta que vibre.", "Intentar otra vez") { intento++ }
                        else -> Esperando()
                    }
                }
            }
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
        Text("Acerca tu TuLlave", style = LectorType.Hero, color = LectorColors.Text)
        Text("Ponla contra la parte de atrás del celular, cerca de la cámara, y déjala quieta.", style = LectorType.Body, color = LectorColors.Muted, modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
private fun Resultado(t: TuLlave.Tarjeta, otraVez: () -> Unit) {
    Column {
        DibujoTarjeta(Modifier.fillMaxWidth().aspectRatio(1.586f), t.numero.ifBlank { null })
        Spacer(Modifier.height(20.dp))
        Text("SALDO", style = LectorType.Label.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = LectorColors.Muted)
        co.saerling.rutas.fx.NumeroFlow(t.saldo, LectorType.Display.copy(fontSize = 44.sp), color = LectorColors.Text, prefijo = "$")
        val viajes = t.saldo / PASAJE_COP
        Text(if (viajes == 0) "No te alcanza para un pasaje ($3.550)" else "Te alcanza para $viajes pasaje${if (viajes > 1) "s" else ""}", style = LectorType.Body, color = LectorColors.Muted)
        Spacer(Modifier.height(14.dp))
        BotonExpresivo("Leer otra vez", Icons.Rounded.Refresh, otraVez, estilo = EstiloBoton.TONAL)
        if (t.movimientos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            var mes: String? = "-"
            t.movimientos.forEach { m ->
                val esteMes = m.fecha?.take(7)
                if (esteMes != mes) { mes = esteMes; Seccion(nombreMes(esteMes), Modifier.offset(x = (-20).dp)) }
                FilaMovimiento(m)
            }
        }
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
            Text(listOfNotNull(m.fecha?.let { f -> "${f.substring(8, 10)}/${f.substring(5, 7)} · ${f.substring(11, 16)}" }, "quedó ${pesos(m.saldoDespues)}").joinToString(" · "), style = LectorType.Label, color = LectorColors.Muted)
        }
        Text("$signo${pesos(m.valor)}", style = LectorType.Section.copy(fontWeight = FontWeight.SemiBold), color = if (signo == "+") Color(0xFF34C759) else LectorColors.Text)
    }
}
