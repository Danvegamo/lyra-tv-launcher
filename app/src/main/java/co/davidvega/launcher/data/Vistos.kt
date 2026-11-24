package co.davidvega.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Un título que el usuario ya vio. `enCurso` solo lo usa Reproducciones con series: no cuentan como vistas ni se ocultan
 * de las recomendaciones.
 */
data class Visto(val id: Int, val medio: String, val titulo: String, val fecha: String, val enCurso: Boolean = false) {
    val clave get() = "$medio:$id"
    fun aJson(): JSONObject = JSONObject().put("id", id).put("medio", medio).put("titulo", titulo).put("fecha", fecha).put("en_curso", enCurso)
}

/** Un título marcado «No recomendar» (menú del título) y el día en que se marcó. */
data class NoRecomendado(val t: Titulo, val fecha: String) {
    val clave get() = t.medio + ":" + t.id
}

/**
 * «Ya lo vi»: vistos.json en el almacenamiento interno (como milista.json). Lo comparten la pantalla y el servicio de
 * escucha de reproducción, por eso vive en App.
 */
class Vistos(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val archivo = File(ctx.filesDir, "vistos.json")
    private val lock = Any()
    private var todos: List<Visto> = cargar()
    private val _lista = MutableStateFlow(todos.filter { !it.enCurso })

    /** Solo los vistos de verdad (sin los «en curso»), los más recientes primero. */
    val lista: StateFlow<List<Visto>> = _lista

    private fun cargar(): List<Visto> = try {
        val a = JSONArray(archivo.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Visto(o.getInt("id"), o.getString("medio"), o.optString("titulo"), o.optString("fecha"), o.optBoolean("en_curso"))
        }
    } catch (e: Exception) { emptyList() }

    private fun publicar() {
        _lista.value = todos.filter { !it.enCurso }
        Archivos.escribirOk(archivo, JSONArray(todos.map { it.aJson() }).toString())
    }

    private fun hoy() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // «No recomendar» (menú del título): no_recomendar.json guarda el título entero (géneros e idioma) para el repaso
    // semanal del aprendizaje. Los primeros que se guardaron eran solo claves «medio:id»: se siguen leyendo.
    private val archivoNo = File(ctx.filesDir, "no_recomendar.json")
    private val archivoSemillaNo = File(ctx.filesDir, "no_recomendar_semilla.json")
    private val archivoSemanaNo = File(ctx.filesDir, "no_recomendar_semana.txt")
    private val _noLista = MutableStateFlow(cargarNo())
    /** Lo marcado «No recomendar», lo más reciente primero (Ajustes > No recomendar). */
    val noLista: StateFlow<List<NoRecomendado>> = _noLista
    private val _no = MutableStateFlow(_noLista.value.map { it.clave }.toSet())
    /** Claves medio:id que no vuelven a salir en el inicio. */
    val noRecomendar: StateFlow<Set<String>> = _no

    private fun vacio(medio: String, id: Int, titulo: String) = Titulo(id, medio, titulo, "", null, null, "", 0.0, emptyList())

    private fun cargarNo(): List<NoRecomendado> {
        val lista = try {
            val a = JSONArray(archivoNo.readText())
            (0 until a.length()).mapNotNull { i ->
                when (val e = a.get(i)) {
                    is String -> e.split(":").takeIf { it.size == 2 }?.let { (m, id) -> id.toIntOrNull()?.let { NoRecomendado(vacio(m, it, ""), "") } }
                    is JSONObject -> NoRecomendado(Titulo.deJson(e.getJSONObject("titulo")), e.optString("fecha"))
                    else -> null
                }
            }
        } catch (e: Exception) { emptyList() }
        // Semilla del asset no_recomendar.json (datos/): cada entrada entra una sola vez; si luego se quita, no vuelve.
        val aplicadas = try { JSONArray(archivoSemillaNo.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } } catch (e: Exception) { mutableSetOf() }
        val semilla = try {
            val a = JSONObject(appCtx.assets.open("no_recomendar.json").bufferedReader().use { it.readText() }).getJSONArray("items")
            (0 until a.length()).map { a.getJSONObject(it).let { o -> NoRecomendado(vacio(o.getString("medio"), o.getInt("id"), o.optString("titulo")), hoy()) } }
        } catch (e: Exception) { emptyList() }
        val pendientes = semilla.filter { it.clave !in aplicadas }
        if (pendientes.isEmpty()) return lista
        val nuevas = pendientes.filter { p -> lista.none { it.clave == p.clave } }
        aplicadas += pendientes.map { it.clave }
        Archivos.escribirOk(archivoSemillaNo, JSONArray(aplicadas.toList()).toString())
        return (nuevas + lista).also { guardarNo(it) }
    }

    private fun guardarNo(l: List<NoRecomendado>) {
        Archivos.escribirOk(archivoNo, JSONArray(l.map { JSONObject().put("titulo", it.t.aJson()).put("fecha", it.fecha) }).toString())
    }

    private fun publicarNo(l: List<NoRecomendado>) { _noLista.value = l; _no.value = l.map { it.clave }.toSet(); guardarNo(l) }

    fun ocultar(t: Titulo) = synchronized(lock) {
        if (t.id <= 0) return@synchronized
        val c = t.medio + ":" + t.id
        publicarNo(listOf(NoRecomendado(t.copy(motivo = null), hoy())) + _noLista.value.filter { it.clave != c })
    }

    fun quitarNo(medio: String, id: Int) = synchronized(lock) {
        publicarNo(_noLista.value.filter { !(it.t.medio == medio && it.t.id == id) })
    }

    /** Sustituye los datos de un título de la lista (los de la semilla llegan sin géneros) y conserva la fecha. */
    fun actualizarNo(t: Titulo) = synchronized(lock) {
        publicarNo(_noLista.value.map { if (it.t.medio == t.medio && it.t.id == t.id) it.copy(t = t.copy(motivo = null)) else it })
    }

    /** true una vez por semana (lunes a domingo): toca repasar «No recomendar» en el aprendizaje. */
    fun tocaRepasoSemanal(): Boolean {
        val semana = claveSemana(java.util.Calendar.getInstance())
        if (runCatching { archivoSemanaNo.readText() }.getOrNull() == semana) return false
        Archivos.escribirOk(archivoSemanaNo, semana)
        return true
    }

    /** Vistos en otras cuentas (datos/vistos_externos.json, de AniList): solo se ocultan de las recomendaciones. */
    val externos: Set<String> by lazy {
        try {
            val a = JSONObject(appCtx.assets.open("vistos_externos.json").bufferedReader().use { it.readText() }).getJSONArray("items")
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("medio") + ":" + o.getInt("id") } }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun esVisto(medio: String, id: Int) = _lista.value.any { it.medio == medio && it.id == id }
    fun esVisto(t: Titulo) = t.id > 0 && esVisto(t.medio, t.id)

    /** Marca como visto. Devuelve false si ya lo estaba o no tiene id de TMDB. */
    fun marcar(t: Titulo): Boolean = synchronized(lock) {
        if (t.id <= 0 || esVisto(t)) return false
        todos = listOf(Visto(t.id, t.medio, t.titulo, hoy())) + todos.filter { !(it.medio == t.medio && it.id == t.id) }
        publicar(); true
    }

    /** Anota una serie «en curso» (se reproduce un episodio). No oculta nada. */
    fun anotarEnCurso(t: Titulo) = synchronized(lock) {
        if (t.id <= 0 || todos.any { it.medio == t.medio && it.id == t.id }) return@synchronized
        todos = listOf(Visto(t.id, t.medio, t.titulo, hoy(), enCurso = true)) + todos
        publicar()
    }

    fun quitar(medio: String, id: Int) = synchronized(lock) {
        if (todos.none { it.medio == medio && it.id == id && !it.enCurso }) return@synchronized
        todos = todos.filter { !(it.medio == medio && it.id == id) }
        publicar()
    }

    companion object {
        /**
         * Clave de la semana (lunes a domingo) de una fecha: la fecha de su lunes, «aaaa-mm-dd». Antes era «año-semana del año», y la última
         * semana de diciembre (semana 1 con el año aún anterior) chocaba con la primera de enero (hallazgo de F2).
         */
        fun claveSemana(c: java.util.Calendar): String {
            val lunes = c.clone() as java.util.Calendar
            lunes.add(java.util.Calendar.DAY_OF_YEAR, -((lunes.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7))
            return "%04d-%02d-%02d".format(lunes.get(java.util.Calendar.YEAR), lunes.get(java.util.Calendar.MONTH) + 1, lunes.get(java.util.Calendar.DAY_OF_MONTH))
        }
    }
}
