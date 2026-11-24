package co.davidvega.launcher.pruebas

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.Shrinker
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Infraestructura del paquete F. Perfil normal: semilla fija y pocas iteraciones (< 60 s en total).
 * Modo largo: `gradlew.bat testDebugUnitTest -Plargo` (semilla nueva en cada tanda, impresa en el log, 25 veces más iteraciones).
 * Para reproducir una tanda larga que falló, fijar la semilla impresa con -Pfsemilla=<n>.
 */
object F_Base {
    val largo: Boolean = System.getProperty("pruebas.largo") == "true"
    val semilla: Long = System.getProperty("pruebas.semilla")?.toLongOrNull() ?: if (largo) System.nanoTime() else 20261121L
    fun iteraciones(base: Int) = if (largo) base * 25 else base

    /** Ejecuta la propiedad con kotest-property (con reducción del caso fallido). */
    fun <A> propiedad(nombre: String, arb: Arb<A>, base: Int = 200, cuerpo: (A) -> Unit) {
        println("[F] $nombre semilla=$semilla iteraciones=${iteraciones(base)} largo=$largo")
        runBlocking { checkAll(PropTestConfig(seed = semilla, iterations = iteraciones(base)), arb) { a -> cuerpo(a) } }
    }

    fun corpus(nombre: String): String =
        F_Base::class.java.getResourceAsStream("/corpus/$nombre")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    val NOMBRES_JSON = listOf(
        "tmdb_detalle_pelicula.json", "tmdb_detalle_serie.json", "tmdb_listado.json", "justwatch_popular.json",
        "reproducciones.jsonl", "seguir.json", "bloqueo.json", "gustos.json", "titulo.json",
    )
}

/** Reducción simple de textos: mitades y recorte de extremos. */
object F_ReduceTexto : Shrinker<String> {
    override fun shrink(value: String): List<String> =
        if (value.length <= 1) emptyList()
        else listOf(value.substring(0, value.length / 2), value.substring(value.length / 2), value.drop(1), value.dropLast(1)).distinct()
}

/** Generadores de JSON (en texto, para que la reducción por texto sirva) y mutador de corpus. */
object F_Json {
    private val TROZOS = listOf(
        "null", "true", "false", "[]", "{}", "[[[[[[[[", "]]]]]]", "\"", "\\", "\\u00", "\\ud800", "1e999", "-0", "99999999999999999999",
        "NaN", "\u0000", "🙂", ",", ":", "\"\":", "{\"a\":", "-", ".5", "0x10", " ", "\n",
    )
    private val PALABRAS = listOf(
        "erotic", "porn", "sexo explícito", "Action", "Drama", "hentai", "MILF", "love", "nude", "family", "NC-17", "18", "X", "es", "ja", "en", "ERÓTICA",
    )

    fun palabra(r: Random): String =
        if (r.nextInt(3) == 0) PALABRAS[r.nextInt(PALABRAS.size)] else (1..r.nextInt(1, 9)).map { 'a' + r.nextInt(26) }.joinToString("")

    /** Valor JSON arbitrario de cualquier tipo, con profundidad acotada. */
    fun valor(r: Random, prof: Int = 3): Any? = when (r.nextInt(if (prof > 0) 8 else 6)) {
        0 -> JSONObject.NULL
        1 -> r.nextBoolean()
        2 -> r.nextInt(-5, 100000)
        3 -> r.nextDouble(-10.0, 10.0)
        4, 5 -> palabra(r)
        6 -> JSONArray().also { a -> repeat(r.nextInt(4)) { a.put(valor(r, prof - 1)) } }
        else -> JSONObject().also { o -> repeat(r.nextInt(4)) { o.put(palabra(r), valor(r, prof - 1)) } }
    }

    enum class Modo { BIEN, NULOS, MALOS }

    private val OMITIR = Any()

    /** Valor de un campo: bien tipado; con NULOS puede faltar o ser null; con MALOS además puede tener cualquier tipo. */
    private fun campo(r: Random, modo: Modo, bueno: () -> Any?): Any? {
        if (modo == Modo.BIEN) return bueno()
        val x = r.nextInt(100)
        return when {
            x < 15 -> OMITIR
            x < 25 -> JSONObject.NULL
            modo == Modo.MALOS && x < 45 -> valor(r, 2)
            else -> bueno()
        }
    }

    private fun JSONObject.pon(k: String, v: Any?) { if (v !== OMITIR) put(k, v) }
    private fun lista(r: Random, max: Int, f: () -> Any?) = JSONArray().also { a -> repeat(r.nextInt(max + 1)) { a.put(f()) } }

    /** Detalle de TMDB (append_to_response) con la forma de las respuestas reales. */
    fun detalle(r: Random, medio: String, modo: Modo): JSONObject {
        val d = JSONObject()
        d.pon("adult", campo(r, modo) { r.nextInt(20) == 0 })
        d.pon("vote_count", campo(r, modo) { r.nextInt(0, 3000) })
        d.pon("overview", campo(r, modo) { (1..r.nextInt(0, 12)).joinToString(" ") { palabra(r) } })
        d.pon("genres", campo(r, modo) { lista(r, 3) { JSONObject().also { g -> g.pon("id", campo(r, modo) { listOf(18, 10749, 12, 35)[r.nextInt(4)] }) } } })
        val kws = campo(r, modo) { lista(r, 4) { JSONObject().also { k -> k.pon("id", campo(r, modo) { r.nextInt(1, 500) }); k.pon("name", campo(r, modo) { palabra(r) }) } } }
        d.pon("keywords", campo(r, modo) { JSONObject().also { it.pon(if (medio == "tv") "results" else "keywords", kws) } })
        if (medio == "tv") {
            d.pon("content_ratings", campo(r, modo) {
                JSONObject().also { o ->
                    o.pon("results", campo(r, modo) {
                        lista(r, 3) {
                            JSONObject().also { c ->
                                c.pon("iso_3166_1", campo(r, modo) { listOf("MX", "ES", "BR", "US")[r.nextInt(4)] })
                                c.pon("rating", campo(r, modo) { listOf("NC-17", "18", "C", "TV-MA", "")[r.nextInt(5)] })
                            }
                        }
                    })
                }
            })
        } else {
            d.pon("release_dates", campo(r, modo) {
                JSONObject().also { o ->
                    o.pon("results", campo(r, modo) {
                        lista(r, 3) {
                            JSONObject().also { c ->
                                c.pon("iso_3166_1", campo(r, modo) { listOf("MX", "ES", "BR", "US")[r.nextInt(4)] })
                                c.pon("release_dates", campo(r, modo) {
                                    lista(r, 2) { JSONObject().also { f -> f.pon("certification", campo(r, modo) { listOf("NC-17", "18", "C", "R", "")[r.nextInt(5)] }) } }
                                })
                            }
                        }
                    })
                }
            })
        }
        return d
    }

    /** Ofertas de JustWatch; con BIEN solo ofertas de la forma real (las que el código lee). */
    fun ofertas(r: Random, modo: Modo): JSONArray = lista(r, 6) {
        if (modo == Modo.MALOS && r.nextInt(5) == 0) valor(r, 1) else JSONObject().also { o ->
            o.pon("monetizationType", campo(r, modo) { listOf("FLATRATE", "FREE", "ADS", "BUY", "RENT")[r.nextInt(5)] })
            o.pon("package", campo(r, modo) { JSONObject().also { p -> p.pon("shortName", campo(r, modo) { listOf("prv", "aho", "mxx", "cru", "nfx", "xyz")[r.nextInt(6)] }) } })
            o.pon("standardWebURL", campo(r, modo) { url(r) })
        }
    }

    private fun hex(r: Random, n: Int) = (1..n).map { "0123456789abcdef-"[r.nextInt(17)] }.joinToString("")

    /** URL que a veces trae el identificador de un servicio y a veces ruido. */
    fun url(r: Random): String = when (r.nextInt(6)) {
        0 -> "https://www.primevideo.com/detail?gti=amzn1.dv.gti.${hex(r, r.nextInt(1, 40))}&x=1"
        1 -> "https://play.hbomax.com/video/watch/${hex(r, r.nextInt(1, 40))}"
        2 -> "https://www.crunchyroll.com/watch/${(1..r.nextInt(1, 12)).map { "ABCXYZ0189"[r.nextInt(10)] }.joinToString("")}/serie"
        3 -> "https://www.netflix.com/${listOf("title", "watch")[r.nextInt(2)]}/${r.nextInt(0, 99999999)}"
        4 -> "gti=amzn1.dv.gti.${hex(r, 8).uppercase()}"
        else -> (1..r.nextInt(0, 30)).map { "ab/:.=?&-_0"[r.nextInt(11)] }.joinToString("")
    }

    /** Mutación del texto de una semilla del corpus: borrar, duplicar, insertar trozos raros, cambiar o truncar. */
    fun mutar(base: String, r: Random): String {
        var s = base
        repeat(1 + r.nextInt(4)) {
            if (s.isEmpty()) s = "{}"
            val i = r.nextInt(s.length)
            val fin = minOf(s.length, i + 1 + r.nextInt(30))
            s = when (r.nextInt(10)) {
                0 -> s.removeRange(i, fin)
                1 -> s.substring(0, i) + s.substring(i, fin).repeat(2 + r.nextInt(5)) + s.substring(i)
                2 -> s.substring(0, i) + TROZOS[r.nextInt(TROZOS.size)] + s.substring(i)
                3 -> s.substring(0, i) + r.nextInt(0x2FF).toChar() + s.substring(i + 1)
                4 -> s.substring(0, i)
                5 -> s.replace(Regex("\\d+")) { m -> if (r.nextInt(3) == 0) listOf("99999999999999999999", "-1", "0", "1e999")[r.nextInt(4)] else m.value }
                6 -> s.substring(0, i) + "́".repeat(1 + r.nextInt(3000)) + s.substring(i)
                7 -> s.substring(0, i) + "[".repeat(1 + r.nextInt(700)) + s.substring(i)
                8 -> s.substring(0, i) + "a".repeat(1 + r.nextInt(5000)) + s.substring(i)
                else -> s.substring(0, i) + valor(r).toString() + s.substring(i)
            }
        }
        return s
    }

    /** Arbitrario de textos mutados a partir del corpus, con reducción de texto. */
    fun mutados(nombres: List<String> = F_Base.NOMBRES_JSON): Arb<String> =
        arbitrary(F_ReduceTexto) { rs -> mutar(F_Base.corpus(nombres[rs.random.nextInt(nombres.size)]), rs.random) }

    /** Arbitrario de textos JSON de estructura y tipos arbitrarios. */
    fun aleatorios(): Arb<String> = arbitrary(F_ReduceTexto) { rs -> valor(rs.random, 4).toString() }

    /** Arbitrario de semillas para generadores propios (sin reducción útil: el caso se imprime entero por el propio test). */
    fun semillas(): Arb<Long> = arbitrary { rs -> rs.random.nextLong() }
}
