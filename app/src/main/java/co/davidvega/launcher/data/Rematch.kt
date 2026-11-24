package co.davidvega.launcher.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Corrige los registros de reproducciones.jsonl que se emparejaron con la versión anterior del emparejamiento
 * (los que no llevan `"emp": 2`). Casos que motivaron el cambio: HBO «El Contador» (película) quedó como «El contador de
 * cartas» y «El Camino de Regreso» (película, sin subtítulo) como la serie «El largo camino de regreso»; Prime Video llegaba
 * como «Prime Video 8b35f685».
 *  - Se vuelve a emparejar con TMDB usando la duración y el tipo (película si no hay subtítulo y dura más de ~70 min).
 *  - Prime Video: el título se recupera por el GTI de los metadatos (ficha pública). Si no se puede, el registro sigue sin
 *    marcar para reintentarlo en el próximo arranque, y «Seguir viendo» lo ignora.
 *  - Si el título cambió, el aprendizaje devuelve lo sumado al título equivocado y lo suma al correcto; si había una película
 *    «vista» por error, pasa al título correcto.
 * Antes de tocar nada se deja una copia `reproducciones.jsonl.bak` (una sola vez).
 */
object Rematch {
    suspend fun corregir(ctx: Context, app: co.davidvega.launcher.App): Boolean {
        val archivo = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "reproducciones.jsonl")
        if (!archivo.exists()) return false
        val lineas = Archivos.lineas(archivo)
        val pendientes = lineas.any { l -> l.isNotBlank() && runCatching { JSONObject(l).optInt("emp", 1) < Reproducciones.EMPAREJADO }.getOrDefault(false) }
        if (!pendientes) return false
        var cambio = false
        val cambios = HashMap<String, String>() // línea original -> línea corregida
        for (l in lineas) {
            val o = runCatching { JSONObject(l) }.getOrNull()
            if (o == null || o.optInt("emp", 1) >= Reproducciones.EMPAREJADO) continue
            val nuevo = try { arreglar(ctx, app, o) } catch (e: Exception) { Log.w("LauncherRematch", "falló: $e"); false }
            if (nuevo) cambio = true
            cambios[l] = o.toString()
        }
        // El emparejamiento es lento (red): se vuelve a leer el archivo y solo se sustituyen las líneas procesadas, así no se pierde
        // ningún registro que Reproducciones haya anexado mientras tanto (todo bajo el mismo cerrojo).
        if (cambio) Archivos.transformarLineas(archivo) { actuales ->
            val bak = File(archivo.parentFile, "reproducciones.jsonl.bak")
            if (!bak.exists()) archivo.copyTo(bak)
            actuales.map { cambios[it] ?: it }
        }
        return cambio
    }

    /** Vuelve a emparejar un registro (modifica `o`). Devuelve true si algo cambió. */
    private suspend fun arreglar(ctx: Context, app: co.davidvega.launcher.App, o: JSONObject): Boolean {
        var titulo = o.optString("titulo")
        var cambio = false
        var serie: String? = o.optString("serie").ifBlank { null }
        val meta = o.optJSONObject("metadatos")?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } }.orEmpty()
        if (PrimeVideo.esMarcador(titulo) || o.optBoolean("sin_titulo")) {
            val gti = PrimeVideo.gtiDe(meta) ?: return false
            val real = PrimeVideo.titulo(ctx, gti)
            if (real == null) {
                o.put("sin_titulo", true).remove("tmdb_id"); o.remove("medio"); o.remove("titulo_tmdb")
                return false // sin red o sin título: se reintenta en el próximo arranque
            }
            titulo = real; serie = null
            o.put("titulo", real).remove("sin_titulo")
            cambio = true
        }
        val dur = o.optLong("duracion_s")
        val nombres = listOfNotNull(serie, titulo).filter { it.isNotBlank() }.distinct()
        val fallos0 = app.catalogo.fallosTmdb()
        val t = app.catalogo.resolver(nombres, null, esSerie = serie != null, duracionS = dur, anio = meta["YEAR"]?.takeIf { it.length == 4 })
        if (t == null) {
            // Sin resultado: si fue por la red se reintenta; si TMDB no conoce el título, queda como estaba.
            if (app.catalogo.fallosTmdb() > fallos0) return cambio
            o.put("emp", Reproducciones.EMPAREJADO); return true
        }
        val idViejo = o.optInt("tmdb_id"); val medioViejo = o.optString("medio")
        if (t.id != idViejo || t.medio != medioViejo) {
            Log.i("LauncherRematch", "«$titulo»: $medioViejo:$idViejo (${o.optString("titulo_tmdb")}) -> ${t.medio}:${t.id} (${t.titulo})")
            val peso = if (o.optLong("visto_s") >= 600) 4.0 else 2.0
            val viejo = if (idViejo > 0 && medioViejo.isNotBlank()) app.catalogo.detalle(medioViejo, idViejo) else null
            // Aprendizaje y Vistos los lee la interfaz en el hilo principal: se tocan desde ahí (si no, ConcurrentModificationException).
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (viejo != null) {
                    app.aprendizaje.senal(viejo, -peso)
                    if (dur > 0 && o.optLong("visto_s") * 10 >= dur * 8) app.vistos.quitar(medioViejo, idViejo)
                }
                app.aprendizaje.senal(t, peso)
                if (t.medio == "movie" && dur > 0 && o.optLong("visto_s") * 10 >= dur * 8 && app.vistos.marcar(t)) app.aprendizaje.senal(t, 3.0)
            }
            o.put("tmdb_id", t.id).put("medio", t.medio).put("titulo_tmdb", t.titulo)
            cambio = true
        }
        o.put("emp", Reproducciones.EMPAREJADO)
        return true
    }
}
