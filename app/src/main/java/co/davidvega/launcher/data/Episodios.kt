package co.davidvega.launcher.data

import java.text.Normalizer

/**
 * Cruce del nombre de un episodio con la lista de episodios de TMDB para sacar su número (Crunchyroll solo publica el nombre).
 * Es aproximado: se compara el nombre normalizado (sin tildes ni signos); un empate entre episodios distintos o ninguna coincidencia da null.
 */
object Episodios {
    private val MARCAS = Regex("\\p{Mn}+")
    private val NO_ALFANUM = Regex("[^\\p{L}\\p{N}]+")

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARCAS, "").lowercase().replace(NO_ALFANUM, " ").trim()

    /**
     * `episodios`: (número, nombre) de todas las temporadas candidatas. Devuelve el número si exactamente un episodio tiene ese nombre;
     * si hay dos o más con el mismo nombre en números distintos (empate), o ninguno, null. Un nombre de menos de 3 caracteres no vale.
     */
    fun numeroPorNombre(nombre: String, episodios: List<Pair<Int, String>>): Int? {
        val n = norm(nombre)
        if (n.length < 3) return null
        val coinciden = episodios.filter { norm(it.second) == n }
        return coinciden.singleOrNull()?.first
    }
}
