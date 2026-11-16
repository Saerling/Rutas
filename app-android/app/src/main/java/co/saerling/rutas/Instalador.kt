package co.saerling.rutas

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Descarga el APK dentro de la app y lo instala con el instalador de Android (una sesión de PackageInstaller), sin navegador. */
object Instalador {
    private const val ACCION = "co.saerling.rutas.INSTALACION"

    suspend fun descargar(url: String, destino: File, aceptar: String = "application/vnd.android.package-archive", progreso: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            var actual = URL(url)
            var c: HttpURLConnection
            // GitHub manda a su CDN con una redirección: se sigue a mano para no perder la cabecera Accept.
            while (true) {
                c = (actual.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", aceptar)
                    setRequestProperty("User-Agent", "Rutas")
                }
                if (c.responseCode in 300..399) { actual = URL(actual, c.getHeaderField("Location")); c.disconnect() } else break
            }
            if (c.responseCode !in 200..299) error("El servidor respondió ${c.responseCode}")
            val total = c.contentLengthLong.coerceAtLeast(1)
            c.inputStream.use { entrada ->
                destino.outputStream().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    var leidos = 0L
                    var ultimo = -1
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        leidos += n
                        val p = (leidos * 100 / total).toInt().coerceIn(0, 100)
                        if (p != ultimo) { ultimo = p; progreso(p) }
                    }
                }
            }
            c.disconnect()
        }

    /** Abre la sesión de instalación. Android muestra su confirmación; [alFallar] recibe el motivo si no se instala. */
    suspend fun instalar(ctx: Context, apk: File, alFallar: (String?) -> Unit) = withContext(Dispatchers.IO) {
        val instalador = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sesion ->
            sesion.openWrite("rutas.apk", 0, apk.length()).use { salida -> apk.inputStream().use { it.copyTo(salida) }; sesion.fsync(salida) }
            val receptor = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            @Suppress("DEPRECATION")
                            val confirmar = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                        }
                        PackageInstaller.STATUS_SUCCESS -> runCatching { c.unregisterReceiver(this) }
                        else -> {
                            runCatching { c.unregisterReceiver(this) }
                            alFallar(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(ctx, receptor, IntentFilter(ACCION), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ACCION).setPackage(ctx.packageName), flags)
            sesion.commit(pi.intentSender)
        }
    }
}
