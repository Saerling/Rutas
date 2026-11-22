package co.saerling.rutas.ui.rutas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import co.saerling.rutas.R
import co.saerling.rutas.data.Bus
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * Buses del mapa como en Paradero (croma-labs/paradero, MIT: src/lib/moving-buses.ts, src/transit/path.ts y
 * src/components/transit-map.tsx). Cada bus se dibuja donde su velocidad dice que va ahora, por la línea de la
 * ruta cuando está sobre ella, y se acomoda a cada reporte nuevo en vez de saltar. Las posiciones llegan cada
 * 15 a 20 s; entre una y otra es una estimación, y con movimiento reducido los buses se quedan donde reportaron.
 */

/** Punto [lon, lat], como en GeoJSON. */
typealias LngLat = DoubleArray

private const val METROS_POR_GRADO = 111_320.0
private fun rad(g: Double) = g * Math.PI / 180

/** Una línea de ruta medida, para poner y mover un bus sobre ella. */
class Linea(val puntos: List<LngLat>) {
    /** Metros desde el primer punto hasta cada punto. */
    val hasta: DoubleArray = DoubleArray(puntos.size).also { a -> for (i in 1 until puntos.size) a[i] = a[i - 1] + metros(puntos[i - 1], puntos[i]) }
    val largo: Double get() = hasta.lastOrNull() ?: 0.0

    /** El punto de la línea más cercano a [en]: cuánto lleva recorrido y a qué distancia queda [en] de la línea. */
    fun proyectar(en: LngLat): Pair<Double, Double>? {
        if (puntos.size < 2) return null
        val c = cos(rad(en[1]))
        fun x(p: LngLat) = (p[0] - en[0]) * METROS_POR_GRADO * c
        fun y(p: LngLat) = (p[1] - en[1]) * METROS_POR_GRADO
        var mejor: Pair<Double, Double>? = null
        for (i in 0 until puntos.size - 1) {
            val ax = x(puntos[i]); val ay = y(puntos[i]); val bx = x(puntos[i + 1]); val by = y(puntos[i + 1])
            val l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (l2 == 0.0) 0.0 else min(1.0, max(0.0, -(ax * (bx - ax) + ay * (by - ay)) / l2))
            val desvio = hypot(ax + t * (bx - ax), ay + t * (by - ay))
            if (mejor == null || desvio < mejor.second) mejor = (hasta[i] + t * (hasta[i + 1] - hasta[i])) to desvio
        }
        return mejor
    }

    /** El lugar a [recorrido] metros por la línea y el rumbo ahí, en grados desde el norte. */
    fun puntoEn(recorrido: Double): Pair<LngLat, Double> {
        val d = min(largo, max(0.0, recorrido))
        var i = 0
        while (i + 2 < hasta.size && hasta[i + 1] < d) i++
        val a = puntos[i]; val b = puntos.getOrElse(i + 1) { a }
        val tramo = hasta.getOrElse(i + 1) { hasta[i] } - hasta[i]
        val t = if (tramo > 0) (d - hasta[i]) / tramo else 0.0
        return doubleArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])) to rumbo(a, b)
    }

    companion object {
        fun metros(a: LngLat, b: LngLat): Double =
            hypot((b[0] - a[0]) * METROS_POR_GRADO * cos(rad((a[1] + b[1]) / 2)), (b[1] - a[1]) * METROS_POR_GRADO)

        fun rumbo(a: LngLat, b: LngLat): Double =
            (Math.toDegrees(atan2((b[0] - a[0]) * cos(rad((a[1] + b[1]) / 2)), b[1] - a[1])) + 360) % 360
    }
}

/** Un bus en el mapa en este instante. */
class BusDibujado(val id: String, val en: LngLat, val rumbo: Double, val codigo: String?, val sistema: String?)

/**
 * El movimiento de los buses entre reportes. [reportar] se llama con cada lista nueva del servidor y
 * [cuadro] en cada cuadro de dibujo (cada 100 ms, o cada segundo con movimiento reducido).
 */
class BusesEnMovimiento {
    private class Pista(
        val fixAt: Long, val fix: LngLat, val linea: Int, val recorrido: Double?,
        val velocidad: Double, val medida: Boolean, val este: Double, val norte: Double, val rumbo: Double,
        val desde: LngLat?, val acomodoAt: Long, val codigo: String?, val sistema: String?,
    )

    private val pistas = HashMap<String, Pista>()
    private val rumbos = HashMap<String, Double>()
    private val avances = HashMap<String, Double>()
    private var lineas: List<Linea> = emptyList()
    private var ultimo = System.currentTimeMillis()

    /** Las líneas de ruta del mapa. Al cambiar, los buses se vuelven a ubicar sobre ellas. */
    fun lineas(nuevas: List<Linea>) {
        if (nuevas.map { it.puntos.size } == lineas.map { it.puntos.size } && nuevas.zip(lineas).all { (a, b) -> a.largo == b.largo }) return
        lineas = nuevas
        pistas.clear(); avances.clear()
    }

    /** Un reporte nuevo: [enCamino] es el bus que vas a tomar, que se mueve aunque solo haya un reporte. */
    fun reportar(buses: List<Bus>, enCamino: String?) {
        val ahora = System.currentTimeMillis()
        val vistos = buses.map { it.id }.toSet()
        for (b in buses) pistas[b.id] = seguir(pistas[b.id], b, b.id == enCamino, ahora)
        pistas.keys.retainAll(vistos); rumbos.keys.retainAll(vistos); avances.keys.retainAll(vistos)
    }

    fun cuadro(quieto: Boolean): List<BusDibujado> {
        val ahora = System.currentTimeMillis()
        val segundos = min(1.0, (ahora - ultimo) / 1000.0)
        ultimo = ahora
        return pistas.map { (id, p) ->
            var en = p.fix; var rumbo = p.rumbo
            val linea = lineas.getOrNull(p.linea)
            if (!quieto && linea != null && p.recorrido != null) {
                val recorrido = avanzar(avances[id], p, ahora, segundos)
                avances[id] = recorrido
                val (aqui, rumboLinea) = linea.puntoEn(recorrido)
                en = aqui
                rumbo = if (p.velocidad > 0.5) rumboLinea else if (p.velocidad < -0.5) (rumboLinea + 180) % 360 else p.rumbo
            } else if (!quieto) {
                val (aqui, r) = ubicar(p, ahora); en = aqui; rumbo = r
            }
            val antes = rumbos[id]
            val final = if (quieto || antes == null) rumbo else girar(antes, rumbo)
            rumbos[id] = final
            BusDibujado(id, en, final, p.codigo, p.sistema)
        }
    }

    private fun sobreLinea(en: LngLat): Pair<Int, Double>? {
        var mejor: Triple<Int, Double, Double>? = null
        lineas.forEachIndexed { i, l ->
            val (recorrido, desvio) = l.proyectar(en) ?: return@forEachIndexed
            if (desvio <= EN_LINEA_M && (mejor == null || desvio < mejor!!.third)) mejor = Triple(i, recorrido, desvio)
        }
        return mejor?.let { it.first to it.second }
    }

    private fun seguir(vieja: Pista?, b: Bus, enCamino: Boolean, ahora: Long): Pista {
        val fix = doubleArrayOf(b.lon, b.lat)
        val reportado = b.reportedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: ahora
        val fixAt = min(ahora, reportado)
        val sobre = sobreLinea(fix)
        val linea = sobre?.first ?: -1
        val recorrido = sobre?.second
        if (vieja == null) {
            val rumbo = if (sobre != null) lineas[linea].puntoEn(recorrido!!).second else b.bearing ?: 0.0
            return Pista(fixAt, fix, linea, recorrido, if (enCamino && sobre != null) CRUCERO else 0.0, false, 0.0, 0.0, rumbo, null, ahora, b.code, b.system)
        }
        if (fixAt <= vieja.fixAt) return vieja
        val segundos = (fixAt - vieja.fixAt) / 1000.0
        val dibujado = ubicar(vieja, ahora).first
        var velocidad = vieja.velocidad; var este = vieja.este; var norte = vieja.norte; var rumbo = vieja.rumbo
        if (sobre != null && vieja.recorrido != null && vieja.linea == linea) {
            // Con signo: un bus que va en contra de la línea (la otra dirección por la misma calle) retrocede por ella.
            val paso = (recorrido!! - vieja.recorrido) / segundos
            velocidad = if (abs(paso) > TOPE) vieja.velocidad * 0.5 else if (vieja.medida) 0.6 * paso + 0.4 * vieja.velocidad else paso
            val rumboLinea = lineas[linea].puntoEn(recorrido).second
            rumbo = if (abs(paso) < 0.5) vieja.rumbo else if (paso < 0) (rumboLinea + 180) % 360 else rumboLinea
        } else {
            val e = (fix[0] - vieja.fix[0]) * METROS_POR_GRADO * cos(rad(vieja.fix[1])) / segundos
            val n = (fix[1] - vieja.fix[1]) * METROS_POR_GRADO / segundos
            val paso = hypot(e, n)
            if (paso <= TOPE) { este = e; norte = n } else { este = vieja.este * 0.5; norte = vieja.norte * 0.5 }
            if (paso > 0.5) rumbo = Linea.rumbo(vieja.fix, fix) else if (b.bearing != null) rumbo = b.bearing
        }
        return Pista(fixAt, fix, linea, recorrido, velocidad, true, este, norte, rumbo, dibujado, ahora, b.code ?: vieja.codigo, b.system ?: vieja.sistema)
    }

    /** Cuántos segundos, a la velocidad que traía, ha seguido rodando un bus desde su reporte. Luego frena suave, no en seco. */
    private fun rodado(p: Pista, ahora: Long): Double {
        val edad = (ahora - p.fixAt).toDouble()
        if (edad > VIEJO_MS) return 0.0
        return (if (edad <= SIGUE_MS) edad else SIGUE_MS + FRENA_MS * (1 - exp(-(edad - SIGUE_MS) / FRENA_MS))) / 1000
    }

    /**
     * Cuánto ha avanzado por la línea, un cuadro después de [dibujado]. Va a la velocidad de sus reportes, un poco
     * más rápido o más lento para alcanzar donde lo ponen, y nunca retrocede: si va adelantado, se espera, como en un semáforo.
     */
    private fun avanzar(dibujado: Double?, p: Pista, ahora: Long, segundos: Double): Double {
        val meta = p.recorrido!! + p.velocidad * rodado(p, ahora)
        if (dibujado == null || abs(meta - dibujado) > SALTO_M) return meta
        val sentido = if (abs(p.velocidad) > 0.5) sign(p.velocidad) else sign(meta - dibujado).takeIf { it != 0.0 } ?: 1.0
        val brecha = (meta - dibujado) * sentido
        val ritmo = abs(p.velocidad)
        val maximo = min(TOPE, max(ritmo, CRUCERO) * 1.6 + 2)
        return dibujado + sentido * min(maximo, max(0.0, ritmo + brecha / ALCANCE_S)) * segundos
    }

    /** Dónde va un bus fuera de la línea: sigue con la velocidad que traía y se acomoda desde donde estaba dibujado. */
    private fun ubicar(p: Pista, ahora: Long): Pair<LngLat, Double> {
        val s = rodado(p, ahora)
        val meta = doubleArrayOf(p.fix[0] + p.este * s / (METROS_POR_GRADO * cos(rad(p.fix[1]))), p.fix[1] + p.norte * s / METROS_POR_GRADO)
        val desde = p.desde ?: return meta to p.rumbo
        val k = min(1.0, (ahora - p.acomodoAt).toDouble() / ACOMODO_MS)
        val suave = k * k * (3 - 2 * k)
        return doubleArrayOf(desde[0] + (meta[0] - desde[0]) * suave, desde[1] + (meta[1] - desde[1]) * suave) to p.rumbo
    }

    /** Gira una parte de lo que le falta en cada cuadro, para tomar la esquina en vez de voltearse de golpe. */
    private fun girar(desde: Double, hacia: Double): Double {
        val giro = ((hacia - desde + 540) % 360) - 180
        return if (abs(giro) < 0.5) hacia else (desde + giro * GIRO + 360) % 360
    }

    companion object {
        /** A menos de esto de la línea de la ruta, el bus se mueve por ella. */
        const val EN_LINEA_M = 60.0
        /** Más rápido que cualquier bus de Bogotá: un salto así es un mal reporte, no velocidad. */
        const val TOPE = 22.0
        const val SIGUE_MS = 30_000.0
        const val FRENA_MS = 15_000.0
        /** Un reporte más viejo que esto se muestra donde quedó, sin moverlo. */
        const val VIEJO_MS = 120_000.0
        const val ACOMODO_MS = 3_000.0
        /** Ritmo de ciudad para el bus que vas a tomar mientras solo tiene un reporte. */
        const val CRUCERO = 4.0
        const val ALCANCE_S = 5.0
        const val SALTO_M = 600.0
        const val GIRO = 0.09
        /** Diez cuadros por segundo: los buses van a pocos metros por segundo y queda tiempo para el resto de la app. */
        const val CUADRO_MS = 100L
        const val QUIETO_MS = 1_000L

        /** Los íconos de Paradero: un bus pequeño visto desde arriba, del color de su sistema. */
        val SISTEMAS = mapOf(
            "troncal" to R.drawable.bus_troncal, "dual" to R.drawable.bus_dual, "zonal_urbano" to R.drawable.bus_zonal_urbano,
            "alimentador" to R.drawable.bus_alimentador, "zonal_complementario" to R.drawable.bus_zonal_complementario,
            "zonal_especial" to R.drawable.bus_zonal_especial, "transmicable" to R.drawable.bus_transmicable, "other" to R.drawable.bus_other,
        )

        fun icono(sistema: String?): String = "bus-${if (sistema != null && sistema in SISTEMAS) sistema else "other"}"

        /** Los PNG vienen a 3x: se marcan como xxhdpi para que MapLibre los dibuje a su tamaño real. */
        fun imagen(c: Context, sistema: String): Bitmap =
            BitmapFactory.decodeResource(c.resources, SISTEMAS.getValue(sistema), BitmapFactory.Options().apply { inScaled = false })
                .also { it.density = DisplayMetrics.DENSITY_XXHIGH }
    }
}
