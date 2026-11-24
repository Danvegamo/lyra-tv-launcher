package co.davidvega.launcher.data

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Registro de lo que se reproduce a partir de las MediaSession de las apps (lo alimenta EscuchaMedios).
 *  - Una sesión = un título en una app. Cuenta solo el tiempo en PLAYING.
 *  - Las pausas cortas (hasta 5 min) o un reinicio de la sesión con el mismo título se juntan en una sola.
 *  - Lo que dura menos de 60 s de reproducción se descarta.
 *  - Al cerrar: se identifica el título en TMDB (con la pista de la última ficha abierta), se guarda en reproducciones.jsonl,
 *    alimenta aprendizaje.json y se encola para el servidor de eventos (opcional).
 */
class Reproducciones(private val ctx: Context, private val app: co.davidvega.launcher.App) {
    class Sesion(val paquete: String, var titulo: String, var meta: Map<String, String>, val inicio: Long) {
        var tiempoVisto = 0L
        var jugandoDesde = 0L
        var ultimaParada = inicio
        var duracionMs = 0L
        /** Última posición conocida (ms), estimada en memoria; -1 si la app no la dio. Se escribe al cerrar la sesión. */
        var posicionMs = -1L
    }

    private val h = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actuales = HashMap<String, Sesion>() // por paquete
    private val cierres = HashMap<String, Runnable>()
    private val archivo get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "reproducciones.jsonl")

    companion object {
        const val UNION_MS = 5 * 60_000L // pausas más cortas que esto se juntan
        const val MINIMO_MS = 60_000L // menos de esto no cuenta
        /** Versión del emparejamiento con TMDB; Rematch corrige los registros con una versión menor. */
        const val EMPAREJADO = 2

        // Sesiones de sistema o de ayuda que no son reproducción de contenido. SmartTube (YouTube, tráileres) tampoco: no son títulos de TMDB.
        private val CLAVES_NUMERO = setOf("DURATION", "YEAR", "TRACK_NUMBER", "NUM_TRACKS", "DISC_NUMBER", "BT_FOLDER_TYPE")
        private val CLAVES_OTRAS = setOf("ART", "ALBUM_ART", "DISPLAY_ICON", "USER_RATING", "RATING")
        /** Días que dura el modo «captura» de las sesiones ignoradas (solo claves de metadatos, sin títulos). */
        const val CAPTURA_DIAS = 7
        const val CAPTURA_ENTRE_MS = 60_000L // como mucho una captura por minuto y paquete

        /**
         * Posición de reproducción ahora: la que publicó la app más lo que avanzó desde entonces a su velocidad (solo en PLAYING).
         * `actualizadoEn` es PlaybackState.lastPositionUpdateTime (elapsedRealtime); `ahora` también. -1 si no hay posición.
         */
        fun posicionEstimada(posicion: Long, jugando: Boolean, actualizadoEn: Long, ahora: Long, velocidad: Float): Long {
            if (posicion < 0) return -1L
            val extra = if (jugando && actualizadoEn > 0 && ahora > actualizadoEn) ((ahora - actualizadoEn) * velocidad).toLong() else 0L
            return posicion + extra.coerceAtLeast(0L)
        }

        /** Ajusta la posición a [0, duración] cuando se conoce la duración; -1 se queda en -1. */
        fun acotar(posicionMs: Long, duracionMs: Long): Long =
            if (posicionMs < 0) -1L else if (duracionMs > 0) posicionMs.coerceAtMost(duracionMs) else posicionMs

        /** Milisegundos de un tramo en PLAYING; nunca negativo (un reloj que retrocede no resta). */
        fun tramo(desde: Long, hasta: Long): Long = (hasta - desde).coerceAtLeast(0L)

        /**
         * Tiempo visto de una secuencia de cambios de estado: cada evento es (jugando, instante) en orden de llegada y `fin` cierra el último tramo.
         * Es la suma de los tramos en PLAYING; nunca es negativo ni mayor que lo transcurrido entre el primer evento y `fin`.
         */
        fun tiempoVisto(eventos: List<Pair<Boolean, Long>>, fin: Long): Long {
            var total = 0L
            var desde = 0L
            var jugando = false
            for ((j, t) in eventos) {
                if (j && !jugando) { desde = t; jugando = true }
                else if (!j && jugando) { total += tramo(desde, t); jugando = false }
            }
            if (jugando) total += tramo(desde, fin)
            return total
        }

        /** Una pausa más corta que UNION_MS junta las dos tandas en la misma sesión. */
        fun seUnen(pausaMs: Long): Boolean = pausaMs < UNION_MS

        /** Una sesión de menos de un minuto visto no se registra. */
        fun descartada(tiempoVistoMs: Long): Boolean = tiempoVistoMs < MINIMO_MS

        val IGNORAR = setOf("co.davidvega.launcher", "com.amazon.vizzini", "com.amazon.alexamediaplayer.runtime.ftv", "com.amazon.tv.launcher", "org.smarttube.stable")
    }

    /**
     * Vuelca los campos de MediaMetadata (para documentar qué da cada app). Cada clave se lee con su tipo: los números
     * con getLong, las imágenes y valoraciones se saltan y el resto es texto. Antes se probaba getString y getText en
     * orden y cada intento fallido dejaba un `W/Bundle ClassCastException` en logcat, cuatro veces por sondeo.
     */
    fun volcado(m: MediaMetadata?): Map<String, String> {
        if (m == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in m.keySet()) {
            val corta = k.removePrefix("android.media.metadata.")
            val v: String? = when {
                corta in CLAVES_NUMERO -> try { m.getLong(k).takeIf { it != 0L }?.toString() } catch (_: Exception) { null }
                corta in CLAVES_OTRAS -> null
                else -> try { m.getString(k) } catch (_: Exception) { null }
            }
            if (!v.isNullOrBlank()) out[corta] = v
        }
        return out
    }

    /** Llamado desde el servicio en cada cambio de metadatos o de estado de una sesión. */
    fun cambio(paquete: String, meta: Map<String, String>, estado: Int, posicionMs: Long = -1L) {
        if (!Preferencias.registrar(ctx)) return
        if (paquete in IGNORAR) { capturar(paquete, meta, estado); return }
        var titulo = meta["TITLE"] ?: meta["DISPLAY_TITLE"] ?: ""
        // Prime Video (firebat) no publica título: solo el gti en PLAYBACK_SOURCE_ID. Aquí (hilo principal) se usa el título ya
        // guardado de ese gti o un marcador «Prime Video <fin del gti>»; registrarSesion lo resuelve con la ficha pública.
        // Ya no se usa la última ficha abierta: daba títulos falsos si lo que se veía no era lo último que se abrió.
        if (titulo.isBlank()) PrimeVideo.gtiDe(meta)?.let { g -> titulo = PrimeVideo.guardado(ctx, g) ?: ("Prime Video " + g.takeLast(8)) }
        val ahora = System.currentTimeMillis()
        val previa = actuales[paquete]
        // Cambio de título en la misma app: se cierra la sesión anterior.
        if (previa != null && titulo.isNotBlank() && previa.titulo.isNotBlank() && !igual(previa.titulo, titulo)) cerrar(paquete, ahora)
        var s = actuales[paquete]
        if (estado == PlaybackState.STATE_PLAYING) {
            if (titulo.isBlank() && s == null) { capturar(paquete, meta, estado); return } // reproduce algo sin metadatos: no se sabe qué (solo se capturan las claves)
            if (s == null) { s = Sesion(paquete, titulo, meta, ahora); actuales[paquete] = s }
            cancelarCierre(paquete)
            if (titulo.isNotBlank() && s.titulo.isBlank()) s.titulo = titulo
            if (meta.size > s.meta.size) s.meta = meta // se queda con los metadatos más completos
            meta["DURATION"]?.toLongOrNull()?.let { s.duracionMs = it }
            if (s.jugandoDesde == 0L) s.jugandoDesde = ahora
            if (posicionMs >= 0) s.posicionMs = posicionMs
        } else if (s != null) {
            if (posicionMs >= 0) s.posicionMs = posicionMs // pausa o parada: la posición exacta que dejó la app
            parar(s, ahora)
            programarCierre(paquete)
        }
    }

    /** Posición conocida de una sesión en curso (solo memoria, no escribe nada): así una tele apagada en pleno episodio deja un minuto cercano. */
    fun posicion(paquete: String, posicionMs: Long) {
        if (posicionMs >= 0) actuales[paquete]?.posicionMs = posicionMs
    }

    private val capturaUltima = HashMap<String, Long>()
    private val prefs get() = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Arranca (una sola vez) la ventana de captura de 7 días. Después ya no se reabre sola. */
    fun iniciarCaptura() {
        if (!prefs.contains("captura_medios_hasta")) prefs.edit().putLong("captura_medios_hasta", System.currentTimeMillis() + CAPTURA_DIAS * 86_400_000L).apply()
    }

    /**
     * Modo captura: de los paquetes ignorados (SmartTube...) y de las sesiones sin título útil guarda en capturas_medios.jsonl
     * solo el paquete, el estado, el número de claves y los NOMBRES de las claves de metadatos. Nunca títulos ni valores.
     */
    private fun capturar(paquete: String, meta: Map<String, String>, estado: Int) {
        if (paquete == ctx.packageName) return
        val ahora = System.currentTimeMillis()
        if (ahora > prefs.getLong("captura_medios_hasta", 0L)) return
        if (ahora - (capturaUltima[paquete] ?: 0L) < CAPTURA_ENTRE_MS) return
        capturaUltima[paquete] = ahora
        val o = JSONObject().put("t", ahora).put("paquete", paquete).put("estado", estado).put("n", meta.size)
            .put("claves", org.json.JSONArray(meta.keys.sorted()))
        scope.launch { Archivos.anexar(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "capturas_medios.jsonl"), o.toString() + "\n") }
    }

    fun sesionDestruida(paquete: String) {
        val s = actuales[paquete] ?: return
        parar(s, System.currentTimeMillis())
        programarCierre(paquete)
    }

    private fun parar(s: Sesion, ahora: Long) {
        if (s.jugandoDesde > 0) { s.tiempoVisto += tramo(s.jugandoDesde, ahora); s.jugandoDesde = 0 }
        s.ultimaParada = ahora
    }

    private fun programarCierre(paquete: String) {
        cancelarCierre(paquete)
        val r = Runnable { cerrar(paquete, System.currentTimeMillis()) }
        cierres[paquete] = r
        h.postDelayed(r, UNION_MS)
    }

    private fun cancelarCierre(paquete: String) { cierres.remove(paquete)?.let { h.removeCallbacks(it) } }

    /** Cierra la sesión de una app (si ya no se reanudó). */
    private fun cerrar(paquete: String, ahora: Long) {
        cancelarCierre(paquete)
        val s = actuales.remove(paquete) ?: return
        parar(s, ahora)
        if (descartada(s.tiempoVisto)) { Log.i("LauncherMedios", "descartada (${s.tiempoVisto / 1000}s): ${s.titulo}"); return }
        scope.launch { registrarSesion(s) }
    }

    /** Solo pruebas: cierra ya todas las sesiones abiertas (sin esperar los 5 minutos de unión). */
    fun cerrarTodas() { val ahora = System.currentTimeMillis(); actuales.keys.toList().forEach { cerrar(it, ahora) } }

    private suspend fun registrarSesion(s: Sesion) {
        val servicio = servicioDe(s.paquete)
        // Prime Video: el título real sale de la ficha pública del gti. Sin él no se inventa ninguno: el registro queda
        // marcado «sin_titulo» y no entra en «Seguir viendo» ni en el aprendizaje.
        var sinTitulo = false
        if (PrimeVideo.esMarcador(s.titulo)) {
            val real = PrimeVideo.gtiDe(s.meta)?.let { PrimeVideo.titulo(ctx, it) }
            if (real != null) s.titulo = real else sinTitulo = true
        }
        val (serie, episodio) = if (sinTitulo) null to null else serieYEpisodio(s)
        val nombres = listOfNotNull(serie, s.titulo).filter { it.isNotBlank() }.distinct()
        val anio = s.meta["YEAR"]?.takeIf { it.length == 4 }
        val pista = if (s.paquete == "com.amazon.firebat") null else app.eventos.ultimaPista(90 * 60_000L)
        val t = if (sinTitulo) null else try { app.catalogo.resolver(nombres, pista, esSerie = serie != null, duracionS = s.duracionMs / 1000, anio = anio) } catch (e: Exception) { null }?.copy(servicios = listOfNotNull(servicio))
        val o = JSONObject().put("paquete", s.paquete).put("servicio", servicio ?: s.paquete).put("titulo", s.titulo)
            .put("inicio", s.inicio).put("fin", s.ultimaParada).put("visto_s", s.tiempoVisto / 1000).put("duracion_s", s.duracionMs / 1000)
            .put("metadatos", JSONObject(s.meta as Map<*, *>)).put("emp", EMPAREJADO)
        if (sinTitulo) o.put("sin_titulo", true)
        // Campos de «dónde me quedé» (0.14): los registros antiguos no los traen y se siguen leyendo igual.
        acotar(s.posicionMs, s.duracionMs).takeIf { it >= 0 }?.let { o.put("posicion_ms", it) }
        s.meta["MEDIA_ID"]?.takeIf { it.isNotBlank() }?.let { o.put("media_id", it) }
        PrimeVideo.gtiDe(s.meta)?.let { o.put("gti", it) }
        // Crunchyroll solo publica el nombre del episodio: el número sale de cruzarlo con TMDB (es-MX). Es aproximado y se marca como tal.
        var numero = AniList.numero(episodio)
        var aprox = false
        if (numero == null && servicio == "crunchyroll" && episodio != null && t != null && t.medio == "tv" && t.id > 0) {
            numero = try { app.catalogo.numeroEpisodio(t.id, episodio) } catch (e: Exception) { null }
            aprox = numero != null
        }
        numero?.let { o.put("episodio_n", it) }
        if (aprox) o.put("episodio_aprox", true)
        serie?.let { o.put("serie", it) }
        episodio?.let { o.put("episodio", it) }
        if (t != null) o.put("tmdb_id", t.id).put("medio", t.medio).put("titulo_tmdb", t.titulo)
        Archivos.anexar(archivo, o.toString() + "\n")
        Log.i("LauncherMedios", "sesión: ${s.paquete} «${s.titulo}» ${s.tiempoVisto / 1000}s tmdb=${t?.id} (${t?.titulo})")
        if (sinTitulo) return
        // Aprendizaje y Vistos los lee la interfaz en el hilo principal: se tocan desde ahí.
        kotlinx.coroutines.withContext(Dispatchers.Main) {
        if (t != null) app.aprendizaje.sesion(t, acotar(s.posicionMs, s.duracionMs), s.duracionMs, s.tiempoVisto, episodio = episodio)
        // Al menos el 80 % visto: una película pasa a «vista»; de una serie solo se anota que está en curso.
        if (t != null && t.id > 0 && s.duracionMs > 0 && s.tiempoVisto * 10 >= s.duracionMs * 8) {
            if (t.medio == "movie") { if (app.vistos.marcar(t)) app.aprendizaje.senal(t, 3.0) } else app.vistos.anotarEnCurso(t)
        }
        }
        app.seguir.refrescar()
        // Anime de Crunchyroll visto al 80 %: se anota el episodio en AniList (si hay ANILIST_TOKEN).
        val n = numero
        if (servicio == "crunchyroll" && serie != null && n != null && s.duracionMs > 0 && s.tiempoVisto * 10 >= s.duracionMs * 8) AniList.anotar(ctx, serie, n)
        val extra = JSONObject().put("visto_s", s.tiempoVisto / 1000).put("duracion_s", s.duracionMs / 1000)
            .put("inicio", s.inicio).put("fin", s.ultimaParada).put("titulo_app", s.titulo)
        episodio?.let { extra.put("episodio", it) }
        app.eventos.registrar("reproduccion", t ?: Titulo(-1, if (serie != null) "tv" else "movie", serie ?: s.titulo, "", null, null, "", 0.0, emptyList()), servicio = servicio ?: s.paquete, extra = extra)
    }

    private fun servicioDe(paquete: String): String? = when (paquete) {
        "com.example.iptv" -> "iptv"
        "com.amazon.avod" -> "prime"
        else -> app.catalogo.gustos().proveedores.firstOrNull { it.paquete == paquete }?.clave
    }

    /**
     * Serie y episodio según lo que publica cada app (ver rutas_comunicacion.md, sección 10). Por omisión, ALBUM, ARTIST o
     * DISPLAY_SUBTITLE traen la serie y TITLE el episodio; si la app solo da TITLE, se trata como película.
     */
    private fun serieYEpisodio(s: Sesion): Pair<String?, String?> {
        val m = s.meta
        // HBO Max: DISPLAY_SUBTITLE = serie, TITLE = «duración» del episodio. Crunchyroll: TITLE = serie, DISPLAY_TITLE = episodio.
        val serie = listOf("DISPLAY_SUBTITLE", "ALBUM", "ARTIST").firstNotNullOfOrNull { k -> m[k]?.takeIf { it.isNotBlank() && !igual(it, s.titulo) } }
        if (serie != null) return serie to s.titulo
        val ep = m["DISPLAY_TITLE"]?.takeIf { it.isNotBlank() && !igual(it, s.titulo) }
        return if (ep != null) s.titulo to ep else null to null
    }

    private fun igual(a: String, b: String) = norm(a) == norm(b)
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
