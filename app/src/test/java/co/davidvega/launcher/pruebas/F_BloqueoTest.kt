package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.Bloqueo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Propiedades de `Bloqueo` (plan 6.2). El estado de `Bloqueo` es global: cada prueba parte de la configuración por defecto. */
class F_BloqueoTest {
    @Before @After fun reiniciar() = Bloqueo.cargar("{}")

    private fun detalles(medio: String, modo: F_Json.Modo): Arb<String> =
        arbitrary(F_ReduceTexto) { rs -> F_Json.detalle(rs.random, medio, modo).toString() }

    // Regla: con campos ausentes o null (pero del tipo correcto), veredicto nunca lanza y devuelve null o un motivo no vacío.
    @Test fun veredictoNoLanzaConCamposAusentesONulos() {
        for (medio in listOf("movie", "tv")) F_Base.propiedad("veredicto-nulos-$medio", detalles(medio, F_Json.Modo.NULOS)) { t ->
            val v = Bloqueo.veredicto(medio, JSONObject(t))
            assertTrue(t, v == null || v.isNotEmpty())
        }
    }

    // Regla (más débil): con campos de cualquier tipo, veredicto no lanza. (F-1, corregido).
    @Test fun veredictoNoLanzaConTiposInesperados() {
        for (medio in listOf("movie", "tv")) F_Base.propiedad("veredicto-malos-$medio", detalles(medio, F_Json.Modo.MALOS)) { t ->
            Bloqueo.veredicto(medio, JSONObject(t))
        }
    }

    // Regresión fija de F-1 (F-1, corregido): genres con un elemento que no es objeto.
    @Test fun veredictoGenerosConElementoNoObjeto() {
        Bloqueo.veredicto("movie", JSONObject("""{"genres":[1]}"""))
        Bloqueo.veredicto("movie", JSONObject("""{"genres":[null]}"""))
    }

    // Regla: si adult=true el veredicto es siempre "adult", sea cual sea el resto del objeto.
    @Test fun adultSiempreBloquea() {
        for (medio in listOf("movie", "tv")) F_Base.propiedad("veredicto-adult-$medio", detalles(medio, F_Json.Modo.NULOS)) { t ->
            val d = JSONObject(t).put("adult", true)
            assertEquals(t, "adult", Bloqueo.veredicto(medio, d))
        }
    }

    // Regla (monotonía): añadir una palabra clave de la lista dura a cualquier detalle siempre produce un bloqueo.
    @Test fun anadirPalabraClaveDuraNuncaPermiteMas() {
        for (medio in listOf("movie", "tv")) F_Base.propiedad("veredicto-monotonia-$medio", detalles(medio, F_Json.Modo.NULOS)) { t ->
            val d = JSONObject(t)
            val ruta = if (medio == "tv") "results" else "keywords"
            val previas = d.optJSONObject("keywords")?.optJSONArray(ruta) ?: JSONArray()
            val nuevas = JSONArray().also { a -> for (i in 0 until previas.length()) a.put(previas.opt(i)); a.put(JSONObject().put("id", 0).put("name", "softcore")) }
            d.put("keywords", JSONObject().put(ruta, nuevas))
            val antes = Bloqueo.veredicto(medio, JSONObject(t))
            val despues = Bloqueo.veredicto(medio, d)
            assertNotNull("$t -> antes=$antes", despues)
        }
    }

    // Regla (oráculo): el bloqueo por certificación dura es el mismo con el mayúsculas/minúsculas y espacios que traiga TMDB.
    @Test fun certificacionDuraSeNormalizaAMayusculas() {
        F_Base.propiedad("veredicto-cert-dura", Arb.element("NC-17", "X", "XXX", "R18", "R18+", "R-18")) { c ->
            for (v in listOf(c, c.lowercase(), " $c ")) {
                val d = JSONObject("""{"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":${JSONObject.quote(v)}}]}]}}""")
                val r = Bloqueo.veredicto("movie", d)
                assertTrue("$v -> $r", r != null && r.startsWith("cert:"))
            }
        }
    }

    /** Configuración generada: ids, certificaciones duras y palabras de las dos expresiones. */
    private data class Cfg(val ids: Set<Int>, val duras: List<String>, val palabras: List<String>) {
        fun aJson(): String {
            val o = JSONObject()
            o.put("keywords", JSONObject().also { k -> ids.forEach { k.put(it.toString(), "kw$it") } })
            o.put("certificaciones_duras", JSONArray(duras))
            o.put("palabras_clave_regex", palabras.joinToString("|"))
            o.put("texto_regex", """\b(${palabras.joinToString("|")})\b""")
            return o.toString()
        }
    }

    private val CFG = arbitrary { rs ->
        val r = rs.random
        Cfg(
            ids = (1..r.nextInt(0, 6)).map { r.nextInt(1, 99999) }.toSet(),
            duras = (1..r.nextInt(1, 4)).map { (1..r.nextInt(1, 5)).map { "ABCDEF0123456789-"[r.nextInt(17)] }.joinToString("") }.distinct(),
            palabras = (1..r.nextInt(1, 4)).map { (1..r.nextInt(3, 8)).map { 'a' + r.nextInt(26) }.joinToString("") }.distinct(),
        )
    }

    // Regla (ida y vuelta): lo que se serializa en un bloqueo.json y se carga se observa igual a través de la API pública.
    @Test fun cargarDeUnaConfigSerializadaLaReproduce() {
        F_Base.propiedad("bloqueo-ida-y-vuelta", CFG) { c ->
            Bloqueo.cargar(c.aJson())
            assertEquals(c.aJson(), c.ids, Bloqueo.idsKeywords)
            for (p in c.palabras) {
                assertTrue("$p en ${c.aJson()}", Bloqueo.palabraClave(p.uppercase()))
                assertTrue(Bloqueo.explicito("una $p así"))
                assertTrue(Bloqueo.consultaExplicita("  $p "))
            }
            for (d in c.duras) {
                val det = JSONObject("""{"release_dates":{"results":[{"iso_3166_1":"MX","release_dates":[{"certification":${JSONObject.quote(d.lowercase())}}]}]}}""")
                val v = Bloqueo.veredicto("movie", det)
                assertTrue("$d en ${c.aJson()} -> $v", v != null && v.startsWith("cert:"))
            }
            // una certificación fuera de la lista y sin tema sexual pasa
            assertEquals(null, Bloqueo.veredicto("movie", JSONObject("""{"release_dates":{"results":[{"iso_3166_1":"MX","release_dates":[{"certification":"ZZZ"}]}]}}""")))
        }
    }

    // Regla: cargar cualquier texto (aleatorio, nulo o mutado del corpus) no lanza.
    @Test fun cargarNuncaLanza() {
        F_Base.propiedad("cargar-aleatorio", Arb.choice(Arb.string(0..200), F_Json.aleatorios(), F_Json.mutados())) { t -> Bloqueo.cargar(t) }
        Bloqueo.cargar(null)
    }

    // Regla: si el texto no es JSON válido, o trae una expresión regular inválida, la configuración anterior queda intacta.
    @Test fun cargarInvalidoConservaLaConfiguracionAnterior() {
        F_Base.propiedad("cargar-invalido", F_Json.mutados(listOf("bloqueo.json"))) { t ->
            Bloqueo.cargar("""{"keywords":{"7":"a"}}""")
            Bloqueo.cargar(t)
            val parseable = try { JSONObject(t); true } catch (_: JSONException) { false }
            if (!parseable) assertEquals(t, setOf(7), Bloqueo.idsKeywords)
        }
        for (mala in listOf("(", "[", "*a", "a{2,1}", "\\")) {
            Bloqueo.cargar("""{"keywords":{"7":"a"}}""")
            Bloqueo.cargar("""{"keywords":{"9":"b"},"palabras_clave_regex":${JSONObject.quote(mala)}}""")
            assertEquals(mala, setOf(7), Bloqueo.idsKeywords)
        }
    }

    // Regla (idempotencia): cargar el mismo texto dos veces deja el mismo estado observable.
    @Test fun cargarEsIdempotente() {
        F_Base.propiedad("cargar-idempotente", CFG) { c ->
            Bloqueo.cargar(c.aJson()); val a = Bloqueo.idsKeywords to Bloqueo.palabraClave(c.palabras[0])
            Bloqueo.cargar(c.aJson()); assertEquals(a, Bloqueo.idsKeywords to Bloqueo.palabraClave(c.palabras[0]))
        }
    }

    private val ASCII = arbitrary { rs -> val r = rs.random; (1..r.nextInt(0, 30)).map { "abcxyzABCXYZ -/.,0123"[r.nextInt(21)] }.joinToString("") }
    private val CON_CLAVES = arbitrary { rs ->
        val r = rs.random
        val trozos = listOf("porn", "XXX", "Hentai", "erotic", "MILF", "nc-17", "sexo explícito", "adults only", "casa", "mar", " ", "x-rated")
        (1..r.nextInt(1, 5)).joinToString("") { if (r.nextBoolean()) trozos[r.nextInt(trozos.size)] else ASCII.sample(rs).value }
    }

    // Regla: explicito y palabraClave no distinguen mayúsculas de minúsculas (texto ASCII).
    @Test fun explicitoInsensibleAMayusculasEnAscii() {
        F_Base.propiedad("explicito-mayusculas", CON_CLAVES) { s ->
            assertEquals(s, Bloqueo.explicito(s.lowercase()), Bloqueo.explicito(s.uppercase()))
            assertEquals(s, Bloqueo.palabraClave(s.lowercase()), Bloqueo.palabraClave(s.uppercase()))
        }
    }

    // Regla: con tildes, "erótica" y "ERÓTICA" dan lo mismo (Regex de Kotlin añade UNICODE_CASE; comprobado en la JVM).
    @Test fun explicitoInsensibleAMayusculasConTildes() {
        assertEquals(true, Bloqueo.explicito("una película erótica"))
        assertEquals(true, Bloqueo.explicito("UNA PELÍCULA ERÓTICA"))
    }

    // Regla: consultaExplicita(x) implica explicito(x) o palabraClave(x).
    @Test fun consultaExplicitaImplicaExplicitoOPalabraClave() {
        F_Base.propiedad("consulta-explicita", Arb.choice(CON_CLAVES, Arb.string(0..30))) { s ->
            if (Bloqueo.consultaExplicita(s)) assertTrue(s, Bloqueo.explicito(s) || Bloqueo.palabraClave(s) || Bloqueo.palabraClave(s.trim()))
            // lo contrario: si explicito es verdadero, la consulta también lo es
            if (Bloqueo.explicito(s)) assertTrue(s, Bloqueo.consultaExplicita(s))
        }
    }

    // Regla: sospechosoSinDetalle no lanza con campos ausentes o nulos.
    @Test fun sospechosoSinDetalleNoLanza() {
        F_Base.propiedad("sospechoso-sin-detalle", detalles("movie", F_Json.Modo.NULOS)) { t ->
            Bloqueo.sospechosoSinDetalle(JSONObject(t))
        }
    }

    // Mutación manual comprobada: si veredicto ignorara adult, adultSiempreBloquea fallaría con el primer caso generado.
    @Test fun ejemploFijoDeAdult() { assertEquals("adult", Bloqueo.veredicto("tv", JSONObject("""{"adult":true}"""))) }
}
