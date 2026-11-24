package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArchivosTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun escribirReemplazaYNoDejaTemporal() {
        val f = File(tmp.root, "a.json")
        Archivos.escribir(f, "uno"); Archivos.escribir(f, "dos")
        assertEquals("dos", f.readText())
        assertFalse(File(tmp.root, "a.json.tmp").exists())
    }

    @Test fun creaLaCarpetaSiFalta() {
        val f = File(tmp.root, "x/y/b.json")
        Archivos.escribir(f, "ok")
        assertEquals("ok", f.readText())
    }

    @Test fun unTemporalAMediasNoTocaElArchivoReal() {
        // El temporal queda a medias (simula un corte de luz): el archivo real sigue entero y el siguiente guardado funciona.
        val f = File(tmp.root, "c.json")
        Archivos.escribir(f, "{\"bueno\":1}")
        File(tmp.root, "c.json.tmp").writeText("{\"par")
        assertEquals("{\"bueno\":1}", f.readText())
        Archivos.escribir(f, "{\"nuevo\":2}")
        assertEquals("{\"nuevo\":2}", f.readText())
        assertFalse(File(tmp.root, "c.json.tmp").exists())
    }

    @Test fun escribirOkDevuelveFalseSiNoPuede() {
        val bloqueo = tmp.newFile("es-archivo")
        assertFalse(Archivos.escribirOk(File(bloqueo, "dentro.json"), "x")) // la «carpeta» es un archivo
        assertTrue(Archivos.escribirOk(File(tmp.root, "bien.json"), "x"))
    }

    @Test fun escritoresConcurrentesNuncaDejanElArchivoMezclado() {
        val f = File(tmp.root, "d.json")
        val textos = (1..8).map { n -> "{\"n\":$n,\"relleno\":\"" + "x".repeat(50_000) + "\"}" }
        val pool = Executors.newFixedThreadPool(8)
        val listos = CountDownLatch(8)
        textos.forEach { t -> pool.submit { repeat(20) { Archivos.escribir(f, t) }; listos.countDown() } }
        assertTrue(listos.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("el archivo debe ser exactamente uno de los textos completos", f.readText() in textos)
    }

    @Test fun anexosYReescrituraNoPierdenRegistros() {
        val f = File(tmp.root, "e.jsonl")
        Archivos.anexar(f, "a\nb\n")
        // Reescritura lenta (como Rematch): mientras tanto otro hilo anexa; el cerrojo los serializa y nada se pierde.
        val pool = Executors.newFixedThreadPool(2)
        val en = pool.submit { Archivos.transformarLineas(f) { l -> Thread.sleep(200); l.map { it.uppercase() } } }
        Thread.sleep(50)
        val an = pool.submit { Archivos.anexar(f, "c\n") }
        en.get(); an.get(); pool.shutdown()
        assertEquals(listOf("A", "B", "c"), Archivos.lineas(f))
    }

    @Test fun lineasIgnoraVaciasYArchivoInexistente() {
        assertEquals(emptyList<String>(), Archivos.lineas(File(tmp.root, "no-existe")))
        val f = tmp.newFile("g.txt"); f.writeText("a\n\n  \nb\n")
        assertEquals(listOf("a", "b"), Archivos.lineas(f))
    }
}
