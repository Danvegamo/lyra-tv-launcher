package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class C_SaludTest {
    @Test fun nivelesDeAlmacenamiento() {
        assertEquals(SaludCalculo.Nivel.OK, SaludCalculo.nivelAlmacen(25, 100))
        assertEquals(SaludCalculo.Nivel.AMARILLO, SaludCalculo.nivelAlmacen(20, 100))
        assertEquals(SaludCalculo.Nivel.AMARILLO, SaludCalculo.nivelAlmacen(11, 100))
        assertEquals(SaludCalculo.Nivel.ROJO, SaludCalculo.nivelAlmacen(10, 100))
        assertEquals(SaludCalculo.Nivel.OK, SaludCalculo.nivelAlmacen(0, 0))
    }

    @Test fun meminfo() {
        val m = SaludCalculo.parsearMeminfo("MemTotal:        1720360 kB\nMemAvailable:     596000 kB\nSwapTotal:        524284 kB\nSwapFree:         173000 kB\nraro\n")
        assertEquals(1720360L, m["MemTotal"]); assertEquals(596000L, m["MemAvailable"]); assertEquals(173000L, m["SwapFree"])
    }

    @Test fun pss() {
        val t = "Applications Memory Usage (in Kilobytes):\n\nTotal PSS by process:\n    210,500K: com.hbo.hbonow (pid 900 / activities)\n    109,123K: org.smarttube.stable (pid 333 / activities)\n     55,000K: system (pid 1)\n\nTotal PSS by OOM adjustment:\n    999,000K: Native\n"
        val r = SaludCalculo.parsearPss(t, 2)
        assertEquals(listOf("com.hbo.hbonow" to 210500L, "org.smarttube.stable" to 109123L), r)
    }

    @Test fun llaves() {
        assertEquals(listOf("david@pc", "(sin nombre)"), SaludCalculo.parsearLlaves("QAAA david@pc\n\nQBBB\n"))
    }

    @Test fun instantaneaIdaYVuelta() {
        val i = Instantanea(1, 2, 3, 4, 5, 6, 7)
        assertEquals(i, Instantanea.deTexto(i.aTexto()))
        assertNull(Instantanea.deTexto("1,2,x"))
        assertNull(Instantanea.deTexto(null))
    }
}
