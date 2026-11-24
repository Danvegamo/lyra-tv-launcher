package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.Fila
import co.davidvega.launcher.data.Gustos
import co.davidvega.launcher.data.Titulo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Propiedades de `Titulo`, `Fila` y `Gustos.parsear` (plan 6.2). */
class F_ModelosTest {
    private fun txt(r: Random, max: Int = 30): String {
        val a = "abcdeñáé 日本語\"\\/\n\t{}[]:,.0123 "
        return (1..r.nextInt(0, max + 1)).map { a[r.nextInt(a.length)] }.joinToString("")
    }
    private fun url(r: Random): String = "https://image.tmdb.org/t/p/w342/" + (1..r.nextInt(1, 10)).map { "abcxyz019"[r.nextInt(9)] }.joinToString("") + ".jpg"

    private fun titulo(r: Random) = Titulo(
        id = r.nextInt(), medio = if (r.nextBoolean()) "movie" else "tv", titulo = txt(r), sinopsis = txt(r, 400),
        poster = if (r.nextBoolean()) url(r) else null, fondo = if (r.nextBoolean()) url(r) else null,
        anio = r.nextInt(1900, 2100).toString(), nota = (r.nextInt(0, 1001)) / 100.0,
        servicios = (1..r.nextInt(0, 4)).map { listOf("netflix", "prime", "hbomax", "crunchyroll")[r.nextInt(4)] },
        motivo = if (r.nextBoolean()) "m" + txt(r) else null, gradiente = r.nextInt(0, 8), noDisponible = r.nextBoolean(),
        generos = (1..r.nextInt(0, 5)).map { r.nextInt(0, 99999) }, idioma = listOf("", "ja", "en", "ko")[r.nextInt(4)],
    )
    private val TITULOS: Arb<Titulo> = arbitrary { rs -> titulo(rs.random) }

    // Regla (ida y vuelta): deJson(aJson(t)) == t, también pasando por el texto como lo hace el caché en disco.
    @Test fun tituloIdaYVuelta() {
        F_Base.propiedad("titulo-ida-y-vuelta", TITULOS) { t ->
            assertEquals(t, Titulo.deJson(t.aJson()))
            assertEquals(t, Titulo.deJson(JSONObject(t.aJson().toString())))
        }
    }

    // Regla (ida y vuelta): Fila.deJson(Fila.aJson(f)) == f. Los ids de la fila no dependen del índice porque los títulos traen id.
    @Test fun filaIdaYVuelta() {
        F_Base.propiedad("fila-ida-y-vuelta", arbitrary { rs -> Fila(txt(rs.random, 8), txt(rs.random), (1..rs.random.nextInt(0, 5)).map { titulo(rs.random) }) }, 100) { f ->
            assertEquals(f, Fila.deJson(JSONObject(f.aJson().toString())))
        }
    }

    // Regla: sinopsisCorta nunca pasa de 151 caracteres (150 más la elipsis) y no lanza.
    @Test fun sinopsisCortaAcotada() {
        F_Base.propiedad("sinopsis-corta", TITULOS) { t -> assertTrue(t.sinopsis, t.sinopsisCorta.length <= 151) }
    }

    // Regla: con tipos de campo inesperados en un texto del corpus, deJson solo lanza JSONException (nunca otra clase).
    @Test fun tituloDeJsonSoloLanzaJSONException() {
        F_Base.propiedad("titulo-dejson-mutado", F_Json.mutados(listOf("titulo.json"))) { t ->
            val o = try { JSONObject(t) } catch (_: JSONException) { return@propiedad }
            try { Titulo.deJson(o) } catch (_: JSONException) { }
        }
    }

    // Regla (más débil): deJson solo lanza JSONException. HALLAZGO F-4 abajo: aLista/aListaInt usan getString/getInt y rompen toda la
    // lectura de un título (y, vía Fila.deJson, de una fila entera) si un solo elemento de "servicios" o "generos" es de otro tipo.
    @Test fun tituloDeJsonToleraElementosDeOtroTipo() {
        Titulo.deJson(JSONObject("""{"id":1,"servicios":[1]}"""))
        Titulo.deJson(JSONObject("""{"id":1,"generos":["a"]}"""))
    }

    // ---- Gustos.parsear ----

    private data class Perfil(val provs: Map<String, List<Int>>, val anclas: List<String>, val filas: List<String>) {
        fun aJson(): String = JSONObject().apply {
            put("proveedores", JSONObject().also { p ->
                p.put("_nota", "x")
                provs.forEach { (k, ids) -> p.put(k, JSONObject().put("nombre", k.uppercase()).put("ids", JSONArray(ids)).put("paquete", "p.$k")) }
            })
            put("anclas", JSONArray().also { a -> anclas.forEach { a.put(JSONObject().put("titulo", it).put("peso", 2)) } })
            put("filas", JSONArray().also { a -> filas.forEach { a.put(JSONObject().put("id", it).put("titulo", "T$it")) } })
        }.toString()
    }

    private val PERFILES = arbitrary { rs ->
        val r = rs.random
        Perfil(
            provs = (1..r.nextInt(1, 5)).associate { "prov" + r.nextInt(0, 30) to (1..r.nextInt(0, 4)).map { r.nextInt(1, 999) } },
            anclas = (1..r.nextInt(0, 5)).map { "a" + txt(r, 10) },
            filas = (1..r.nextInt(0, 5)).map { "f$it" },
        )
    }

    // Regla (ida y vuelta): un perfil serializado y parseado conserva proveedores (sin las claves "_"), anclas y filas.
    @Test fun gustosIdaYVuelta() {
        F_Base.propiedad("gustos-ida-y-vuelta", PERFILES) { p ->
            val g = Gustos.parsear(p.aJson())
            assertEquals(p.provs.keys, g.proveedores.map { it.clave }.toSet())
            p.provs.forEach { (k, ids) -> assertEquals(ids, g.proveedores.first { it.clave == k }.ids) }
            assertEquals(p.anclas, g.anclas.map { it.titulo })
            assertEquals(p.filas, g.filas.map { it.id })
        }
    }

    // Regla (metamórfica): meter entradas basura entre las anclas y las filas no cambia las demás.
    @Test fun gustosEntradasMalasNoAfectanALasBuenas() {
        F_Base.propiedad("gustos-basura-aislada", PERFILES) { p ->
            val limpio = Gustos.parsear(p.aJson())
            val o = JSONObject(p.aJson())
            val basura = listOf("x", 3, JSONObject.NULL, JSONObject(), JSONArray(), JSONObject().put("titulo", JSONObject()), JSONObject().put("id", 5))
            o.put("anclas", JSONArray().also { a -> basura.forEach { a.put(it) }; for (i in 0 until o.getJSONArray("anclas").length()) a.put(o.getJSONArray("anclas").get(i)) })
            o.put("filas", JSONArray().also { a -> basura.forEach { a.put(it) }; for (i in 0 until o.getJSONArray("filas").length()) a.put(o.getJSONArray("filas").get(i)) })
            val sucio = Gustos.parsear(o.toString())
            assertEquals(limpio.anclas.map { it.titulo }, sucio.anclas.map { it.titulo })
            assertEquals(limpio.filas.map { it.id }, sucio.filas.map { it.id })
        }
    }

    // Regla: si la raíz trae "proveedores" como objeto, parsear no lanza con ningún tipo en el resto de campos.
    @Test fun gustosNoLanzaConTiposInesperados() {
        F_Base.propiedad("gustos-tipos-malos", arbitrary(F_ReduceTexto) { rs ->
            val r = rs.random
            JSONObject().also { o ->
                o.put("proveedores", JSONObject().also { p -> repeat(r.nextInt(3)) { p.put(F_Json.palabra(r), F_Json.valor(r)) } })
                listOf("anclas", "filas", "declarados", "region", "idioma").forEach { if (r.nextBoolean()) o.put(it, F_Json.valor(r)) }
            }.toString()
        }) { t -> Gustos.parsear(t) }
    }

    // Regla: sobre el corpus mutado, parsear solo lanza JSONException (texto no JSON o sin "proveedores") y cargar nunca lanza.
    @Test fun gustosMutadoNoLanzaOtraCosa() {
        F_Base.propiedad("gustos-mutado", F_Json.mutados(listOf("gustos.json"))) { t ->
            try { Gustos.parsear(t) } catch (_: JSONException) { }
            Gustos.cargar({ t }, { F_Base.corpus("gustos.json") })
        }
    }
}
