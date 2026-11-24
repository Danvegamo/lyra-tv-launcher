package co.davidvega.launcher.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Caché de proveedores por título (`medio:id` a claves de servicio), con vigencia de 24 h. Vive en filesDir (no en cacheDir: `pm trim-caches` lo vacía).
 * Cada entrada recuerda la huella de los proveedores del perfil: si el perfil cambia, la entrada ya no vale. Sin red ni Context para poder probarla.
 */
class CacheProveedores(private val vigenciaMs: Long = 24 * 3600_000L, private val maximo: Int = 4000) {
    private class Entrada(val t: Long, val huella: String, val claves: List<String>)

    private val mapa = ConcurrentHashMap<String, Entrada>()
    @Volatile private var sucia = false

    /** Las claves guardadas si la entrada es del mismo perfil y está vigente (o, con `aceptarVieja`, aunque haya caducado: mejor eso que no saber). */
    fun obtener(k: String, huella: String, ahora: Long, aceptarVieja: Boolean = false): List<String>? {
        val e = mapa[k] ?: return null
        if (e.huella != huella) return null
        return if (aceptarVieja || ahora - e.t in 0 until vigenciaMs) e.claves else null
    }

    fun poner(k: String, huella: String, claves: List<String>, ahora: Long) {
        mapa[k] = Entrada(ahora, huella, claves)
        sucia = true
    }

    fun tamano() = mapa.size

    /** Texto para guardar si hubo cambios desde la última vez (y queda marcada como limpia); null si no hace falta escribir. Descarta las más viejas por encima del máximo. */
    @Synchronized
    fun serializarSiCambio(): String? {
        if (!sucia) return null
        sucia = false
        if (mapa.size > maximo) mapa.entries.sortedBy { it.value.t }.take(mapa.size - maximo).forEach { mapa.remove(it.key) }
        val o = JSONObject()
        mapa.forEach { (k, e) -> o.put(k, JSONObject().put("t", e.t).put("h", e.huella).put("c", org.json.JSONArray(e.claves))) }
        return o.toString()
    }

    /** Carga tolerante: una entrada mala se salta. */
    fun cargar(texto: String) {
        val o = JSONObject(texto)
        o.keys().forEach { k ->
            try {
                val w = o.getJSONObject(k)
                val c = w.getJSONArray("c")
                mapa[k] = Entrada(w.getLong("t"), w.getString("h"), (0 until c.length()).mapNotNull { c.optString(it).takeIf { s -> s.isNotBlank() } })
            } catch (_: Exception) {}
        }
    }
}
