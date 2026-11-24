package co.davidvega.launcher.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import co.davidvega.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.UUID

/** Ajustes locales sencillos (SharedPreferences). */
object Preferencias {
    fun registrar(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("registrar", true)
    fun registrar(ctx: Context, si: Boolean) { ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("registrar", si).apply() }

    /** «Efectos glitch»: separación RGB breve al cambiar de título y al entrar. */
    fun efectosGlitch(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("efectos_glitch", true)
    fun efectosGlitch(ctx: Context, si: Boolean) { ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("efectos_glitch", si).apply() }
    /** «Liberar memoria al abrir una app»: al abrir una app de video se cierran las otras de video que sigan en segundo plano (por defecto Sí). */
    fun liberarMemoria(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("liberar_memoria", true)
    fun liberarMemoria(ctx: Context, si: Boolean) { ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("liberar_memoria", si).apply() }
    /** «Mantenimiento nocturno (4:30)»: recorta cachés y reinicia la tele de madrugada si no se usa (por defecto Sí). */
    fun mantenimiento(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getBoolean("mantenimiento", true)
    fun mantenimiento(ctx: Context, si: Boolean) { ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putBoolean("mantenimiento", si).apply() }
    /** «Sonido de inicio»: 0 con sonido (por defecto), 1 sin sonido (el mismo vídeo en silencio). */
    fun inicioModo(ctx: Context) = if (ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getInt("inicio_modo", 0) == 2) 1 else 0
    fun inicioModo(ctx: Context, n: Int) { ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putInt("inicio_modo", if (n == 1) 2 else 0).apply() }
}

/**
 * Eventos del launcher y de la reproducción: se anotan en una cola local (cola_eventos.jsonl) y se envían por lotes al servidor de EVENTOS_URL (opcional).
 * Con «Registrar lo que veo» apagado no se anota ni se envía nada.
 * Sin EVENTOS_URL (config.local.properties o el mismo archivo en la carpeta externa de la app) el envío queda en espera:
 * los eventos se acumulan (máx. 3.000) y salen cuando se configure. Si falla la red, reintenta con espera creciente (30 s a 10 min).
 * Formato: ver servidor\api_eventos.md (si ese archivo define otro, se ajusta `aJson` y `enviar`).
 */
/** Lectura tolerante de la cola (cola_eventos.jsonl): una línea cortada por un corte de luz no debe atascar el envío. */
internal object ColaEventos {
    /** Línea válida: un objeto JSON con `id`. `id` es la clave con la que se borra después de enviar. */
    class Linea(val id: String, val texto: String)

    class Analisis(val validas: List<Linea>, val corruptas: Int)

    fun analizar(lineas: List<String>): Analisis {
        var malas = 0
        val ok = ArrayList<Linea>()
        for (l in lineas) {
            if (l.isBlank()) continue
            val id = try { JSONObject(l).optString("id").ifBlank { null } } catch (_: Exception) { null }
            if (id == null) malas++ else ok += Linea(id, l)
        }
        return Analisis(ok, malas)
    }

    /** Quita de la cola las líneas con esos ids y las corruptas; conserva todo lo demás (también lo anexado mientras se enviaba). */
    fun quitar(lineas: List<String>, ids: Set<String>): List<String> =
        analizar(lineas).validas.filter { it.id !in ids }.map { it.texto }

    /** Espera tras un fallo: se duplica desde 30 s hasta 10 min. */
    fun siguienteEspera(actual: Long): Long = minOf(actual * 2, 600_000L)
}

class Eventos(private val ctx: Context) {
    private val cola = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "cola_eventos.jsonl") // externa: se puede revisar con adb pull
    private val hilo = HandlerThread("eventos").apply { start() }
    private val h = Handler(hilo.looper)
    @Volatile private var espera = 30_000L
    /** Hasta cuándo no se intenta enviar (espera tras un fallo de red); `reintentar()` la reinicia. */
    @Volatile private var noAntesDe = 0L
    private val tarea = Runnable { enviar() }

    @Volatile private var pistaT: Titulo? = null
    @Volatile private var pistaTs = 0L
    @Volatile private var pistaNivel = 0

    /** Lo último que el usuario abrió desde la ficha (1) o con «Ver en/Buscar en» (2): pista para identificar lo que se reproduce. */
    fun pista(t: Titulo, nivel: Int) { pistaT = t; pistaTs = System.currentTimeMillis(); pistaNivel = nivel }
    fun ultimaPista(maxMs: Long): Titulo? = pistaT?.takeIf { System.currentTimeMillis() - pistaTs <= maxMs }

    private fun config(clave: String, defecto: String): String {
        val ext = ctx.getExternalFilesDir(null)?.let { File(it, "config.local.properties") }?.takeIf { it.exists() }
        if (ext != null) try { ext.inputStream().use { Properties().apply { load(it) }.getProperty(clave)?.trim()?.takeIf { v -> v.isNotEmpty() }?.let { v -> return v } } } catch (_: Exception) {}
        return defecto
    }

    val url get() = config("EVENTOS_URL", BuildConfig.EVENTOS_URL)
    private val token get() = config("EVENTOS_TOKEN", BuildConfig.EVENTOS_TOKEN)
    private val dispositivo get() = config("EVENTOS_DISPOSITIVO", BuildConfig.EVENTOS_DISPOSITIVO)

    fun registrar(tipo: String, t: Titulo?, servicio: String? = null, texto: String? = null, extra: JSONObject? = null) {
        if (!Preferencias.registrar(ctx)) return
        val e = JSONObject().put("id", UUID.randomUUID().toString()).put("dispositivo", dispositivo).put("tipo", tipo).put("ts", System.currentTimeMillis())
        t?.let { e.put("titulo", it.titulo).put("medio", it.medio); if (it.id > 0) e.put("tmdb_id", it.id); if (it.anio.isNotBlank()) e.put("anio", it.anio) }
        servicio?.let { e.put("servicio", it) }
        texto?.let { e.put("texto", it) }
        extra?.keys()?.forEach { k -> e.put(k, extra.get(k)) }
        h.post {
            Archivos.anexar(cola, e.toString() + "\n"); recortar()
            if (System.currentTimeMillis() >= noAntesDe) enviar() // en espera por un fallo de red: ya hay un reintento programado
        }
    }

    fun pendientes(): Int = ColaEventos.analizar(Archivos.lineas(cola)).validas.size

    /** Reintento al arrancar la app o el servicio (salta la espera). */
    fun reintentar() { h.post { noAntesDe = 0L; enviar() } }

    private fun recortar() {
        if (Archivos.lineas(cola).size > 3000) Archivos.transformarLineas(cola) { l -> l.takeLast(3000) }
    }

    /**
     * Traduce un evento de la cola al formato de servidorpi_eventos.md (POST /v1/eventos, arreglo de hasta 100):
     * title, tmdb_id, media_type, app (paquete), started_at ISO con -05:00, duration_watched_s, total_s, completed, source, event_id.
     * Los eventos del launcher (ficha, ver_en, búsquedas, Mi lista) van con source "launcher", duracion 0 y el campo extra `tipo`.
     */
    private fun aSpec(e: JSONObject): JSONObject {
        val tipo = e.optString("tipo")
        val rep = tipo == "reproduccion"
        val servicio = e.optString("servicio")
        val paquete = when {
            servicio.isBlank() -> "co.davidvega.launcher"
            servicio == "iptv" -> "com.example.iptv"
            else -> try { (ctx.applicationContext as co.davidvega.launcher.App).catalogo.gustos().proveedores.firstOrNull { it.clave == servicio }?.paquete } catch (_: Exception) { null } ?: servicio
        }
        val ts = e.optLong("ts")
        val inicio = if (rep && e.has("inicio")) e.optLong("inicio") else ts
        val visto = if (rep) e.optLong("visto_s") else 0L
        val total = if (rep) e.optLong("duracion_s") else 0L
        val o = JSONObject()
            .put("event_id", e.optString("id")).put("title", e.optString("titulo", e.optString("texto")).ifBlank { e.optString("texto") })
            .put("app", paquete).put("started_at", iso(inicio)).put("duration_watched_s", visto)
            .put("completed", rep && total > 0 && visto * 10 >= total * 9).put("source", if (rep) "media_session" else "launcher")
            .put("tipo", tipo).put("dispositivo", e.optString("dispositivo"))
        if (e.has("tmdb_id")) o.put("tmdb_id", e.optInt("tmdb_id"))
        if (e.has("medio")) o.put("media_type", e.optString("medio"))
        if (total > 0) o.put("total_s", total)
        if (e.has("episodio")) o.put("episode_title", e.optString("episodio"))
        if (e.has("texto")) o.put("texto", e.optString("texto"))
        if (e.has("anio")) o.put("anio", e.optString("anio"))
        return o
    }

    private fun iso(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("America/Bogota") }.format(java.util.Date(ms))

    private fun enviar() {
        val destino = url
        if (!Preferencias.registrar(ctx)) return
        if (destino.isBlank()) { if (pendientes() > 0) Diagnostico.registrar("eventos", "falta la dirección de envío: la cola se acumula"); return }
        val analisis = ColaEventos.analizar(Archivos.lineas(cola))
        if (analisis.corruptas > 0) { // línea cortada (corte de luz): se tira, no atasca la cola
            Archivos.transformarLineas(cola) { l -> ColaEventos.quitar(l, emptySet()) }
            Diagnostico.registrar("eventos", "se descartaron ${analisis.corruptas} líneas corruptas de la cola")
        }
        val lote = analisis.validas.take(50)
        if (lote.isEmpty()) return
        val enviados = HashSet<String>() // ids que el servidor aceptó (2xx) o rechazó por malos (400): salen de la cola
        var fallo = false
        val code = post(destino, lote)
        when {
            code in 200..299 -> { lote.forEach { enviados += it.id }; Log.i("LauncherEventos", "enviados ${lote.size}") }
            code == 400 -> {
                // Un 400 es culpa de alguna línea, no de todas: se prueba una por una y solo se descartan las rechazadas.
                for (l in lote) {
                    val c1 = post(destino, listOf(l))
                    if (c1 in 200..299 || c1 == 400) { enviados += l.id; if (c1 == 400) Diagnostico.registrar("eventos", "HTTP 400: se descartó un evento") }
                    else { fallo = true; break }
                }
            }
            else -> {
                fallo = true
                if (code > 0) { Log.w("LauncherEventos", "HTTP $code"); Diagnostico.registrar("eventos", "el servidor respondió HTTP $code; pendientes ${pendientes()}") }
                else Diagnostico.registrar("eventos", "sin red o error; pendientes ${pendientes()}")
            }
        }
        // Se borra por id: no se pierde nada que no salió ni lo anexado mientras se enviaba.
        if (enviados.isNotEmpty()) Archivos.transformarLineas(cola) { l -> ColaEventos.quitar(l, enviados) }
        if (!fallo) {
            espera = 30_000L; noAntesDe = 0L
            if (pendientes() > 0) h.post { enviar() }
        } else {
            noAntesDe = System.currentTimeMillis() + espera
            h.removeCallbacks(tarea)
            h.postDelayed(tarea, espera)
            espera = ColaEventos.siguienteEspera(espera)
        }
    }

    /** POST de un lote; devuelve el código HTTP o -1 si no hubo respuesta (red caída o tiempo agotado). */
    private fun post(destino: String, lote: List<ColaEventos.Linea>): Int = try {
        val c = URL(destino).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 10000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            token.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            // Una línea que no se pueda convertir al formato del servidor equivale a una corrupta: se omite.
            val cuerpo = JSONArray(lote.mapNotNull { l -> runCatching { aSpec(JSONObject(l.texto)) }.getOrNull() }).toString()
            c.outputStream.use { it.write(cuerpo.toByteArray()) }
            val code = c.responseCode
            if (code == 400) Log.w("LauncherEventos", "400 del servidor (${c.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)})")
            code
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.i("LauncherEventos", "sin red o error: $e"); -1 }
}
