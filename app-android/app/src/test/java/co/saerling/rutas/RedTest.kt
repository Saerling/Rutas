package co.saerling.rutas

import co.saerling.rutas.data.ClaveFondo
import co.saerling.rutas.data.EnFondo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** El marcador de consultas en segundo plano debe poder crearse y encontrarse (si falla, toda la app queda «sin conexión»). */
class RedTest {
    @Test fun marcadorDeFondo() {
        assertNotNull((kotlin.coroutines.EmptyCoroutineContext + EnFondo)[ClaveFondo])
        assertNull(kotlin.coroutines.EmptyCoroutineContext[ClaveFondo])
    }
}
