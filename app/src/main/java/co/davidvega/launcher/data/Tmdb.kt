package co.davidvega.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cliente mínimo de TMDB (v3). Acepta la API key (v3) o el Read Access Token (v4). Sin librerías extra. */
class Tmdb(
    private val clave: String?,
    private val token: String?,
    private val idioma: String,
    private val region: String,
) {
    val disponible: Boolean get() = !clave.isNullOrBlank() || !token.isNullOrBlank()

    /** Peticiones fallidas (red, HTTP distinto de 200 y 404) desde que arrancó el proceso. Sirve para saber si una carga quedó incompleta. */
    val fallos = java.util.concurrent.atomic.AtomicInteger(0)

    /** Respuesta de TMDB: `json` es null si falló o no existe; `error` distingue un fallo (red, HTTP) de un 404. */
    class Resp(val json: JSONObject?, val error: Boolean)

    suspend fun get(ruta: String, vararg params: Pair<String, String>): JSONObject? = getR(ruta, *params).json

    /** Resultado de un intento: `reintentable` solo si fue timeout o HTTP 5xx; `motivo` describe el fallo para el diagnóstico. */
    private class Intento(val resp: Resp, val reintentable: Boolean, val motivo: String?)

    /**
     * Una petición con hasta 2 reintentos (esperas de 1 s y 3 s) solo ante timeout o HTTP 5xx. Los 404 y demás 4xx no se reintentan.
     * Las consultas internas del filtro (`_crudo`) van sin reintento: tienen su propio tope de 7 s.
     */
    suspend fun getR(ruta: String, vararg params: Pair<String, String>): Resp = withContext(Dispatchers.IO) {
        val puedeReintentar = params.none { it.first == "_crudo" }
        var n = 0
        var r = intentar(ruta, *params)
        while (r.reintentable && puedeReintentar && n < ESPERAS_MS.size) { kotlinx.coroutines.delay(ESPERAS_MS[n]); n++; r = intentar(ruta, *params) }
        if (r.resp.error) {
            fallos.incrementAndGet()
            Diagnostico.registrar("tmdb", "${r.motivo} en $ruta" + if (n > 0) " (tras $n reintentos)" else "")
        }
        r.resp
    }

    private suspend fun intentar(ruta: String, vararg params: Pair<String, String>): Intento {
        try {
            val lang = params.firstOrNull { it.first == "language" }?.second ?: idioma
            val q = StringBuilder("language=").append(enc(lang))
            if (!clave.isNullOrBlank()) q.append("&api_key=").append(enc(clave))
            params.filter { it.first != "language" && it.first != "_crudo" }.forEach { (k, v) -> q.append('&').append(enc(k)).append('=').append(enc(v)) }
            // Sin pornografía: toda búsqueda o descubrimiento va con include_adult=false y sin las palabras clave explícitas.
            val listado = ruta.startsWith("/search") || ruta.startsWith("/discover") || ruta.startsWith("/trending")
            if (listado && params.none { it.first == "include_adult" }) q.append("&include_adult=false")
            if (ruta.startsWith("/discover") && params.none { it.first == "without_keywords" } && Bloqueo.idsKeywords.isNotEmpty()) q.append("&without_keywords=").append(enc(Bloqueo.idsKeywords.joinToString("|")))
            val c = URL("https://api.themoviedb.org/3$ruta?$q").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            if (clave.isNullOrBlank() && !token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            try {
                val codigo = c.responseCode
                if (codigo != 200) return if (codigo == 404) Intento(Resp(null, false), false, null) else Intento(Resp(null, true), reintentable(codigo, null), "HTTP $codigo")
                return Intento(Resp(JSONObject(c.inputStream.bufferedReader().use { it.readText() }).also { if (params.none { p -> p.first == "_crudo" }) filtrar(ruta, it) }, false), false, null)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            return Intento(Resp(null, true), reintentable(0, e), e.javaClass.simpleName)
        }
    }

    // -- Caché de proveedores por título: 24 h, en filesDir (pm trim-caches vacía cacheDir). Un fallo de red usa la entrada aunque esté vieja.
    private val cacheProv = CacheProveedores()
    @Volatile private var archivoProv: java.io.File? = null

    fun usarCacheProveedores(f: java.io.File) {
        archivoProv = f
        try { cacheProv.cargar(f.readText()) } catch (_: Exception) {}
    }

    /** Escribe la caché de proveedores si cambió desde la última vez (atómico: temporal + renombrado). */
    fun guardarCacheProveedores() {
        val f = archivoProv ?: return
        val texto = cacheProv.serializarSiCambio() ?: return
        Archivos.escribirOk(f, texto)
    }

    // -- Filtro de contenido explícito (ver Filtro.kt): todo listado de TMDB pasa por filtrar() antes de salir de getR.
    private val veredictos = java.util.concurrent.ConcurrentHashMap<String, String>() // «medio:id» -> "" (pasa) o motivo
    private val semFiltro = kotlinx.coroutines.sync.Semaphore(6)
    @Volatile private var archivoFiltro: java.io.File? = null

    /** Caché persistente de veredictos (solo ids y motivos). */
    fun usarCacheFiltro(f: java.io.File) {
        archivoFiltro = f
        try { JSONObject(f.readText()).let { o -> o.keys().forEach { k -> veredictos[k] = o.getString(k) } } } catch (_: Exception) {}
    }

    /** Escritura serializada y atómica (temporal + renombrado); con más de 8000 entradas se descartan las sobrantes del mapa. */
    private fun guardarFiltro() {
        val f = archivoFiltro ?: return
        synchronized(veredictos) {
            try {
                if (veredictos.size > 8000) veredictos.keys.toList().take(veredictos.size - 8000).forEach { veredictos.remove(it) }
                val o = JSONObject()
                veredictos.entries.forEach { o.put(it.key, it.value) }
                Archivos.escribir(f, o.toString())
            } catch (_: Exception) {}
        }
    }

    private fun medioDe(ruta: String, o: JSONObject): String =
        o.optString("media_type").ifBlank { if (Regex("^/(discover|search|trending)/tv|^/tv/").containsMatchIn(ruta)) "tv" else "movie" }

    /** Aplica las cinco capas a los títulos de un listado. Las capas 2, 3 y 5 piden el detalle (palabras clave y certificación) una vez por título. */
    private suspend fun filtrar(ruta: String, j: JSONObject) {
        val r = j.optJSONArray("results") ?: return
        if (ruta.startsWith("/search/keyword")) {
            val ok = org.json.JSONArray()
            for (i in 0 until r.length()) { val o = r.optJSONObject(i) ?: continue; if (!Bloqueo.palabraClave(o.optString("name"))) ok.put(o) }
            j.put("results", ok); return
        }
        if (ruta.endsWith("/keywords") || ruta.endsWith("/videos") || ruta.contains("/watch/providers") || ruta.startsWith("/genre") || ruta.startsWith("/search/person")) return
        val candidatos = ArrayList<Pair<String, JSONObject>>()
        val bloqueados = LinkedHashMap<String, String>()
        val ok = ArrayList<JSONObject>()
        for (i in 0 until r.length()) {
            val o = r.optJSONObject(i) ?: continue
            if (o.optString("media_type") == "person" || !(o.has("overview") || o.has("poster_path"))) { ok += o; continue }
            val m = medioDe(ruta, o)
            val k = m + ":" + o.optInt("id")
            val texto = o.optString("title") + " " + o.optString("original_title") + " " + o.optString("name") + " " + o.optString("original_name") + " " + o.optString("overview")
            when {
                o.optBoolean("adult", false) -> bloqueados[k] = "adult"
                Bloqueo.explicito(texto) -> bloqueados[k] = "texto"
                veredictos[k] != null -> { val v = veredictos[k]!!; if (v.isEmpty()) ok += o else bloqueados[k] = v }
                o.optInt("vote_count", 0) >= 2500 -> ok += o // muy conocido: no hace falta pedir el detalle
                else -> candidatos += k to o
            }
        }
        if (candidatos.isNotEmpty()) {
            val hechos = java.util.concurrent.ConcurrentHashMap<String, String>()
            kotlinx.coroutines.withTimeoutOrNull(7000) {
                kotlinx.coroutines.coroutineScope {
                    candidatos.map { (k, o) ->
                        async {
                            val m = k.substringBefore(':'); val id = k.substringAfter(':')
                            val d = semFiltro.withPermit {
                                getR("/$m/$id", "language" to "en-US", "append_to_response" to (if (m == "tv") "keywords,content_ratings" else "keywords,release_dates"), "_crudo" to "1")
                            }.json
                            if (d != null) hechos[k] = Bloqueo.veredicto(m, d) ?: ""
                        }
                    }.forEach { it.await() }
                }
            }
            for ((k, o) in candidatos) {
                val v = hechos[k]
                if (v == null) { if (Bloqueo.sospechosoSinDetalle(o)) bloqueados[k] = "sin_info" else ok += o; continue } // sin detalle: no se guarda
                veredictos[k] = v
                if (v.isEmpty()) ok += o else bloqueados[k] = v
            }
            guardarFiltro()
        }
        if (bloqueados.isNotEmpty()) {
            bloqueados.forEach { (k, motivo) -> Diagnostico.registrarFiltro(k, motivo) }
            android.util.Log.i("LauncherBloqueo", "$ruta: ocultos ${bloqueados.size} de ${r.length()}")
        }
        // Se conserva el orden original del listado.
        val orden = LinkedHashSet<Int>()
        val salida = org.json.JSONArray()
        for (i in 0 until r.length()) { val o = r.optJSONObject(i) ?: continue; if (ok.any { it === o }) salida.put(o) }
        j.put("results", salida)
    }

    /** Busca el primer resultado de un título. Devuelve (id, medio) o null. */
    suspend fun buscar(titulo: String, medio: String?): Pair<Int, String>? {
        if (medio == "tv" || medio == "movie") {
            val r = get("/search/$medio", "query" to titulo)?.optJSONArray("results") ?: return null
            if (r.length() == 0) return null
            return r.getJSONObject(0).getInt("id") to medio
        }
        val r = get("/search/multi", "query" to titulo)?.optJSONArray("results") ?: return null
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val m = o.optString("media_type")
            if (m == "tv" || m == "movie") return o.getInt("id") to m
        }
        return null
    }

    /** Un resultado de búsqueda con sus nombres en español, inglés y original, para emparejar lo que reproduce una app. */
    class Candidato(val medio: String, val id: Int, val es: String, var en: String, val original: String, val anio: String, val votos: Int, val json: JSONObject) {
        val clave get() = "$medio:$id"
        var runtimeMin = -1
        /** Título en español de México: el que usan HBO Max y Prime Video en Latinoamérica («El contador», no «El contable»). */
        var mx = ""
        val nombres get() = listOf(es, en, original, mx).filter { it.isNotBlank() }
    }

    /** Busca películas y series por nombre en es-CO, en-US y es-MX y junta los resultados por título. */
    suspend fun candidatos(nombre: String): List<Candidato> {
        val out = LinkedHashMap<String, Candidato>()
        for (lang in listOf("es-CO", "en-US", "es-MX")) {
            val r = get("/search/multi", "query" to nombre, "language" to lang, "include_adult" to "false")?.optJSONArray("results") ?: continue
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i)
                val m = o.optString("media_type")
                if (m != "tv" && m != "movie") continue
                val titulo = o.optString("title").ifBlank { o.optString("name") }
                val k = m + ":" + o.optInt("id")
                val ex = out[k]
                if (ex != null) { if (lang == "en-US") ex.en = titulo else if (lang == "es-MX") ex.mx = titulo; continue }
                val fecha = o.optString("release_date").ifBlank { o.optString("first_air_date") }
                out[k] = Candidato(m, o.optInt("id"), titulo, if (lang == "en-US") titulo else "", o.optString("original_title").ifBlank { o.optString("original_name") },
                    fecha.take(4), o.optInt("vote_count"), o).also { if (lang == "es-MX") it.mx = titulo }
            }
        }
        return out.values.toList()
    }

    /** Duración en minutos de una película (o -1). */
    /** Duración o temporadas, géneros, dirección y reparto de un título para el hero (una sola petición con créditos). */
    suspend fun detalle(medio: String, id: Int): Detalle? {
        val o = get("/$medio/$id", "append_to_response" to "credits,images", "include_image_language" to "es,en,null") ?: return null
        val duracion = if (medio == "tv") {
            val n = o.optInt("number_of_seasons", 0)
            if (n > 0) "$n temporada" + (if (n == 1) "" else "s") else ""
        } else {
            val m = o.optInt("runtime", 0)
            if (m > 0) (if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min") else ""
        }
        val gen = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty().filter { it.isNotBlank() }.take(3)
        val cred = o.optJSONObject("credits")
        val reparto = cred?.optJSONArray("cast")?.let { a -> (0 until minOf(a.length(), 4)).map { a.getJSONObject(it).optString("name") } }.orEmpty().filter { it.isNotBlank() }
        val dir = if (medio == "tv") o.optJSONArray("created_by")?.let { a -> (0 until minOf(a.length(), 2)).map { a.getJSONObject(it).optString("name") } }?.joinToString(", ")?.takeIf { it.isNotBlank() }
        else cred?.optJSONArray("crew")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("job") == "Director" }.map { it.optString("name") } }?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() }
        // Logo del título: primero en español, luego en inglés, luego sin idioma; el más ancho de su idioma.
        val logos = o.optJSONObject("images")?.optJSONArray("logos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        fun pref(l: org.json.JSONObject) = when (l.optString("iso_639_1")) { "es" -> 0; "en" -> 1; "" , "null" -> 2; else -> 3 }
        val logo = logos.filter { pref(it) < 3 && it.optString("file_path").endsWith(".png") }.sortedWith(compareBy({ pref(it) }, { -it.optDouble("vote_average", 0.0) })).firstOrNull()?.optString("file_path")?.let { IMG + "w500" + it }
        val temporadas = if (medio == "tv") o.optJSONArray("seasons")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("season_number") > 0 }.map { it.optInt("season_number") to it.optInt("episode_count") } }.orEmpty() else emptyList()
        return Detalle(duracion, gen, dir, reparto, logo, temporadas)
    }

    suspend fun duracionMin(id: Int): Int = get("/movie/$id")?.optInt("runtime", -1)?.takeIf { it > 0 } ?: -1

    suspend fun idPalabraClave(texto: String): Int? {
        val r = get("/search/keyword", "query" to texto)?.optJSONArray("results") ?: return null
        return if (r.length() > 0) r.getJSONObject(0).getInt("id") else null
    }

    /** Claves de proveedor (según el perfil) donde está disponible en la región, por prioridad. */
    suspend fun proveedoresDe(medio: String, id: Int, gustos: Gustos): List<String> = proveedoresONull(medio, id, gustos) ?: emptyList()

    /** Como proveedoresDe, pero null si la consulta falló (red): así no se confunde «no se sabe» con «no está en tus servicios». */
    suspend fun proveedoresONull(medio: String, id: Int, gustos: Gustos): List<String>? {
        val huella = gustos.proveedores.joinToString(",") { it.clave }
        val k = "$medio:$id"
        cacheProv.obtener(k, huella, System.currentTimeMillis())?.let { return it }
        val resp = getR("/$medio/$id/watch/providers")
        if (resp.error) return cacheProv.obtener(k, huella, System.currentTimeMillis(), aceptarVieja = true)
        val r = resp.json?.optJSONObject("results")?.optJSONObject(region)
        val ids = HashSet<Int>()
        if (r != null) for (tipo in listOf("flatrate", "free", "ads")) {
            val a = r.optJSONArray(tipo) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.optInt("provider_id", -1)?.takeIf { it >= 0 }?.let { ids.add(it) }
        }
        val claves = ids.mapNotNull { gustos.claveDeProveedorTmdb(it) }.toSet()
        val res = gustos.proveedores.map { it.clave }.filter { it in claves }
        cacheProv.poner(k, huella, res, System.currentTimeMillis())
        return res
    }

    /** Episodios de una temporada de una serie en es-MX: (número, nombre). Vacío si falla o no existe. */
    suspend fun episodiosDeTemporada(id: Int, temporada: Int): List<Pair<Int, String>> {
        val a = get("/tv/$id/season/$temporada", "language" to "es-MX")?.optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> e.optInt("episode_number", -1).takeIf { it > 0 }?.let { n -> n to e.optString("name") } } }
    }

    /** Recomendaciones de TMDB para un título (para la fila «Títulos parecidos» de la ficha). */
    suspend fun parecidos(medio: String, id: Int): List<Titulo> {
        val r = get("/$medio/$id/recommendations")?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Titulo>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            if (o.optInt("vote_count", 0) < 30) continue
            if (!tieneImagen(o)) continue
            out.add(aTitulo(o, medio, emptyList()))
            if (out.size >= 15) break
        }
        return out
    }

    /** Búsqueda libre de películas y series (es-CO). Sin proveedores: los pone Catalogo.buscar. */
    suspend fun buscarMulti(texto: String): List<Titulo> {
        val r = get("/search/multi", "query" to texto, "language" to "es-CO", "include_adult" to "false")?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Titulo>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val m = o.optString("media_type")
            // Una persona (actor, directora): entran sus obras con «Con <nombre>». Si es el primer resultado, la búsqueda
            // era por esa persona y se piden sus créditos completos; si no, bastan sus tres títulos conocidos.
            if (m == "person") {
                val nombre = o.optString("name")
                val obras = if (i == 0) creditos(o.optInt("id")) else o.optJSONArray("known_for")?.let { k -> (0 until k.length()).map { k.getJSONObject(it) } }.orEmpty()
                obras.filter { x -> x.optString("media_type") in setOf("tv", "movie") && x.optString("poster_path").let { it.isNotBlank() && it != "null" } }
                    .forEach { x -> out.add(aTitulo(x, x.optString("media_type"), emptyList(), motivo = "Con $nombre")) }
                continue
            }
            if (m != "tv" && m != "movie") continue
            if (!tieneImagen(o)) continue
            out.add(aTitulo(o, m, emptyList()))
        }
        return out.distinctBy { it.medio + ":" + it.id }.take(20)
    }

    /** Créditos de una persona (reparto y dirección), los más votados primero, sin programas de entrevistas ni noticias. */
    private suspend fun creditos(persona: Int): List<JSONObject> {
        val j = get("/person/$persona/combined_credits", "language" to "es-CO") ?: return emptyList()
        val todos = listOfNotNull(j.optJSONArray("cast"), j.optJSONArray("crew")).flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { o -> o.optString("job").let { it.isBlank() || it == "Director" } }
        return todos.filter { o -> o.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).none { g.getInt(it) in setOf(10767, 10763) } } ?: true }
            .distinctBy { it.optString("media_type") + it.optInt("id") }
            .sortedByDescending { it.optInt("vote_count") }.take(16)
    }

    /** Palabras clave de un título (id, nombre en inglés). */
    suspend fun palabrasClave(medio: String, id: Int): List<Pair<Int, String>> {
        val j = get("/$medio/$id/keywords") ?: return emptyList()
        val a = j.optJSONArray(if (medio == "tv") "results" else "keywords") ?: return emptyList()
        return (0 until minOf(a.length(), 12)).map { a.getJSONObject(it).let { k -> k.getInt("id") to k.optString("name") } }
    }

    /** Nombres alternativos de un título (inglés y original) para casar con catálogos de otras apps. */
    suspend fun nombres(medio: String, id: Int): List<String> {
        val o = get("/$medio/$id", "language" to "en-US") ?: return emptyList()
        return listOf(o.optString("title"), o.optString("name"), o.optString("original_title"), o.optString("original_name")).filter { it.isNotBlank() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Títulos en alfabeto no latino (kanji, cirílico…) se cambian por el título en inglés de TMDB: nunca se muestra CJK como título principal.
     * Orden: español, inglés y, solo si el original está en alfabeto latino, el original. Las respuestas se recuerdan en memoria.
     */
    suspend fun latinizar(lista: List<Titulo>): List<Titulo> = kotlinx.coroutines.coroutineScope {
        lista.map { t ->
            async {
                if (t.id <= 0 || esLatino(t.titulo)) return@async t
                val k = t.medio + t.id
                val en = latinos[k] ?: get("/${t.medio}/${t.id}", "language" to "en-US")?.let { j -> j.optString("title").ifBlank { j.optString("name") } }
                    ?.takeIf { it.isNotBlank() && esLatino(it) }?.also { latinos[k] = it }
                if (en != null) t.copy(titulo = en) else t
            }
        }.awaitAll()
    }

    companion object {
        /** Esperas entre intentos: 1 s y 3 s (dos reintentos). */
        val ESPERAS_MS = longArrayOf(1000L, 3000L)

        /** Solo timeout (de conexión o de lectura) y HTTP 5xx merecen otro intento. */
        fun reintentable(codigo: Int, e: Throwable?): Boolean = e is java.net.SocketTimeoutException || codigo in 500..599

        private val latinos = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Verdadero si todas las letras son latinas (con tildes y ñ incluidas). */
        fun esLatino(s: String) = s.none { c -> Character.isLetter(c) && Character.UnicodeScript.of(c.code).let { it != Character.UnicodeScript.LATIN } }

        const val IMG = "https://image.tmdb.org/t/p/"

        /** TMDB manda "poster_path": null y optString lo convierte en "null": hay que descartarlo aparte. */
        private fun ruta(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        fun tieneImagen(o: JSONObject) = ruta(o, "poster_path") != null || ruta(o, "backdrop_path") != null

        fun aTitulo(o: JSONObject, medio: String, servicios: List<String>, motivo: String? = null): Titulo {
            val fecha = o.optString("release_date").ifBlank { o.optString("first_air_date") }
            val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val fondo = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            return Titulo(
                id = o.optInt("id"), medio = medio,
                titulo = o.optString("title").ifBlank { o.optString("name") }.let { if (esLatino(it)) it else latinos[medio + o.optInt("id")] ?: it },
                sinopsis = o.optString("overview"),
                poster = poster?.let { IMG + "w342" + it },
                fondo = (fondo ?: poster)?.let { IMG + (if (fondo != null) "w1280" else "w780") + it },
                anio = fecha.take(4), nota = o.optDouble("vote_average", 0.0),
                servicios = servicios, motivo = motivo,
                generos = o.optJSONArray("genre_ids").aListaInt(), idioma = o.optString("original_language"),
            )
        }
    }
}
