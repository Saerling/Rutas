package co.saerling.rutas.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.saerling.rutas.MainActivity
import co.saerling.rutas.R

/** Dónde vas dentro de un viaje guiado. */
sealed interface Guia {
    data object Esperando : Guia
    /**
     * A pie. [metros] es lo que falta hasta el final del tramo; [paso] es el giro que viene (índice en [pasosLimpios],
     * -1 si ya no hay más giros) y [metrosPaso] cuánto falta para hacerlo; [progreso] va de 0 a 1.
     */
    data class Caminando(val tramo: Int, val metros: Int, val paso: Int = -1, val metrosPaso: Int = 0, val progreso: Float = 0f) : Guia
    /** En la parada o estación donde tomas el bus, esperándolo. */
    data class EnParada(val tramo: Int) : Guia
    /**
     * En el bus. [ultima] es la última parada por la que pasaste (índice en [Leg.paradas]) y [enParada] si estás detenido en
     * ella; [faltan] cuenta las paradas que quedan hasta la tuya (incluida).
     */
    data class EnBus(val tramo: Int, val ultima: Int, val total: Int, val enParada: Boolean, val faltan: Int, val bajarYa: Boolean) : Guia
    data object Llegaste : Guia
}

/** Tramo en el que vas, sea a pie, esperando o en el bus. */
val Guia.tramoActual: Int? get() = when (this) {
    is Guia.Caminando -> tramo
    is Guia.EnParada -> tramo
    is Guia.EnBus -> tramo
    else -> null
}

/** Todas las paradas de un tramo en bus: donde lo tomas, las intermedias y donde te bajas. */
fun Leg.paradas(): List<LegStop> = listOf(from) + stops + to

/** Lo que hay que avisar con vibración y notificación al cambiar de paso. */
enum class AvisoGuia { NINGUNO, TOMA_EL_BUS, PREPARATE, BAJATE, BAJA_AQUI }

/**
 * Viaje guiado como en Moovit: paso a paso, parada a parada y estación a estación. Sigue funcionando con la pantalla
 * apagada (servicio en primer plano con notificación), avanza solo hacia adelante (el GPS que salta no te devuelve) y
 * vibra cuando te toca tomar el bus, prepararte y bajarte.
 */
object GuiaViaje {
    var viaje by mutableStateOf<Itinerary?>(null); private set
    var guia by mutableStateOf<Guia>(Guia.Esperando); private set
    private var tramo = 0
    /** Última parada alcanzada en el tramo en bus actual y último punto del trazado alcanzado a pie. */
    private var ultima = 0
    private var cursor = 0
    private val avisados = mutableSetOf<String>()
    private var largos: Pair<Int, DoubleArray>? = null

    fun empezar(c: Context, it: Itinerary) {
        viaje = it; tramo = 0; ultima = 0; cursor = 0; largos = null; avisados.clear(); guia = Guia.Esperando
        ContextCompat.startForegroundService(c, Intent(c, GuiaServicio::class.java))
        Ubicacion.actual?.let { yo -> actualizar(yo) }
    }

    fun terminar(c: Context) {
        viaje = null; guia = Guia.Esperando
        c.stopService(Intent(c, GuiaServicio::class.java))
    }

    fun guiando(it: Itinerary) = viaje?.id == it.id

    private fun d(a: Pair<Double, Double>, s: LegStop) = Ubicacion.metros(a, s.lat to s.lon)
    private fun d(a: LegStop, b: LegStop) = Ubicacion.metros(a.lat to a.lon, b.lat to b.lon)

    /** Calcula la guía con una nueva posición y dice qué avisar. */
    internal fun actualizar(yo: Pair<Double, Double>): AvisoGuia {
        val it = viaje ?: return AvisoGuia.NINGUNO
        val ultimo = it.legs.last()
        if (d(yo, ultimo.to) < 120 && (ultimo.mode != "transit" || ultimo.paradas().size <= 2 || ultima >= ultimo.paradas().lastIndex - 1)) { guia = Guia.Llegaste; return AvisoGuia.NINGUNO }
        // Solo hacia adelante: pasas al siguiente tramo al llegar al final del actual (o si ya estás más cerca del siguiente).
        val antes = tramo
        while (tramo < it.legs.lastIndex) {
            val l = it.legs[tramo]
            val sig = it.legs[tramo + 1]
            val alFinal = d(yo, l.to)
            if (alFinal < 80 || d(yo, sig.to) < alFinal && alFinal < 400) { tramo++; ultima = 0; cursor = 0 } else break
        }
        val l = it.legs[tramo]
        var aviso = if (tramo != antes && it.legs[antes].mode == "transit" && avisados.add("aqui$antes")) AvisoGuia.BAJA_AQUI else AvisoGuia.NINGUNO
        if (l.mode != "transit") { guia = caminando(l, yo); return aviso }

        val ps = l.paradas()
        // La parada más cercana desde la última alcanzada (nunca hacia atrás).
        val n = (ultima..ps.lastIndex).minByOrNull { k -> d(yo, ps[k]) } ?: ultima
        val enParada = d(yo, ps[n]) < 70
        ultima = maxOf(ultima, when {
            enParada -> n
            n < ps.lastIndex && d(yo, ps[n + 1]) < d(ps[n], ps[n + 1]) -> n
            else -> n - 1
        }.coerceAtLeast(0))
        val total = ps.lastIndex
        val faltan = (total - ultima).coerceAtLeast(0)
        if (ultima == 0 && (enParada || d(yo, ps[0]) < 120)) {
            guia = Guia.EnParada(tramo)
            if (aviso == AvisoGuia.NINGUNO && avisados.add("toma$tramo")) aviso = AvisoGuia.TOMA_EL_BUS
            return aviso
        }
        val bajar = faltan <= 1
        guia = Guia.EnBus(tramo, ultima, total, enParada && n == ultima, faltan, bajar)
        if (aviso != AvisoGuia.NINGUNO) return aviso
        return when {
            bajar && avisados.add("bajar$tramo") -> AvisoGuia.BAJATE
            faltan == 2 && total > 3 && avisados.add("prep$tramo") -> AvisoGuia.PREPARATE
            else -> AvisoGuia.NINGUNO
        }
    }

    /** A pie: dónde vas sobre el trazado y cuál es el próximo giro. */
    private fun caminando(l: Leg, yo: Pair<Double, Double>): Guia.Caminando {
        val recto = d(yo, l.to).toInt()
        val path = l.path.filter { p -> p.size >= 2 }
        if (path.size < 2) return Guia.Caminando(tramo, recto)
        val acum = largos?.takeIf { (t, _) -> t == tramo }?.second ?: DoubleArray(path.size).also { a ->
            for (i in 1 until path.size) a[i] = a[i - 1] + Ubicacion.metros(path[i - 1][0] to path[i - 1][1], path[i][0] to path[i][1])
            largos = tramo to a
        }
        val cerca = (cursor until path.size).minByOrNull { i -> Ubicacion.metros(yo, path[i][0] to path[i][1]) } ?: cursor
        cursor = maxOf(cursor, cerca)
        val largo = acum.last().coerceAtLeast(1.0)
        val hecho = acum[cursor]
        val metros = (largo - hecho).toInt().coerceAtMost(maxOf(recto, 0) + 300).coerceAtLeast(recto.coerceAtMost(30))
        val pasos = l.pasosLimpios()
        val suma = pasos.sumOf { s -> s.metres }.toDouble()
        var paso = -1; var metrosPaso = 0
        if (pasos.size > 1 && suma > 0) {
            val escala = largo / suma
            var inicio = 0.0
            for (j in pasos.indices) {
                if (j > 0 && inicio > hecho + 5) { paso = j; metrosPaso = (inicio - hecho).toInt(); break }
                inicio += pasos[j].metres * escala
            }
        }
        return Guia.Caminando(tramo, metros, paso, metrosPaso, (hecho / largo).toFloat().coerceIn(0f, 1f))
    }

    fun texto(it: Itinerary, g: Guia): Pair<String, String> = when (g) {
        Guia.Esperando -> "Buscando tu ubicación…" to "Activa la ubicación para que te guiemos paso a paso."
        Guia.Llegaste -> "Llegaste" to "¡Buen viaje!"
        is Guia.Caminando -> it.legs[g.tramo].let { l ->
            val p = l.pasosLimpios().getOrNull(g.paso)
            if (p != null) "En ${g.metrosPaso} m, ${(GIROS_GUIA[p.turn] ?: "sigue derecho").replaceFirstChar { c -> c.lowercase() }}" to
                listOfNotNull(p.street.takeIf { s -> s.isNotBlank() }?.let { s -> "por $s" }, "${g.metros} m hasta ${l.to.name}").joinToString(" · ")
            else "Camina hasta ${l.to.name}" to "Faltan ${g.metros} m"
        }
        is Guia.EnParada -> it.legs[g.tramo].let { l ->
            "Espera el ${codigoVisible(l.code, l.headsign)}" to "En ${l.from.name} · bájate en ${l.to.name} (${l.paradas().lastIndex} paradas)"
        }
        is Guia.EnBus -> it.legs[g.tramo].let { l ->
            val ps = l.paradas()
            when {
                g.enParada && g.faltan == 0 -> "Bájate aquí" to l.to.name
                g.bajarYa -> "Bájate en la próxima" to l.to.name
                g.enParada -> "En ${ps[g.ultima].name}" to "Siguiente: ${ps[g.ultima + 1].name} · faltan ${g.faltan} paradas"
                else -> "Próxima: ${ps[g.ultima + 1].name}" to "${if (g.faltan == 2) "Prepárate: " else ""}faltan ${g.faltan} paradas · bájate en ${l.to.name}"
            }
        }
    }
}

/** Cómo se dice cada giro de la ruta a pie (OpenTripPlanner). */
val GIROS_GUIA = mapOf(
    "CONTINUE" to "Sigue derecho", "LEFT" to "Gira a la izquierda", "RIGHT" to "Gira a la derecha",
    "SLIGHTLY_LEFT" to "Ve un poco a la izquierda", "SLIGHTLY_RIGHT" to "Ve un poco a la derecha",
    "HARD_LEFT" to "Gira cerrado a la izquierda", "HARD_RIGHT" to "Gira cerrado a la derecha",
    "UTURN_LEFT" to "Da la vuelta", "UTURN_RIGHT" to "Da la vuelta", "CIRCLE_CLOCKWISE" to "Toma la glorieta",
    "CIRCLE_COUNTERCLOCKWISE" to "Toma la glorieta", "ELEVATOR" to "Toma el ascensor", "STAIRS" to "Toma las escaleras",
)

/** Servicio en primer plano: GPS con la pantalla apagada y una notificación que se actualiza con la guía. */
class GuiaServicio : Service() {
    private var escucha: LocationListener? = null
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        crearCanales(this)
        val tipo = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTI, notificacion("Viaje en curso", "Buscando tu ubicación…", false), tipo)
        if (escucha == null && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val l = LocationListener { loc: Location -> alMoverse(loc.latitude to loc.longitude) }
            escucha = l
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) runCatching { lm.requestLocationUpdates(p, 4_000L, 10f, l, Looper.getMainLooper()) }
        }
        return START_STICKY
    }

    private fun alMoverse(yo: Pair<Double, Double>) {
        Ubicacion.poner(yo)
        val it = GuiaViaje.viaje ?: run { stopSelf(); return }
        val aviso = GuiaViaje.actualizar(yo)
        val (t, d) = GuiaViaje.texto(it, GuiaViaje.guia)
        val fuerte = aviso == AvisoGuia.BAJATE || aviso == AvisoGuia.BAJA_AQUI
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(t, d, aviso != AvisoGuia.NINGUNO, progreso = progreso(it, GuiaViaje.guia)))
        if (aviso != AvisoGuia.NINGUNO) vibrar(fuerte)
        if (GuiaViaje.guia is Guia.Llegaste) { getSystemService(NotificationManager::class.java).notify(NOTI + 1, notificacion("Llegaste", it.legs.last().to.name, true, fija = false)); GuiaViaje.terminar(this) }
    }

    private fun vibrar(fuerte: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(if (fuerte) longArrayOf(0, 400, 200, 400, 200, 600) else longArrayOf(0, 180, 120, 180), -1))
    }

    /** Barra de progreso de la notificación: paradas del tramo en bus o trecho recorrido a pie. */
    private fun progreso(it: Itinerary, g: Guia): Pair<Int, Int>? = when (g) {
        is Guia.EnBus -> g.total to g.ultima
        is Guia.EnParada -> it.legs[g.tramo].paradas().lastIndex to 0
        is Guia.Caminando -> 100 to (g.progreso * 100).toInt()
        else -> null
    }

    private fun notificacion(titulo: String, texto: String, alerta: Boolean, fija: Boolean = true, progreso: Pair<Int, Int>? = null): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, if (alerta) CANAL_ALERTA else CANAL_VIAJE)
            .setSmallIcon(R.drawable.ic_noti).setContentTitle(titulo).setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .apply { progreso?.let { (max, va) -> setProgress(max.coerceAtLeast(1), va.coerceIn(0, max.coerceAtLeast(1)), false) } }
            .setOngoing(fija).setOnlyAlertOnce(!alerta).setContentIntent(abrir)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(if (alerta) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        escucha?.let { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        escucha = null
        super.onDestroy()
    }

    companion object {
        const val NOTI = 4101
        const val CANAL_VIAJE = "viaje"
        const val CANAL_ALERTA = "bajarse"
        fun crearCanales(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CANAL_VIAJE, "Viaje en curso", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CANAL_ALERTA, "Avisos para bajarte", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel("recordatorios", "Recordatorios de salida", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
