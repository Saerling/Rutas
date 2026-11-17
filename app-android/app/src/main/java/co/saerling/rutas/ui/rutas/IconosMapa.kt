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

    /**
     * Bus visto desde arriba, como los carros de Uber: el frente mira al norte y MapLibre lo gira con el rumbo.
     * Los troncales y duales son articulados (dos cuerpos y el fuelle). Borde blanco para verse en mapa claro y oscuro.
     */
    fun busArriba(c: Context, color: Int, articulado: Boolean): Bitmap {
        val w = 20f; val h = if (articulado) 58f else 42f
        return lienzo(c, w, h) { d ->
            val ox = 3 * d; val bw = (w - 6) * d
            val vidrio = relleno(0xFF1D2733.toInt())
            val techo = relleno(0x30FFFFFF)
            val borde = relleno(0xFFFFFFFF.toInt())
            fun cuerpo(top: Float, bottom: Float, frente: Boolean, cola: Boolean) {
                val r = RectF(ox, top, ox + bw, bottom)
                drawRoundRect(RectF(r.left, r.top + 1.5f * d, r.right, r.bottom + 1.5f * d), 4.5f * d, 4.5f * d, relleno(SOMBRA))
                drawRoundRect(RectF(r.left - 1.3f * d, r.top - 1.3f * d, r.right + 1.3f * d, r.bottom + 1.3f * d), 5.5f * d, 5.5f * d, borde)
                drawRoundRect(r, 4.5f * d, 4.5f * d, relleno(color))
                if (frente) {
                    // Parabrisas curvo y espejos.
                    drawRoundRect(RectF(r.left + 1.6f * d, r.top + 1.8f * d, r.right - 1.6f * d, r.top + 7.2f * d), 3f * d, 3f * d, vidrio)
                    drawRoundRect(RectF(r.left - 2.6f * d, r.top + 3.2f * d, r.left - 0.4f * d, r.top + 5.6f * d), d, d, borde)
                    drawRoundRect(RectF(r.right + 0.4f * d, r.top + 3.2f * d, r.right + 2.6f * d, r.top + 5.6f * d), d, d, borde)
                }
                if (cola) drawRoundRect(RectF(r.left + 2.4f * d, r.bottom - 3.4f * d, r.right - 2.4f * d, r.bottom - 1.4f * d), d, d, vidrio)
                // Techo: franja clara y la unidad de aire.
                val t0 = r.top + (if (frente) 9.5f else 2.5f) * d; val t1 = r.bottom - (if (cola) 5f else 2.5f) * d
                if (t1 > t0) {
                    drawRoundRect(RectF(r.left + 2.2f * d, t0, r.right - 2.2f * d, t1), 2f * d, 2f * d, techo)
                    val mid = (t0 + t1) / 2
                    drawRoundRect(RectF(r.left + 3.4f * d, mid - 3f * d, r.right - 3.4f * d, mid + 3f * d), 1.4f * d, 1.4f * d, relleno(0x55FFFFFF))
                }
            }
            if (!articulado) cuerpo(2 * d, (h - 2) * d, frente = true, cola = true)
            else {
                val corte = 30f * d
                cuerpo(2 * d, corte - 1.6f * d, frente = true, cola = false)
                cuerpo(corte + 1.6f * d, (h - 2) * d, frente = false, cola = true)
                // Fuelle: acordeón oscuro entre los dos cuerpos.
                val f = RectF(ox + 2 * d, corte - 2.4f * d, ox + bw - 2 * d, corte + 2.4f * d)
                drawRect(f, relleno(0xFF2A2D33.toInt()))
                val raya = relleno(0xFF5A5F68.toInt())
                for (k in 0..2) drawRect(f.left, f.top + (0.8f + k * 1.4f) * d, f.right, f.top + (1.3f + k * 1.4f) * d, raya)
            }
        }
    }

    /** Bus en vía: círculo del color de su sistema con el bus en blanco (para listas y la tarjeta del bus). */
    fun busCirculo(c: Context, color: Int): Bitmap = lienzo(c, 34f, 34f) { d ->
        val cx = 17 * d
        drawCircle(cx, cx + 1.5f * d, 13 * d, relleno(SOMBRA))
        drawCircle(cx, cx, 13 * d, relleno(0xFFFFFFFF.toInt()))
        drawCircle(cx, cx, 11 * d, relleno(color))
        bus(cx - 7.5f * d, cx - 7.5f * d, 15 * d, 0xFFFFFFFF.toInt(), color)
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
