package co.saerling.rutas.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Ubicación con el LocationManager de Android (sin servicios de Google). Solo con la app abierta; no se guarda. */
object Ubicacion {
    var actual by mutableStateOf<Pair<Double, Double>?>(null); private set
    var permiso by mutableStateOf(false); private set

    fun enBogota(lat: Double, lon: Double) = lat in 4.4..4.85 && lon in -74.3..-73.95
    val BOGOTA = 4.6486 to -74.0628

    fun tienePermiso(c: Context) = ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private var escucha: LocationListener? = null

    /** El servicio de viaje guiado también actualiza la ubicación (con la pantalla apagada). */
    fun poner(p: Pair<Double, Double>) { actual = p }

    /** Hay algún proveedor de ubicación encendido (GPS o red). */
    fun gpsEncendido(c: Context): Boolean {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Empieza a seguir la ubicación (cada 5 s o 20 m). Llamar de nuevo no duplica nada. */
    @SuppressLint("MissingPermission")
    fun empezar(c: Context) {
        permiso = tienePermiso(c)
        if (!permiso || escucha != null) return
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val mejor = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        mejor?.let { actual = it.latitude to it.longitude }
        val l = LocationListener { loc: Location -> actual = loc.latitude to loc.longitude }
        var alguno = false
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) alguno = runCatching { lm.requestLocationUpdates(p, 5_000L, 20f, l, Looper.getMainLooper()); true }.getOrDefault(false) || alguno
        }
        // Si no había proveedor encendido no se marca como escuchando: al volver con el GPS prendido se intenta otra vez.
        if (alguno) escucha = l
    }

    fun parar(c: Context) {
        val l = escucha ?: return
        (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l)
        escucha = null
    }

    fun metros(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = FloatArray(1)
        Location.distanceBetween(a.first, a.second, b.first, b.second, r)
        return r[0].toDouble()
    }
}
