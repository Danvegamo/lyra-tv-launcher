package co.davidvega.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class B_DoblajeTest {
    private fun of(sv: String, vararg a: String) = Doblaje.Oferta(sv, a.toSet())
    private val servicios = mapOf("mxx" to "hbomax", "cru" to "crunchyroll", "aho" to "prime")

    @Test fun hboConEspanolEsLatino() = assertEquals(Doblaje.LATINO, Doblaje.calcular(listOf(of("hbomax", "en", "es")), true))
    @Test fun crunchyrollConEspanolEsSoloEspanol() = assertEquals(Doblaje.ESPAÑOL, Doblaje.calcular(listOf(of("crunchyroll", "ja", "es")), true))
    @Test fun animeSinEspanolEsSubtitulado() = assertEquals(Doblaje.SUBTITULADO, Doblaje.calcular(listOf(of("crunchyroll", "ja", "en")), true))
    @Test fun sinDatosEsDesconocido() {
        assertEquals(Doblaje.DESCONOCIDO, Doblaje.calcular(emptyList(), true))
        assertEquals(Doblaje.DESCONOCIDO, Doblaje.calcular(listOf(of("netflix")), true))
        assertEquals(Doblaje.DESCONOCIDO, Doblaje.calcular(listOf(of("prime", "en")), false))
    }
    @Test fun loManualManda() {
        assertEquals(Doblaje.LATINO, Doblaje.calcular(listOf(of("crunchyroll", "ja")), true, true))
        assertEquals(Doblaje.SUBTITULADO, Doblaje.calcular(listOf(of("hbomax", "es")), true, false))
    }

    // Propiedad: agregar `es` al audio de cualquier oferta nunca baja el estado (rango menor o igual).
    @Test fun agregarEspanolNuncaBajaElEstado() {
        val r = Random(14)
        val sv = listOf("hbomax", "crunchyroll", "prime", "netflix")
        val idiomas = listOf("ja", "en", "pt", "fr", "de")
        repeat(500) {
            val ofertas = List(r.nextInt(0, 5)) { Doblaje.Oferta(sv.random(r), idiomas.shuffled(r).take(r.nextInt(0, 4)).toSet()) }
            val anime = r.nextBoolean()
            val antes = Doblaje.calcular(ofertas, anime)
            val i = if (ofertas.isEmpty()) -1 else r.nextInt(ofertas.size)
            val con = ofertas.mapIndexed { k, o -> if (k == i) Doblaje.Oferta(o.servicio, o.audio + "es") else o }
            assertTrue("$antes -> ${Doblaje.calcular(con, anime)}", Doblaje.calcular(con, anime).rango <= antes.rango)
        }
    }

    @Test fun parseaOfertasYTolerasEsquemaRoto() {
        val j = JSONArray("""[{"monetizationType":"FLATRATE","package":{"shortName":"mxx"},"audioLanguages":["en","es"]},
            {"monetizationType":"BUY","package":{"shortName":"cru"},"audioLanguages":["es"]},
            {"monetizationType":"FLATRATE","package":{"shortName":"zzz"},"audioLanguages":["es"]},
            {"monetizationType":"FLATRATE","package":{"shortName":"cru"},"audioLanguages":"raro"},
            "basura", {"monetizationType":"FLATRATE"}]""")
        val o = Doblaje.aOfertas(j) { servicios[it] }
        assertEquals(listOf("hbomax", "crunchyroll"), o.map { it.servicio })
        assertEquals(setOf("en", "es"), o[0].audio)
        assertTrue(o[1].audio.isEmpty())
        assertTrue(Doblaje.aOfertas(null) { servicios[it] }.isEmpty())
    }

    @Test fun cacheCaducaALos7DiasYArchivoRotoNoTumba() {
        val d = File.createTempFile("dbl", "").let { it.delete(); it.mkdirs(); it }
        val f = File(d, "doblaje_cache.json")
        val ahora = 10_000_000_000L
        f.writeText(JSONObject().put("tv:1", JSONObject().put("t", ahora - 1000).put("e", "LATINO"))
            .put("tv:2", JSONObject().put("t", ahora - 8 * 24 * 3600_000L).put("e", "LATINO"))
            .put("tv:3", JSONObject().put("t", ahora).put("e", "NO_EXISTE")).toString())
        assertEquals(mapOf("tv:1" to Doblaje.LATINO), Doblaje.leerCache(f, ahora))
        f.writeText("{no es json")
        assertTrue(Doblaje.leerCache(f, ahora).isEmpty())
        assertTrue(Doblaje.leerCache(File(d, "no_hay.json"), ahora).isEmpty())
    }

    @Test fun lasMarcasManualesPersistenEntreArranques() {
        val d = File.createTempFile("dbl", "").let { it.delete(); it.mkdirs(); it }
        Doblaje.iniciar(d)
        Doblaje.marcar("tv", 77, true)
        Doblaje.marcar("tv", 78, false)
        Doblaje.iniciar(File(d, "otra")); Doblaje.iniciar(d) // simula reinicio: se vuelve a leer el disco
        assertEquals(true, Doblaje.manual("tv", 77))
        assertEquals(false, Doblaje.manual("tv", 78))
        assertEquals(Doblaje.LATINO, Doblaje.resultado("tv", 77).estado)
        assertTrue(Doblaje.resultado("tv", 77).manual)
        Doblaje.marcar("tv", 77, null)
        assertNull(Doblaje.manual("tv", 77))
    }

    @Test fun manualMandaSobreJustWatchYSobreviveAlRegistro() {
        val d = File.createTempFile("dbl", "").let { it.delete(); it.mkdirs(); it }
        Doblaje.iniciar(d)
        Doblaje.registrar("tv", 5, listOf(of("crunchyroll", "ja")), true)
        assertEquals(Doblaje.SUBTITULADO, Doblaje.resultado("tv", 5).estado)
        Doblaje.marcar("tv", 5, true)
        Doblaje.registrar("tv", 5, listOf(of("crunchyroll", "ja")), true)
        assertEquals(Doblaje.LATINO, Doblaje.resultado("tv", 5).estado)
    }

    @Test fun tituloAntiguoSinCampoDoblajeSigueLeyendo() {
        val viejo = JSONObject("""{"id":1,"medio":"tv","titulo":"X","sinopsis":"","anio":"2020","nota":1.0,"servicios":[]}""")
        val t = Titulo.deJson(viejo)
        assertEquals("", t.doblaje)
        assertEquals(t, Titulo.deJson(t.aJson()))
        val nuevo = t.copy(doblaje = "LATINO")
        assertEquals(nuevo, Titulo.deJson(nuevo.aJson()))
    }
}
