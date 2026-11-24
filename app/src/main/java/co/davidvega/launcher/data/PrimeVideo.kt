package co.davidvega.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prime Video (com.amazon.firebat) no publica el título en su MediaSession: solo `PLAYBACK_SOURCE_ID = amzn1.dv.gti.<uuid>`.
 * La ficha pública `https://www.primevideo.com/detail/<gti>` trae el título en su `<title>` («Prime Video: El Código Enigma»),
 * que está en los primeros ~20 KB de una página de 600 KB: se lee solo el principio. El resultado se guarda por GTI en
 * `prime_gti.json` (almacenamiento interno) para no repetir la consulta. Un fallo de red no se guarda.
 */
object PrimeVideo {
    private val GTI = Regex("""amzn1\.dv\.gti\.[0-9a-fA-F-]{20,}""")
    private val TITULO = Regex("""<title[^>]*>\s*(?:Prime Video:\s*)?([^<]+?)\s*(?:\|\s*Prime Video)?\s*</title>""", RegexOption.IGNORE_CASE)
    private val lock = Any()

    /** El GTI dentro de lo que publique la app (el valor entero de PLAYBACK_SOURCE_ID), o null. */
    fun gtiDe(meta: Map<String, String>): String? =
        meta.entries.firstOrNull { it.key.endsWith("PLAYBACK_SOURCE_ID") }?.value?.let { GTI.find(it)?.value }

    /** Un nombre del tipo «Prime Video 8b35f685» es el marcador de «no sé qué es»: no es un título real. */
    fun esMarcador(titulo: String) = titulo.startsWith("Prime Video ") && titulo.length <= 24

    // Fallos por GTI en este proceso: espera creciente (5 min, 30 min, 3 h) y como mucho 3 intentos, para que cada refrescar() de
    // «Seguir viendo» no repita dos conexiones de hasta 20 s por cada registro sin resolver.
    private class Fallo(var intentos: Int, var ultimo: Long)
    private val fallos = java.util.concurrent.ConcurrentHashMap<String, Fallo>()
    private val ESPERAS_MS = longArrayOf(5 * 60_000L, 30 * 60_000L, 3 * 3_600_000L)

    private fun puedeReintentar(gti: String): Boolean {
        val f = fallos[gti] ?: return true
        return f.intentos < ESPERAS_MS.size && System.currentTimeMillis() - f.ultimo >= ESPERAS_MS[f.intentos - 1]
    }

    private fun anotarFallo(gti: String) { fallos.compute(gti) { _, f -> (f ?: Fallo(0, 0)).also { it.intentos++; it.ultimo = System.currentTimeMillis() } } }

    private fun archivo(ctx: Context) = File(ctx.filesDir, "prime_gti.json")

    private fun leer(ctx: Context): JSONObject = try { JSONObject(archivo(ctx).readText()) } catch (_: Exception) { JSONObject() }

    /** Solo lo que ya está guardado (sin red): para usar desde el hilo principal. */
    fun guardado(ctx: Context, gti: String): String? = synchronized(lock) { leer(ctx).optString(gti).takeIf { it.isNotBlank() } }

    /** Título por GTI: primero lo guardado; si no, la ficha pública. Null si no se pudo (sin red o sin título). */
    suspend fun titulo(ctx: Context, gti: String): String? = withContext(Dispatchers.IO) {
        guardado(ctx, gti)?.let { return@withContext it }
        if (!puedeReintentar(gti)) return@withContext null
        val t = descargar(gti)
        if (t == null) { anotarFallo(gti); return@withContext null }
        fallos.remove(gti)
        synchronized(lock) { Archivos.escribirOk(archivo(ctx), leer(ctx).put(gti, t).toString()) }
        Log.i("LauncherPrime", "gti ...${gti.takeLast(8)} -> $t")
        t
    }

    private fun descargar(gti: String): String? = try {
        val c = URL("https://www.primevideo.com/detail/$gti").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
        c.setRequestProperty("Accept-Language", "es-CO,es;q=0.9")
        try {
            if (c.responseCode != 200) { Log.w("LauncherPrime", "HTTP ${c.responseCode}"); null }
            else {
                // El <title> está al principio: se leen como mucho 96 KB.
                val buf = CharArray(96 * 1024)
                val n = c.inputStream.bufferedReader().use { r -> var total = 0; while (total < buf.size) { val k = r.read(buf, total, buf.size - total); if (k < 0) break; total += k }; total }
                TITULO.find(String(buf, 0, n))?.groupValues?.get(1)?.let { limpiar(it) }?.takeIf { it.isNotBlank() && !it.equals("Prime Video", true) }
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w("LauncherPrime", "${e.javaClass.simpleName}"); null }

    private fun limpiar(s: String) = android.text.Html.fromHtml(s, 0).toString().trim()
}
