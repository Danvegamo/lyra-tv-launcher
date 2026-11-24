package co.davidvega.launcher.data

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Una línea del registro: cuándo (ms), de qué tipo y qué pasó. */
data class Incidencia(val t: Long, val tipo: String, val detalle: String)

/** Resultado de una comprobación en vivo para «Estado del sistema». */
data class Comprobacion(val nombre: String, val bien: Boolean, val detalle: String = "")

/**
 * El launcher anota aquí lo que falla, para saberlo sin mirar logcat: diagnostico.jsonl en la carpeta externa de la app
 * (se baja con `adb pull /sdcard/Android/data/co.davidvega.launcher/files/diagnostico.jsonl`). Una línea JSON {t, tipo, detalle}
 * por incidencia, con un máximo de unas 500 líneas (al pasarse se conservan las últimas 400).
 * No se escribe nada personal: ni títulos buscados, ni la clave de TMDB (se filtra `api_key=` y los tokens).
 * Tipos: busqueda_guiada, enlace, tmdb, justwatch, accesibilidad, escucha_medios, eventos, recomendaciones.
 * La misma incidencia no se repite dentro de 10 minutos, para que una ráfaga de errores de red no llene el archivo.
 */
object Diagnostico {
    private const val MAXIMO = 500
    private const val CONSERVAR = 400
    private const val REPETIDO_MS = 10 * 60_000L

    private val lock = Any()
    @Volatile private var archivo: File? = null
    private val ultimas = HashMap<String, Long>()
    private var lineas = -1

    fun iniciar(ctx: Context) {
        archivo = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "diagnostico.jsonl")
    }

    /**
     * Quita repeticiones para siempre: la primera vez de cada clave devuelve true y cuenta las veces que se pidió después.
     * Acotado a `maximo` claves (al pasarse se descartan las más viejas).
     */
    class Unicos(private val maximo: Int = 3000) {
        private val veces = object : LinkedHashMap<String, Int>() {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>?) = size > maximo
        }

        @Synchronized fun nuevo(clave: String): Boolean {
            val n = veces[clave]
            veces[clave] = (n ?: 0) + 1
            return n == null
        }

        @Synchronized fun veces(clave: String) = veces[clave] ?: 0
    }

    private val filtroVisto = Unicos()

    /** Entradas de tipo «filtro» (título ocultado por el filtro de contenido): una sola vez por título y motivo; las repeticiones solo se cuentan en memoria. */
    fun registrarFiltro(clave: String, motivo: String) {
        if (filtroVisto.nuevo("$clave $motivo")) registrar("filtro", "$clave $motivo")
    }

    fun registrar(tipo: String, detalle: String) {
        val f = archivo ?: return
        val limpio = limpiar(detalle)
        val ahora = System.currentTimeMillis()
        synchronized(lock) {
            val k = "$tipo|$limpio"
            if (ahora - (ultimas[k] ?: 0L) < REPETIDO_MS) return
            ultimas[k] = ahora
            try {
                f.parentFile?.mkdirs()
                if (lineas < 0) {
                    val previas = if (f.exists()) f.readLines() else emptyList()
                    lineas = previas.size
                    // Lo ya anotado en otras sesiones también cuenta como visto.
                    previas.forEach { l -> runCatching { JSONObject(l) }.getOrNull()?.takeIf { it.optString("tipo") == "filtro" }?.let { filtroVisto.nuevo(it.optString("detalle")) } }
                }
                f.appendText(JSONObject().put("t", ahora).put("tipo", tipo).put("detalle", limpio).toString() + "\n")
                lineas++
                if (lineas > MAXIMO) {
                    val resto = f.readLines().filter { it.isNotBlank() }.takeLast(CONSERVAR)
                    f.writeText(resto.joinToString("\n") + "\n")
                    lineas = resto.size
                }
            } catch (_: Exception) {}
        }
    }

    /** Quita claves y tokens que pudieran colarse en un texto y lo acorta. */
    private fun limpiar(s: String): String = s
        .replace(Regex("(api_key|token|authorization|bearer)[=: ]+[^&\\s]+", RegexOption.IGNORE_CASE), "$1=…")
        .replace('\n', ' ').take(180)

    /** Las últimas `n` incidencias, la más reciente primero. */
    fun recientes(n: Int): List<Incidencia> = synchronized(lock) {
        val f = archivo ?: return emptyList()
        try {
            f.readLines().filter { it.isNotBlank() }.takeLast(n).mapNotNull { l ->
                try { JSONObject(l).let { Incidencia(it.getLong("t"), it.getString("tipo"), it.optString("detalle")) } } catch (_: Exception) { null }
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    /** Texto legible de una incidencia, para la pantalla. */
    fun legible(i: Incidencia): String {
        val q = when (i.tipo) {
            "busqueda_guiada" -> "Búsqueda guiada falló"
            "enlace" -> "Enlace directo sin resolver"
            "tmdb" -> "TMDB"
            "justwatch" -> "JustWatch"
            "accesibilidad" -> "Accesibilidad"
            "escucha_medios" -> "Escucha de medios"
            "voz" -> "Búsqueda por voz (recuperaciones)"
            "filtro" -> "Contenido explícito ocultado"
            "eventos" -> "Eventos sin enviar"
            "recomendaciones" -> "Recomendaciones"
            else -> i.tipo
        }
        return if (i.detalle.isBlank()) q else "$q: ${i.detalle}"
    }

    fun hora(t: Long): String {
        val d = Date(t)
        val dia = SimpleDateFormat("yyyyMMdd", Locale.US)
        return if (dia.format(d) == dia.format(Date())) SimpleDateFormat("HH:mm", Locale.US).format(d)
        else SimpleDateFormat("d MMM HH:mm", Locale("es", "CO")).format(d)
    }

    private val APPS = listOf(
        "Crunchyroll" to "com.crunchyroll.crunchyroid",
        "HBO Max" to "com.hbo.hbonow",
        "Prime Video" to "com.amazon.firebat",
        "IPTV" to "com.example.iptv",
    )

    private fun mb(b: Long) = "${b / (1024 * 1024)} MB"

    /** Comprobaciones en vivo. `tmdb` es la petición ligera a TMDB (la hace el Catalogo). */
    suspend fun comprobar(ctx: Context, tmdb: suspend () -> Boolean): List<Comprobacion> {
        val out = ArrayList<Comprobacion>()
        val acc = co.davidvega.launcher.sistema.HomeAccessibilityService.activo
        out += Comprobacion("Servicio de accesibilidad", acc, if (acc) "" else "búsqueda guiada y botón Home apagados")
        val esc = co.davidvega.launcher.sistema.EscuchaMedios.instancia != null
        out += Comprobacion("Escucha de medios", esc, if (esc) "" else "no se anota lo que ves")
        val t = try { tmdb() } catch (_: Exception) { false }
        out += Comprobacion("TMDB responde", t)
        val pm = ctx.packageManager
        for ((n, p) in APPS) {
            val esta = pm.getLaunchIntentForPackage(p) != null || pm.getLeanbackLaunchIntentForPackage(p) != null
            out += Comprobacion("App $n", esta, if (esta) "" else "no instalada")
        }
        try {
            val libre = StatFs(ctx.filesDir.path).availableBytes
            out += Comprobacion("Espacio libre", libre > 500L * 1024 * 1024, mb(libre))
        } catch (_: Exception) { out += Comprobacion("Espacio libre", false, "no se pudo leer") }
        try {
            val mi = ActivityManager.MemoryInfo()
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            out += Comprobacion("RAM libre", mi.availMem > 150L * 1024 * 1024, mb(mi.availMem))
        } catch (_: Exception) { out += Comprobacion("RAM libre", false, "no se pudo leer") }
        return out
    }
}
