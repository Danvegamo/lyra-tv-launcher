package co.davidvega.launcher.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/** Filtros de la búsqueda por temática, operables con el control. Se recuerdan entre usos. */
data class FiltrosTema(
    val ocultarVistas: Boolean = true,
    val medio: Int = 0, // 0 todo, 1 películas, 2 series
    val misServicios: Boolean = false,
    val epoca: Int = 0, // 0 todas, 1 2020+, 2 2010s, 3 2000s, 4 antes de 2000
    val notaAlta: Boolean = false,
) {
    fun guardar(ctx: Context) {
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putString("tema_filtros", "${if (ocultarVistas) 1 else 0},$medio,${if (misServicios) 1 else 0},$epoca,${if (notaAlta) 1 else 0}").apply()
    }

    companion object {
        fun cargar(ctx: Context): FiltrosTema = try {
            val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString("tema_filtros", null)!!.split(",").map { it.toInt() }
            FiltrosTema(p[0] == 1, p[1], p[2] == 1, p[3], p[4] == 1)
        } catch (e: Exception) { FiltrosTema() }
    }
}

/** Lo que se entendió de la consulta: etiqueta para los títulos de fila, géneros de TMDB y ids de palabras clave. */
class TemaEntendido(val etiqueta: String, val generosPelicula: List<Int>, val generosSerie: List<Int>, val palabras: List<Int>)

/** Filas de la búsqueda por temática. `masMejores` y `masGusto` indican si se pueden pedir más páginas. */
class ResultadoTema(
    val tema: TemaEntendido,
    val coincidencias: List<Titulo>,
    val mejores: List<Titulo>,
    val gusto: List<Titulo>,
    val inspiradoEn: String?,
    val inspirado: List<Titulo>,
    val vistas: List<Titulo>,
)

/**
 * Búsqueda por temática: «navidad», «terror», «misterio», «locas», «travestis»…
 * Un diccionario español a géneros y palabras clave de TMDB (en inglés) traduce lo que se escribe; las palabras que no están se prueban
 * con /search/keyword en español y en inglés. Después /discover/movie y /discover/tv (palabras clave con OR, varias páginas, ambos medios).
 * Las respuestas de TMDB se guardan 24 h por consulta en el caché de la app y las peticiones van en paralelo con un máximo de 4.
 */
class Tematica(private val tmdb: Tmdb, private val gustos: Gustos, private val carpeta: File) {
    private val sem = Semaphore(4)

    private class Entrada(val generosPelicula: List<Int>, val generosSerie: List<Int>, val palabras: List<String>)

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private val dic: Map<String, Entrada> = HashMap<String, Entrada>().also { m ->
        fun a(claves: String, g: List<Int>, gs: List<Int>, vararg p: String) { val e = Entrada(g, gs, p.toList()); claves.split(" ").forEach { m[it] = e } }
        a("navidad navidades navideno navidena navidenos navidenas", emptyList(), emptyList(), "christmas", "santa claus")
        a("terror miedo horror miedosa miedosas sustos susto", listOf(27), emptyList(), "horror", "slasher")
        a("misterio misterios intriga", listOf(9648), listOf(9648), "mystery", "whodunit")
        a("locas loca locos loco locura absurda absurdo disparatada disparatadas", emptyList(), emptyList(), "absurd humor", "screwball comedy", "slapstick")
        a("travestis travesti travestismo drag", emptyList(), emptyList(), "cross dressing", "drag queen", "transvestite")
        a("comedia comedias risa graciosa graciosas", listOf(35), listOf(35))
        a("drama dramas", listOf(18), listOf(18))
        a("accion", listOf(28), listOf(10759))
        a("aventura aventuras", listOf(12), listOf(10759))
        a("romance romantica romanticas romantico amor", listOf(10749), emptyList(), "romance")
        a("ciencia ficcion scifi", listOf(878), listOf(10765))
        a("fantasia", listOf(14), listOf(10765))
        a("animacion animada animadas", listOf(16), listOf(16))
        a("anime", listOf(16), listOf(16), "anime")
        a("documental documentales", listOf(99), listOf(99))
        a("crimen policial policiaca", listOf(80), listOf(80))
        a("guerra belica", listOf(10752), listOf(10768))
        a("western vaqueros", listOf(37), listOf(37))
        a("familia familiar infantil", listOf(10751), listOf(10751))
        a("musical musicales", listOf(10402), emptyList(), "musical")
        a("suspenso thriller", listOf(53), listOf(9648), "thriller")
        a("zombis zombies zombi", emptyList(), emptyList(), "zombie")
        a("vampiros vampiro", emptyList(), emptyList(), "vampire")
        a("fantasmas fantasma", emptyList(), emptyList(), "ghost", "haunted house")
        a("espias espia", emptyList(), emptyList(), "spy")
        a("carceles carcel prision", emptyList(), emptyList(), "prison")
        a("apocalipsis apocaliptica postapocaliptica", emptyList(), emptyList(), "post-apocalyptic", "apocalypse")
        a("superheroes superheroe", emptyList(), emptyList(), "superhero")
        a("deportes deporte", emptyList(), emptyList(), "sports")
        a("cocina gastronomia", emptyList(), emptyList(), "cooking", "chef")
        a("lgbt gay lesbiana queer", emptyList(), emptyList(), "lgbt", "gay theme")
        a("boda bodas", emptyList(), emptyList(), "wedding")
        a("piratas pirata", emptyList(), emptyList(), "pirate", "pirate ship")
        a("monstruos monstruo", emptyList(), emptyList(), "monster", "creature feature")
        a("dragones dragon", emptyList(), emptyList(), "dragon")
        a("robots robot androides", emptyList(), emptyList(), "robot", "artificial intelligence")
        a("magia magos hechiceros brujas bruja", emptyList(), emptyList(), "magic", "witch", "wizard")
        a("lobos licantropos", emptyList(), emptyList(), "werewolf")
        a("extraterrestres aliens alienigenas ovnis", emptyList(), emptyList(), "alien", "alien invasion")
        a("tiempo viajes", emptyList(), emptyList(), "time travel")
        a("mafia mafiosos gangsters", emptyList(), emptyList(), "mafia", "gangster")
        a("robos atracos golpe", emptyList(), emptyList(), "heist", "bank robbery")
        a("samurais samurai ninjas ninja", emptyList(), emptyList(), "samurai", "ninja")
        a("detectives detective", emptyList(), emptyList(), "detective", "private detective")
        a("colegio escuela instituto", emptyList(), emptyList(), "high school", "school")
        a("selva jungla", emptyList(), emptyList(), "jungle", "survival")
        a("tiburones tiburon", emptyList(), emptyList(), "shark")
        a("dinosaurios dinosaurio", emptyList(), emptyList(), "dinosaur")
        a("juicio abogados tribunal", emptyList(), emptyList(), "lawyer", "courtroom")
        a("hospital medicos doctores", emptyList(), emptyList(), "hospital", "doctor")
        a("secuestro secuestros", emptyList(), emptyList(), "kidnapping")
        a("venganza", emptyList(), emptyList(), "revenge")
        a("verano playa", emptyList(), emptyList(), "beach", "summer")
    }

    private val sinSentido = setOf("de", "la", "el", "los", "las", "un", "una", "y", "o", "en", "con", "para", "del", "que", "peliculas", "pelicula", "series", "serie", "sobre", "tipo", "como", "muy")

    // -- Caché de 24 h por consulta en disco
    private suspend fun g(ruta: String, vararg p: Pair<String, String>): JSONObject? {
        val clave = ruta + "?" + p.sortedBy { it.first }.joinToString("&") { it.first + "=" + it.second }
        val f = File(carpeta, MessageDigest.getInstance("SHA-1").digest(clave.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
        withContext(Dispatchers.IO) {
            try { if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return@withContext JSONObject(f.readText()) } catch (_: Exception) {}
            null
        }?.let { return it }
        val r = sem.withPermit { tmdb.get(ruta, *p) } ?: return null
        withContext(Dispatchers.IO) { Archivos.escribirOk(f, r.toString()) }
        return r
    }

    private suspend fun idPalabra(texto: String): Int? {
        for (lang in listOf("es-CO", "en-US")) {
            val r = g("/search/keyword", "query" to texto, "language" to lang)?.optJSONArray("results") ?: continue
            if (r.length() > 0) return r.getJSONObject(0).getInt("id")
        }
        return null
    }

    /** Respaldo para una palabra fuera del diccionario: las dos primeras palabras clave de TMDB en español y las dos en inglés (sin repetir). */
    private suspend fun idsPalabraDesconocida(texto: String): List<Int> {
        val ids = LinkedHashSet<Int>()
        for (lang in listOf("es-CO", "en-US")) {
            val r = g("/search/keyword", "query" to texto, "language" to lang)?.optJSONArray("results") ?: continue
            for (i in 0 until minOf(2, r.length())) ids += r.getJSONObject(i).getInt("id")
        }
        return ids.toList()
    }

    /** Traduce la consulta a géneros y palabras clave. Null si no hay nada temático (se queda la búsqueda de títulos de siempre). */
    suspend fun entender(texto: String): TemaEntendido? {
        if (Bloqueo.consultaExplicita(texto)) return null
        val fichas = norm(texto).split(" ").filter { it.length > 2 && it !in sinSentido }
        if (fichas.isEmpty() || fichas.size > 5) return null
        val gp = LinkedHashSet<Int>(); val gs = LinkedHashSet<Int>(); val palabras = LinkedHashSet<String>(); val desconocidas = ArrayList<String>()
        for (t in fichas) {
            val e = dic[t] ?: dic[t.removeSuffix("s")] ?: dic[t.removeSuffix("es")]
            if (e == null) desconocidas += t else { gp += e.generosPelicula; gs += e.generosSerie; palabras += e.palabras }
        }
        // Palabras que no están en el diccionario: probar como palabra clave de TMDB (solo si no hay nada más, para no meter ruido).
        val ids = coroutineScope {
            val propias = palabras.map { p -> async { idPalabra(p) } }
            val extra = desconocidas.filter { it.length > 3 }.map { d -> async { idsPalabraDesconocida(d) } }
            (propias.awaitAll().filterNotNull() + extra.awaitAll().flatten()).distinct()
        }
        if (gp.isEmpty() && gs.isEmpty() && ids.isEmpty()) return null
        return TemaEntendido(texto.trim(), gp.toList(), gs.toList(), ids)
    }

    /** Una página de /discover con los filtros. Las palabras clave van con OR y los géneros en otra petición (así se suman en vez de cruzarse). */
    suspend fun pagina(e: TemaEntendido, f: FiltrosTema, mejores: Boolean, pagina: Int): List<Titulo> = coroutineScope {
        val medios = when (f.medio) { 1 -> listOf("movie"); 2 -> listOf("tv"); else -> listOf("movie", "tv") }
        val llamadas = ArrayList<kotlinx.coroutines.Deferred<List<Titulo>>>()
        for (m in medios) {
            val gen = if (m == "movie") e.generosPelicula else e.generosSerie
            for (modo in 0..1) {
                if (modo == 0 && e.palabras.isEmpty()) continue
                if (modo == 1 && gen.isEmpty()) continue
                llamadas += async {
                    val p = ArrayList<Pair<String, String>>()
                    p += "include_adult" to "false"; p += "page" to pagina.toString()
                    if (modo == 0) p += "with_keywords" to e.palabras.joinToString("|") else p += "with_genres" to gen.joinToString("|")
                    if (mejores) { p += "sort_by" to "vote_average.desc"; p += "vote_count.gte" to (if (modo == 0) "25" else "400") } else { p += "sort_by" to "popularity.desc" }
                    if (f.notaAlta) p += "vote_average.gte" to "7"
                    val campo = if (m == "movie") "primary_release_date" else "first_air_date"
                    when (f.epoca) {
                        1 -> p += "$campo.gte" to "2020-01-01"
                        2 -> { p += "$campo.gte" to "2010-01-01"; p += "$campo.lte" to "2019-12-31" }
                        3 -> { p += "$campo.gte" to "2000-01-01"; p += "$campo.lte" to "2009-12-31" }
                        4 -> p += "$campo.lte" to "1999-12-31"
                    }
                    if (f.misServicios) {
                        val ids = gustos.proveedores.flatMap { it.ids }.distinct()
                        if (ids.isNotEmpty()) { p += "watch_region" to gustos.region; p += "with_watch_providers" to ids.joinToString("|"); p += "with_watch_monetization_types" to "flatrate|free|ads" }
                    }
                    val r = g("/discover/$m", *p.toTypedArray())?.optJSONArray("results") ?: return@async emptyList<Titulo>()
                    (0 until r.length()).map { r.getJSONObject(it) }.filter { Tmdb.tieneImagen(it) }.map { Tmdb.aTitulo(it, m, emptyList()) }
                }
            }
        }
        // Intercala los resultados de películas y series para que las filas mezclen los dos medios.
        val listas = llamadas.awaitAll()
        val out = ArrayList<Titulo>()
        var i = 0
        while (listas.any { i < it.size }) { listas.forEach { l -> if (i < l.size) out += l[i] }; i++ }
        tmdb.latinizar(out.distinctBy { it.medio + ":" + it.id })
    }

    /**
     * Arma las filas: coincidencias de título/persona (la búsqueda de siempre, que llega ya hecha), mejores del tema, por tu gusto
     * (orden por el aprendizaje), inspirado en lo último visto y ya las viste. `vistas` y `ocultos` son claves «medio:id».
     */
    suspend fun filas(
        e: TemaEntendido, f: FiltrosTema, coincidencias: List<Titulo>, vistas: Set<String>, ocultos: Set<String>,
        puntaje: (Titulo) -> Double, ultimoVisto: Titulo?,
    ): ResultadoTema = coroutineScope {
        fun k(t: Titulo) = t.medio + ":" + t.id
        val mejoresJ = async { (1..1).map { n -> async { pagina(e, f, true, n) } }.awaitAll().flatten() }
        val populares = async { (1..1).map { n -> async { pagina(e, f, false, n) } }.awaitAll().flatten() }
        val parecidosJ = async { if (ultimoVisto != null && ultimoVisto.id > 0) tmdb.parecidos(ultimoVisto.medio, ultimoVisto.id) else emptyList() }
        val todosMejores = mejoresJ.await().distinctBy(::k); val todosPop = populares.await().distinctBy(::k)
        val parecidos = parecidosJ.await()
        fun limpia(l: List<Titulo>) = l.filter { k(it) !in ocultos && (!f.ocultarVistas || k(it) !in vistas) }
        val mejores = limpia(todosMejores).take(20)
        val usados = mejores.map(::k).toSet()
        val gusto = limpia(todosPop).filter { k(it) !in usados }.sortedByDescending(puntaje).take(20)
        // «Inspirado en»: parecidos del último visto que encajan con el tema (comparten género o salen en el grupo del tema).
        val poolIds = (todosMejores + todosPop).map(::k).toSet()
        val genTema = (e.generosPelicula + e.generosSerie).toSet()
        val inspirado = limpia(parecidos).filter { k(it) in poolIds || it.generos.any { g -> g in genTema } }.take(20)
        val vistasTema = (todosMejores + todosPop).filter { k(it) in vistas && k(it) !in ocultos }.distinctBy(::k).take(20)
        ResultadoTema(e, limpia(coincidencias), mejores, gusto, ultimoVisto?.titulo, inspirado, vistasTema)
    }
}
