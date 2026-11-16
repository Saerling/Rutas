package co.saerling.rutas.ui.rutas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Marcadores del mapa dibujados a mano (nítidos a cualquier densidad):
 * - Paradero SITP: placa azul con un bus, como las señales de la calle.
 * - Estación troncal: placa roja con el perfil del articulado.
 * - Bus en vía: píldora del color de su sistema con el código y una flecha de hacia dónde va.
 */
object IconosMapa {
    private const val SOMBRA = 0x40000000

    private fun lienzo(c: Context, wDp: Float, hDp: Float, dibujar: Canvas.(d: Float) -> Unit): Bitmap {
        val d = c.resources.displayMetrics.density
        val b = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        b.density = c.resources.displayMetrics.densityDpi
        Canvas(b).dibujar(d)
        return b
    }

    private fun relleno(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    /** Bus de frente, dentro de una caja de [t] px con esquina en (x, y). */
    private fun Canvas.bus(x: Float, y: Float, t: Float, color: Int, fondo: Int) {
        val p = relleno(color)
        drawRoundRect(RectF(x + t * .18f, y + t * .08f, x + t * .82f, y + t * .80f), t * .14f, t * .14f, p)
        val v = relleno(fondo)
        drawRoundRect(RectF(x + t * .27f, y + t * .18f, x + t * .73f, y + t * .46f), t * .05f, t * .05f, v)
        drawCircle(x + t * .33f, y + t * .64f, t * .055f, v)
        drawCircle(x + t * .67f, y + t * .64f, t * .055f, v)
        drawRect(x + t * .24f, y + t * .80f, x + t * .36f, y + t * .92f, p)
        drawRect(x + t * .64f, y + t * .80f, x + t * .76f, y + t * .92f, p)
    }

    /** Articulado de lado (dos cuerpos y el fuelle), para las estaciones troncales. */
    private fun Canvas.articulado(x: Float, y: Float, t: Float, color: Int, fondo: Int) {
        val p = relleno(color)
        drawRoundRect(RectF(x + t * .06f, y + t * .30f, x + t * .50f, y + t * .70f), t * .08f, t * .08f, p)
        drawRoundRect(RectF(x + t * .54f, y + t * .30f, x + t * .96f, y + t * .70f), t * .08f, t * .08f, p)
        drawRect(x + t * .48f, y + t * .36f, x + t * .56f, y + t * .64f, p)
        val v = relleno(fondo)
        for (k in 0..1) {
            val ox = x + t * (.12f + k * .48f)
            drawRoundRect(RectF(ox, y + t * .37f, ox + t * .14f, y + t * .50f), t * .02f, t * .02f, v)
            drawRoundRect(RectF(ox + t * .18f, y + t * .37f, ox + t * .32f, y + t * .50f), t * .02f, t * .02f, v)
        }
        drawCircle(x + t * .18f, y + t * .72f, t * .07f, p.apply { this.color = fondo })
        drawCircle(x + t * .82f, y + t * .72f, t * .07f, p)
    }

    /** Placa con colita, del tamaño [lado] dp; [grande] para el paradero que estás viendo. */
    fun placa(c: Context, estacion: Boolean, grande: Boolean, oscuro: Boolean): Bitmap {
        val lado = if (grande) 34f else 24f
        return lienzo(c, lado + 4, lado + 9) { d ->
            val s = lado * d
            val o = 2 * d
            val color = if (estacion) 0xFFD7262C.toInt() else 0xFF2F6FC6.toInt()
            val borde = if (oscuro) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()
            drawRoundRect(RectF(o, o + d, o + s, o + s + d), s * .3f, s * .3f, relleno(SOMBRA))
            val cola = Path().apply { moveTo(o + s * .38f, o + s * .9f); lineTo(o + s / 2, o + s + 6 * d); lineTo(o + s * .62f, o + s * .9f); close() }
            drawPath(cola, relleno(borde))
            drawRoundRect(RectF(o, o, o + s, o + s), s * .3f, s * .3f, relleno(borde))
            val m = 2.2f * d
            drawRoundRect(RectF(o + m, o + m, o + s - m, o + s - m), s * .24f, s * .24f, relleno(color))
            if (estacion) articulado(o + s * .14f, o + s * .14f, s * .72f, 0xFFFFFFFF.toInt(), color)
            else bus(o + s * .2f, o + s * .17f, s * .6f, 0xFFFFFFFF.toInt(), color)
        }
    }

    /** Punto pequeño para los paraderos cuando el mapa está lejos. */
    fun punto(c: Context, estacion: Boolean, oscuro: Boolean): Bitmap = lienzo(c, 12f, 12f) { d ->
        drawCircle(6 * d, 6 * d, 5.5f * d, relleno(if (oscuro) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()))
        drawCircle(6 * d, 6 * d, 3.8f * d, relleno(if (estacion) 0xFFD7262C.toInt() else 0xFF2F6FC6.toInt()))
    }

    /** Bus en vía: círculo del color de su sistema con el bus en blanco. */
    fun busCirculo(c: Context, color: Int): Bitmap = lienzo(c, 34f, 34f) { d ->
        val cx = 17 * d
        drawCircle(cx, cx + 1.5f * d, 13 * d, relleno(SOMBRA))
        drawCircle(cx, cx, 13 * d, relleno(0xFFFFFFFF.toInt()))
        drawCircle(cx, cx, 11 * d, relleno(color))
        bus(cx - 7.5f * d, cx - 7.5f * d, 15 * d, 0xFFFFFFFF.toInt(), color)
    }

    /** Flecha que rodea el círculo del bus; MapLibre la gira con el rumbo. */
    fun flecha(c: Context, color: Int): Bitmap = lienzo(c, 50f, 50f) { d ->
        val cx = 25 * d
        val f = Path().apply { moveTo(cx, 3 * d); lineTo(cx - 7 * d, 13 * d); lineTo(cx + 7 * d, 13 * d); close() }
        drawPath(f, relleno(0xFFFFFFFF.toInt()))
        val g = Path().apply { moveTo(cx, 5.5f * d); lineTo(cx - 4.5f * d, 12 * d); lineTo(cx + 4.5f * d, 12 * d); close() }
        drawPath(g, relleno(color))
    }

    /** Etiqueta con el código, debajo del bus. */
    fun etiqueta(c: Context, codigo: String, color: Int): Bitmap {
        val dm = c.resources.displayMetrics.density
        val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); textSize = 11.5f * dm; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val t = codigo.ifBlank { "Bus" }
        val w = texto.measureText(t) / dm + 14
        return lienzo(c, w + 2, 21f) { d ->
            val r = RectF(1 * d, 1 * d, (w + 1) * d, 19 * d)
            drawRoundRect(RectF(r.left, r.top + d, r.right, r.bottom + d), 9 * d, 9 * d, relleno(SOMBRA))
            drawRoundRect(r, 9 * d, 9 * d, relleno(0xFFFFFFFF.toInt()))
            val i = RectF(r.left + 1.6f * d, r.top + 1.6f * d, r.right - 1.6f * d, r.bottom - 1.6f * d)
            drawRoundRect(i, 8 * d, 8 * d, relleno(color))
            val fm = texto.fontMetrics
            drawText(t, i.centerX() - texto.measureText(t) / 2, i.centerY() - (fm.ascent + fm.descent) / 2, texto)
        }
    }
}
