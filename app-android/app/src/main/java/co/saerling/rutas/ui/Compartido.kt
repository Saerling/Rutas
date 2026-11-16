package co.saerling.rutas.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.isSpecified
import co.saerling.rutas.fx.*
import co.saerling.rutas.ui.mochy.*
import co.saerling.rutas.ui.mochy.search.SearchFieldGlyph
import co.saerling.rutas.ui.mochy.search.SearchFieldHeight

// Piezas pequeñas que Shiori tenía repartidas en pantallas de manga y que el diseño de Mochy necesita.

@Composable
fun LText(
    text: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = LectorColors.Text, maxLines: Int = Int.MAX_VALUE
) = Text(text, modifier, color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Botón tonal Expressive: píldora que cuadra sus esquinas al pulsar.
    BotonExpresivo(text, icon, onClick, modifier, EstiloBoton.TONAL, alto = 46.dp)
}

@Composable
internal fun CampoBusqueda(valor: String, alCambiar: (String) -> Unit, pista: String) {
    // El SearchField de Mochy (mochy/search/SearchDesign.kt): píldora de 48 dp, Field (FieldFocused con el foco),
    // lupa y borrar de 20 dp a los lados y pista gris de 15 sp. Se queda con texto plano para no cambiar quién lo usa.
    var foco by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = valor, onValueChange = alCambiar, singleLine = true,
        textStyle = AppTypography.bodyMedium.copy(color = SearchColors.Primary, fontSize = 15.sp),
        cursorBrush = SolidColor(SearchColors.Primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.onFocusChanged { foco = it.isFocused }.then(if (foco && fxOn("searchinput")) Modifier.hazBorde(24.dp, MaterialTheme.colorScheme.primary) else Modifier),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(SearchFieldHeight).clip(RoundedCornerShape(50))
                    .background(if (foco) SearchColors.FieldFocused else SearchColors.Field).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchFieldGlyph(Icons.Rounded.Search, null)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (valor.isEmpty()) Text(
                        pista, style = AppTypography.bodyMedium.copy(fontSize = 15.sp), color = SearchColors.Placeholder,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    inner()
                }
                if (valor.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    SearchFieldGlyph(Icons.Rounded.Close, "Borrar") { alCambiar("") }
                }
            }
        }
    )
}

val IdsFxShiori = listOf("blurfade", "themetoggle", "noise", "ripple", "switch", "checkbox", "segmented", "empty", "skeleton", "spinner", "dialogs", "sheet",
    "progress", "searchinput", "textmorph", "toast", "shinytext", "nf_unread", "progressring", "slidingnumber", "nf_stats", "follow", "download", "holddelete", "weekstrip", "textshimmer", "comictext", "pulsating", "tilt", "glow", "magiccard", "glare", "backlight", "marquee", "carousel",
    "stats", "numberticker", "activity", "circularprogress", "nf_page", "scrollprogress", "battery", "confetti", "lens", "cmd", "onboarding")

/** Diálogos de Material y del sistema de Mochy: el estilo elegido (Dialogs 01–12). */
fun estiloDialogoFxM(): Modifier = Modifier.estiloDialogoFx()

/** Avisos de arriba (Toast de coss): grano y haz de luz en el borde. */
fun estiloAvisoFx(): Modifier = Modifier.composed {
    if (fxOn("toast")) Modifier.noise(.06f).hazBorde(30.dp, MaterialTheme.colorScheme.primary) else Modifier
}


@Composable
internal fun VistaBarra(s: Superficie, modifier: Modifier = Modifier) {
    val forma = CircleShape
    val oscuro = TemaActual.oscuro
    Box(
        modifier.padding(10.dp).fillMaxWidth().height(26.dp).clip(forma)
            .background(
                when (s) {
                    Superficie.SOLIDO -> LectorColors.Cristal
                    Superficie.CRISTAL -> LectorColors.Cristal.copy(alpha = 0.78f)
                    Superficie.LIQUIDO -> LectorColors.Cristal.copy(alpha = 0.40f)
                },
            )
            .then(
                if (s == Superficie.LIQUIDO) Modifier.background(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = if (oscuro) 0.16f else 0.4f), 0.5f to Color.Transparent),
                ).border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f), 1f to Color.White.copy(alpha = 0.08f)), forma)
                else Modifier.border(1.dp, LectorColors.Linea, forma),
            ),
    ) {
        Box(Modifier.padding(4.dp).size(28.dp, 18.dp).clip(forma).background(LectorColors.Iluminada))
    }
}
/** Curva de cierre de las páginas (empieza suave: un primer fotograma lento no se nota). */
val CierreEasing = CubicBezierEasing(0.4f, 0f, 0.1f, 1f)
/** Los 12 estilos de diálogo (blocks.so Dialogs 01–12) como decoración del diálogo real de la app. */
fun Modifier.estiloDialogoFx(): Modifier = composed {
    if (!fxOn("dialogs")) return@composed Modifier
    val acento = MaterialTheme.colorScheme.primary
    when (Fx.variante("dialogs")) {
        0 -> Modifier.hazBorde(30.dp, acento)
        1 -> Modifier.bordeBrillante(30.dp)
        2 -> Modifier.noise(.06f)
        3 -> Modifier.foco(LectorColors.Text)
        4 -> Modifier.hazBorde(30.dp, acento).noise(.05f)
        5 -> Modifier.aparecerDifuso()
        6 -> Modifier.halo(listOf(acento, LectorColors.Text, acento))
        7 -> Modifier.bordeBrillante(30.dp, listOf(acento, LectorColors.Text, acento))
        8 -> Modifier.patron(Patron.Puntos, LectorColors.Text.copy(alpha = .10f))
        9 -> Modifier.patron(Patron.Cuadricula, LectorColors.Text.copy(alpha = .06f))
        10 -> Modifier.hazBorde(30.dp, acento, ms = 1400).aparecerDifuso()
        else -> Modifier.foco(acento).noise(.04f)
    }
}

/** Último punto tocado, como origen de la pantalla que se abre (efecto «la portada crece hasta ser la página»). */
object OrigenToque {
    /** Portada tocada (su rectángulo en la ventana), si el toque fue sobre una: la ficha crece desde ella. */
    var rect: androidx.compose.ui.geometry.Rect? = null
    var punto = androidx.compose.ui.geometry.Offset.Unspecified
    var tam = androidx.compose.ui.unit.IntSize.Zero
    fun origen(): androidx.compose.ui.graphics.TransformOrigin =
        if (!punto.isSpecified || tam.width == 0 || tam.height == 0) androidx.compose.ui.graphics.TransformOrigin.Center
        else androidx.compose.ui.graphics.TransformOrigin((punto.x / tam.width).coerceIn(0f, 1f), (punto.y / tam.height).coerceIn(0f, 1f))
}
const val ContenedorCierreMs = 560
