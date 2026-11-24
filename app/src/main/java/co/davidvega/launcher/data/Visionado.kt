package co.davidvega.launcher.data

import kotlin.math.max
import kotlin.math.min

/**
 * Reglas puras de «cuánto vio el usuario de verdad» (0.15), calibradas con un mes de uso real (89 sesiones):
 *  - Un visionado es lo que ve de un mismo episodio o película en una ventana de 48 horas, aunque lo parta en varias sesiones
 *    (el 13 % de sus visionados tuvo más de una sesión: «El Conjuro 2» fue 22 % + 52 % + 21 % y contaba como tres pruebas).
 *  - Abandonar (menos de 10 %) anula casi toda la intención de abrirlo (ver en +3, ficha +1): «Mother Mary», vista al 1 %, seguía con +6,5.
 *  - Una señal negativa sobre un género amplio (drama, comedia, acción...) pesa una cuarta parte: rechazar una telenovela no es rechazar el drama.
 *  - El peso negativo tiene un piso más cercano (-3) que el techo positivo (6).
 * Sin Context ni red: se prueba con propiedades.
 */
object Visionado {
    /** Una vista que se reanuda dentro de esta ventana suma a la misma. */
    const val VENTANA_MS = 48L * 3_600_000L

    /** Lo máximo que un título suma en un día: una vista completa (el nivel más alto). */
    const val TOPE_DIA = 4.0

    /** Cuánto se resta, además de -0,5, a una vista abandonada (<10 %) para anular la intención previa. */
    const val ANULA_ABANDONO = 2.0

    /** Piso del peso negativo: se acerca a -3 (el techo positivo sigue en `Aprendizaje.K_TECHO`). */
    const val K_PISO = 3.0

    /** Factor de una señal negativa sobre un género amplio. */
    const val ESCALA_NEGATIVO_AMPLIO = 0.25

    /** Géneros que casi cualquier título tiene (drama, comedia, acción, aventura y sus equivalentes de series): una vista aislada no los define. */
    val GENEROS_AMPLIOS = setOf(18, 35, 28, 12, 10759, 10765)

    /** Menos de 5 minutos: tráiler o clip, no cuenta como vista. */
    const val CLIP_MAX_S = 300L

    /**
     * Estado de la vista en curso de un título. `frac` -1 = ninguna; `ep` = episodio («» en películas); `previoDia` = suma de los niveles positivos de las
     * vistas anteriores de ese día; `vistaNivel` = nivel ya sumado por la vista en curso.
     */
    data class Estado(
        val frac: Double = -1.0, val ep: String = "", val t: Long = -1L, val dia: Long = -1L,
        val previoDia: Double = 0.0, val vistaNivel: Double = 0.0, val terminadoT: Long = -1L,
    )

    val NINGUNO = Estado()

    /** Resultado de una sesión: lo que suma (o resta) a los pesos del título y el estado nuevo. */
    data class Resultado(val delta: Double, val estado: Estado)

    /** Nivel de una vista: el de `Aprendizaje.nivelSesion` y, si se abandonó (<10 %), `ANULA_ABANDONO` menos. */
    fun nivelVisionado(fraccion: Double?, vistoMs: Long): Double =
        Aprendizaje.nivelSesion(fraccion, vistoMs) - (if (fraccion != null && fraccion < 0.10) ANULA_ABANDONO else 0.0)

    /**
     * Una sesión de reproducción. Si es del mismo episodio y dentro de 48 h de la anterior, la fracción se acumula (tope 1) y solo se suma la
     * diferencia de nivel: la suma de todas las sesiones de una vista da exactamente el nivel de la fracción total. Los niveles positivos de las
     * vistas de un mismo título en un día suman como mucho `TOPE_DIA` (otro episodio el mismo día ya casi no suma). Sin duración (fracción null)
     * se aplica el nivel de la sesión una sola vez por vista.
     */
    fun aplicar(e: Estado, episodio: String?, fraccion: Double?, vistoMs: Long, ahora: Long, dia: Long): Resultado {
        val ep = episodio.orEmpty()
        val mismo = e.t >= 0 && ahora - e.t in 0..VENTANA_MS && e.ep == ep
        val previa = if (mismo) e.frac else -1.0
        val previo = when {
            e.dia != dia -> 0.0
            mismo -> e.previoDia
            else -> e.previoDia + max(0.0, e.vistaNivel)
        }
        val anterior = if (mismo) e.vistaNivel else 0.0
        val total: Double
        val nivel: Double
        if (fraccion == null) {
            total = previa
            nivel = if (mismo) anterior else Aprendizaje.nivelSesion(null, vistoMs)
        } else {
            total = min(1.0, max(previa, 0.0) + fraccion.coerceIn(0.0, 1.0))
            nivel = nivelVisionado(total, vistoMs)
        }
        val permitido = if (nivel > 0) min(nivel, max(0.0, TOPE_DIA - previo)) else nivel
        val terminado = if (total >= 0.80) ahora else e.terminadoT
        return Resultado(permitido - anterior, Estado(total, ep, ahora, dia, previo, permitido, terminado))
    }

    /**
     * Un delta negativo a un género amplio pesa `ESCALA_NEGATIVO_AMPLIO`; lo positivo y los géneros específicos, entero.
     * El repaso semanal de «No recomendar» (`repaso`) no toca los géneros amplios: se repite cada semana y en un mes dejaba el drama bajo cero
     * por 7 títulos rechazados (telenovelas, Dahmer, Flash), cuando el drama es el tercer género que más ve.
     */
    fun deltaGenero(genero: Int, delta: Double, repaso: Boolean = false): Double = when {
        delta >= 0 || genero !in GENEROS_AMPLIOS -> delta
        repaso -> 0.0
        else -> delta * ESCALA_NEGATIVO_AMPLIO
    }

    /** Como `Aprendizaje.techo` en lo positivo, pero con piso -K_PISO en lo negativo. Creciente, nunca NaN. */
    fun acotarAsimetrico(w: Double): Double = if (w < 0) -Aprendizaje.techo(-w, K_PISO) else Aprendizaje.techo(w)

    /** Peso de una vista según su duración: un episodio de 24 minutos pesa menos que una película de 2 horas. (duración/30 min)^0,5 entre 0,5 y 2; sin duración, 1. */
    fun pesoDuracion(duracionMs: Long): Double =
        if (duracionMs <= 0) 1.0 else Math.sqrt(duracionMs / 1_800_000.0).coerceIn(0.5, 2.0)

    /** Un tráiler o clip: menos de 5 minutos de duración o la palabra tráiler/teaser en el título. Sin duración conocida solo cuenta el título. */
    fun esClip(titulo: String, duracionS: Long): Boolean =
        (duracionS in 1 until CLIP_MAX_S) || Regex("(?i)\\b(tr[aá]iler|teaser|avance)\\b").containsMatchIn(titulo)
}
