package co.davidvega.launcher.data

import org.json.JSONObject

/**
 * Parte pura del recomendador (sin red ni Context), separada de `Recomendador` para poder probarla con propiedades (plan 0.14, 6.1):
 * puntuar candidatos, filtrarlos, armar la fila final y traducir géneros.
 */
object RecomendadorPuro {
    /** Un candidato de TMDB con su puntaje acumulado. */
    class Cand(val json: JSONObject, val medio: String, var puntaje: Double, var motivo: String?)

    /** Votos mínimos para entrar en una fila. */
    const val VOTOS_MINIMOS = 30

    /** Puntaje de un candidato que viene de las recomendaciones o similares de un ancla: peso del ancla por el multiplicador (2 o 1) más la nota/10. */
    fun puntuarAncla(pesoAncla: Int, multiplicador: Double, notaTmdb: Double): Double = pesoAncla * multiplicador + notaTmdb / 10.0

    /** Puntaje de un candidato de discover: base 0,5 más la nota/10 y 0,3 si salió con palabras clave. */
    fun puntuarDiscover(notaTmdb: Double, conClaves: Boolean): Double = 0.5 + notaTmdb / 10.0 + (if (conClaves) 0.3 else 0.0)

    /** TMDB usa otros ids de género en series. Traducción de los que usa el perfil. */
    fun generoTv(g: Int): Int = when (g) {
        27, 53 -> 9648 // terror/thriller -> misterio
        28, 12 -> 10759
        14, 878 -> 10765
        10752 -> 10768
        else -> g
    }

    /** Con filtro_generos, los candidatos de anclas deben compartir al menos un género con el discover de la fila. `genre_ids` ausente o mal formado no lanza. */
    fun comparteGenero(json: JSONObject, medio: String, spec: FilaSpec): Boolean {
        val pedidos = spec.discover?.generos?.map { if (medio == "tv") generoTv(it) else it }?.toSet() ?: return true
        if (pedidos.isEmpty()) return true
        val ids = json.optJSONArray("genre_ids") ?: return false
        return (0 until ids.length()).any { i -> (ids.opt(i) as? Number)?.toInt()?.let { it in pedidos } == true }
    }

    /**
     * Candidatos que pasan el filtro de la fila, de mayor a menor puntaje (a igual puntaje, por clave: el resultado no depende del orden de entrada),
     * como mucho min(spec.max * 2, 40). Exige la nota mínima (la menor entre la de la fila y la global), 30 votos y alguna imagen.
     */
    fun filtrarCandidatos(cands: Map<String, Cand>, spec: FilaSpec, excluir: Set<String>, notaMinimaGlobal: Double): List<Pair<String, Cand>> {
        val minNota = spec.discover?.minNota ?: notaMinimaGlobal
        return cands.entries.asSequence()
            .filter { it.key !in excluir }
            .filter { spec.medio == "ambos" || it.value.medio == spec.medio }
            .filter { it.value.json.optDouble("vote_average", 0.0) >= minOf(minNota, notaMinimaGlobal) }
            .filter { it.value.json.optInt("vote_count", 0) >= VOTOS_MINIMOS }
            .filter { !spec.filtroGeneros || comparteGenero(it.value.json, it.value.medio, spec) }
            .filter { Tmdb.tieneImagen(it.value.json) }
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Cand>> { it.second.puntaje }.thenBy { it.first })
            .take(minOf(spec.max * 2, 40))
            .toList()
    }

    /** Quita los ya usados en filas anteriores, salvo que queden menos de 6 (entonces se dejan todos). Aplicarlo dos veces da lo mismo que una. */
    fun sinUsados(lista: List<Pair<String, Titulo>>, usados: Set<String>): List<Pair<String, Titulo>> {
        val sinUsar = lista.filter { it.first !in usados }
        return if (sinUsar.size >= 6) sinUsar else lista
    }

    /** Fila final: sin repetidos por `medio:id`, sin los usados (según `sinUsados`) y con spec.max títulos como mucho. */
    fun armarFila(lista: List<Pair<String, Titulo>>, spec: FilaSpec, usados: Set<String>): List<Pair<String, Titulo>> =
        sinUsados(lista.distinctBy { it.first }, usados).take(spec.max)
}
