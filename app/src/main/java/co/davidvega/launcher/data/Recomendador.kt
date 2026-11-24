package co.davidvega.launcher.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import co.davidvega.launcher.data.RecomendadorPuro.Cand
import org.json.JSONObject

/**
 * Lógica de recomendaciones. La misma que implementa web/generar_web.py:
 *  1. Resuelve las anclas (títulos de gustos.json) a ids de TMDB.
 *  2. Junta candidatos: recomendaciones y similares de cada ancla (peso x2 y x1) y discover por género/palabra clave/cadena.
 *  3. Filtra por nota mínima y votos; consulta los proveedores en la región y conserva solo los de tus servicios.
 */
class Recomendador(private val gustos: Gustos, private val tmdb: Tmdb) {


    // Se leen y escriben desde varias corrutinas a la vez (hasta 4 en IO): mapas seguros para hilos (los dos con null permitido, sincronizados).
    private val resueltas: MutableMap<String, Pair<Int, String>?> = java.util.Collections.synchronizedMap(HashMap())
    private val provCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val claves: MutableMap<String, Int?> = java.util.Collections.synchronizedMap(HashMap())
    private val usados = HashSet<String>()
    private val region = gustos.region
    private val sem = Semaphore(4)

    /** Títulos cuya consulta de proveedores falló (red): se muestran igual, sin saber dónde están, y no se guardan como «sin servicio». */
    val desconocidos = java.util.concurrent.atomic.AtomicInteger(0)

    fun filas(): Flow<Fila> = flow {
        for (spec in gustos.filas) {
            val construidas = try { construir(spec) } catch (e: Exception) { android.util.Log.w("LauncherFilas", "fila ${spec.id} falló: $e"); emptyList() }
            android.util.Log.i("LauncherFilas", "fila ${spec.id}: ${construidas.sumOf { it.items.size }} títulos")
            for (f in construidas) if (f.items.isNotEmpty()) emit(f)
        }
    }

    private suspend fun construir(spec: FilaSpec): List<Fila> = when (spec.tipo) {
        // Las filas «Porque viste X» ya no salen en el inicio: las filas se arman por género aprendido (Catalogo.filasPorGusto).
        "por_ancla" -> emptyList()
        "por_ancla_antigua" -> {
            // Una ancla por servicio: si no, las tres filas «Porque viste» salían todas de Crunchyroll (anime).
            val anclas = gustos.anclas.filter { it.servicio.isNotBlank() }.sortedByDescending { it.peso }.distinctBy { it.servicio }.take(spec.maxFilas)
            anclas.mapNotNull { a ->
                val cands = LinkedHashMap<String, Cand>()
                val r = resolver(a) ?: return@mapNotNull null
                deAncla(a, r, cands, "Porque viste ${a.titulo}")
                filaDe(spec.id + "_" + a.titulo, spec.titulo.replace("{ancla}", a.titulo), spec, cands, setOf(r.second + ":" + r.first))
            }
        }
        else -> {
            val cands = LinkedHashMap<String, Cand>()
            val propias = HashSet<String>()
            for (nombre in spec.anclas) {
                val a = gustos.anclas.firstOrNull { it.titulo == nombre } ?: Ancla(nombre, nombre, null, "", 2)
                val r = resolver(a) ?: continue
                propias.add(r.second + ":" + r.first)
                deAncla(a, r, cands, null)
            }
            spec.discover?.let { descubrir(spec, it, cands) }
            listOf(filaDe(spec.id, spec.titulo, spec, cands, propias))
        }
    }

    private suspend fun resolver(a: Ancla): Pair<Int, String>? {
        if (a.tmdbId != null && a.medio != null) return a.tmdbId to a.medio
        val k = a.busqueda + "|" + a.medio
        if (!resueltas.containsKey(k)) resueltas[k] = tmdb.buscar(a.busqueda, a.medio)
        return resueltas[k]
    }

    /** Construye una fila suelta (las de género aprendido), con el mismo filtro de servicios que las demás. */
    suspend fun fila(spec: FilaSpec): List<Fila> = try { construir(spec) } catch (e: Exception) { android.util.Log.w("LauncherFilas", "fila ${spec.id} falló: $e"); emptyList() }

    private suspend fun deAncla(a: Ancla, r: Pair<Int, String>, cands: MutableMap<String, Cand>, motivo: String?) {
        val (id, medio) = r
        for ((ruta, mult) in listOf("recommendations" to 2.0, "similar" to 1.0)) {
            val res = tmdb.get("/$medio/$id/$ruta")?.optJSONArray("results") ?: continue
            for (i in 0 until res.length()) {
                val o = res.getJSONObject(i)
                anadir(cands, o, medio, RecomendadorPuro.puntuarAncla(a.peso, mult, o.optDouble("vote_average", 0.0)), motivo)
            }
        }
    }

    private fun anadir(cands: MutableMap<String, Cand>, o: JSONObject, medio: String, puntaje: Double, motivo: String?) {
        val k = medio + ":" + o.optInt("id")
        val ex = cands[k]
        if (ex != null) { ex.puntaje += puntaje; return }
        cands[k] = Cand(o, medio, puntaje, motivo)
    }

    private suspend fun descubrir(spec: FilaSpec, d: Discover, cands: MutableMap<String, Cand>) {
        val medios = if (spec.medio == "ambos") listOf("movie", "tv") else listOf(spec.medio)
        val kw = d.palabrasClave.mapNotNull { t -> claves.getOrPut(t) { tmdb.idPalabraClave(t) } }
        for (medio in medios) {
            for (conClaves in if (kw.isNotEmpty()) listOf(true, false) else listOf(false)) {
                val p = ArrayList<Pair<String, String>>()
                val generos = if (medio == "tv") d.generos.map(::generoTv).distinct() else d.generos
                if (generos.isNotEmpty()) p += "with_genres" to generos.joinToString(if (d.generosO) "|" else ",")
                if (d.sinGeneros.isNotEmpty()) p += "without_genres" to d.sinGeneros.joinToString(",")
                d.idiomaOriginal?.let { p += "with_original_language" to it }
                if (conClaves) p += "with_keywords" to kw.joinToString("|")
                if (medio == "tv" && d.redes.isNotEmpty()) p += "with_networks" to d.redes.joinToString("|")
                p += "sort_by" to d.orden
                p += "vote_count.gte" to d.minVotos.toString()
                p += "vote_average.gte" to d.minNota.toString()
                p += "include_adult" to "false"
                // Filas estrictas (solo lo que está en tus servicios): TMDB filtra por proveedor en la misma consulta, en vez de pedirlo título por título.
                if (spec.estricto) proveedoresDiscover?.let { ids -> p += "watch_region" to region; p += "with_watch_providers" to ids; p += "with_watch_monetization_types" to "flatrate|free|ads" }
                d.desdeAnio?.let { p += (if (medio == "tv") "first_air_date.gte" else "primary_release_date.gte") to "$it-01-01" }
                var total = 0
                for (pagina in 1..d.paginas) {
                    val res = tmdb.get("/discover/$medio", *(p + ("page" to pagina.toString())).toTypedArray())?.optJSONArray("results") ?: break
                    total += res.length()
                    for (i in 0 until res.length()) {
                        val o = res.getJSONObject(i)
                        anadir(cands, o, medio, RecomendadorPuro.puntuarDiscover(o.optDouble("vote_average", 0.0), conClaves), null)
                    }
                }
                if (conClaves && total >= 10) break // con palabras clave alcanza; si no, repite sin ellas
            }
        }
    }

    /** Ids de proveedor de TMDB de tus servicios, para `with_watch_providers` (null si el perfil no trae ninguno). */
    private val proveedoresDiscover: String? get() = gustos.proveedores.flatMap { it.ids }.distinct().joinToString("|").ifBlank { null }

    private fun generoTv(g: Int) = RecomendadorPuro.generoTv(g)

    private suspend fun filaDe(id: String, titulo: String, spec: FilaSpec, cands: Map<String, Cand>, excluir: Set<String>): Fila {
        val ordenados = RecomendadorPuro.filtrarCandidatos(cands, spec, excluir, gustos.notaMinima)
        // Proveedores en paralelo (máximo 4 a la vez).
        val items = coroutineScope {
            ordenados.map { (k, c) ->
                async {
                    val sid = c.json.optInt("id")
                    // null = la consulta falló: no se guarda ni se descarta el título por eso.
                    val servicios = provCache[k] ?: sem.withPermit { tmdb.proveedoresONull(c.medio, sid, gustos) }?.also { provCache[k] = it }
                    if (servicios == null) desconocidos.incrementAndGet()
                    Triple(k, aTitulo(c, servicios ?: emptyList()), servicios != null)
                }
            }.map { it.await() }
        }
        tmdb.guardarCacheProveedores()
        // Un título cuya consulta de proveedores falló (y sin caché) no se muestra como «sin servicio»: se espera a la siguiente carga, que se reintenta sola.
        val conocidos = items.filter { it.third }
        var lista = if (spec.estricto) conocidos.filter { it.second.servicios.isNotEmpty() }.map { it.first to it.second }
        else conocidos.sortedByDescending { it.second.servicios.isNotEmpty() }.map { it.first to if (it.second.servicios.isEmpty()) it.second.copy(noDisponible = true) else it.second }
        lista = RecomendadorPuro.armarFila(lista, spec, usados)
        usados.addAll(lista.map { it.first })
        return Fila(id, titulo, lista.map { it.second })
    }

    private fun aTitulo(c: Cand, servicios: List<String>): Titulo = Tmdb.aTitulo(c.json, c.medio, servicios, c.motivo)
}
