package co.davidvega.launcher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fila «Seguir viendo»: lo último visto de cada título, el más reciente primero. Junta dos fuentes:
 *  - seguir.json: historiales exportados (importar_gustos.py --generico). Va como asset; se puede sustituir con adb push
 *    a la carpeta externa de la app sin recompilar.
 *  - reproducciones.jsonl: lo que el launcher registra en el aparato (Reproducciones).
 * Cada título se identifica una sola vez en TMDB (imagen y sinopsis) y queda en seguir_tmdb.json (almacenamiento interno).
 */
class SeguirViendo(private val ctx: Context, private val app: co.davidvega.launcher.App) {
    private class Entrada(
        val nombre: String, val servicio: String, val medio: String, val temporada: String, val episodio: String, val t: Long, val tmdbId: Int,
        val numero: Int? = null, val mediaId: String = "", val gti: String = "", val posMs: Long = -1L, val durS: Long = 0L,
        val aprox: Boolean = false, // el número de episodio salió de cruzar el nombre con TMDB (Crunchyroll)
    )

    private val _fila = MutableStateFlow<Fila?>(null)
    val fila: StateFlow<Fila?> = _fila
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val externo get() = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    private val archivoTmdb = File(ctx.filesDir, "seguir_tmdb3.json") // 3: tras mejorar el emparejamiento (tipo, duración, título exacto); el 2 guardaba «El contador de cartas»

    companion object {
        const val DIAS = 90 // lo visto hace más de esto no sale
        const val MAX = 15

        /** Qué hace falta para retomar cada título de la fila («medio:id» de TMDB). Lo llena construir() y lo lee Abrir.enServicio. */
        @Volatile var retomables: Map<String, Retomar> = emptyMap()
    }

    private val prefs get() = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private fun ocultos(): Set<String> = prefs.getStringSet("seguir_ocultos", emptySet()) ?: emptySet()

    /** «Quitar de Seguir viendo»: el título deja de salir en esa fila (no toca Ya lo vi ni Mi lista). */
    fun ocultar(t: Titulo) {
        prefs.edit().putStringSet("seguir_ocultos", ocultos() + (t.medio + ":" + t.id)).apply()
        _fila.value = _fila.value?.let { f -> f.items.filter { (it.medio + ":" + it.id) != t.medio + ":" + t.id }.let { r -> if (r.isEmpty()) null else Fila(f.id, f.titulo, r) } }
    }

    @Volatile private var reintentos = 0

    /** Tras un fallo de red: reintento con espera creciente (1, 2, 4... min, hasta 5 intentos); se reinicia al refrescar sin fallos. */
    private fun programarReintento() {
        if (reintentos >= 5) return
        val espera = 60_000L shl reintentos
        reintentos++
        scope.launch { kotlinx.coroutines.delay(espera); refrescar() }
    }

    fun refrescar() { scope.launch { mutex.withLock { try { Rematch.corregir(ctx, app) } catch (_: Exception) {}; _fila.value = construir() } } }

    private fun leerSeguir(): List<Entrada> = try {
        val texto = File(externo, "seguir.json").takeIf { it.exists() }?.readText() ?: ctx.assets.open("seguir.json").bufferedReader().use { it.readText() }
        val a = JSONObject(texto).getJSONArray("items")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val t = runCatching { fmt.parse(o.optString("fecha"))?.time }.getOrNull() ?: return@mapNotNull null
            Entrada(o.optString("titulo"), o.optString("servicio"), o.optString("medio", "tv"), o.optString("temporada"), o.optString("episodio"), t, o.optInt("tmdb_id"))
        }
    } catch (e: Exception) { emptyList() }

    private fun leerReproducciones(): List<Entrada> = try {
        File(externo, "reproducciones.jsonl").takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { l ->
            val o = runCatching { JSONObject(l) }.getOrNull() ?: return@mapNotNull null
            // Película vista al 80 % o más: ya está en «Ya lo vi», no hay nada que retomar.
            // Sin título real (Prime Video sin resolver el gti) o sin emparejar con TMDB por ser un marcador: no se muestra.
            if (o.optBoolean("sin_titulo") || (PrimeVideo.esMarcador(o.optString("titulo")) && o.optInt("tmdb_id") <= 0)) return@mapNotNull null
            val dur = o.optLong("duracion_s")
            if (o.optString("medio") == "movie" && dur > 0 && o.optLong("visto_s") * 10 >= dur * 8) return@mapNotNull null
            val nombre = o.optString("titulo_tmdb").ifBlank { o.optString("serie").ifBlank { o.optString("titulo") } }
            val medio = o.optString("medio").ifBlank { if (o.has("serie")) "tv" else "movie" }
            val episodio = o.optString("episodio")
            // episodio_n lo guarda el registro nuevo; en los antiguos se intenta leer del texto.
            val numero = if (o.has("episodio_n")) o.optInt("episodio_n").takeIf { it > 0 } else AniList.numero(episodio)
            Entrada(nombre, o.optString("servicio"), medio, "", episodio, o.optLong("fin"), o.optInt("tmdb_id"),
                numero, o.optString("media_id"), o.optString("gti"), if (o.has("posicion_ms")) o.optLong("posicion_ms") else -1L, dur,
                o.optBoolean("episodio_aprox"))
        }
    } catch (e: Exception) { emptyList() }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private suspend fun construir(): Fila? {
        val desde = System.currentTimeMillis() - DIAS * 86_400_000L
        // Lo más reciente de cada título (por nombre normalizado y tipo).
        val ultimas = LinkedHashMap<String, Entrada>()
        (leerSeguir() + leerReproducciones()).filter { it.t >= desde && it.nombre.isNotBlank() }.sortedByDescending { it.t }.forEach { e ->
            ultimas.putIfAbsent(norm(e.nombre) + "|" + e.medio, e)
        }
        val cache = try { JSONObject(archivoTmdb.readText()) } catch (e: Exception) { JSONObject() }
        var cambio = false
        var huboFallo = false
        val items = ArrayList<Titulo>()
        val claves = HashSet<String>()
        val retomables = HashMap<String, Retomar>()
        for ((k, e) in ultimas) {
            if (items.size >= MAX) break
            val t = if (cache.has(k)) cache.optJSONObject(k)?.let { Titulo.deJson(it) } else {
                val fallos0 = app.catalogo.fallosTmdb()
                val r = try {
                    if (e.tmdbId > 0) app.catalogo.detalle(e.medio, e.tmdbId) else app.catalogo.resolver(listOf(e.nombre), null, esSerie = e.medio == "tv")
                } catch (ex: Exception) { return@construir _fila.value } // sin red: se queda lo que había
                // Un fallo de red (TMDB dio error al resolver) no se cachea como «no existe»: se reintenta con espera creciente.
                if (r == null && app.catalogo.fallosTmdb() > fallos0) { huboFallo = true; continue }
                cache.put(k, r?.aJson() ?: JSONObject.NULL); cambio = true
                android.util.Log.i("LauncherSeguir", "${e.nombre} (${e.servicio}) -> ${r?.medio}:${r?.id} ${r?.titulo}")
                r
            } ?: continue
            if (t.id <= 0 || (t.medio + ":" + t.id) in ocultos() || !claves.add(t.medio + ":" + t.id)) continue
            items += t.copy(servicios = listOf(e.servicio).filter { it.isNotBlank() }, motivo = motivo(e), noDisponible = false)
            if (e.mediaId.isNotBlank() || e.gti.isNotBlank()) retomables[t.medio + ":" + t.id] = Retomar(e.servicio, e.mediaId, e.gti, e.posMs, e.durS)
        }
        SeguirViendo.retomables = retomables
        if (cambio) Archivos.escribirOk(archivoTmdb, cache.toString())
        if (huboFallo) programarReintento() else reintentos = 0
        return if (items.isEmpty()) null else Fila("seguir", "Seguir viendo", items)
    }

    private fun motivo(e: Entrada): String {
        val cuando = TextoRetomar.cuando(((System.currentTimeMillis() - e.t) / 86_400_000L).toInt())
        val n = e.numero ?: e.episodio.toIntOrNull()
        val ep = n?.let { (e.temporada.toIntOrNull()?.takeIf { t -> t > 1 }?.let { t -> "T$t · " } ?: "") + (if (e.aprox) "~E$n" else "E$n") }
        return TextoRetomar.motivo(ep, e.episodio, e.posMs, e.durS, cuando)
    }
}

/** Lo que se sabe para retomar un título: servicio, id del episodio (Crunchyroll) o gti (Prime) y minuto guardado (-1 = no se sabe). */
data class Retomar(val servicio: String, val mediaId: String, val gti: String, val posMs: Long, val durS: Long)

/** Textos de la tarjeta de «Seguir viendo»; separado para poder probarlo sin Android. */
object TextoRetomar {
    /** «hoy», «ayer» o «hace N días» con N de 2 en adelante; días negativos (reloj atrasado) cuentan como hoy. */
    fun cuando(dias: Int): String = when { dias <= 0 -> "hoy"; dias == 1 -> "ayer"; else -> "hace $dias días" }

    /** 754000 -> «12:34»; con horas «1:02:03». Negativo -> «0:00». */
    fun reloj(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /** «12:38 de 23:40», «12:38» si no se sabe la duración, null si no hay posición. La posición se acota a la duración. */
    fun minuto(posMs: Long, durS: Long): String? {
        if (posMs < 0) return null
        val p = if (durS > 0) posMs.coerceAtMost(durS * 1000) else posMs
        return if (durS > 0) reloj(p) + " de " + reloj(durS * 1000) else reloj(p)
    }

    /** «Seguir viendo · E7 · 12:38 de 23:40 · hace 2 días». Sin número se muestra el nombre del episodio (corto), que es el dato real. */
    fun motivo(numero: String?, episodio: String, posMs: Long, durS: Long, cuando: String): String {
        val ep = numero ?: episodio.trim().takeIf { it.isNotEmpty() && it.toIntOrNull() == null }?.let { if (it.length > 34) it.take(33).trimEnd() + "…" else it }
        return listOfNotNull("Seguir viendo", ep, minuto(posMs, durS), cuando).joinToString(" · ")
    }
}
