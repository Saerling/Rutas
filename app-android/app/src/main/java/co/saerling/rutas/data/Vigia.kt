package co.saerling.rutas.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.saerling.rutas.MainActivity
import co.saerling.rutas.R
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Un bus que estás esperando en un paradero: te avisamos cuando le falten [antes] minutos. */
@Serializable data class Espera(val paradero: String, val nombre: String, val codigo: String, val sistema: String? = null, val antes: Int = 3, val desde: Long = System.currentTimeMillis()) {
    val clave get() = "$paradero|$codigo"
}

/**
 * La campana de Moovit, pero en vivo: en vez de una alarma a una hora fija, un servicio pequeño pregunta cada 20 s
 * cuánto le falta al bus y avisa cuando está por llegar. Se puede apagar en cualquier momento (tocando otra vez la
 * campana, desde Cerca o desde la notificación). Se apaga solo si el bus ya pasó o tras 90 minutos.
 */
object Vigia {
    private val json = Json { ignoreUnknownKeys = true }
    val esperas = mutableStateListOf<Espera>()
    /** Minutos que faltan para cada espera, según la última consulta. */
    val faltan = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    private var prefs: android.content.SharedPreferences? = null

    fun iniciar(c: Context) {
        if (prefs != null) return
        prefs = c.getSharedPreferences("vigia", Context.MODE_PRIVATE)
        val viejas = runCatching { json.decodeFromString<List<Espera>>(prefs!!.getString("v1", null) ?: "[]") }.getOrDefault(emptyList())
        esperas.addAll(viejas.filter { System.currentTimeMillis() - it.desde < 90 * 60_000 })
        if (esperas.isNotEmpty()) arrancar(c)
    }

    private fun guardar() { prefs?.edit()?.putString("v1", json.encodeToString(esperas.toList()))?.apply() }

    fun esperando(paradero: String, codigo: String) = esperas.any { it.paradero == paradero && it.codigo == codigo }

    /** Pone o quita la espera. Devuelve true si quedó puesta. */
    fun alternar(c: Context, e: Espera): Boolean {
        val ya = esperas.firstOrNull { it.clave == e.clave }
        if (ya != null) quitar(c, ya.clave) else { esperas.add(e); guardar(); arrancar(c) }
        return ya == null
    }

    fun quitar(c: Context, clave: String) {
        esperas.removeAll { it.clave == clave }; faltan.remove(clave); guardar()
        if (esperas.isEmpty()) c.stopService(Intent(c, VigiaServicio::class.java))
        else c.startService(Intent(c, VigiaServicio::class.java)) // refresca la notificación
    }

    private fun arrancar(c: Context) = runCatching { ContextCompat.startForegroundService(c, Intent(c, VigiaServicio::class.java)) }
}

class VigiaServicio : Service() {
    private val escena = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bucle: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Vigia.iniciar(applicationContext)
        intent?.getStringExtra("quitar")?.let { Vigia.quitar(applicationContext, it) }
        crearCanal(this)
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTI, resumen(), tipo) }
        if (Vigia.esperas.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        if (bucle?.isActive != true) bucle = escena.launch { while (isActive && Vigia.esperas.isNotEmpty()) { revisar(); delay(20_000) }; stopSelf() }
        return START_STICKY
    }

    private suspend fun revisar() {
        val nm = getSystemService(NotificationManager::class.java)
        for (e in Vigia.esperas.toList()) {
            if (System.currentTimeMillis() - e.desde > 90 * 60_000) { Vigia.quitar(this, e.clave); continue }
            val det = runCatching { withContext(EnFondo) { Api.paradero(e.paradero) } }.getOrNull() ?: continue
            val llegada = det.arrivals.filter { it.code == e.codigo }.minByOrNull { it.minutes ?: Int.MAX_VALUE }
            val m = llegada?.minutes
            if (m == null) continue
            Vigia.faltan[e.clave] = m
            if (m <= e.antes) {
                nm.notify(e.clave.hashCode(), alerta(e, m, llegada.basis != "scheduled"))
                vibrar()
                Vigia.quitar(this, e.clave)
            }
        }
        if (Vigia.esperas.isNotEmpty()) nm.notify(NOTI, resumen())
    }

    private fun abrir() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun resumen(): Notification {
        val lista = Vigia.esperas.toList()
        val texto = lista.joinToString(" · ") { e -> "${e.codigo} ${Vigia.faltan[e.clave]?.let { "en $it min" } ?: "…"}" }
        val b = NotificationCompat.Builder(this, CANAL_ESPERA).setSmallIcon(R.drawable.ic_noti)
            .setContentTitle(if (lista.size == 1) "Esperando el ${lista[0].codigo} en ${lista[0].nombre}" else "Esperando ${lista.size} buses")
            .setContentText(texto.ifBlank { "Te avisamos cuando esté por llegar" }).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(abrir()).setPriority(NotificationCompat.PRIORITY_LOW)
        lista.take(2).forEach { e ->
            val pi = PendingIntent.getForegroundService(this, e.clave.hashCode(), Intent(this, VigiaServicio::class.java).putExtra("quitar", e.clave), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, if (lista.size == 1) "Ya no me avises" else "Quitar ${e.codigo}", pi)
        }
        return b.build()
    }

    private fun alerta(e: Espera, m: Int, vivo: Boolean) = NotificationCompat.Builder(this, GuiaServicio.CANAL_ALERTA).setSmallIcon(R.drawable.ic_noti)
        .setContentTitle(if (m <= 0) "El ${e.codigo} está llegando" else "El ${e.codigo} llega en $m min")
        .setContentText("A ${e.nombre}${if (vivo) " · en vivo" else " · según horario"}")
        .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).setContentIntent(abrir()).build()

    private fun vibrar() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    override fun onDestroy() { escena.cancel(); super.onDestroy() }

    companion object {
        const val NOTI = 4201
        const val CANAL_ESPERA = "espera"
        fun crearCanal(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CANAL_ESPERA, "Buses que esperas", NotificationManager.IMPORTANCE_LOW))
            GuiaServicio.crearCanales(c)
        }
    }
}
