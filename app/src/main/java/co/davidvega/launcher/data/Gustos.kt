package co.davidvega.launcher.data

import org.json.JSONObject

/** `busqueda`: enlace de búsqueda de la app con {q} en lugar del texto (opcional: así se integra una app nueva sin recompilar). */
data class Proveedor(val clave: String, val nombre: String, val ids: List<Int>, val paquete: String, val busqueda: String = "")

data class Ancla(val titulo: String, val busqueda: String, val medio: String?, val servicio: String, val peso: Int, val tmdbId: Int? = null)

data class Discover(
    val generos: List<Int>,
    val generosO: Boolean,
    val sinGeneros: List<Int>,
    val idiomaOriginal: String?,
    val palabrasClave: List<String>,
    val redes: List<Int>,
    val orden: String,
    val minVotos: Int,
    val minNota: Double,
    val desdeAnio: Int?,
    val paginas: Int,
)

data class FilaSpec(
    val id: String,
    val titulo: String,
    val tipo: String, // por_ancla | mixto | discover
    val medio: String, // tv | movie | ambos
    val anclas: List<String>,
    val discover: Discover?,
    val estricto: Boolean,
    val max: Int,
    val maxFilas: Int,
    val filtroGeneros: Boolean,
)

/** Perfil de gustos cargado de gustos.json. Misma estructura que usa web/generar_web.py. */
data class Gustos(
    val region: String,
    val idioma: String,
    val proveedores: List<Proveedor>,
    val notaMinima: Double,
    val anclas: List<Ancla>,
    val filas: List<FilaSpec>,
) {
    fun claveDeProveedorTmdb(id: Int): String? = proveedores.firstOrNull { id in it.ids }?.clave

    companion object {
        /**
         * Perfil de gustos que nunca lanza: prueba el texto externo (adb push) y, si está mal formado o no trae ningún proveedor,
         * usa el de respaldo (el de los assets) y avisa con `aviso`. Devuelve el texto usado junto al perfil.
         */
        fun cargar(externo: () -> String?, respaldo: () -> String, aviso: (String) -> Unit = {}): Pair<String, Gustos> {
            try {
                externo()?.let { t -> return t to parsear(t).also { require(it.proveedores.isNotEmpty()) { "sin proveedores" } } }
            } catch (e: Exception) {
                aviso("gustos.json externo inválido (${e.javaClass.simpleName}): se usa el de la app")
            }
            val t = respaldo()
            return t to parsear(t)
        }

        fun parsear(texto: String): Gustos {
            val r = JSONObject(texto)
            // Lectura tolerante: un proveedor, ancla o fila mal formados se saltan; el resto del perfil se conserva.
            val provs = r.getJSONObject("proveedores").let { po ->
                po.keys().asSequence().filter { !it.startsWith("_") }.mapNotNull { k ->
                    try {
                        val p = po.getJSONObject(k)
                        Proveedor(k, p.optString("nombre", k), p.optJSONArray("ids").aListaInt(), p.optString("paquete"), p.optString("busqueda"))
                    } catch (_: Exception) { null }
                }.toList()
            }
            val anclas = r.optJSONArray("anclas")?.let { a ->
                (0 until a.length()).mapNotNull {
                    try {
                        val o = a.getJSONObject(it)
                        Ancla(o.getString("titulo"), o.optString("titulo_busqueda", o.getString("titulo")),
                            o.optStringOrNull("medio"), o.optString("servicio"), o.optInt("peso", 1),
                            if (o.has("tmdb_id")) o.getInt("tmdb_id") else null)
                    } catch (_: Exception) { null }
                }
            } ?: emptyList()
            val filas = r.optJSONArray("filas")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                  try {
                    val o = a.getJSONObject(i)
                    FilaSpec(
                        id = o.getString("id"), titulo = o.getString("titulo"),
                        tipo = o.optString("tipo", "discover"), medio = o.optString("medio", "ambos"),
                        anclas = o.optJSONArray("anclas").aLista(),
                        discover = o.optJSONObject("discover")?.let { d ->
                            Discover(
                                generos = d.optJSONArray("generos").aListaInt(),
                                generosO = d.optString("generos_modo", "y") == "o",
                                sinGeneros = d.optJSONArray("sin_generos").aListaInt(),
                                idiomaOriginal = d.optStringOrNull("idioma_original"),
                                palabrasClave = d.optJSONArray("palabras_clave").aLista(),
                                redes = d.optJSONArray("redes").aListaInt(),
                                orden = d.optString("orden", "popularity.desc"),
                                minVotos = d.optInt("min_votos", 100),
                                minNota = d.optDouble("min_nota", 6.5),
                                desdeAnio = if (d.has("desde_anio")) d.getInt("desde_anio") else null,
                                paginas = d.optInt("paginas", 1),
                            )
                        },
                        estricto = o.optString("filtro", "estricto") != "preferir",
                        max = o.optInt("max", 20), maxFilas = o.optInt("max_filas", 3),
                        filtroGeneros = o.optBoolean("filtro_generos", false),
                    )
                  } catch (_: Exception) { null }
                }
            } ?: emptyList()
            return Gustos(
                region = r.optString("region", "CO"), idioma = r.optString("idioma", "es-MX"),
                proveedores = provs, notaMinima = r.optJSONObject("declarados")?.optDouble("nota_minima", 6.5) ?: 6.5,
                anclas = anclas, filas = filas,
            )
        }
    }
}
