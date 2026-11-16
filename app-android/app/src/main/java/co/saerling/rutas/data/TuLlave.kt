package co.saerling.rutas.data

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lee la tarjeta TuLlave por NFC (lo mismo que hacía Paradero): saldo, número y últimos movimientos.
 * El número no sale del teléfono. Los comandos y el formato vienen de modules/tullave y src/transit/tullave.ts.
 */
object TuLlave {
    enum class Tipo { VIAJE, RECARGA, RECARGA_ANULADA, OTRO }
    data class Movimiento(val tipo: Tipo, val fecha: String?, val valor: Int, val saldoDespues: Int)
    data class Tarjeta(val numero: String, val saldo: Int, val movimientos: List<Movimiento>)
    sealed interface Lectura { data class Ok(val t: Tarjeta) : Lectura; data object NoEsTuLlave : Lectura; data object SeSolto : Lectura; data object SinNfc : Lectura }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val SELECCIONAR_APP = bytes(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD4, 0x10, 0x00, 0x00, 0x03, 0x00, 0x01)
    private val SALDO = bytes(0x90, 0x4C, 0x00, 0x00, 0x04)
    private val MONEDERO = bytes(0x00, 0xB0, 0x86, 0x00, 0x42)
    private val SELECCIONAR_TARJETA = bytes(0x00, 0xA4, 0x00, 0x00, 0x02, 0x42, 0x00)
    private fun registro(n: Int) = bytes(0x00, 0xB2, n, 0x24, 0x2E)

    fun disponible(a: Activity) = NfcAdapter.getDefaultAdapter(a)?.isEnabled == true
    fun tieneNfc(a: Activity) = NfcAdapter.getDefaultAdapter(a) != null

    /** Espera a que acerques la tarjeta y la lee. Se cancela al salir de la pantalla. */
    suspend fun leer(a: Activity): Lectura = suspendCancellableCoroutine { cont ->
        val nfc = NfcAdapter.getDefaultAdapter(a)
        if (nfc == null || !nfc.isEnabled) { cont.resume(Lectura.SinNfc); return@suspendCancellableCoroutine }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfc.enableReaderMode(a, { tag ->
            val r = runCatching {
                val iso = IsoDep.get(tag) ?: return@runCatching Lectura.NoEsTuLlave
                try {
                    iso.timeout = 5000; iso.connect()
                    if (!ok(iso.transceive(SELECCIONAR_APP))) Lectura.NoEsTuLlave
                    else parsear(iso.transceive(SALDO), iso.transceive(MONEDERO), iso.transceive(SELECCIONAR_TARJETA), (1..10).map { runCatching { iso.transceive(registro(it)) }.getOrNull() })
                        ?.let { Lectura.Ok(it) } ?: Lectura.NoEsTuLlave
                } finally { runCatching { iso.close() } }
            }.getOrElse { Lectura.SeSolto }
            runCatching { nfc.disableReaderMode(a) }
            if (cont.isActive) cont.resume(r)
        }, flags, null)
        cont.invokeOnCancellation { runCatching { nfc.disableReaderMode(a) } }
    }

    private fun ok(b: ByteArray) = b.size >= 2 && b[b.size - 2] == 0x90.toByte() && b[b.size - 1] == 0x00.toByte()
    private fun u(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xFF else 0
    private fun be(b: ByteArray, from: Int, to: Int) = (from until to).fold(0L) { n, i -> n * 256 + u(b, i) }
    private fun bcd(x: Int): Int? { val hi = x shr 4; val lo = x and 0x0F; return if (hi > 9 || lo > 9) null else hi * 10 + lo }

    private fun fecha(b: ByteArray): String? {
        val v = listOf(29, 28, 26, 27, 30, 31, 32).map { if (it < b.size) bcd(u(b, it)) else null }
        if (v.any { it == null }) return null
        val dia = v[0]!!; val mes = v[1]!!; val siglo = v[2]!!; val anio = v[3]!!; val h = v[4]!!; val m = v[5]!!; val s = v[6]!!
        if (mes !in 1..12 || dia !in 1..31 || h > 23 || m > 59 || s > 59) return null
        return "%02d%02d-%02d-%02dT%02d:%02d:%02d".format(siglo, anio, mes, dia, h, m, s)
    }

    private fun movimiento(b: ByteArray?): Movimiento? {
        if (b == null || !ok(b) || b.size < 35) return null
        val f = fecha(b); val valor = be(b, 11, 14).toInt(); val despues = be(b, 3, 6).toInt()
        if (f == null && valor == 0 && despues == 0) return null
        val tipo = when (u(b, 0)) { 1 -> Tipo.VIAJE; 2 -> Tipo.RECARGA; 4 -> Tipo.RECARGA_ANULADA; else -> Tipo.OTRO }
        return Movimiento(tipo, f, valor, despues)
    }

    fun parsear(saldo: ByteArray, monedero: ByteArray, tarjeta: ByteArray, registros: List<ByteArray?>): Tarjeta? {
        if (!ok(saldo)) return null
        var valor = be(saldo, 0, 4) + be(saldo, 5, 8)
        if (ok(monedero) && u(monedero, 0) == 0x01) valor -= be(monedero, 4, 8)
        val numero = if (ok(tarjeta)) (8 until 16).joinToString("") { "%02x".format(u(tarjeta, it)) } else ""
        return Tarjeta(numero, valor.toInt(), registros.mapNotNull(::movimiento))
    }

    fun agrupar(numero: String) = numero.chunked(4).joinToString(" ").uppercase()
}
