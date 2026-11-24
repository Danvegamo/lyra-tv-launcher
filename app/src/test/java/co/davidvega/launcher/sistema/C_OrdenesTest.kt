package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Seguridad de C: ninguna orden del launcher puede apuntar a un paquete de no_tocar.txt. */
class C_OrdenesTest {
    @get:Rule val tmp = TemporaryFolder()

    // Las pruebas corren con el directorio de trabajo en app/.
    private val noTocar: Set<String> by lazy { NoTocar.parsear(File("src/main/assets/no_tocar.txt").readText()) }

    @Test fun elAssetTieneLos32Paquetes() {
        assertEquals(32, noTocar.size)
        assertTrue("com.amazon.firebat" in noTocar && "com.amazon.venezia" in noTocar && "com.amazon.tv.livetv" in noTocar)
    }

    @Test fun ningunaOrdenGeneradaApuntaAUnPaqueteProtegido() {
        for (p in noTocar) {
            assertTrue("$p: se generó una orden", Ordenes.todasPara(p, noTocar).all { it == null })
            assertFalse(Ordenes.puedeMatar(p, noTocar))
            // también con variantes: mayúsculas, componente, prefijo
            for (v in listOf(p.uppercase(), "$p/.Main", "$p.Algo")) {
                assertTrue("$v: se generó una orden", Ordenes.todasPara(v, noTocar).all { it == null })
                assertFalse(Ordenes.puedeMatar(v, noTocar))
            }
        }
    }

    @Test fun ningunaFormaPermitidaDejaPasarUnProtegidoNiLasOrdenesVetadas() {
        for (p in noTocar) {
            val vetadas = listOf(
                "pm disable-user --user 0 $p", "pm disable-user $p", "pm clear $p", "pm uninstall $p", "am force-stop $p",
                "cmd appops set $p RUN_IN_BACKGROUND ignore", "cmd appops set $p RUN_IN_BACKGROUND default", "cmd appops get $p RUN_IN_BACKGROUND",
                "cmd appops set $p/.X RUN_IN_BACKGROUND ignore", "settings put global activity_manager_constants $p",
                "echo ok; pm disable-user $p", "echo ok && am force-stop $p", "echo \$(echo $p)",
            )
            vetadas.forEach { assertFalse("permitida: $it", Ordenes.permitida(it, noTocar)) }
        }
    }

    @Test fun lasFormasLegitimasSiPasan() {
        for (c in listOf("echo ok", "reboot", "pm trim-caches 2G", "dumpsys meminfo", "cat /data/misc/adb/adb_keys")) assertTrue(c, Ordenes.permitida(c, noTocar))
        for (a in Control.APPS) {
            assertNotNull(a.paquete, Ordenes.segundoPlano(a.paquete, true, noTocar))
            assertNotNull(a.paquete, Ordenes.segundoPlano(a.paquete, false, noTocar))
            assertNotNull(a.paquete, Ordenes.leerSegundoPlano(a.paquete, noTocar))
            assertTrue(Ordenes.puedeMatar(a.paquete, noTocar))
            assertEquals("cmd appops set ${a.paquete} RUN_IN_BACKGROUND ignore", Ordenes.segundoPlano(a.paquete, false, noTocar))
        }
        assertEquals("cmd appops set org.smarttube.stable RUN_IN_BACKGROUND ignore", Ordenes.segundoPlano("org.smarttube.stable", false, noTocar))
    }

    @Test fun lasOrdenesFueraDeLaListaBlancaSeRechazan() {
        for (c in listOf("pm clear org.smarttube.stable", "pm disable-user org.smarttube.stable", "am force-stop org.smarttube.stable",
            "settings put global x 1", "rm -rf /sdcard", "cmd appops set org.smarttube.stable RUN_IN_BACKGROUND deny", "pm trim-caches 2G; reboot", "echo ok\nreboot"))
            assertFalse(c, Ordenes.permitida(c, noTocar))
    }

    @Test fun conListaVaciaNoSePermiteNada() {
        assertFalse(Ordenes.permitida("echo ok", emptySet()))
        assertFalse(Ordenes.permitida("reboot", emptySet()))
        assertFalse(Ordenes.puedeMatar("org.smarttube.stable", emptySet()))
        assertNull(Ordenes.segundoPlano("org.smarttube.stable", false, emptySet()))
    }

    @Test fun paqueteConInyeccionNoGeneraOrden() {
        assertNull(Ordenes.segundoPlano("org.x; reboot", false, noTocar))
        assertNull(Ordenes.segundoPlano("org.x reboot", false, noTocar))
        assertFalse(Ordenes.puedeMatar("org.x; reboot", noTocar))
    }

    @Test fun lasAppsDeControlNoSonProtegidas() {
        for (a in Control.APPS) assertNull(a.paquete, Ordenes.apunta(a.paquete, noTocar))
    }

    @Test fun parsearIgnoraComentarios() {
        assertEquals(setOf("a.b", "c.d"), NoTocar.parsear("# hola\n a.b \n\nC.D # x\n"))
    }

    // ---- S3 y S4 puros ----

    @Test fun combinarConservaOtrasClaves() {
        assertEquals("max_cached_processes=16", Control.combinar(null, 16))
        assertEquals("a=1,max_cached_processes=8", Control.combinar("a=1,max_cached_processes=16", 8))
        assertEquals("a=1", Control.combinar("a=1,max_cached_processes=16", 32))
        assertNull(Control.combinar("max_cached_processes=16", 32))
        assertNull(Control.combinar(null, 32))
    }

    @Test fun capturasViejasSoloPngDeMasDe30Dias() {
        val ahora = 100L * 86_400_000L
        val dir = tmp.newFolder("files")
        fun f(n: String, edadDias: Long) = File(dir, n).apply { writeText("x"); setLastModified(ahora - edadDias * 86_400_000L) }
        val vieja = f("ap.png", 40); val nueva = f("nueva.png", 5); val otra = f("datos.json", 90); val borde = f("borde.png", 30)
        val sub = File(dir, "sub").apply { mkdir() }; val dentro = File(sub, "x.png").apply { writeText("x"); setLastModified(0) }
        val (n, bytes) = MantenimientoLimpieza.borrarCapturasViejas(dir, ahora, 30L * 86_400_000L)
        assertEquals(1, n); assertEquals(1L, bytes)
        assertFalse(vieja.exists()); assertTrue(nueva.exists() && otra.exists() && borde.exists() && dentro.exists())
        assertEquals(0 to 0L, MantenimientoLimpieza.borrarCapturasViejas(null, ahora, 1))
    }

    @Test fun usoRecienteIgnoraAlPropioLauncher() {
        assertTrue(MantenimientoLimpieza.esUso(1, "com.hbo.hbonow", "co.davidvega.launcher"))
        assertFalse(MantenimientoLimpieza.esUso(1, "co.davidvega.launcher", "co.davidvega.launcher"))
        assertFalse(MantenimientoLimpieza.esUso(2, "com.hbo.hbonow", "co.davidvega.launcher"))
        assertFalse(MantenimientoLimpieza.esUso(1, null, "co.davidvega.launcher"))
    }
}
