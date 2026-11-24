package co.davidvega.launcher.data

import org.json.JSONObject
import java.io.File

/**
 * Afinidad por servicio y por tipo («anime en Crunchyroll», «series en HBO», «películas en Prime»), calculada de reproducciones.jsonl.
 * Sirve para dos cosas (plan 0.14, R2): subir o bajar el orden de las filas con un bono pequeño y decidir qué servicio se ofrece primero
 * cuando un título está en varios. Para que el servicio más usado no se coma todo, `limitarPorServicio` deja como mucho el 60 % de una fila
 * con el mismo servicio principal.
 * Todo son funciones puras salvo `cargar`, que solo lee el archivo.
 */
object Afinidad {
    /** Una sesión resumida: servicio («crunchyroll», «hbomax»...), tipo («anime», «serie», «pelicula»), fracción vista (null si no se sabe) y cuándo terminó (ms). */
    data class Registro(
        val servicio: String, val tipo: String, val fraccion: Double?, val vistoMs: Long, val fin: Long,
        /** `medio:tmdb_id` y episodio: dos sesiones con la misma clave y día son una sola vista. Vacío = sesión suelta. */
        val clave: String = "",
        /** Duración del contenido en ms (0 = no se sabe). */
        val duracionMs: Long = 0,
    )

    /** Pesos ya comprimidos con el techo del aprendizaje: por servicio y por «servicio:tipo». */
    class Mapa(val porServicio: Map<String, Double>, val porServicioTipo: Map<String, Double>)

    val VACIA = Mapa(emptyMap(), emptyMap())

    /** Vida media del peso de una vista en la afinidad: 21 días (el peso de los géneros sigue en 30): el gusto por app cambia más rápido que el de género. */
    const val VIDA_AFINIDAD_MS = 21.0 * 86_400_000.0

    /** Suma mínima que se toma como «mucho»: con menos (unas 3 vistas completas) la afinidad no llega al tope. */
    const val SUMA_REFERENCIA = 12.0

    /** La afinidad del servicio más visto no pasa de este valor (0,9 del techo): deja espacio sobre el tope. */
    const val TOPE_AFINIDAD = 0.9 * Aprendizaje.K_TECHO

    /** Máximo de títulos de una fila con el mismo servicio principal. */
    const val TOPE_SERVICIO = 0.6

    /** Magnitud máxima del bono que suma al puntaje de un título. */
    const val BONO_MAXIMO = 2.0

    /** Crunchyroll es anime; lo demás se separa por medio de TMDB. */
    fun tipoDe(servicio: String?, medio: String): String = when {
        servicio == "crunchyroll" -> "anime"
        medio == "movie" -> "pelicula"
        else -> "serie"
    }

    private fun decaeAfinidad(v: Double, t: Long, ahora: Long) = v * Math.pow(0.5, maxOf(0L, ahora - t) / VIDA_AFINIDAD_MS)

    /**
     * Pesos por servicio y por tipo (0.15). Las sesiones con la misma clave en el mismo día se juntan en una vista (fracciones sumadas, tope 1)
     * con el nivel de `Visionado.nivelVisionado` (abandono -2,5 a completa 4), un título suma como mucho 4 por día, cada vista pesa según su
     * duración (`Visionado.pesoDuracion`) y decae con vida media de 21 días. En vez de comprimir cada suma con el techo (con 70 de suma HBO y
     * Crunchyroll quedaban en 5,5 y 5,6 aunque uno tuviera el doble de horas), se reparte en proporción a la suma del servicio más visto:
     * el más visto vale `TOPE_AFINIDAD` (menos si hay pocas vistas: `SUMA_REFERENCIA`) y los demás su proporción. Un servicio con más
     * abandono que vistas queda en negativo (piso -3).
     */
    fun calcular(registros: List<Registro>, ahora: Long): Mapa {
        // Una vista = clave + día local; las sesiones sueltas (clave vacía) van cada una por su lado.
        val vistas = LinkedHashMap<String, MutableList<Registro>>()
        registros.forEachIndexed { i, r ->
            if (r.servicio.isBlank()) return@forEachIndexed
            val k = if (r.clave.isBlank()) "#$i" else r.servicio + "|" + r.clave + "|" + Aprendizaje.diaDe(r.fin)
            vistas.getOrPut(k) { ArrayList() }.add(r)
        }
        val s = HashMap<String, Double>()
        val st = HashMap<String, Double>()
        for (grupo in vistas.values) {
            val r = grupo.maxByOrNull { it.fin }!!
            val fracciones = grupo.map { it.fraccion }
            val total = if (fracciones.any { it == null }) null else fracciones.sumOf { it!! }.coerceIn(0.0, 1.0)
            val visto = grupo.sumOf { it.vistoMs }
            val nivel = Visionado.nivelVisionado(total, visto).let { if (it > 0) minOf(it, Visionado.TOPE_DIA) else it }
            val dur = grupo.maxOf { it.duracionMs }
            val w = decaeAfinidad(nivel * Visionado.pesoDuracion(dur), r.fin, ahora)
            s.merge(r.servicio, w, Double::plus)
            st.merge(r.servicio + ":" + r.tipo, w, Double::plus)
        }
        val referencia = maxOf(s.values.maxOrNull() ?: 0.0, SUMA_REFERENCIA)
        fun escala(w: Double) = (w / referencia * TOPE_AFINIDAD).coerceIn(-Visionado.K_PISO, TOPE_AFINIDAD)
        return Mapa(s.mapValues { escala(it.value) }, st.mapValues { escala(it.value) })
    }

    /** Líneas de reproducciones.jsonl a registros; una línea mala o sin título (Prime sin resolver) se salta. */
    fun parsear(lineas: List<String>): List<Registro> = lineas.mapNotNull { l ->
        val o = runCatching { JSONObject(l) }.getOrNull() ?: return@mapNotNull null
        if (o.optBoolean("sin_titulo")) return@mapNotNull null
        val servicio = o.optString("servicio").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val medio = o.optString("medio").ifBlank { if (o.has("serie")) "tv" else "movie" }
        val dur = o.optLong("duracion_s") * 1000
        val pos = if (o.has("posicion_ms")) o.optLong("posicion_ms") else -1L
        val visto = o.optLong("visto_s") * 1000
        // Tráileres y clips (menos de 5 minutos o «tráiler» en el título) no son vistas.
        if (Visionado.esClip(o.optString("titulo"), o.optLong("duracion_s"))) return@mapNotNull null
        val id = o.optInt("tmdb_id", -1)
        val clave = if (id > 0) medio + ":" + id + ":" + o.optString("episodio") else ""
        Registro(servicio, tipoDe(servicio, medio), Aprendizaje.fraccionVista(pos, dur, visto), visto, o.optLong("fin"), clave, dur)
    }

    /** Lee las últimas 600 líneas del archivo (las más recientes pesan más por el decaimiento). Archivo ausente: afinidad vacía. */
    fun cargar(archivo: File, ahora: Long): Mapa {
        if (!archivo.exists()) return VACIA
        return calcular(parsear(archivo.readLines().takeLast(600)), ahora)
    }

    /** Bono de un título: la afinidad de su servicio principal y tipo (0 a 0,9) más la del servicio solo (0 a 0,9), a partes iguales, dentro de ±BONO_MAXIMO (2: es la única señal de servicio que sale de lo visto). */
    fun bono(m: Mapa, t: Titulo): Double {
        val servicio = t.servicios.firstOrNull() ?: return 0.0
        val a = m.porServicioTipo[servicio + ":" + tipoDe(servicio, t.medio)] ?: 0.0
        val b = m.porServicio[servicio] ?: 0.0
        return ((a + b) / 2.0 / Aprendizaje.K_TECHO * BONO_MAXIMO).coerceIn(-BONO_MAXIMO, BONO_MAXIMO)
    }

    /** Servicios de un título con el más visto primero; a igual afinidad se conserva el orden original. */
    fun ordenarServicios(servicios: List<String>, m: Mapa): List<String> = servicios.sortedByDescending { m.porServicio[it] ?: 0.0 }

    /**
     * Deja como mucho `tope` (60 %) de los títulos con el mismo servicio principal, en el mismo orden. Los títulos sin servicio no cuentan.
     * Si al recortar la fila quedara con menos de 6 títulos (no hay otro servicio de dónde sacar), se devuelve intacta.
     */
    fun limitarPorServicio(items: List<Titulo>, tope: Double = TOPE_SERVICIO): List<Titulo> {
        if (items.size < 6) return items
        val limite = maxOf(1, Math.floor(items.size * tope).toInt())
        val cuenta = HashMap<String, Int>()
        val salida = items.filter { t ->
            val s = t.servicios.firstOrNull() ?: return@filter true
            val n = (cuenta[s] ?: 0) + 1
            cuenta[s] = n
            n <= limite
        }
        return if (salida.size >= 6) salida else items
    }
}
