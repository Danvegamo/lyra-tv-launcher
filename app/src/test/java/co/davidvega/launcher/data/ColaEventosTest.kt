package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ColaEventosTest {
    private fun ev(id: String) = """{"id":"$id","tipo":"ficha","ts":1}"""

    @Test fun lineaCorruptaSeSaltaYElRestoSeEnvia() {
        val a = ColaEventos.analizar(listOf(ev("a"), """{"id":"b","tipo":"fi""", ev("c"), "", "basura", """{"sin_id":1}"""))
        assertEquals(listOf("a", "c"), a.validas.map { it.id })
        assertEquals(3, a.corruptas) // cortada, basura y sin id
    }

    @Test fun colaVaciaOSoloEspacios() {
        val a = ColaEventos.analizar(listOf("", "   "))
        assertEquals(0, a.validas.size); assertEquals(0, a.corruptas)
    }

    @Test fun quitarBorraPorIdYLasCorruptas() {
        val cola = listOf(ev("a"), "{roto", ev("b"), ev("c"))
        assertEquals(listOf(ev("b"), ev("c")), ColaEventos.quitar(cola, setOf("a")))
    }

    @Test fun loAnexadoMientrasSeEnviabaNoSePierde() {
        // El lote fue a, b; mientras tanto se anexó d. Borrar por id conserva d (antes drop(lote.size) dependía de la posición).
        val cola = listOf(ev("a"), ev("b"), ev("d"))
        assertEquals(listOf(ev("d")), ColaEventos.quitar(cola, setOf("a", "b")))
    }

    @Test fun siSeRecortoLaColaNoSeBorraLoNoEnviado() {
        // recortar() ya expulsó a: borrar por id «a» no toca b ni c.
        assertEquals(listOf(ev("b"), ev("c")), ColaEventos.quitar(listOf(ev("b"), ev("c")), setOf("a")))
    }

    @Test fun esperaDuplicaHastaDiezMinutos() {
        var e = 30_000L
        val vistas = ArrayList<Long>()
        repeat(8) { vistas += e; e = ColaEventos.siguienteEspera(e) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L, 600_000L), vistas)
    }
}
