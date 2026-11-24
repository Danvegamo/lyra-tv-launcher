package co.davidvega.launcher.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/**
 * Anota en AniList (la cuenta del token configurado) el episodio de anime que se terminó de ver, para no perder nada sin importar la cuenta
 * de Crunchyroll. Necesita ANILIST_TOKEN en config.local.properties (carpeta externa de la app); sin él no hace nada.
 * Solo avanza: si AniList ya tiene un progreso igual o mayor, o el episodio pasa del total de la serie (otra temporada
 * con otra entrada en AniList), no escribe y lo deja en el diagnóstico.
 */
object AniList {
    private const val BUSCAR = "query(\$s: String) { Media(search: \$s, type: ANIME) { id episodes title { romaji } mediaListEntry { progress status } } }"
    private const val GUARDAR = "mutation(\$id: Int, \$p: Int, \$e: MediaListStatus) { SaveMediaListEntry(mediaId: \$id, progress: \$p, status: \$e) { id progress status } }"

    private fun token(ctx: Context): String? = try {
        File(ctx.getExternalFilesDir(null), "config.local.properties").takeIf { it.exists() }?.inputStream()?.use {
            Properties().apply { load(it) }.getProperty("ANILIST_TOKEN")?.trim()?.ifBlank { null }
        }
    } catch (e: Exception) { null }

    /** Número de episodio a partir de lo que publica la app («E7 - Título», «Episodio 7», «7»). */
    private val NUMERO = Regex("^(?:[ST]\\d+\\s*[:.·-]?\\s*)?(?:E|Ep\\.?|Episodio|Episode|Cap[ií]tulo|Cap\\.?)\\s*(\\d+)|^(\\d+)\\b", RegexOption.IGNORE_CASE)

    /** También entiende «S1 E7», «T2 E7», «Episode 7» y «Capítulo 7»; si la app solo da el nombre del episodio (Crunchyroll) da null. */
    fun numero(episodio: String?): Int? = episodio?.let { NUMERO.find(it.trim()) }
        ?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() }

    suspend fun anotar(ctx: Context, serie: String, episodio: Int) = withContext(Dispatchers.IO) {
        val tk = token(ctx) ?: return@withContext
        try {
            val media = pedir(tk, BUSCAR, JSONObject().put("s", serie))?.optJSONObject("data")?.optJSONObject("Media") ?: run {
                Diagnostico.registrar("anilist", "sin coincidencia: $serie"); return@withContext
            }
            val total = media.optInt("episodes", 0)
            val actual = media.optJSONObject("mediaListEntry")?.optInt("progress", 0) ?: 0
            if (total in 1 until episodio) { Diagnostico.registrar("anilist", "E$episodio pasa del total ($total) de $serie: no se anota"); return@withContext }
            if (actual >= episodio) return@withContext
            val estado = if (total > 0 && episodio == total) "COMPLETED" else "CURRENT"
            pedir(tk, GUARDAR, JSONObject().put("id", media.getInt("id")).put("p", episodio).put("e", estado))
            Log.i("LauncherAniList", "$serie: $actual -> $episodio ($estado)")
        } catch (e: Exception) { Diagnostico.registrar("anilist", e.javaClass.simpleName + ": $serie") }
    }

    private fun pedir(token: String, consulta: String, variables: JSONObject): JSONObject? {
        val c = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            c.outputStream.use { it.write(JSONObject().put("query", consulta).put("variables", variables).toString().toByteArray()) }
            if (c.responseCode != 200) { Diagnostico.registrar("anilist", "HTTP " + c.responseCode); return null }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
