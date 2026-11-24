package co.davidvega.launcher.sistema

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import co.davidvega.launcher.data.Diagnostico
import co.davidvega.launcher.data.SeguirViendo
import co.davidvega.launcher.data.TextoRetomar
import co.davidvega.launcher.data.Titulo

/**
 * Abre un título en su app. Si el deep link no resuelve, abre la app a secas; si la app no está, avisa.
 * Resultados de las pruebas en el Fire TV: ver README (sección "Deep links").
 */
object Abrir {

    /** Servicios del título cuya app está instalada, en orden de prioridad. */
    fun instalados(ctx: Context, t: Titulo, paquetes: Map<String, String>) =
        t.servicios.filter { s -> paquetes[s]?.let { ctx.packageManager.getLaunchIntentForPackage(it) != null || ctx.packageManager.getLeanbackLaunchIntentForPackage(it) != null } == true }

    /** Apps cuyo deep link de búsqueda no llega a la búsqueda (probado): se abre la app y el título queda en el portapapeles. */
    private val SIN_BUSQUEDA = setOf("crunchyroll")

    /**
     * Abre el título en un servicio, por escalones:
     *  0. retomar lo que se estaba viendo (episodio de Crunchyroll por media_id, gti de Prime) si «Seguir viendo» lo tiene;
     *  1. título exacto (ruta de JustWatch: gti de Prime, uuid de HBO, episodio de Crunchyroll);
     *  2. búsqueda con texto (Prime: app.primevideo.com/search; HBO: pantalla de búsqueda);
     *  3. la app a secas y el título en el portapapeles.
     * Devuelve el escalón usado (1, 2 o 3).
     */
    fun enServicio(ctx: Context, t: Titulo, servicio: String, paquetes: Map<String, String>, rutaExacta: String? = null): Int {
        val paquete = paquetes[servicio] ?: return 0
        val retomar = SeguirViendo.retomables[t.medio + ":" + t.id]?.takeIf { it.servicio == servicio }
        val enlaceRetomar = retomar?.let { enlaceRetomar(servicio, it.mediaId, it.gti) }
        if (enlaceRetomar != null && lanzarVista(ctx, enlaceRetomar, paquete, 0)) {
            // El minuto lo decide la app (su historial en la nube): no se promete, solo se avisa de dónde se quedó.
            TextoRetomar.minuto(retomar.posMs, retomar.durS)?.let { Toast.makeText(ctx, "Retoma en el minuto $it (lo decide la app)", Toast.LENGTH_LONG).show() }
            return 1
        }
        if (rutaExacta != null && lanzarVista(ctx, rutaExacta, paquete, 1)) return 1
        val enlace = enlace(servicio, t)
        if (enlace != null && lanzarVista(ctx, enlace, paquete, 2)) {
            if (servicio in SIN_BUSQUEDA) avisarPortapapeles(ctx, t)
            return 2
        }
        // Escalón 3: abrir la app y, si el servicio de accesibilidad está activo y hay perfil, escribir el título en su buscador.
        Diagnostico.registrar("enlace", "$servicio: sin enlace directo, cayó al escalón 3 (${if (HomeAccessibilityService.activo) "búsqueda guiada" else "portapapeles"})")
        Ajustes.copiar(ctx, t.titulo)
        val guiada = HomeAccessibilityService.activo && BusquedaAccesible.iniciar(ctx, paquete, t.titulo)
        app(ctx, paquete, limpio = guiada && paquete != IPTV) // IPTV arranca lento en limpio y no llega a la lupa a tiempo
        if (guiada) Toast.makeText(ctx, "Escribiendo «${t.titulo}» en ${servicio.replaceFirstChar { it.uppercase() }}…", Toast.LENGTH_SHORT).show()
        else avisarPortapapeles(ctx, t)
        return 3
    }

    /** Enlace al episodio exacto: Crunchyroll por media_id, Prime por gti. HBO no tiene id en la sesión: solo se abre la app. */
    fun enlaceRetomar(servicio: String, mediaId: String, gti: String): String? = when {
        servicio == "crunchyroll" && mediaId.isNotBlank() -> "crunchyroll://episode?id=" + java.net.URLEncoder.encode(mediaId, "UTF-8")
        servicio == "prime" && gti.isNotBlank() -> "https://app.primevideo.com/detail?gti=" + java.net.URLEncoder.encode(gti, "UTF-8")
        else -> null
    }

    private fun avisarPortapapeles(ctx: Context, t: Titulo) {
        Ajustes.copiar(ctx, t.titulo)
        Toast.makeText(ctx, "Busca «${t.titulo}» en la app (copiado)", Toast.LENGTH_LONG).show()
    }

    /** «Buscar directo en…» del buscador: manda el texto tal cual por la ruta de cada app (HBO y Prime por enlace; Crunchyroll e IPTV por búsqueda guiada). */
    fun buscarDirecto(ctx: Context, servicio: String, texto: String, paquetes: Map<String, String>) {
        val q = Uri.encode(texto)
        when (servicio) {
            "hbomax" -> paquetes["hbomax"]?.let { if (!lanzarVista(ctx, "https://play.hbomax.com/search/result?searchTerm=$q", it)) { app(ctx, it); Ajustes.copiar(ctx, texto) } }
            "prime" -> paquetes["prime"]?.let { if (!lanzarVista(ctx, "https://app.primevideo.com/search?phrase=$q", it)) app(ctx, it) }
            "crunchyroll" -> paquetes["crunchyroll"]?.let { paquete ->
                Ajustes.copiar(ctx, texto)
                val guiada = HomeAccessibilityService.activo && BusquedaAccesible.iniciar(ctx, paquete, texto)
                app(ctx, paquete, limpio = guiada)
                Toast.makeText(ctx, if (guiada) "Buscando «$texto» en Crunchyroll…" else "«$texto» copiado: búscalo en Crunchyroll", Toast.LENGTH_LONG).show()
            }
            "iptv" -> {
                Ajustes.copiar(ctx, texto)
                val guiada = HomeAccessibilityService.activo && BusquedaAccesible.iniciar(ctx, IPTV, texto)
                Toast.makeText(ctx, if (guiada) "Buscando «$texto» en IPTV…" else "«$texto» copiado: búscalo en IPTV", Toast.LENGTH_LONG).show()
                app(ctx, IPTV)
            }
            "youtube" -> if (!lanzarVista(ctx, "https://www.youtube.com/results?search_query=$q", SMARTTUBE)) app(ctx, SMARTTUBE)
            // Cualquier otra app: su plantilla de búsqueda de gustos.json; si no tiene, la app con el texto copiado.
            else -> paquetes[servicio]?.let { p ->
                val url = plantillas[servicio]?.replace("{q}", q) ?: if (servicio == "netflix") "https://www.netflix.com/search?q=$q" else null
                if (url == null || !lanzarVista(ctx, url, p)) { Ajustes.copiar(ctx, texto); app(ctx, p); Toast.makeText(ctx, "«$texto» copiado: búscalo en la app", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Apps a las que el buscador puede mandar el texto («Buscar directo en»): las conocidas y las de gustos.json con plantilla, solo si están instaladas. */
    fun destinosBusqueda(ctx: Context, paquetes: Map<String, String>, nombres: Map<String, String>): List<Pair<String, String>> {
        val conocidos = listOf("hbomax" to "HBO Max", "crunchyroll" to "Crunchyroll", "iptv" to "IPTV", "prime" to "Prime Video", "netflix" to "Netflix", "youtube" to "YouTube")
        val extra = plantillas.keys.filter { k -> conocidos.none { it.first == k } }.map { it to (nombres[it] ?: it.replaceFirstChar { c -> c.uppercase() }) }
        return (conocidos + extra).filter { (k, _) ->
            val p = when (k) { "iptv" -> IPTV; "youtube" -> SMARTTUBE; else -> paquetes[k] } ?: return@filter false
            ctx.packageManager.getLaunchIntentForPackage(p) != null || ctx.packageManager.getLeanbackLaunchIntentForPackage(p) != null
        }
    }

    /** Respaldo: copia el título y abre IPTV (su buscador no admite intents). Busca solo la palabra clave: el catálogo de IPTV rara vez trae el título exacto. */
    fun enIptv(ctx: Context, t: Titulo) {
        val q = palabraClave(t.titulo)
        Ajustes.copiar(ctx, q)
        val guiada = HomeAccessibilityService.activo && BusquedaAccesible.iniciar(ctx, IPTV, q)
        Toast.makeText(ctx, if (guiada) "Buscando «$q» en IPTV…" else "«$q» copiado: búscalo en IPTV", Toast.LENGTH_LONG).show()
        app(ctx, IPTV)
    }

    private val vacias = setOf(
        "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del", "al", "y", "e", "o", "en", "con", "por", "para", "sin", "su", "sus", "mi", "tu",
        "the", "a", "an", "of", "and", "or", "in", "on", "to", "for", "with", "from", "at", "by", "my", "your", "his", "her",
        "parte", "part", "temporada", "season", "capitulo", "capítulo", "episode", "vol", "volumen", "volume", "movie", "pelicula", "película", "serie",
    )

    /** La palabra con más peso del título: la más larga que no sea artículo, preposición, número ni «parte/temporada». Si no queda ninguna, el título entero. */
    fun palabraClave(titulo: String): String {
        val palabras = titulo.split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }
        val utiles = palabras.filter { it.lowercase() !in vacias && !it.all(Char::isDigit) && !Regex("^[ivxlc]+$", RegexOption.IGNORE_CASE).matches(it) }
        return utiles.maxByOrNull { it.length } ?: titulo.trim()
    }

    const val IPTV = "com.example.iptv"
    const val SMARTTUBE = "org.smarttube.stable"

    /** Plantillas de búsqueda por servicio (campo «busqueda» de los proveedores de gustos.json, con {q}). Las carga el VM. */
    @Volatile var plantillas: Map<String, String> = emptyMap()

    /** Deep link por servicio. null = no hay forma conocida: se abre solo la app. */
    fun enlace(servicio: String, t: Titulo): String? {
        val q = Uri.encode(t.titulo)
        return when (servicio) {
            "crunchyroll" -> null // sin búsqueda por enlace: se usa la búsqueda guiada (accesibilidad)
            // Ruta oficial hallada en la fase 5: abre los resultados con el texto ya puesto.
            "hbomax" -> "https://play.hbomax.com/search/result?searchTerm=$q"
            "netflix" -> "https://www.netflix.com/search?q=$q"
            // Prime Video en Fire OS vive en com.amazon.firebat (el launcher de Amazon lo abre así).
            // La búsqueda funciona; /detail?gti= necesita un id de Amazon que TMDB no da.
            "prime" -> "https://app.primevideo.com/search?phrase=$q"
            else -> plantillas[servicio]?.replace("{q}", q)
        }
    }

    /** Apps de video que se cierran entre sí al abrir otra (la 3.ª tarea de la 0.10.0). */
    private val VIDEO = listOf("com.example.iptv", "org.smarttube.stable", "com.crunchyroll.crunchyroid", "com.hbo.hbonow", "com.amazon.firebat", "com.amazon.avod")

    /** Lo que firetv-debloat
o-tocar.txt protege y es de esta lista: nunca se pide cerrarlo (firebat es el Prime del sistema). */
    private val NO_TOCAR = setOf("com.amazon.firebat")

    /** A qué paquetes se pide cerrar en segundo plano al abrir `abre`: la lista de video menos la app que se abre y menos lo protegido. */
    fun paquetesALiberar(abre: String): List<String> = VIDEO.filter { it != abre && it !in NO_TOCAR }

    /**
     * Antes de abrir una app de video, cierra las otras de la lista que estén en segundo plano.
     * Nunca toca la que se abre. Respeta el ajuste «Liberar memoria al abrir una app» y lo anota en diagnostico.jsonl.
     */
    fun liberarMemoria(ctx: Context, abre: String) {
        if (abre !in VIDEO || !co.davidvega.launcher.data.Preferencias.liberarMemoria(ctx)) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val cerradas = paquetesALiberar(abre)
            cerradas.forEach { am.killBackgroundProcesses(it) }
            Diagnostico.registrar("liberar", "al abrir $abre se pidió cerrar en segundo plano: ${cerradas.joinToString()}")
        } catch (e: Exception) {
            Diagnostico.registrar("liberar", "no se pudo liberar memoria (${e.javaClass.simpleName})")
        }
    }

    /** Escalones de enlace que fallan seguido (3 veces con el mismo paquete y escalón) se saltan 24 h. Solo en memoria. */
    val saltos = SaltosAbrir()

    /**
     * Lanza el enlace en el paquete. `escalon` (0 retomar, 1 ruta exacta, 2 búsqueda) sirve para medir y para saltar los que fallan:
     * cada intento queda en diagnostico.jsonl como «tiempos» (resolvió o no, milisegundos del lanzamiento, escalón).
     */
    private fun lanzarVista(ctx: Context, uri: String, paquete: String, escalon: Int = 2): Boolean {
        val clave = "$paquete|$escalon"
        if (saltos.saltar(clave)) { Diagnostico.registrar("tiempos", "apertura $paquete esc=$escalon omitido (3 fallos seguidos, 24 h)"); return false }
        val t0 = android.os.SystemClock.elapsedRealtime()
        var motivo = "ok"
        val ok = try {
            liberarMemoria(ctx, paquete)
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(paquete).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Diagnostico.registrar("enlace", "$paquete no resolvió el enlace (ActivityNotFound)"); motivo = "ActivityNotFound"
            false
        } catch (e: SecurityException) {
            Diagnostico.registrar("enlace", "$paquete rechazó el enlace (SecurityException)"); motivo = "SecurityException"
            false
        }
        saltos.resultado(clave, ok)
        Diagnostico.registrar("tiempos", "apertura $paquete esc=$escalon resolvio=$ok ms=${android.os.SystemClock.elapsedRealtime() - t0} $motivo")
        return ok
    }

    /** limpio: abre la app desde su inicio (CLEAR_TASK), sin la ficha o el reproductor que dejó abiertos; lo usa la búsqueda guiada. */
    /** Tráiler de YouTube en SmartTube; si no está, en la app que abra enlaces de YouTube. */
    fun trailer(ctx: Context, clave: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$clave")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(Intent(i).setPackage(SMARTTUBE)) } catch (e: ActivityNotFoundException) {
            try { ctx.startActivity(i) } catch (e2: ActivityNotFoundException) { Toast.makeText(ctx, "No hay app para ver el tráiler", Toast.LENGTH_LONG).show() }
        }
    }

    fun app(ctx: Context, paquete: String, limpio: Boolean = false) {
        if (paquete == Apps.AJUSTES && ctx.packageManager.getLaunchIntentForPackage(paquete) == null) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(paquete) ?: ctx.packageManager.getLaunchIntentForPackage(paquete)
        if (i == null) {
            Toast.makeText(ctx, "App no instalada: $paquete", Toast.LENGTH_LONG).show()
            return
        }
        liberarMemoria(ctx, paquete)
        val t0 = android.os.SystemClock.elapsedRealtime()
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (limpio) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))); Diagnostico.registrar("tiempos", "apertura $paquete esc=3 resolvio=true ms=${android.os.SystemClock.elapsedRealtime() - t0}") }
        catch (e: Exception) { Diagnostico.registrar("enlace", "no se pudo abrir $paquete (${e.javaClass.simpleName})"); Toast.makeText(ctx, "No se pudo abrir la app", Toast.LENGTH_LONG).show() }
    }
}

/** Cuenta fallos seguidos por clave (paquete|escalón): al tercero se salta durante 24 h; un éxito reinicia la cuenta. */
class SaltosAbrir(private val reloj: () -> Long = System::currentTimeMillis) {
    private val fallos = HashMap<String, Int>()
    private val hasta = HashMap<String, Long>()

    @Synchronized fun saltar(clave: String): Boolean {
        val h = hasta[clave] ?: return false
        if (reloj() < h) return true
        hasta.remove(clave); fallos.remove(clave) // pasó el castigo: se vuelve a intentar
        return false
    }

    @Synchronized fun resultado(clave: String, ok: Boolean) {
        if (ok) { fallos.remove(clave); hasta.remove(clave); return }
        val n = (fallos[clave] ?: 0) + 1
        fallos[clave] = n
        if (n >= FALLOS) hasta[clave] = reloj() + CASTIGO_MS
    }

    companion object { const val FALLOS = 3; const val CASTIGO_MS = 24 * 3_600_000L }
}
