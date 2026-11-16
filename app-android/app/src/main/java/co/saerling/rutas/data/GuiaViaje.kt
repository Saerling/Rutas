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
    data class Caminando(val tramo: Int, val metros: Int) : Guia
    data class EnBus(val tramo: Int, val faltan: Int, val bajarYa: Boolean) : Guia
    data object Llegaste : Guia
}

/**
 * Viaje guiado como en Moovit: sigue funcionando con la pantalla apagada (servicio en primer plano con notificación),
 * avanza de tramo en tramo solo hacia adelante y vibra con una alerta cuando te toca bajarte.
 */
object GuiaViaje {
    var viaje by mutableStateOf<Itinerary?>(null); private set
    var guia by mutableStateOf<Guia>(Guia.Esperando); private set
    private var tramo = 0
    private val avisados = mutableSetOf<Int>()

    fun empezar(c: Context, it: Itinerary) {
        viaje = it; tramo = 0; avisados.clear(); guia = Guia.Esperando
        ContextCompat.startForegroundService(c, Intent(c, GuiaServicio::class.java))
    }

    fun terminar(c: Context) {
        viaje = null; guia = Guia.Esperando
        c.stopService(Intent(c, GuiaServicio::class.java))
    }

    fun guiando(it: Itinerary) = viaje?.id == it.id

    /** Calcula la guía con una nueva posición. Devuelve true si hay que avisar «bájate en la próxima». */
    internal fun actualizar(yo: Pair<Double, Double>): Boolean {
        val it = viaje ?: return false
        val ultimo = it.legs.last()
        if (Ubicacion.metros(yo, ultimo.to.lat to ultimo.to.lon) < 120) { guia = Guia.Llegaste; return false }
        // Solo hacia adelante: pasas al siguiente tramo al llegar al final del actual (o si ya estás más cerca del siguiente).
        while (tramo < it.legs.lastIndex) {
            val l = it.legs[tramo]
            val sig = it.legs[tramo + 1]
            if (Ubicacion.metros(yo, l.to.lat to l.to.lon) < 80 || Ubicacion.metros(yo, sig.to.lat to sig.to.lon) < Ubicacion.metros(yo, l.to.lat to l.to.lon) && Ubicacion.metros(yo, l.to.lat to l.to.lon) < 400) tramo++ else break
        }
        val l = it.legs[tramo]
        if (l.mode != "transit") { guia = Guia.Caminando(tramo, Ubicacion.metros(yo, l.to.lat to l.to.lon).toInt()); return false }
        val todas = l.stops + l.to
        val cercana = todas.indices.minByOrNull { k -> Ubicacion.metros(yo, todas[k].lat to todas[k].lon) } ?: 0
        val pasada = if (Ubicacion.metros(yo, todas[cercana].lat to todas[cercana].lon) < 120) cercana + 1 else cercana
        val faltan = (todas.size - pasada).coerceAtLeast(0)
        val bajar = faltan <= 1
        guia = Guia.EnBus(tramo, faltan, bajar)
        return bajar && avisados.add(tramo)
    }

    fun texto(it: Itinerary, g: Guia): Pair<String, String> = when (g) {
        Guia.Esperando -> "Buscando tu ubicación…" to "Activa la ubicación para que te avisemos."
        Guia.Llegaste -> "Llegaste" to "¡Buen viaje!"
        is Guia.Caminando -> "Camina hasta ${it.legs[g.tramo].to.name}" to "Faltan ${g.metros} m"
        is Guia.EnBus -> it.legs[g.tramo].let { l ->
            if (g.bajarYa) "Bájate en la próxima" to l.to.name
            else "${g.faltan} paradas" to "En el ${codigoVisible(l.code, l.headsign)} · bájate en ${l.to.name}"
        }
    }
}

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
        val avisar = GuiaViaje.actualizar(yo)
        val (t, d) = GuiaViaje.texto(it, GuiaViaje.guia)
        getSystemService(NotificationManager::class.java).notify(NOTI, notificacion(t, d, avisar))
        if (avisar) vibrar()
        if (GuiaViaje.guia is Guia.Llegaste) { getSystemService(NotificationManager::class.java).notify(NOTI + 1, notificacion("Llegaste", it.legs.last().to.name, true, fija = false)); GuiaViaje.terminar(this) }
    }

    private fun vibrar() {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
    }

    private fun notificacion(titulo: String, texto: String, alerta: Boolean, fija: Boolean = true): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, if (alerta) CANAL_ALERTA else CANAL_VIAJE)
            .setSmallIcon(R.drawable.ic_noti).setContentTitle(titulo).setContentText(texto)
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
