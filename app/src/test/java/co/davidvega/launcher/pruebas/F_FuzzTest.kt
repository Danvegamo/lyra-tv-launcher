package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.AniList
import co.davidvega.launcher.data.Bloqueo
import co.davidvega.launcher.data.Fila
import co.davidvega.launcher.data.Gustos
import co.davidvega.launcher.data.JustWatch
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.data.Tmdb
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Fuzzing ligero de los parseadores (plan 6.6) con entradas mutadas del corpus (src/test/resources/corpus).
 * Criterio de fallo: cualquier excepción que no sea JSONException al leer el texto (o, en los tres objetivos con hallazgos F-1, F-3 y F-4,
 * JSONException al recorrer tipos inesperados) y cualquier entrada que tarde más de 1 s. Todo corre en la JVM, sin red ni Android real.
 */
class F_FuzzTest {
    @Before @After fun reiniciar() = Bloqueo.cargar("{}")

    private val LIMITE_MS = 1000L

    /** Ejecuta el objetivo, mide el tiempo y deja pasar solo las excepciones esperadas. */
    private fun objetivo(nombre: String, entrada: String, jsonTolerado: Boolean = false, cuerpo: () -> Unit) {
        val t0 = System.nanoTime()
        try { cuerpo() }
        catch (e: JSONException) { if (!jsonTolerado) throw AssertionError("$nombre: JSONException inesperada con entrada de ${entrada.length} caracteres: ${entrada.take(300)}", e) }
        catch (e: Throwable) { throw AssertionError("$nombre: ${e.javaClass.simpleName} con entrada de ${entrada.length} caracteres: ${entrada.take(300)}", e) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > LIMITE_MS) throw AssertionError("$nombre: tardó $ms ms (> $LIMITE_MS) con entrada de ${entrada.length} caracteres: ${entrada.take(300)}")
    }

    private fun objeto(t: String): JSONObject? = try { JSONObject(t) } catch (_: JSONException) { null }

    private fun buscar(v: Any?, clave: String, salida: MutableList<JSONArray>) {
        when (v) {
            is JSONObject -> v.keys().forEach { k -> val x = v.opt(k); if (k == clave && x is JSONArray) salida += x; buscar(x, clave, salida) }
            is JSONArray -> for (i in 0 until v.length()) buscar(v.opt(i), clave, salida)
        }
    }

    private val aRutasM = JustWatch::class.java.getDeclaredMethod("aRutas", JSONArray::class.java).apply { isAccessible = true }
    private val normalizarM = JustWatch::class.java.getDeclaredMethod("normalizar", String::class.java, String::class.java).apply { isAccessible = true }
    private fun invocar(f: () -> Any?) { try { f() } catch (e: InvocationTargetException) { throw e.targetException } }

    private val SONDAS = listOf("", " ", "porn", "a".repeat(5000), "erótic ".repeat(300), "́".repeat(3000), "xxx ".repeat(2000), "ERÓTICA", "😀".repeat(500))

    // Regla: Bloqueo.cargar con un bloqueo.json mutado no lanza, y las expresiones cargadas no se cuelgan con textos largos o raros.
    @Test fun fuzzBloqueo() {
        F_Base.propiedad("fuzz-bloqueo", F_Json.mutados(listOf("bloqueo.json")), 300) { t ->
            objetivo("Bloqueo.cargar+regex", t) {
                Bloqueo.cargar(t)
                SONDAS.forEach { Bloqueo.explicito(it); Bloqueo.palabraClave(it); Bloqueo.consultaExplicita(it) }
                Bloqueo.idsKeywords.size
            }
        }
    }

    // Regla: veredicto y sospechosoSinDetalle sobre detalles mutados solo lanzan la JSONException conocida (F-1), nada más, y rápido.
    @Test fun fuzzVeredicto() {
        F_Base.propiedad("fuzz-veredicto", F_Json.mutados(listOf("tmdb_detalle_pelicula.json", "tmdb_detalle_serie.json", "tmdb_listado.json")), 400) { t ->
            val o = objeto(t) ?: return@propiedad
            objetivo("Bloqueo.veredicto", t) { Bloqueo.veredicto("movie", o); Bloqueo.veredicto("tv", o) }
            objetivo("Bloqueo.sospechosoSinDetalle", t) { Bloqueo.sospechosoSinDetalle(o) }
            objetivo("Bloqueo.explicito(texto)", t) { Bloqueo.explicito(t) }
        }
    }

    // Regla: Gustos.parsear y Gustos.cargar sobre gustos.json mutado: parsear solo lanza JSONException (sin "proveedores"), cargar nunca.
    @Test fun fuzzGustos() {
        F_Base.propiedad("fuzz-gustos", F_Json.mutados(listOf("gustos.json")), 400) { t ->
            objetivo("Gustos.parsear", t, jsonTolerado = true) { Gustos.parsear(t) }
            objetivo("Gustos.cargar", t) { Gustos.cargar({ t }, { F_Base.corpus("gustos.json") }) }
        }
    }

    // Regla: Titulo.deJson y Fila.deJson sobre textos mutados solo lanzan JSONException (getString/getInt con tipos raros, hallazgo F-4).
    @Test fun fuzzTituloYFila() {
        F_Base.propiedad("fuzz-titulo", F_Json.mutados(listOf("titulo.json", "tmdb_listado.json", "seguir.json")), 400) { t ->
            val o = objeto(t) ?: return@propiedad
            objetivo("Titulo.deJson", t) { Titulo.deJson(o).let { x -> Titulo.deJson(x.aJson()) } }
            objetivo("Fila.deJson", t) { Fila.deJson(o) }
        }
    }

    // Regla: Tmdb.aTitulo sobre un resultado de listado mutado nunca lanza (usa solo optX).
    @Test fun fuzzTmdbATitulo() {
        F_Base.propiedad("fuzz-tmdb-atitulo", F_Json.mutados(listOf("tmdb_listado.json", "tmdb_detalle_pelicula.json", "tmdb_detalle_serie.json")), 400) { t ->
            val o = objeto(t) ?: return@propiedad
            val r = o.optJSONArray("results")
            val casos = if (r != null) (0 until r.length()).mapNotNull { r.optJSONObject(it) } else listOf(o)
            objetivo("Tmdb.aTitulo", t) { casos.forEach { Tmdb.aTitulo(it, "movie", emptyList()); Tmdb.aTitulo(it, "tv", listOf("netflix")) } }
        }
    }

    // Regla: JustWatch.aRutas y normalizar sobre respuestas de popularTitles mutadas solo lanzan la JSONException conocida (F-3).
    @Test fun fuzzJustWatch() {
        F_Base.propiedad("fuzz-justwatch", F_Json.mutados(listOf("justwatch_popular.json")), 400) { t ->
            val o = objeto(t) ?: return@propiedad
            val ofertas = ArrayList<JSONArray>(); buscar(o, "offers", ofertas)
            objetivo("JustWatch.aRutas", t) { ofertas.forEach { a -> invocar { aRutasM.invoke(JustWatch, a) } } }
            objetivo("JustWatch.normalizar", t) {
                for (s in listOf("prime", "hbomax", "crunchyroll", "netflix", "otro")) invocar { normalizarM.invoke(JustWatch, s, t) }
            }
        }
    }

    // Regla: AniList.numero sobre cualquier línea del corpus mutado devuelve null o un entero, rápido.
    @Test fun fuzzAniListNumero() {
        F_Base.propiedad("fuzz-anilist-numero", F_Json.mutados(), 400) { t ->
            objetivo("AniList.numero", t) { AniList.numero(t); t.lineSequence().forEach { AniList.numero(it); AniList.numero(JSONObject.quote(it)) } }
            objeto(t)?.let { o -> objetivo("AniList.numero(episodio)", t) { AniList.numero(o.optString("episodio")) } }
        }
    }
}
