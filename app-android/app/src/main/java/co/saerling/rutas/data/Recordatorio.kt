package co.saerling.rutas.data

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import co.saerling.rutas.MainActivity
import co.saerling.rutas.R

/** «Avísame 5 min antes de salir»: una alarma del sistema que suena aunque la app esté cerrada. */
class Recordatorio : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        GuiaServicio.crearCanales(c)
        val abrir = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "recordatorios").setSmallIcon(R.drawable.ic_noti)
            .setContentTitle("Ya casi es hora de salir").setContentText(i.getStringExtra("texto") ?: "Sal ahora para alcanzar tu bus.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(abrir).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(i.getIntExtra("id", 1), n) }
    }

    companion object {
        /** Programa el aviso 5 min antes de [salidaMs]. Devuelve la hora del aviso, o null si ya pasó. */
        fun programar(c: Context, salidaMs: Long, texto: String): Long? {
            val cuando = salidaMs - 5 * 60_000
            if (cuando <= System.currentTimeMillis()) return null
            val id = (salidaMs / 1000 % 100_000).toInt()
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, Recordatorio::class.java).putExtra("texto", texto).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            return cuando
        }
    }
}
