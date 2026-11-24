package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class A_AbrirTest {
    @Test fun enlaceCrunchyrollPorMediaId() =
        assertEquals("crunchyroll://episode?id=GRDQPM1ZY", Abrir.enlaceRetomar("crunchyroll", "GRDQPM1ZY", ""))

    @Test fun enlacePrimePorGti() =
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.8b35f685-aaaa", Abrir.enlaceRetomar("prime", "", "amzn1.dv.gti.8b35f685-aaaa"))

    @Test fun sinIdNoHayEnlace() {
        assertNull(Abrir.enlaceRetomar("crunchyroll", "", ""))
        assertNull(Abrir.enlaceRetomar("hbomax", "x", "y"))
    }

    @Test fun alAbrirPrimeSeLiberaAvodYNuncaFirebat() {
        val l = Abrir.paquetesALiberar("com.amazon.firebat")
        assertTrue("com.amazon.avod" in l)
        assertFalse("com.amazon.firebat" in l)
        assertFalse("com.amazon.firebat" in Abrir.paquetesALiberar("com.hbo.hbonow"))
    }

    @Test fun nuncaSeLiberaNadaDeNoTocar() {
        val f = listOf("src/main/assets/no_tocar.txt").map { File(it) }.firstOrNull { it.exists() } ?: return
        val protegidos = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        for (abre in listOf("com.amazon.firebat", "com.hbo.hbonow", "com.crunchyroll.crunchyroid", "org.smarttube.stable", "com.example.iptv"))
            assertTrue(Abrir.paquetesALiberar(abre).none { it in protegidos })
    }

    @Test fun tresFallosSeguidosSaltanElEscalonUnDia() {
        var t = 0L
        val s = SaltosAbrir { t }
        repeat(2) { s.resultado("p|0", false) }
        assertFalse(s.saltar("p|0"))
        s.resultado("p|0", false)
        assertTrue(s.saltar("p|0"))
        assertFalse(s.saltar("p|1"))
        t += 24 * 3_600_000L + 1
        assertFalse(s.saltar("p|0"))
    }

    @Test fun unExitoReiniciaLaCuenta() {
        val s = SaltosAbrir { 0L }
        repeat(2) { s.resultado("p|0", false) }
        s.resultado("p|0", true)
        repeat(2) { s.resultado("p|0", false) }
        assertFalse(s.saltar("p|0"))
    }
}
