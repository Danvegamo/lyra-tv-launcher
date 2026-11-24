package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GustosTest {
    private val bueno = """{"region":"CO","idioma":"es-MX","proveedores":{"_nota":"x","netflix":{"nombre":"Netflix","ids":[8],"paquete":"com.netflix.ninja"}},
        "anclas":[{"titulo":"Dark","peso":3}],"filas":[{"id":"f1","titulo":"Fila 1","tipo":"discover","discover":{"generos":[18]}}]}"""

    @Test fun parseaUnPerfilNormal() {
        val g = Gustos.parsear(bueno)
        assertEquals("CO", g.region)
        assertEquals(listOf("netflix"), g.proveedores.map { it.clave })
        assertEquals(1, g.anclas.size)
        assertEquals(listOf(18), g.filas.single().discover?.generos)
    }

    @Test fun unaEntradaMalaNoTumbaElResto() {
        val texto = """{"proveedores":{"netflix":{"ids":[8],"paquete":"p"},"roto":"no soy un objeto"},
            "anclas":[{"titulo":"Bien"},{"sin_titulo":true},"cadena"],
            "filas":[{"id":"a","titulo":"A"},{"titulo":"sin id"},{"id":"b","titulo":"B"}]}"""
        val g = Gustos.parsear(texto)
        assertEquals(listOf("netflix"), g.proveedores.map { it.clave })
        assertEquals(listOf("Bien"), g.anclas.map { it.titulo })
        assertEquals(listOf("a", "b"), g.filas.map { it.id })
    }

    @Test fun jsonExternoMalFormadoUsaElRespaldoSinLanzar() {
        val avisos = ArrayList<String>()
        val (texto, g) = Gustos.cargar(externo = { "{esto no es json" }, respaldo = { bueno }, aviso = { avisos += it })
        assertEquals(bueno, texto)
        assertEquals(listOf("netflix"), g.proveedores.map { it.clave })
        assertEquals(1, avisos.size)
    }

    @Test fun externoSinProveedoresTambienCaeAlRespaldo() {
        val (texto, _) = Gustos.cargar(externo = { """{"proveedores":{},"filas":[]}""" }, respaldo = { bueno })
        assertEquals(bueno, texto)
    }

    @Test fun externoValidoSeUsa() {
        val avisos = ArrayList<String>()
        val externo = bueno.replace("\"CO\"", "\"MX\"")
        val (texto, g) = Gustos.cargar(externo = { externo }, respaldo = { error("no debe leerse") }, aviso = { avisos += it })
        assertEquals(externo, texto)
        assertEquals("MX", g.region)
        assertTrue(avisos.isEmpty())
    }

    @Test fun sinExternoUsaElRespaldo() {
        val (texto, _) = Gustos.cargar(externo = { null }, respaldo = { bueno })
        assertEquals(bueno, texto)
    }
}
