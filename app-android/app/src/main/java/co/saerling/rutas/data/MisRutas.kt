package co.saerling.rutas.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import co.saerling.rutas.MainActivity
import co.saerling.rutas.R

/**
 * Las rutas que usas: las guardadas, las de los viajes que empiezas, las que abres y las de la campana, con cuántas
 * veces. Sirve para avisarte cuando una novedad o un boletín de TransMilenio las afecta. Solo en el teléfono.
 */
object MisRutas {
    private var prefs: android.content.SharedPreferences? = null
    val usos = mutableStateMapOf<String, Int>()
    private val avisados = mutableSetOf<String>()

    fun iniciar(c: Context) {
        if (prefs != null) return
        prefs = c.getSharedPreferences("misrutas", Context.MODE_PRIVATE)
        prefs!!.getString("usos", "")!!.split(";").filter { it.contains("=") }.forEach { val (k, v) = it.split("="); usos[k] = v.toIntOrNull() ?: 1 }
        avisados.addAll(prefs!!.getStringSet("avisados", emptySet())!!)
    }

    private fun guardar() { prefs?.edit()?.putString("usos", usos.entries.joinToString(";") { "${it.key}=${it.value}" })?.apply() }

    /** Suma un uso de la ruta (por ejemplo «K309·B309» o «DK211»). */
    fun usar(codigo: String?, peso: Int = 1) {
        val c = codigo?.trim()?.uppercase()?.replace(" ", "")?.takeIf { it.isNotBlank() && it != "?" } ?: return
        usos[c] = (usos[c] ?: 0) + peso
        guardar()
    }

    /** Todos los códigos con que se puede nombrar una ruta: DK211 → DK211, D211, K211; «K211·D211» → K211, D211. */
    fun fichas(codigo: String): Set<String> {
        val out = mutableSetOf<String>()
        for (parte in codigo.uppercase().replace(" ", "").split("·", "-·", "/")) {
            if (parte.isBlank()) continue
            out += parte
            Regex("^([A-Z])([A-Z])(\\d{2,4}[A-Z]?)$").find(parte)?.destructured?.let { (a, b, n) -> out += "$a$n"; out += "$b$n" }
        }
        return out
    }

    /** Las fichas de tus rutas (con al menos 2 usos, o guardadas). */
    fun mias(): Set<String> =
        (usos.filter { it.value >= 2 }.keys + Guardados.rutas.map { it.codigo }).flatMap { fichas(it) }.toSet()

    /** Las rutas tuyas que menciona una lista de códigos. */
    fun afectadas(codigos: List<String>): List<String> {
        val m = mias()
        if (m.isEmpty()) return emptyList()
        return codigos.filter { c -> fichas(c).any { it in m } }.distinct()
    }

    /** Las estaciones de tus paraderos guardados que menciona una lista de nombres. */
    fun estacionesAfectadas(nombres: List<String>): List<String> {
        val mias = Guardados.paraderos.filter { it.estacion }.map { it.nombre.lowercase() }
        return nombres.filter { n -> mias.any { it.contains(n.lowercase()) || n.lowercase().contains(it) } }
    }

    /** Notifica una vez cada novedad que toca tus rutas o estaciones. */
    fun avisar(c: Context, id: String, titulo: String, texto: String) {
        if (!avisados.add(id)) return
        prefs?.edit()?.putStringSet("avisados", avisados.toSet())?.apply()
        GuiaServicio.crearCanales(c)
        val abrir = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("atajo", "novedades"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "recordatorios").setSmallIcon(R.drawable.ic_noti).setContentTitle(titulo).setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto)).setAutoCancel(true).setContentIntent(abrir).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n) }
    }
}
