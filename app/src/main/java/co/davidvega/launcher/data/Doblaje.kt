package co.davidvega.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Idioma de audio de un título en Colombia, sacado de JustWatch (audioLanguages por oferta) y de lo que el usuario marca a mano.
 * Orden de «mejor» a «peor»: LATINO, ESPAÑOL, SUBTITULADO, DESCONOCIDO. Lo manual («Lo vi doblado / subtitulado») manda sobre JustWatch.
 * Caché en disco de 7 días por medio:tmdbId (filesDir/doblaje_cache.json); lo manual vive en filesDir/doblaje_manual.json, sin caducidad.
 * Si algo falla (red, esquema, disco) el resultado es DESCONOCIDO y nada se rompe.
 */
enum class Doblaje {
    LATINO, ESPAÑOL, SUBTITULADO, DESCONOCIDO;

    /** Posición para ordenar filas: menor primero (LATINO, ESPAÑOL, SUBTITULADO, DESCONOCIDO). */
    val rango: Int get() = ordinal

    /** Marca corta de la tarjeta, o null si no hay nada que decir. */
    val marca: String? get() = when (this) { LATINO, ESPAÑOL -> "ES"; SUBTITULADO -> "SUB"; DESCONOCIDO -> null }

    /** Texto de la ficha, o null si no se sabe. */
    val etiqueta: String? get() = when (this) { LATINO, ESPAÑOL -> "Doblaje en español"; SUBTITULADO -> "Subtitulado"; DESCONOCIDO -> null }

    /** Una oferta de JustWatch reducida a lo que importa: servicio del launcher e idiomas de audio. */
    class Oferta(val servicio: String, val audio: Set<String>)

    /** Estado y de dónde sale: `manual` = lo confirmó el usuario, sin nota de JustWatch. */
    class Resultado(val estado: Doblaje, val manual: Boolean) {
        val nota: String? get() = when {
            manual || estado == DESCONOCIDO -> null
            estado == ESPAÑOL -> "según JustWatch, variante sin confirmar"
            else -> "según JustWatch"
        }
    }

    companion object {
        private const val SIETE_DIAS = 7 * 24 * 3600_000L
        private val cacheMem = ConcurrentHashMap<String, Doblaje>() // medio:id -> estado de JustWatch (sin lo manual)
        private val manualMem = ConcurrentHashMap<String, Boolean>() // true = doblado, false = subtitulado
        private val tiempos = ConcurrentHashMap<String, Long>() // medio:id -> cuándo se consultó JustWatch
        @Volatile private var dir: File? = null

        fun clave(medio: String, id: Int) = "$medio:$id"

        /**
         * Estado a partir de las ofertas. Monótono: agregar `es` al audio de una oferta nunca lo baja.
         * LATINO si HBO Max trae `es`; ESPAÑOL si cualquier servicio trae `es`; SUBTITULADO si es anime y hay audio conocido pero sin `es`.
         * `manual` (true = lo vi doblado, false = lo vi subtitulado) manda sobre todo.
         */
        fun calcular(ofertas: List<Oferta>, anime: Boolean, manual: Boolean? = null): Doblaje {
            if (manual != null) return if (manual) LATINO else SUBTITULADO
            if (ofertas.any { it.servicio == "hbomax" && "es" in it.audio }) return LATINO
            if (ofertas.any { "es" in it.audio }) return ESPAÑOL
            if (anime && ofertas.any { it.audio.isNotEmpty() }) return SUBTITULADO
            return DESCONOCIDO
        }

        /** Ofertas de un nodo de JustWatch (`offers` con audioLanguages). Lo que no cuadra se ignora. */
        fun aOfertas(ofertas: JSONArray?, servicioDe: (String) -> String?): List<Oferta> {
            if (ofertas == null) return emptyList()
            val gratis = setOf("FLATRATE", "FREE", "ADS")
            val out = ArrayList<Oferta>()
            for (i in 0 until ofertas.length()) {
                val o = ofertas.optJSONObject(i) ?: continue
                if (o.optString("monetizationType") !in gratis) continue
                val sv = servicioDe(o.optJSONObject("package")?.optString("shortName").orEmpty()) ?: continue
                val a = o.optJSONArray("audioLanguages")
                val audio = if (a == null) emptySet() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
                out.add(Oferta(sv, audio))
            }
            return out
        }

        /** Hay que llamarlo (es barato e idempotente) antes de usar el disco; sin esto todo queda solo en memoria. */
        fun iniciar(ctx: Context) { iniciar(ctx.filesDir) }

        internal fun iniciar(carpeta: File) {
            if (dir == carpeta) return
            dir = carpeta
            cacheMem.clear(); manualMem.clear(); tiempos.clear()
            val ahora = System.currentTimeMillis()
            leerCacheConTiempo(File(carpeta, "doblaje_cache.json")).filterValues { ahora - it.first in 0 until SIETE_DIAS }.forEach { (k, v) -> cacheMem[k] = v.second; tiempos[k] = v.first }
            leerManual(File(carpeta, "doblaje_manual.json")).forEach { (k, v) -> manualMem[k] = v }
        }

        /** Estado de un título: lo manual si hay, si no lo de JustWatch en caché, si no DESCONOCIDO. No hace red. */
        fun resultado(medio: String, id: Int): Resultado {
            val k = clave(medio, id)
            manualMem[k]?.let { return Resultado(calcular(emptyList(), false, it), true) }
            return Resultado(cacheMem[k] ?: DESCONOCIDO, false)
        }

        fun estado(t: Titulo): Doblaje = resultado(t.medio, t.id).estado

        /** La etiqueta solo se muestra en anime (idioma original japonés) o si el usuario ya marcó el título; en el resto sería ruido. */
        fun mostrable(t: Titulo): Boolean = t.idioma == "ja" || manual(t.medio, t.id) != null

        /**
         * Para ordenar filas de anime y «Esta semana en Colombia»: `sortedBy { Doblaje.rangoDe(it) }` (estable, así a igual puntaje
         * queda LATINO, ESPAÑOL, SUBTITULADO, DESCONOCIDO). No hace red; solo lee lo ya sabido.
         */
        fun rangoDe(t: Titulo): Int = estado(t).rango

        /** Lo que dice JustWatch ahora: se guarda en memoria y disco (sin pisar lo manual, que se aplica al leer). */
        fun registrar(medio: String, id: Int, ofertas: List<Oferta>, anime: Boolean) {
            if (id <= 0) return
            val k = clave(medio, id)
            cacheMem[k] = calcular(ofertas, anime); tiempos[k] = System.currentTimeMillis()
            guardarCache()
        }

        /** «Lo vi doblado» (true), «Lo vi subtitulado» (false) o quitar la marca (null). Persiste por serie. */
        fun marcar(medio: String, id: Int, doblado: Boolean?) {
            if (id <= 0) return
            if (doblado == null) manualMem.remove(clave(medio, id)) else manualMem[clave(medio, id)] = doblado
            dir?.let { Archivos.escribirOk(File(it, "doblaje_manual.json"), serializarManual(manualMem)) }
        }

        fun manual(medio: String, id: Int): Boolean? = manualMem[clave(medio, id)]

        private fun guardarCache() {
            val d = dir ?: return
            val salida = JSONObject()
            for ((k, v) in cacheMem) salida.put(k, JSONObject().put("t", tiempos[k] ?: System.currentTimeMillis()).put("e", v.name))
            Archivos.escribirOk(File(d, "doblaje_cache.json"), salida.toString())
        }

        private fun leerCacheConTiempo(f: File): Map<String, Pair<Long, Doblaje>> = try {
            val j = JSONObject(f.readText())
            j.keys().asSequence().mapNotNull { k ->
                val o = j.optJSONObject(k) ?: return@mapNotNull null
                val e = runCatching { valueOf(o.optString("e")) }.getOrNull() ?: return@mapNotNull null
                k to (o.optLong("t") to e)
            }.toMap()
        } catch (_: Exception) { emptyMap() }

        /** Entradas de la caché de disco con menos de 7 días. Archivo ausente o roto = vacío. */
        internal fun leerCache(f: File, ahora: Long): Map<String, Doblaje> =
            leerCacheConTiempo(f).filterValues { ahora - it.first in 0 until SIETE_DIAS }.mapValues { it.value.second }

        internal fun leerManual(f: File): Map<String, Boolean> = try {
            val j = JSONObject(f.readText())
            j.keys().asSequence().filter { j.opt(it) is Boolean }.associateWith { j.getBoolean(it) }
        } catch (_: Exception) { emptyMap() }

        internal fun serializarManual(m: Map<String, Boolean>): String = JSONObject().also { j -> m.forEach { (k, v) -> j.put(k, v) } }.toString()
    }
}
