package co.davidvega.launcher.data

import android.content.Context
import android.util.Log
import co.davidvega.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * Orquesta la carga: datos de ejemplo si no hay clave, caché de 12 h si es válida, TMDB si no.
 * Archivos que se pueden sobrescribir sin recompilar (adb push a getExternalFilesDir):
 *   gustos.json, config.local.properties.
 */
class Catalogo(private val ctx: Context) {

    private val externo: File? get() = ctx.getExternalFilesDir(null)

    private fun leerAsset(nombre: String) = ctx.assets.open(nombre).bufferedReader().use { it.readText() }

    /** gustos.json externo (adb push) si es válido; si está mal formado, el de assets (nunca lanza: antes un JSON roto tiraba el inicio en bucle). */
    private val gustosYTexto: Pair<String, Gustos> by lazy {
        Gustos.cargar(
            externo = { externo?.let { File(it, "gustos.json") }?.takeIf { it.exists() }?.readText() },
            respaldo = { runCatching { leerAsset("gustos.json") }.getOrElse { leerAsset("gustos.example.json") } },
            aviso = { Diagnostico.registrar("gustos", it) },
        )
    }
    private fun textoGustos(): String = gustosYTexto.first

    private fun claves(): Pair<String?, String?> {
        var k = BuildConfig.TMDB_API_KEY.ifBlank { null }
        var t = BuildConfig.TMDB_READ_TOKEN.ifBlank { null }
        externo?.let { File(it, "config.local.properties") }?.takeIf { it.exists() }?.inputStream()?.use {
            val p = Properties().apply { load(it) }
            p.getProperty("TMDB_API_KEY")?.trim()?.takeIf { v -> v.isNotEmpty() }?.let { v -> k = v }
            p.getProperty("TMDB_READ_TOKEN")?.trim()?.takeIf { v -> v.isNotEmpty() }?.let { v -> t = v }
        }
        return k to t
    }

    fun muestra(): List<Fila> {
        val o = JSONObject(leerAsset("muestra.json"))
        return (0 until o.getJSONArray("filas").length()).map { Fila.deJson(o.getJSONArray("filas").getJSONObject(it)) }
    }

    // En filesDir, no en cacheDir: Fire OS (y pm trim-caches) vacía cacheDir y el inicio volvía a consultar TMDB entero.
    private val archivoCache get() = File(ctx.filesDir, "filas.json")

    /** Una carga completa vale 12 h; una incompleta (algún fallo de red a medias) solo 10 minutos. */
    private fun leerCache(huella: String, aceptarVieja: Boolean): List<Fila>? = try {
        val o = JSONObject(archivoCache.readText())
        val vigencia = if (o.optBoolean("completa", true)) 12 * 3600_000L else 10 * 60_000L
        val fresca = System.currentTimeMillis() - o.getLong("t") < vigencia
        if (o.getString("huella") == huella && (fresca || aceptarVieja)) {
            val a = o.getJSONArray("filas")
            (0 until a.length()).map { Fila.deJson(a.getJSONObject(it)) }
        } else null
    } catch (e: Exception) { null }

    private fun guardarCache(huella: String, filas: List<Fila>, completa: Boolean) {
        Archivos.escribirOk(archivoCache, JSONObject().put("t", System.currentTimeMillis()).put("huella", huella).put("completa", completa)
            .put("filas", JSONArray(filas.map { it.aJson() })).toString())
    }

    /**
     * Carga las filas. Antes, una red caída al arrancar (la tele despierta antes que el wifi) dejaba filas vacías o a medias,
     * y esa carga a medias se guardaba 12 h como buena. Ahora:
     *  - espera a que TMDB responda (hasta ~30 s, 4 intentos) antes de pedir nada;
     *  - una carga con fallos no se guarda como completa (vale 10 min) y se reintenta sola al minuto, hasta 3 veces;
     *  - si no llegó ninguna fila se muestra la última carga guardada o, en último caso, los datos de ejemplo;
     *  - todo queda en `diagnostico.jsonl` (tipo «recomendaciones») y en logcat (`LauncherFilas`).
     */
    fun cargar(): Flow<Estado> = flow {
        val texto = textoGustos()
        val gustos = gustosCargados
        val (k, t) = claves()
        val tmdb = Tmdb(k, t, gustos.idioma, gustos.region).also { it.usarCacheProveedores(File(ctx.filesDir, "proveedores_tmdb.json")) }
        if (!tmdb.disponible) {
            emit(Estado(muestra(), Modo.MUESTRA, "DATOS DE EJEMPLO: falta la clave de TMDB", false))
            return@flow
        }
        val huella = texto.hashCode().toString() + "v9"
        leerCache(huella, false)?.let { emit(Estado(it, Modo.CACHE, null, false)); return@flow }

        var intento = 0
        while (true) {
            intento++
            if (intento == 1) emit(Estado(emptyList(), Modo.TMDB, null, true))
            // Esperar la red: 4 intentos con 0, 4, 8 y 15 s de pausa.
            var hayRed = false
            for (pausa in listOf(0L, 4000L, 8000L, 15000L)) {
                if (pausa > 0) kotlinx.coroutines.delay(pausa)
                if (tmdb.get("/configuration") != null) { hayRed = true; break }
            }
            val acum = ArrayList<Fila>()
            val fallos0 = tmdb.fallos.get()
            val rec = Recomendador(gustos, tmdb)
            if (hayRed) rec.filas().collect { f ->
                acum.add(f)
                if (intento == 1) emit(Estado(acum.toList(), Modo.TMDB, null, true)) // en los reintentos no se parpadea: se emite al final
            }
            val fallos = tmdb.fallos.get() - fallos0
            val completa = hayRed && fallos == 0 && rec.desconocidos.get() == 0
            Log.i("LauncherFilas", "carga $intento: red=$hayRed filas=${acum.size} títulos=${acum.sumOf { it.items.size }} fallos=$fallos sinProveedor=${rec.desconocidos.get()} completa=$completa")
            if (acum.isEmpty() && intento > 1) {
                Diagnostico.registrar("recomendaciones", "reintento $intento sin filas (red=$hayRed, fallos=$fallos)")
            } else if (acum.isEmpty()) {
                Diagnostico.registrar("recomendaciones", "carga $intento sin filas (red=$hayRed, fallos=$fallos)")
                val vieja = leerCache(huella, true)
                if (vieja != null) emit(Estado(vieja, Modo.CACHE, "Sin conexión con TMDB: última carga guardada", false))
                else emit(Estado(muestra(), Modo.MUESTRA, "DATOS DE EJEMPLO: no se pudo consultar TMDB", false))
            } else {
                guardarCache(huella, acum, completa)
                emit(Estado(acum.toList(), Modo.TMDB, null, false))
                if (!completa) Diagnostico.registrar("recomendaciones", "carga $intento incompleta: ${acum.size} filas, $fallos fallos de red, ${rec.desconocidos.get()} títulos sin proveedor")
            }
            if (completa || intento >= 3) break
            kotlinx.coroutines.delay(60_000L)
        }
    }.flowOn(Dispatchers.IO)

    private val archivoRespaldo get() = File(ctx.filesDir, "respaldo.json")

    /**
     * Fila de respaldo «Para empezar»: lo más popular de TMDB en tus servicios en la región (películas y series intercaladas).
     * Es lo que se muestra si las filas de gustos no dan nada (sin red al arrancar, filtradas por vistos...). Caché de 24 h;
     * si TMDB falla se usa la última guardada, aunque sea vieja.
     */
    suspend fun respaldo(): Fila? = withContext(Dispatchers.IO) {
        fun guardada(): Fila? = try { Fila.deJson(JSONObject(archivoRespaldo.readText()).getJSONObject("fila")) } catch (_: Exception) { null }
        try {
            val o = JSONObject(archivoRespaldo.readText())
            if (System.currentTimeMillis() - o.getLong("t") < 24 * 3600_000L) return@withContext Fila.deJson(o.getJSONObject("fila"))
        } catch (_: Exception) {}
        if (!tmdbCliente.disponible) return@withContext guardada()
        val g = gustosCargados
        val ids = g.proveedores.flatMap { it.ids }.distinct().joinToString("|")
        if (ids.isBlank()) return@withContext guardada()
        val porMedio = listOf("movie", "tv").map { medio ->
            val r = tmdbCliente.get("/discover/$medio", "watch_region" to g.region, "with_watch_providers" to ids, "with_watch_monetization_types" to "flatrate|free|ads",
                "sort_by" to "popularity.desc", "vote_count.gte" to "300", "vote_average.gte" to "6.8", "include_adult" to "false")?.optJSONArray("results")
            (0 until (r?.length() ?: 0)).map { r!!.getJSONObject(it) }.filter { Tmdb.tieneImagen(it) }.map { Tmdb.aTitulo(it, medio, emptyList(), motivo = "Popular en tus servicios") }
        }
        val mezcla = ArrayList<Titulo>()
        for (i in 0 until maxOf(porMedio[0].size, porMedio[1].size)) { porMedio.forEach { l -> l.getOrNull(i)?.let { mezcla.add(it) } } }
        if (mezcla.size < 6) return@withContext guardada()
        val con = conServicios(mezcla.take(24)).filter { it.servicios.isNotEmpty() }.take(20)
        if (con.size < 6) return@withContext guardada()
        val fila = Fila("respaldo", "Para empezar", con)
        Archivos.escribirOk(archivoRespaldo, JSONObject().put("t", System.currentTimeMillis()).put("fila", fila.aJson()).toString())
        fila
    }

    internal val gustosCargados: Gustos get() = gustosYTexto.second
    internal val tmdbCliente by lazy {
        Bloqueo.cargar(externo?.let { File(it, "bloqueo.json") }?.takeIf { it.exists() }?.readText() ?: runCatching { leerAsset("bloqueo.json") }.getOrNull())
        claves().let { (k, t) -> Tmdb(k, t, gustosCargados.idioma, gustosCargados.region) }.also { it.usarCacheFiltro(File(ctx.filesDir, "filtro_tmdb.json")); it.usarCacheProveedores(File(ctx.filesDir, "proveedores_tmdb.json")) }
    }
    /** Búsqueda por temática (navidad, terror, locas…) con caché de 24 h en el caché de la app. */
    val tematica by lazy {
        // La caché de temáticas guarda resultados por consulta: se borra la de las versiones anteriores (incluida la de las pruebas del filtro).
        File(ctx.cacheDir, "tema").deleteRecursively(); File(ctx.cacheDir, "tema2").deleteRecursively()
        Tematica(tmdbCliente, gustosCargados, File(ctx.cacheDir, "tema3"))
    }

    private val detalles = java.util.concurrent.ConcurrentHashMap<String, Detalle>()

    /** Detalle del hero con caché en memoria (el hero lo pide al enfocar y al rotar). */
    suspend fun detalle(t: Titulo): Detalle? {
        if (t.id <= 0 || !tmdbCliente.disponible) return null
        val k = t.medio + ":" + t.id
        detalles[k]?.let { return it }
        return withContext(Dispatchers.IO) { tmdbCliente.detalle(t.medio, t.id) }?.also { detalles[k] = it }
    }

    /** Completa la ficha de un título: proveedores en CO (si faltan) y recomendaciones parecidas. Sin clave, solo lo que ya hay. */
    suspend fun ficha(t0: Titulo): DatosFicha = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible) return@withContext DatosFicha(t0.servicios, emptyList())
        // Un título sin id de TMDB (de una fila de abajo) se identifica por su nombre antes de pedir lo demás.
        var resuelto: Titulo? = null
        val t = if (t0.id > 0) t0 else (try { resolver(listOf(t0.titulo), null, esSerie = t0.medio == "tv", anio = t0.anio.ifBlank { null }) } catch (e: Exception) { null })
            ?.takeIf { it.id > 0 }?.let { r -> resuelto = r; t0.copy(id = r.id, medio = r.medio, sinopsis = t0.sinopsis.ifBlank { r.sinopsis }, poster = t0.poster ?: r.poster, fondo = t0.fondo ?: r.fondo, anio = t0.anio.ifBlank { r.anio }, nota = if (t0.nota > 0) t0.nota else r.nota, generos = t0.generos.ifEmpty { r.generos }) }
            ?: return@withContext DatosFicha(t0.servicios, emptyList())
        // Con servicio de origen (p. ej. «Seguir viendo») va primero, y se suman los demás de TMDB para tener todas las chips.
        // Todo lo lento va en paralelo y con tope: JustWatch tarda hasta 3,5 s (si no llega, sigue en segundo plano y deja su caché para la próxima vez).
        val deOrigen = t.servicios.isNotEmpty() && t.motivo?.startsWith("Seguir viendo") != true
        val jw = segundoPlano.async { JustWatch.rutas(t) }
        val (provs, parecidos, palabras) = coroutineScope {
            val p = async { if (deOrigen) emptyList() else tmdbCliente.proveedoresDe(t.medio, t.id, gustosCargados) }
            val q = async { tmdbCliente.parecidos(t.medio, t.id) }
            val w = async { tmdbCliente.palabrasClave(t.medio, t.id) }
            Triple(p.await(), q.await(), w.await())
        }
        val servicios = if (deOrigen) t.servicios else (t.servicios + provs).distinct()
        val rutas = LinkedHashMap(withTimeoutOrNull(3500) { jw.await() } ?: emptyMap())
        // Crunchyroll: la ficha de la serie (id de serie desde su proveedor de búsqueda) gana al episodio 1 de JustWatch.
        if ("crunchyroll" in servicios || "crunchyroll" in rutas) {
            val nombres = listOf(t.titulo) + tmdbCliente.nombres(t.medio, t.id)
            withTimeoutOrNull(2500) { Crunchyroll.ruta(ctx, nombres, t.anio) }?.let { rutas["crunchyroll"] = it }
        }
        // Si TMDB no lista el servicio pero JustWatch sí, se suma (solo de los servicios del perfil).
        val extra = rutas.keys.filter { it !in servicios && gustosCargados.proveedores.any { p -> p.clave == it } }
        DatosFicha(servicios + extra, parecidos, rutas, palabras, resuelto ?: t.takeIf { t0.id <= 0 })
    }

    private val segundoPlano = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun gustos(): Gustos = gustosCargados

    /** Peticiones fallidas a TMDB desde que arrancó el proceso (para distinguir «no existe» de «sin red»). */
    fun fallosTmdb(): Int = tmdbCliente.fallos.get()

    /** Petición ligera a TMDB para «Estado del sistema». */
    suspend fun tmdbResponde(): Boolean = tmdbCliente.disponible && tmdbCliente.get("/configuration") != null

    private fun norm(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Identifica en TMDB lo que se reprodujo.
     *  - Tipo preferido: con subtítulo (serie) es «tv»; sin subtítulo y con más de ~70 min (4200 s) es «movie».
     *  - Entre los resultados que coinciden exactamente con el nombre (título en es o en, o título original) gana el del tipo
     *    preferido, luego el del año (si lo hay) y, en películas con duración conocida, el de duración parecida (HBO «El Contador»
     *    7700 s era The Accountant, 128 min, no «El contador de cartas»).
     *  - La pista (última ficha abierta) solo vale si el nombre es el mismo y su tipo no contradice al preferido.
     * Si nada coincide exactamente, se toma el primero del tipo preferido (como antes).
     */
    suspend fun resolver(nombres: List<String>, pista: Titulo?, esSerie: Boolean, duracionS: Long = 0, anio: String? = null): Titulo? = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible) return@withContext pista
        val preferido = when { esSerie -> "tv"; duracionS >= 4200 -> "movie"; else -> null }
        val ns = nombres.map(::norm).filter { it.isNotBlank() }
        if (pista != null && (preferido == null || pista.medio == preferido)) {
            val np = norm(pista.titulo)
            if (ns.any { it == np || (minOf(it.length, np.length) * 10 >= maxOf(it.length, np.length) * 8 && (it.contains(np) || np.contains(it))) }) return@withContext pista
        }
        var primero: Tmdb.Candidato? = null
        for (nombre in nombres) {
            val nn = norm(nombre)
            if (nn.isBlank()) continue
            val todos = tmdbCliente.candidatos(nombre)
            // norm() borra la escritura japonesa, china o coreana: «煙霞 -Gold Rush-» quedaba igual a «Gold Rush».
            // Si el nombre buscado es latino, se penalizan los resultados con título en esa escritura.
            val latino = nombre.none { esCjk(it) }
            fun cjk(c: Tmdb.Candidato) = latino && c.es.any { esCjk(it) }
            val exactos = todos.filter { c -> !cjk(c) && c.nombres.any { norm(it) == nn } }
            if (exactos.isNotEmpty()) {
                fun base(c: Tmdb.Candidato): Double {
                    var p = 0.0
                    if (preferido != null) p += if (c.medio == preferido) 30 else -30
                    if (!anio.isNullOrBlank() && c.anio.isNotBlank()) p += if (c.anio == anio) 20 else -15
                    if (norm(c.es) == nn || norm(c.mx) == nn) p += 5 // el título en español coincide: es lo que muestra la app
                    return p + kotlin.math.min(c.votos, 5000) / 1000.0
                }
                val orden = exactos.sortedByDescending(::base)
                // Desempate por duración: solo películas, solo las 4 primeras, solo si la duración es conocida.
                if (duracionS > 0 && orden.size > 1 && (preferido == null || preferido == "movie")) {
                    val dur = duracionS / 60.0
                    val prueba = orden.filter { it.medio == "movie" }.take(4)
                    prueba.forEach { it.runtimeMin = tmdbCliente.duracionMin(it.id) }
                    fun total(c: Tmdb.Candidato): Double {
                        var p = base(c)
                        if (c.medio == "movie" && c.runtimeMin > 0) { val d = kotlin.math.abs(c.runtimeMin - dur); p += if (d <= 5) 40 else if (d <= 12) 10 else -20 }
                        return p
                    }
                    val mejor = orden.maxByOrNull(::total)!!
                    Log.i("LauncherEmparejar", "«$nombre» ${duracionS}s -> ${mejor.clave} ${mejor.es} (${mejor.anio}, ${mejor.runtimeMin} min) de ${orden.size} exactos")
                    return@withContext aTituloDe(mejor)
                }
                val mejor = orden.first()
                Log.i("LauncherEmparejar", "«$nombre» -> ${mejor.clave} ${mejor.es} (${mejor.anio}) de ${orden.size} exactos")
                return@withContext aTituloDe(mejor)
            }
            if (primero == null) primero = todos.firstOrNull { !cjk(it) && (preferido == null || it.medio == preferido) }
        }
        primero?.let { Log.i("LauncherEmparejar", "${nombres.firstOrNull()} -> ${it.clave} ${it.es} (sin coincidencia exacta)"); return@withContext aTituloDe(it) }
        pista // sin coincidencia clara: la pista, si hay
    }

    private fun aTituloDe(c: Tmdb.Candidato): Titulo = Tmdb.aTitulo(c.json, c.medio, emptyList())

    private fun esCjk(c: Char) = Character.UnicodeScript.of(c.code) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

    private val sem = kotlinx.coroutines.sync.Semaphore(4)

    /** Rellena los servicios (CO) de una lista de títulos, 4 consultas a la vez. */
    private suspend fun conServicios(lista: List<Titulo>): List<Titulo> = kotlinx.coroutines.coroutineScope {
        tmdbCliente.latinizar(lista).map { t -> async { sem.withPermit { t.copy(servicios = tmdbCliente.proveedoresDe(t.medio, t.id, gustosCargados)) } } }.awaitAll()
    }

    /** Búsqueda en vivo del buscador: TMDB /search/multi en es-CO con el servicio donde está cada resultado. */
    suspend fun buscar(texto: String): List<Titulo> = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible || texto.isBlank() || Bloqueo.consultaExplicita(texto)) return@withContext emptyList()
        conServicios(tmdbCliente.buscarMulti(texto.trim()).take(16)).map { it.copy(noDisponible = it.servicios.isEmpty()) }
    }

    /**
     * Filas del inicio por género aprendido (no por títulos): combinaciones de los géneros con más peso, anime si el origen japonés
     * pesa, y un origen extranjero fuerte. Los pesos ya decaen con vida media de 30 días, así que lo reciente manda.
     */
    suspend fun filasPorGusto(generos: List<Pair<Int, Double>>, origenes: List<Pair<String, Double>>, palabras: List<Pair<String, String>>): List<Fila> = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible || generos.isEmpty()) return@withContext emptyList()
        val nom = Aprendizaje.NombresGenero
        fun n(g: Int) = (nom[g] ?: "Cine").lowercase()
        val g = generos.map { it.first }.filter { it !in setOf(10770, 10763, 10767) }.take(4)
        val anime = origenes.any { it.first == "ja" && it.second >= 1.0 } && 16 in g
        val specs = ArrayList<Triple<String, String, Discover>>()
        fun disc(gen: List<Int>, idioma: String? = null, kw: List<String> = emptyList()) = Discover(gen, false, if (idioma == "ja") emptyList() else if (16 !in gen) listOf(16) else emptyList(),
            idioma, kw, emptyList(), "popularity.desc", 150, 6.5, null, 2)
        fun titulo(a: Int, b: Int): String = when (setOf(a, b)) {
            setOf(53, 9648) -> "Thrillers de misterio"
            setOf(80, 18) -> "Crimen y gánsteres"
            setOf(27, 53) -> "Terror y suspenso"
            setOf(35, 18) -> "Comedias con fondo"
            setOf(878, 28) -> "Ciencia ficción de acción"
            else -> "${nom[a] ?: "Cine"} y ${n(b)}"
        }
        if (anime) {
            val otro = g.firstOrNull { it != 16 }
            specs += Triple("gusto_anime", if (otro == null) "Anime" else "Anime de ${n(otro)}", disc(listOfNotNull(16, otro), "ja"))
        }
        val base = g.filter { !anime || it != 16 }
        if (base.size >= 2) specs += Triple("gusto_${base[0]}_${base[1]}", titulo(base[0], base[1]), disc(listOf(base[0], base[1])))
        else if (base.size == 1) specs += Triple("gusto_${base[0]}", if (base[0] == 80) "Crimen y gánsteres" else nom[base[0]] ?: "Para ti", disc(listOf(base[0])))
        if (base.size >= 3) specs += Triple("gusto_${base[0]}_${base[2]}", titulo(base[0], base[2]), disc(listOf(base[0], base[2])))
        if (base.size >= 4) specs += Triple("gusto_${base[1]}_${base[2]}", titulo(base[1], base[2]), disc(listOf(base[1], base[2])))
        origenes.firstOrNull { it.first !in setOf("en", "ja") && it.second >= 1.5 }?.let { (o, _) ->
            val pais = Aprendizaje.NombresOrigen[o]
            if (pais != null && base.isNotEmpty()) specs += Triple("gusto_o_$o", "${nom[base[0]] ?: "Cine"} de $pais", disc(listOf(base[0]), o))
        }
        palabras.firstOrNull()?.let { (_, nombre) -> if (base.isNotEmpty()) specs += Triple("gusto_k_" + nombre.hashCode(), "Sobre ${nombre.lowercase()}", disc(listOf(base[0]), null, listOf(nombre))) }
        val rec = Recomendador(gustosCargados, tmdbCliente)
        specs.take(5).flatMap { (id, titulo, d) ->
            rec.fila(FilaSpec(id, titulo, "discover", "ambos", emptyList(), d, true, 20, 1, false)).map { it.copy(id = id, titulo = titulo) }
        }.filter { it.items.size >= 4 }
    }

    /** Fila «Porque viste X»: recomendaciones de TMDB del título ancla, solo las que están en tus servicios. */
    suspend fun recomendarDe(t: Titulo): Fila? = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible || t.id < 0) return@withContext null
        val lista = conServicios(tmdbCliente.parecidos(t.medio, t.id)).filter { it.servicios.isNotEmpty() }.map { it.copy(motivo = "Porque viste ${t.titulo}") }
        if (lista.size < 4) null else Fila("aprendido_" + t.medio + t.id, "Porque viste ${t.titulo}", lista)
    }

    private val archivoTendencias get() = File(ctx.filesDir, "tendencias.json")

    /**
     * «Esta semana en Colombia»: tendencia de JustWatch en los servicios con app instalada, con imagen y sinopsis de TMDB.
     * Como mucho 3 animes, para que la franja de arriba sea variada. Caché de 12 h en filesDir.
     */
    suspend fun tendencias(instalados: Collection<String>): Fila? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(archivoTendencias.readText())
            if (System.currentTimeMillis() - o.getLong("t") < 12 * 3600_000L) return@withContext Fila.deJson(o.getJSONObject("fila"))
        } catch (_: Exception) {}
        if (!tmdbCliente.disponible) return@withContext null
        var animes = 0
        val lista = JustWatch.tendencias(instalados).filter { !it.anime || animes++ < 3 }.take(20)
        val items = kotlinx.coroutines.coroutineScope {
            lista.map { e -> async { sem.withPermit { detalle(e.medio, e.tmdbId) }?.copy(servicios = e.rutas.keys.toList(), motivo = "Tendencia esta semana en Colombia") } }.awaitAll().filterNotNull()
        }
        if (items.size < 4) return@withContext null
        val fila = Fila("tendencias", "Esta semana en Colombia", tmdbCliente.latinizar(items))
        Archivos.escribirOk(archivoTendencias, JSONObject().put("t", System.currentTimeMillis()).put("fila", fila.aJson()).toString())
        fila
    }

    /** Clave de YouTube del tráiler (TMDB /videos): primero tráileres en español, luego oficiales. */
    suspend fun trailer(t: Titulo): String? = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible || t.id <= 0) return@withContext null
        val v = tmdbCliente.get("/${t.medio}/${t.id}/videos", "include_video_language" to "es,en,null")?.optJSONArray("results") ?: return@withContext null
        (0 until v.length()).map { v.getJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("type") in setOf("Trailer", "Teaser") }
            .sortedWith(compareBy({ it.optString("type") != "Trailer" }, { it.optString("iso_639_1") != "es" }, { !it.optBoolean("official") }))
            .firstOrNull()?.optString("key")?.ifBlank { null }
    }

    /** Ficha básica de TMDB por id (para «Seguir viendo» con lo que se registró en el aparato). */
    suspend fun detalle(medio: String, id: Int): Titulo? = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible) return@withContext null
        tmdbCliente.get("/$medio/$id")?.let { o ->
            // El detalle trae «genres» como objetos, no «genre_ids».
            o.optJSONArray("genres")?.let { g -> o.put("genre_ids", org.json.JSONArray((0 until g.length()).map { g.getJSONObject(it).optInt("id") })) }
            Tmdb.aTitulo(o, medio, emptyList())
        }
    }

    /**
     * Número de episodio de una serie a partir del nombre que publica la app (Crunchyroll no da número): cruza el nombre con los episodios
     * de las temporadas de TMDB (`/tv/<id>/season/<n>`, es-MX), de la última a la primera, como mucho 12 temporadas. El resultado es aproximado:
     * si el nombre coincide con más de un episodio (empate) o con ninguno, devuelve null.
     */
    suspend fun numeroEpisodio(tmdbId: Int, nombreEpisodio: String): Int? = withContext(Dispatchers.IO) {
        if (!tmdbCliente.disponible || tmdbId <= 0 || nombreEpisodio.isBlank()) return@withContext null
        val temporadas = detalleTemporadas(tmdbId).ifEmpty { return@withContext null }
        val episodios = coroutineScope {
            temporadas.takeLast(12).map { n -> async { sem.withPermit { tmdbCliente.episodiosDeTemporada(tmdbId, n) } } }.awaitAll()
        }.flatten()
        Episodios.numeroPorNombre(nombreEpisodio, episodios)
    }

    private suspend fun detalleTemporadas(id: Int): List<Int> =
        tmdbCliente.detalle("tv", id)?.temporadas?.map { it.first }?.sorted().orEmpty()

    /** Servicio -> paquete, leído del perfil. */
    fun paquetes(): Map<String, String> =
        gustosCargados.proveedores.associate { it.clave to it.paquete }
}
