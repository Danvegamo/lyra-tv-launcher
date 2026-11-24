package co.davidvega.launcher.data

import org.json.JSONObject

/**
 * Filtro de contenido explícito (pornografía, softcore, erótica) en capas. Todo resultado de TMDB pasa por aquí antes de pintarse:
 * Tmdb.getR lo aplica a cualquier lista (búsqueda, temáticas, «ver más», recomendaciones, descubrimiento, tendencias).
 *
 *  1. adult = true: fuera.
 *  2. Palabras clave de TMDB (ids y nombres de datos/bloqueo.json): erotic, softcore, sexploitation, pornography, hentai, pink film...
 *  3. Certificación (release_dates / content_ratings): NC-17, X y similares siempre; «18» de MX, ES, BR... solo con palabra clave de tema sexual.
 *  4. Palabras en título y sinopsis (es/en/pt/ja romanizado): porn, xxx, erótic, sexo explícito, hentai, «para adultos», MILF...
 *  5. Sin palabras clave ni certificación: si además tiene pocos votos, sinopsis mínima y género Romance o Drama, se oculta.
 *
 * La lista vive en datos/bloqueo.json (assets) y se puede sustituir con un bloqueo.json en la carpeta externa de la app.
 * Lo filtrado se anota en diagnostico.jsonl con tipo «filtro», solo el id y el motivo.
 */
object Bloqueo {
    class Config(
        val ids: Set<Int>, val kwRegex: Regex, val sexRegex: Regex, val duras: Set<String>, val conTema: Map<String, Set<String>>,
        val texto: Regex, val votosMax: Int, val sinopsisMax: Int, val generos: Set<Int>,
    )

    @Volatile private var cfg: Config = porDefecto()

    private fun porDefecto() = Config(
        emptySet(),
        Regex("erotic|erotica|softcore|soft core|sexploitation|pornograph|^porn$|hentai|pink film|pinku|adult film|explicit sex|unsimulated|nunsploitation", RegexOption.IGNORE_CASE),
        Regex("sex|nud|erotic|porn|lingerie|strip|fetish|brothel", RegexOption.IGNORE_CASE),
        setOf("NC-17", "X", "XXX", "R18", "R18+", "R-18"),
        mapOf("MX" to setOf("C", "D"), "ES" to setOf("18"), "BR" to setOf("18")),
        Regex("""\b(porn\w*|xxx|hentai|softcore|sexploitation|pink film|er[oó]tic\w*|erotism\w*|sexo expl[ií]cito|explicit sex|adults? only|adult film|milf|nc-17|x-rated|nympho\w*)\b""", RegexOption.IGNORE_CASE),
        20, 60, setOf(10749, 18),
    )

    fun cargar(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            val d = porDefecto()
            val ids = HashSet<Int>()
            o.optJSONObject("keywords")?.keys()?.forEach { k -> k.toIntOrNull()?.let { ids += it } }
            val ct = HashMap<String, Set<String>>()
            o.optJSONObject("certificaciones_con_tema_sexual")?.let { c -> c.keys().forEach { pais -> ct[pais] = c.getJSONArray(pais).let { a -> (0 until a.length()).map { a.getString(it).uppercase() }.toSet() } } }
            val duras = o.optJSONArray("certificaciones_duras")?.let { a -> (0 until a.length()).map { a.getString(it).uppercase() }.toSet() } ?: d.duras
            val h = o.optJSONObject("heuristica_sin_info")
            cfg = Config(
                ids,
                o.optString("palabras_clave_regex").takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) } ?: d.kwRegex,
                o.optString("tema_sexual_regex").takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) } ?: d.sexRegex,
                duras, if (ct.isEmpty()) d.conTema else ct,
                o.optString("texto_regex").takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) } ?: d.texto,
                h?.optInt("votos_max", 20) ?: 20, h?.optInt("sinopsis_max", 60) ?: 60,
                h?.optJSONArray("generos")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: d.generos,
            )
        } catch (e: Exception) { android.util.Log.w("LauncherBloqueo", "bloqueo.json inválido: $e") }
    }

    /** Capa 4: título o sinopsis explícitos. */
    fun explicito(texto: String) = texto.isNotBlank() && cfg.texto.containsMatchIn(texto)

    /** Una consulta que pide directamente lo explícito («erótico», «hentai»...) no devuelve nada, ni títulos ni temáticas. */
    fun consultaExplicita(texto: String) = explicito(texto) || palabraClave(texto.trim())

    /** Palabra clave explícita por nombre (para /search/keyword). */
    fun palabraClave(nombre: String) = cfg.kwRegex.containsMatchIn(nombre)

    /** Ids de palabras clave que se excluyen en /discover (without_keywords). */
    val idsKeywords: Set<Int> get() = cfg.ids

    /**
     * Capas 1, 2, 3 y 5 sobre el detalle de un título pedido con append_to_response (keywords y release_dates o content_ratings).
     * Devuelve el motivo si hay que ocultarlo, o null si pasa.
     */
    fun veredicto(medio: String, d: JSONObject): String? {
        val c = cfg
        if (d.optBoolean("adult", false)) return "adult"
        val kws = ArrayList<Pair<Int, String>>()
        d.optJSONObject("keywords")?.optJSONArray(if (medio == "tv") "results" else "keywords")?.let { a ->
            for (i in 0 until a.length()) { val k = a.optJSONObject(i) ?: continue; kws += k.optInt("id") to k.optString("name") }
        }
        kws.firstOrNull { it.first in c.ids || c.kwRegex.containsMatchIn(it.second) }?.let { return "kw:" + it.second }
        val temaSexual = kws.any { c.sexRegex.containsMatchIn(it.second) }
        var hayCert = false
        fun cert(pais: String, valor: String): String? {
            val v = valor.trim().uppercase()
            if (v.isEmpty()) return null
            hayCert = true
            if (v in c.duras) return "cert:$pais $v"
            if (temaSexual && c.conTema[pais]?.contains(v) == true) return "cert+tema:$pais $v"
            return null
        }
        if (medio == "tv") {
            d.optJSONObject("content_ratings")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) { val r = a.optJSONObject(i) ?: continue; cert(r.optString("iso_3166_1"), r.optString("rating"))?.let { return it } }
            }
        } else {
            d.optJSONObject("release_dates")?.optJSONArray("results")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    val fechas = r.optJSONArray("release_dates") ?: continue
                    for (j in 0 until fechas.length()) cert(r.optString("iso_3166_1"), fechas.optJSONObject(j)?.optString("certification").orEmpty())?.let { return it }
                }
            }
        }
        if (kws.isEmpty() && !hayCert) {
            val g = d.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } }.orEmpty()
            if (d.optInt("vote_count", 0) <= c.votosMax && d.optString("overview").length <= c.sinopsisMax && g.any { it in c.generos }) return "sin_info"
        }
        return null
    }

    /** Capa 5 cuando no se pudo pedir el detalle (red lenta): con lo que trae el listado. */
    fun sospechosoSinDetalle(o: JSONObject): Boolean {
        val c = cfg
        val g = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() } }.orEmpty()
        return o.optInt("vote_count", 0) <= c.votosMax && o.optString("overview").length <= c.sinopsisMax && g.any { it in c.generos }
    }
}
