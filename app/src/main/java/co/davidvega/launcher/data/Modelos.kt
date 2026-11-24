package co.davidvega.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/** Un título recomendado (película o serie). */
data class Titulo(
    val id: Int,
    val medio: String, // "movie" | "tv"
    val titulo: String,
    val sinopsis: String,
    val poster: String?, // URL completa o null
    val fondo: String?, // URL completa o null
    val anio: String,
    val nota: Double,
    val servicios: List<String>, // claves de proveedor (netflix, prime, hbomax, crunchyroll), por prioridad
    val motivo: String? = null,
    val gradiente: Int = 0, // solo para datos de ejemplo sin imagen
    val noDisponible: Boolean = false, // fila "preferir": no está en tus servicios en CO
    val generos: List<Int> = emptyList(), // ids de género de TMDB (para aprender gustos)
    val idioma: String = "", // idioma original (ja, en, ko...): sirve de «origen»
    val doblaje: String = "", // nombre de `Doblaje` si ya se sabe («» = sin dato); lo llena quien tenga el dato, la tarjeta lo lee con `Doblaje.estado`
) {
    /** Fondo a tamaño de tarjeta (w780) para no decodificar el de 1280 px en cada tarjeta. */
    val fondoTarjeta: String? get() = fondo?.replace("/w1280/", "/w780/")

    /** Sinopsis corta para el hero: hasta dos frases y 150 caracteres. */
    val sinopsisCorta: String get() {
        val t = sinopsis.trim()
        if (t.length <= 150) return t
        val corte = t.substring(0, 150)
        val punto = corte.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (punto > 60) corte.substring(0, punto + 1) else corte.substring(0, corte.lastIndexOf(' ').coerceAtLeast(60)).trimEnd(',', ';', ':') + "…"
    }

    fun aJson() = JSONObject().apply {
        put("id", id); put("medio", medio); put("titulo", titulo); put("sinopsis", sinopsis)
        put("poster", poster ?: JSONObject.NULL); put("fondo", fondo ?: JSONObject.NULL)
        put("anio", anio); put("nota", if (nota.isFinite()) nota else 0.0); put("servicios", JSONArray(servicios))
        put("motivo", motivo ?: JSONObject.NULL); put("gradiente", gradiente); put("noDisponible", noDisponible)
        put("generos", JSONArray(generos)); put("idioma", idioma)
        if (doblaje.isNotEmpty()) put("doblaje", doblaje)
    }

    companion object {
        fun deJson(o: JSONObject, indice: Int = 0) = Titulo(
            id = o.optInt("id", -(indice + 1)),
            medio = o.optString("medio", "tv"),
            titulo = o.optString("titulo"),
            sinopsis = o.optString("sinopsis"),
            poster = o.optStringOrNull("poster"),
            fondo = o.optStringOrNull("fondo"),
            anio = o.optString("anio"),
            nota = o.optDouble("nota", 0.0).takeIf { it.isFinite() } ?: 0.0,
            servicios = o.optJSONArray("servicios").aLista(),
            motivo = o.optStringOrNull("motivo"),
            gradiente = o.optInt("gradiente", 0),
            noDisponible = o.optBoolean("noDisponible", false),
            generos = o.optJSONArray("generos").aListaInt(), idioma = o.optString("idioma"),
            doblaje = o.optString("doblaje"),
        )
    }
}

data class Fila(val id: String, val titulo: String, val items: List<Titulo>) {
    fun aJson() = JSONObject().apply {
        put("id", id); put("titulo", titulo); put("items", JSONArray(items.map { it.aJson() }))
    }

    companion object {
        fun deJson(o: JSONObject) = Fila(
            id = o.optString("id"),
            titulo = o.optString("titulo"),
            items = o.optJSONArray("items").let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Titulo.deJson(it, i) } } },
        )
    }
}

/** Lo que la ficha completa al abrirse: dónde está disponible (CO) y títulos parecidos. */
data class DatosFicha(val servicios: List<String>, val parecidos: List<Titulo>, val rutas: Map<String, String> = emptyMap(), val palabras: List<Pair<Int, String>> = emptyList(), val resuelto: Titulo? = null)

/** Datos extra del hero grande: duración o temporadas, géneros con nombre, dirección y reparto. */
data class Detalle(
    val duracion: String, val generos: List<String>, val direccion: String?, val reparto: List<String>,
    val logo: String? = null, // URL del logo del título (TMDB /images), con transparencia
    val temporadas: List<Pair<Int, Int>> = emptyList(), // (número de temporada, episodios) de las series
)

enum class Modo { MUESTRA, TMDB, CACHE }

data class Estado(
    val filas: List<Fila> = emptyList(),
    val modo: Modo = Modo.MUESTRA,
    val aviso: String? = null,
    val cargando: Boolean = true,
)

/** Contrato de datos para una futura fuente de búsqueda (voz, otro catálogo). Sin implementación real todavía. */
interface FuenteBusqueda {
    val id: String
    suspend fun buscar(consulta: String): List<Titulo>
}

/** Hueco para búsqueda por voz: el día que haya un motor, devuelve el texto reconocido. */
interface BusquedaPorVoz {
    val disponible: Boolean
    suspend fun escuchar(): String?
}

object SinVoz : BusquedaPorVoz {
    override val disponible = false
    override suspend fun escuchar(): String? = null
}

fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

fun JSONArray?.aLista(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
fun JSONArray?.aListaInt(): List<Int> = if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? Number)?.toInt() }
