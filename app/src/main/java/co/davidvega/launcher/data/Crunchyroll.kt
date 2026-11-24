package co.davidvega.launcher.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.Normalizer

/**
 * Título exacto en Crunchyroll sin sesión ni API: la app exporta un ContentProvider de búsqueda global
 * (com.crunchyroll.crunchyroid.google.globalsearch.GlobalSearchProvider) que devuelve su catálogo local con
 * suggest_intent_data = crunchyroll://series?id=<id de serie>. Se descarga una vez al día y se busca por nombre y año.
 */
object Crunchyroll {
    private const val URI = "content://com.crunchyroll.crunchyroid/search/search_suggest_query/x"
    private const val DIA = 24 * 3600_000L

    internal class Fila(val titulo: String, val anio: Int, val uri: String)

    private var memoria: List<Fila>? = null

    private val MARCAS = Regex("\\p{Mn}+")
    private val NO_ALFANUM = Regex("[^\\p{L}\\p{N}]+") // letras de cualquier alfabeto: el original en japonés o coreano también cuenta
    private val SUFIJO_TEMPORADA = Regex(" (season|temporada|part|parte|cour) \\d+$| \\d+(st|nd|rd|th) season$")
    internal fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARCAS, "").lowercase().replace(NO_ALFANUM, " ").trim()

    /**
     * URI crunchyroll://series?id=... (o movie_listing) del título, o null si no hay coincidencia clara.
     * `nombres` trae los títulos de TMDB (español, inglés y también `original_name`/`original_title`); aquí se comparan todos.
     */
    suspend fun ruta(ctx: Context, nombres: List<String>, anio: String): String? = withContext(Dispatchers.IO) {
        val catalogo = cargar(ctx) ?: return@withContext null
        buscar(catalogo, nombres, anio)
    }

    /** Coincidencia de `nombres` en el catálogo. Un nombre original en alfabeto no latino (3+ letras) cuenta igual que los demás. */
    internal fun buscar(catalogo: List<Fila>, nombres: List<String>, anio: String): String? {
        val candidatos = nombres.map(::norm).filter { it.length >= 3 }.distinct()
        if (candidatos.isEmpty()) return null
        val sinTemporada = candidatos.map { it.replace(SUFIJO_TEMPORADA, "") }.filter { it.length >= 3 }.distinct()
        val y = anio.toIntOrNull()
        fun anioOk(f: Fila) = y == null || f.anio == 0 || kotlin.math.abs(f.anio - y) <= 1
        val normas = catalogo.map { norm(it.titulo) }
        fun primero(cond: (Int) -> Boolean): String? = catalogo.indices.firstOrNull(cond)?.let { catalogo[it].uri }
        // 1) nombre exacto (con año si lo hay); 2) exacto sin año; 3) exacto sin «season N»; 4) uno empieza por el otro y el año cuadra.
        return primero { normas[it] in candidatos && anioOk(catalogo[it]) }
            ?: primero { normas[it] in candidatos }
            ?: primero { normas[it] in sinTemporada && anioOk(catalogo[it]) }
            ?: primero { i -> anioOk(catalogo[i]) && y != null && candidatos.any { c -> normas[i].let { n -> n.startsWith("$c ") || c.startsWith("$n ") } } }
    }

    private fun cargar(ctx: Context): List<Fila>? {
        memoria?.let { return it }
        val cache = File(ctx.cacheDir, "cr_catalogo.json")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < DIA) {
            try {
                val a = JSONArray(cache.readText())
                return (0 until a.length()).map { val r = a.getJSONArray(it); Fila(r.getString(0), r.getInt(1), r.getString(2)) }.also { memoria = it }
            } catch (_: Exception) {}
        }
        val filas = ArrayList<Fila>()
        try {
            ctx.contentResolver.query(Uri.parse(URI), null, null, null, null)?.use { c ->
                val t = c.getColumnIndex("suggest_text_1"); val y = c.getColumnIndex("suggest_production_year"); val d = c.getColumnIndex("suggest_intent_data")
                if (t < 0 || d < 0) return null
                while (c.moveToNext()) {
                    val uri = c.getString(d) ?: continue
                    if (!uri.startsWith("crunchyroll://")) continue
                    filas.add(Fila(c.getString(t) ?: continue, if (y >= 0) c.getString(y)?.toIntOrNull() ?: 0 else 0, uri))
                }
            }
        } catch (e: Exception) { return null } // app sin instalar o sin permiso
        if (filas.isEmpty()) return null
        Archivos.escribirOk(cache, JSONArray(filas.map { JSONArray(listOf(it.titulo, it.anio, it.uri)) }).toString())
        return filas.also { memoria = it }
    }
}
