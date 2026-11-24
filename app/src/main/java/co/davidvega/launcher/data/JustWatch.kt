package co.davidvega.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pasa de un título de TMDB al enlace exacto de cada app, usando la API GraphQL pública (no documentada) de JustWatch,
 * la misma fuente que TMDB muestra en «dónde ver». Devuelve, por servicio, un enlace ya listo para el Fire TV.
 * Si JustWatch no responde o no reconoce el título, devuelve vacío y el launcher cae a la búsqueda por texto.
 * Detalle y pruebas en el aparato: rutas_comunicacion.md.
 */
object JustWatch {
    private const val CONSULTA = """
query(${'$'}q: String!) { popularTitles(country: CO, first: 8, filter: {searchQuery: ${'$'}q}) { edges { node {
  objectType
  content(country: CO, language: es) { title originalReleaseYear externalIds { tmdbId } genres { shortName } }
  offers(country: CO, platform: WEB) { monetizationType package { shortName } audioLanguages subtitleLanguages standardWebURL fire: deeplinkURL(platform: FIRE_TV) }
} } } }"""

    private const val TENDENCIA = """
query(${'$'}p: [String!]) { popularTitles(country: CO, first: 40, sortBy: TRENDING, filter: {packages: ${'$'}p}) { edges { node {
  objectType
  content(country: CO, language: es) { externalIds { tmdbId } genres { shortName } }
  offers(country: CO, platform: WEB) { monetizationType package { shortName } audioLanguages subtitleLanguages standardWebURL }
} } } }"""

    class Tendencia(val medio: String, val tmdbId: Int, val anime: Boolean, val rutas: Map<String, String>, val doblaje: Doblaje = Doblaje.DESCONOCIDO)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>() // con tope: ver rutas()

    // Códigos de paquete de JustWatch -> clave de servicio del launcher.
    private val SERVICIOS = mapOf("prv" to "prime", "aho" to "prime", "mxx" to "hbomax", "cru" to "crunchyroll", "nfx" to "netflix")
    private val GRATIS = setOf("FLATRATE", "FREE", "ADS")

    /** servicio -> URI (https, crunchyroll://...) lista para ACTION_VIEW con el paquete de la app. */
    suspend fun rutas(t: Titulo): Map<String, String> = withContext(Dispatchers.IO) {
        val clave = t.medio + t.id
        cache[clave]?.let { return@withContext it }
        val r = try { consultar(t) } catch (e: Exception) { Diagnostico.registrar("justwatch", e.javaClass.simpleName); emptyMap() }
        if (r.isNotEmpty()) { if (cache.size >= 500) cache.clear(); cache[clave] = r }
        r
    }

    /**
     * Lo que está en tendencia esta semana en Colombia (orden TRENDING de JustWatch) dentro de los servicios dados
     * (claves del launcher: prime, hbomax, crunchyroll). Vacío si JustWatch no responde.
     */
    suspend fun tendencias(servicios: Collection<String>): List<Tendencia> = withContext(Dispatchers.IO) {
        val paquetes = SERVICIOS.filterValues { it in servicios }.keys.toList()
        if (paquetes.isEmpty()) return@withContext emptyList()
        try {
            val edges = post(JSONObject().put("query", TENDENCIA).put("variables", JSONObject().put("p", org.json.JSONArray(paquetes))))
                ?.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return@withContext emptyList()
            (0 until edges.length()).mapNotNull { i ->
                val n = edges.getJSONObject(i).getJSONObject("node")
                val c = n.optJSONObject("content")
                val id = c?.optJSONObject("externalIds")?.optString("tmdbId")?.toIntOrNull() ?: return@mapNotNull null
                val gs = c.optJSONArray("genres")
                val anime = gs != null && (0 until gs.length()).any { gs.getJSONObject(it).optString("shortName") == "ani" }
                val medio = if (n.optString("objectType") == "SHOW") "tv" else "movie"
                val doblaje = registrarDoblaje(medio, id, n.optJSONArray("offers"), anime)
                Tendencia(medio, id, anime, aRutas(n.optJSONArray("offers")), doblaje)
            }
        } catch (e: Exception) { Diagnostico.registrar("justwatch", "tendencias " + e.javaClass.simpleName); emptyList() }
    }

    private fun post(cuerpo: JSONObject): JSONObject? {
        val c = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            c.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            if (c.responseCode != 200) { Diagnostico.registrar("justwatch", "HTTP " + c.responseCode); return null }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun consultar(t: Titulo): Map<String, String> {
        val cuerpo = JSONObject().put("query", CONSULTA).put("variables", JSONObject().put("q", t.titulo)).toString()
        val c = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            c.outputStream.use { it.write(cuerpo.toByteArray()) }
            val codigo = c.responseCode
            if (codigo != 200) { Diagnostico.registrar("justwatch", "HTTP $codigo"); return emptyMap() }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val edges = j.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges") ?: return emptyMap()
            val tipo = if (t.medio == "tv") "SHOW" else "MOVIE"
            for (i in 0 until edges.length()) {
                val n = edges.getJSONObject(i).getJSONObject("node")
                if (n.optString("objectType") != tipo) continue
                val tmdb = n.optJSONObject("content")?.optJSONObject("externalIds")?.optString("tmdbId")
                if (tmdb != t.id.toString()) continue
                val gs = n.optJSONObject("content")?.optJSONArray("genres")
                val anime = t.idioma == "ja" || (gs != null && (0 until gs.length()).any { gs.optJSONObject(it)?.optString("shortName") == "ani" })
                registrarDoblaje(t.medio, t.id, n.optJSONArray("offers"), anime)
                return aRutas(n.optJSONArray("offers"))
            }
        } finally {
            c.disconnect()
        }
        return emptyMap()
    }

    /** Calcula el doblaje de las ofertas y lo deja en la caché de Doblaje (7 días). Si el esquema cambia, DESCONOCIDO sin romper nada. */
    private fun registrarDoblaje(medio: String, tmdbId: Int, ofertas: org.json.JSONArray?, anime: Boolean): Doblaje = try {
        val of = Doblaje.aOfertas(ofertas) { SERVICIOS[it] }
        Doblaje.registrar(medio, tmdbId, of, anime)
        Doblaje.calcular(of, anime)
    } catch (e: Exception) { Diagnostico.registrar("justwatch", "doblaje " + e.javaClass.simpleName); Doblaje.DESCONOCIDO }

    private fun aRutas(ofertas: org.json.JSONArray?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (ofertas == null) return out
        for (i in 0 until ofertas.length()) {
            val o = ofertas.optJSONObject(i) ?: continue
            if (o.optString("monetizationType") !in GRATIS) continue
            val servicio = SERVICIOS[o.optJSONObject("package")?.optString("shortName")] ?: continue
            if (servicio in out) continue
            val uri = normalizar(servicio, o.optString("standardWebURL")) ?: continue
            out[servicio] = uri
        }
        return out
    }

    /**
     * Deja el enlace en la forma que se probó en el Fire TV:
     *  - Prime: https://app.primevideo.com/detail?gti=<gti> (firebat). JustWatch ofrece amzn://com.amazon.tv.launcher/detail?... que
     *    pasa por el inicio de Amazon; el https llega directo a la ficha de Prime Video.
     *  - HBO Max: https://play.hbomax.com/video/watch/<uuid> (reproduce).
     *  - Crunchyroll: crunchyroll://episode?id=<id> (reproduce; JustWatch indica el componente .MainActivity, que en el Fire TV es .main.ui.MainActivity).
     *  - Netflix: https://www.netflix.com/watch/<id>.
     */
    private fun normalizar(servicio: String, web: String): String? = when (servicio) {
        "prime" -> Regex("gti=(amzn1\\.dv\\.gti\\.[0-9a-f-]+)").find(web)?.groupValues?.get(1)?.let { "https://app.primevideo.com/detail?gti=$it" }
        "hbomax" -> Regex("play\\.hbomax\\.com/video/watch/([0-9a-f-]+)").find(web)?.groupValues?.get(1)?.let { "https://play.hbomax.com/video/watch/$it" }
        "crunchyroll" -> Regex("crunchyroll\\.com/watch/([A-Z0-9]+)").find(web)?.groupValues?.get(1)?.let { "crunchyroll://episode?id=$it" }
        "netflix" -> Regex("netflix\\.com/(?:title|watch)/(\\d+)").find(web)?.groupValues?.get(1)?.let { "https://www.netflix.com/watch/$it" }
        else -> null
    }
}
