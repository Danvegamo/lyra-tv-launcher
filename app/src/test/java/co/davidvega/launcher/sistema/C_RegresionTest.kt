package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Regresiones de la revisión adversarial 0.14.0 (C). */
class C_RegresionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dia = 86_400_000L

    @Test fun noTocarIgnoraBomYEspaciosUnicode() {
        val s = NoTocar.parsear("\uFEFFcom.amazon.tv.launcher\r\n\u00A0COM.X.Y \u200B\r\n\uFEFF\u2003com.a.b")
        assertTrue("com.amazon.tv.launcher" in s)
        assertTrue("com.x.y" in s && "com.a.b" in s)
        assertEquals(3, s.size)
    }

    @Test fun appopsSoloParaLasAppsDeControl() {
        val nt = NoTocar.parsear("com.amazon.tv.launcher")
        assertFalse(Ordenes.permitida("cmd appops set com.amazon.dcp RUN_IN_BACKGROUND ignore", nt))
        assertFalse(Ordenes.permitida("cmd appops get com.amazon.dcp RUN_IN_BACKGROUND", nt))
        assertTrue(Ordenes.permitida("cmd appops set org.smarttube.stable RUN_IN_BACKGROUND ignore", nt))
    }

    @Test fun combinarNormalizaEspaciosYRestauraElOriginal() {
        assertEquals("max_cached_processes=16", Control.combinar("max_cached_processes =8", 16))
        assertEquals("a=1,max_cached_processes=24", Control.combinar("a=1,max_cached_processes=8", 32, "max_cached_processes=24"))
        assertNull(Control.combinar("max_cached_processes=8", 32, null))
        assertEquals("a=1", Control.combinar("a=1,max_cached_processes=8", 32, null))
        assertEquals("max_cached_processes=24", Control.entradaDe("a=1, max_cached_processes = 24"))
        assertNull(Control.entradaDe("a=1"))
    }

    @Test fun limpiezaSoloBorraCapturasPropiasYNoSigueEnlaces() {
        val ahora = 100L * dia
        val dir = tmp.newFolder("files")
        fun f(n: String) = File(dir, n).apply { writeText("x"); setLastModified(ahora - 40 * dia) }
        val propias = listOf(f("ap.png"), f("home1.png"), f("home_2.png"), f("cap_inicio.png"))
        val ajenas = listOf(f("logo.png"), f("foto.png"))
        val destino = File(tmp.newFolder("fuera"), "dest.png").apply { writeText("x"); setLastModified(ahora - 40 * dia) }
        try { java.nio.file.Files.createSymbolicLink(File(dir, "cap_enlace.png").toPath(), destino.toPath()) } catch (e: Exception) { /* sin permiso de enlaces: se omite */ }
        val (n, _) = MantenimientoLimpieza.borrarCapturasViejas(dir, ahora, 30 * dia)
        assertEquals(propias.size, n)
        assertTrue(propias.none { it.exists() })
        assertTrue(ajenas.all { it.exists() })
        assertTrue(destino.exists())
    }
}
