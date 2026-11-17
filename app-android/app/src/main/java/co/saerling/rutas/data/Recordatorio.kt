package co.saerling.rutas.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import co.saerling.rutas.MainActivity
import co.saerling.rutas.R

/** «Avísame 5 min antes de salir»: una alarma del sistema que suena aunque la app esté cerrada. Se puede quitar. */
class Recordatorio : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        GuiaServicio.crearCanales(c)
        val id = i.getIntExtra("id", 1)
        programados.entries.removeAll { it.value.first == id }
        val abrir = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "recordatorios").setSmallIcon(R.drawable.ic_noti)
            .setContentTitle("Ya casi es hora de salir").setContentText(i.getStringExtra("texto") ?: "Sal ahora para alcanzar tu bus.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(abrir).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    companion object {
        /** Los avisos puestos en esta sesión, por clave (el viaje): id de la alarma y hora a la que suena. */
        val programados = mutableStateMapOf<String, Pair<Int, Long>>()

        private fun pi(c: Context, id: Int, texto: String = "") = PendingIntent.getBroadcast(c, id, Intent(c, Recordatorio::class.java).putExtra("texto", texto).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Programa el aviso 5 min antes de [salidaMs]. Devuelve la hora del aviso, o null si ya pasó. */
        fun programar(c: Context, salidaMs: Long, texto: String, clave: String? = null): Long? {
            val cuando = salidaMs - 5 * 60_000
            if (cuando <= System.currentTimeMillis()) return null
            val id = (salidaMs / 1000 % 100_000).toInt()
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi(c, id, texto))
            if (clave != null) programados[clave] = id to cuando
            return cuando
        }

        fun quitar(c: Context, clave: String) {
            val (id, _) = programados.remove(clave) ?: return
            c.getSystemService(AlarmManager::class.java).cancel(pi(c, id))
        }
    }
}
