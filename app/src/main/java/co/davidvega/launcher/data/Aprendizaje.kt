package co.davidvega.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Aprendizaje de gustos en el aparato, sin servidores. Todo sale de lo que el usuario hace en el launcher:
 *   abrir ficha +1, «Ver en…» o «Buscar en…» +3, añadir a Mi lista +4, quitar -2,
 *   elegir un resultado del buscador +1 a sus géneros, y títulos mostrados muchas veces sin abrir -0,2 cada vez (tope).
 * De ahí salen pesos por género (g:), palabra clave (k:), servicio (s:), década (d:) y origen = idioma original (o:).
 * Cada peso decae con vida media de 30 días. Las semillas de gustos.json son la base fija.
 * Se guarda en <carpeta externa de la app>/aprendizaje.json (para revisarlo desde el PC con adb pull).
 */
class Aprendizaje(ctx: Context, private val semillas: Map<String, Double>) {

    class Peso(var v: Double, var t: Long, var nombre: String?)
    class Senal(val medio: String, val id: Int, var titulo: String, var anio: String, var v: Double, var t: Long, var abiertos: Int) {
        /** Día (desde 1970, hora local) en que una sesión de reproducción sumó por última vez; -1 = nunca. Un título suma una vez por día. */
        var dia: Long = -1L
        /** Vista en curso (fracción acumulada, episodio, día y lo sumado hoy): ver `Visionado`. */
        var vis: Visionado.Estado = Visionado.NINGUNO
    }

    // Todo acceso a los mapas va con el cerrojo de este objeto (@Synchronized): el repaso semanal y las señales corren en hilos de
    // IO mientras la interfaz (hilo principal) calcula puntajes y ordena; sin esto salía ConcurrentModificationException.
    private val archivo: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "aprendizaje.json")
    private val carpeta: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    private val prefs = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val pesos = HashMap<String, Peso>()
    private val titulos = HashMap<String, Senal>()
    private val impresiones = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>?) = size > 600
    }

    @Volatile var activo = true
        private set
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Interruptor de Ajustes («Aprendizaje con techo»): apagado vuelve al comportamiento de la 0.13 (pesos sin tope, dos niveles por sesión, sin límite diario). */
    @Volatile var techoActivo: Boolean = prefs.getBoolean(CLAVE_TECHO, true)
        private set

    @Synchronized
    fun usarTecho(si: Boolean) { techoActivo = si; prefs.edit().putBoolean(CLAVE_TECHO, si).apply(); cambio() }

    /** Afinidad por servicio y tipo (R2), leída de reproducciones.jsonl en el hilo de guardado: `ordenar` solo lee la última calculada. */
    @Volatile private var afinidad: Afinidad.Mapa = Afinidad.VACIA

    private fun refrescarAfinidad() {
        escritor.execute { try { afinidad = Afinidad.cargar(File(carpeta, "reproducciones.jsonl"), System.currentTimeMillis()) } catch (_: Exception) {} }
    }



    private fun decae(v: Double, t: Long, ahora: Long) = Companion.decae(v, t, ahora)

    /** Con el techo: techo positivo de 6 y piso negativo de -3 (`Visionado.acotarAsimetrico`); se aplica al leer, no al sumar (ver `acumular`). */
    private fun acotar(v: Double) = if (techoActivo) Visionado.acotarAsimetrico(v) else v

    private fun clave(t: Titulo) = t.medio + ":" + t.id

    /** Peso leído: la semilla más el acumulado decaído, comprimido con el techo (+6) y el piso (-3) solo al leer (el acumulado se guarda sin comprimir). */
    @Synchronized
    fun peso(k: String, ahora: Long = System.currentTimeMillis()): Double =
        (semillas[k] ?: 0.0) + (pesos[k]?.let { acotar(decae(it.v, it.t, ahora)) } ?: 0.0)

    private fun anadir(k: String, delta: Double, nombre: String? = null) {
        val ahora = System.currentTimeMillis()
        val p = pesos.getOrPut(k) { Peso(0.0, ahora, nombre) }
        p.v = acumular(p.v, p.t, delta, ahora)
        p.t = ahora
        if (nombre != null) p.nombre = nombre
    }

    private fun decada(t: Titulo) = t.anio.toIntOrNull()?.let { "d:" + (it / 10 * 10) }

    /** Aplica una señal de un título (la recoge solo si «Aprender» está activo). */
    @Synchronized
    fun senal(t: Titulo, delta: Double, abre: Boolean = false, dia: Long = -1L, repaso: Boolean = false) {
        if (!activo || t.id < 0) return
        t.generos.forEach { anadir("g:$it", if (techoActivo) Visionado.deltaGenero(it, delta, repaso) else delta) }
        if (t.idioma.isNotBlank()) anadir("o:" + t.idioma, delta * 0.7)
        decada(t)?.let { anadir(it, delta * 0.5) }
        t.servicios.forEach { anadir("s:$it", delta * 0.5) }
        val ahora = System.currentTimeMillis()
        val s = titulos.getOrPut(clave(t)) { Senal(t.medio, t.id, t.titulo, t.anio, 0.0, ahora, 0) }
        s.v = decae(s.v, s.t, ahora) + delta
        s.t = ahora
        if (abre) s.abiertos++
        if (dia >= 0) s.dia = dia
        cambio()
    }

    /**
     * Una sesión de reproducción (0.15): lo que se ve del mismo episodio en 48 horas se acumula (una película vista en tres tandas es una vista),
     * abandonar anula la intención de abrirlo y un título no suma más de una vista completa al día (`Visionado.aplicar`).
     * Con el interruptor apagado se aplica la regla vieja (+4 o +2 según 10 minutos). Los clips de menos de 5 minutos (tráileres) no cuentan.
     */
    @Synchronized
    fun sesion(t: Titulo, posicionMs: Long, duracionMs: Long, vistoMs: Long, ahora: Long = System.currentTimeMillis(), episodio: String? = null) {
        if (!activo || t.id < 0) return
        if (!techoActivo) { senal(t, if (vistoMs >= 10 * 60_000L) 4.0 else 2.0, abre = true); return }
        if (Visionado.esClip("", duracionMs / 1000)) return
        val hoy = diaDe(ahora)
        val r = Visionado.aplicar(titulos[clave(t)]?.vis ?: Visionado.NINGUNO, episodio, fraccionVista(posicionMs, duracionMs, vistoMs), vistoMs, ahora, hoy)
        senal(t, r.delta, abre = true, dia = hoy)
        titulos[clave(t)]?.vis = r.estado
        refrescarAfinidad()
    }

    /** Elegir un resultado del buscador: +1 solo a sus géneros. */
    @Synchronized
    fun soloGeneros(t: Titulo, delta: Double) {
        if (!activo) return
        t.generos.forEach { anadir("g:$it", delta) }
        cambio()
    }

    @Synchronized
    fun palabras(lista: List<Pair<Int, String>>, delta: Double) {
        if (!activo || lista.isEmpty()) return
        lista.forEach { (id, nombre) -> anadir("k:$id", delta * 0.5, nombre) }
        cambio()
    }

    /** Títulos mostrados en el inicio: cada vez que se muestran sin abrirse restan un poco (con tope en el puntaje). */
    @Synchronized
    fun mostrados(lista: List<Titulo>) {
        if (!activo) return
        lista.forEach { t -> val k = clave(t); impresiones[k] = (impresiones[k] ?: 0) + 1 }
        guardar()
    }

    @Synchronized
    fun puntaje(t: Titulo, ahora: Long = System.currentTimeMillis()): Double {
        val k = clave(t)
        val bono = if (techoActivo) Afinidad.bono(afinidad, t) else 0.0
        return puntajeDe(t, { peso(it, ahora) }, (titulos[k]?.abiertos ?: 0) > 0, impresiones[k] ?: 0, bono)
    }

    /**
     * Reordena una fila por puntaje y reserva ~12 % de huecos (posiciones 3, 7, 11...) para títulos «nuevos»:
     * los de menor afinidad pero buena nota, para que las filas no se cierren sobre lo mismo.
     */
    @Synchronized
    fun ordenar(fila: Fila, semilla: Long): Fila {
        if (fila.items.size < 6) return fila
        val ahora = System.currentTimeMillis()
        // El servicio que más ve el usuario va primero en cada título que está en varios (R2); el orden de la fila se calcula con eso.
        val items = if (techoActivo) fila.items.map { t -> if (t.servicios.size > 1) t.copy(servicios = Afinidad.ordenarServicios(t.servicios, afinidad)) else t } else fila.items
        val anime = techoActivo && esFilaAnime(fila.id, items)
        val conP = if (anime) {
            // Anime: a igual puntaje (a una décima) primero el doblaje latino, luego español, luego subtitulado (Doblaje.rangoDe).
            val bruto = items.mapIndexed { i, t -> Triple(t, puntaje(t, ahora), i) }
            bruto.sortedWith(compareByDescending<Triple<Titulo, Double, Int>> { Math.round(it.second * 10) }.thenBy { Doblaje.rangoDe(it.first) }.thenBy { it.third })
        } else items.mapIndexed { i, t -> Triple(t, puntaje(t, ahora) - i * 0.01, i) }.sortedByDescending { it.second }
        val n = maxOf(1, Math.round(fila.items.size * 0.12f))
        val mitad = conP.drop(conP.size / 2).filter { it.first.nota >= 7.0 }
        val rnd = java.util.Random(semilla + fila.id.hashCode())
        val nuevos = mitad.shuffled(rnd).take(n).map { it.first }
        val resto = conP.map { it.first }.filter { it !in nuevos }.toMutableList()
        nuevos.forEachIndexed { j, t -> resto.add(minOf(3 + 4 * j, resto.size), t) }
        return fila.copy(items = if (techoActivo) Afinidad.limitarPorServicio(resto) else resto)
    }

    /** Los n títulos con más señal (decaída) y al menos 3 puntos: anclas de «Porque viste…». */
    @Synchronized
    fun anclas(n: Int = 2): List<Titulo> {
        val ahora = System.currentTimeMillis()
        return titulos.values.filter { it.id > 0 && decae(it.v, it.t, ahora) >= 3.0 }.sortedByDescending { decae(it.v, it.t, ahora) }.take(n)
            .map { Titulo(it.id, it.medio, it.titulo, "", null, null, it.anio, 0.0, emptyList()) }
    }

    /**
     * Anclas de «Porque viste…» (0.15): los títulos que el usuario terminó (80 % de un episodio o película) en los últimos 60 días, con más señal (decaída)
     * primero. Un título que solo abrió, añadió a Mi lista o probó unos minutos no es ancla.
     */
    @Synchronized
    fun anclasTerminadas(n: Int = 1, ahora: Long = System.currentTimeMillis()): List<Titulo> =
        titulos.values.filter { it.id > 0 && it.vis.terminadoT >= 0 && ahora - it.vis.terminadoT <= 60L * 86_400_000L && decae(it.v, it.t, ahora) > 0 }
            .sortedByDescending { decae(it.v, it.t, ahora) }.take(n)
            .map { Titulo(it.id, it.medio, it.titulo, "", null, null, it.anio, 0.0, emptyList()) }

    /**
     * Perfil de gustos: los n valores con más peso (con decaimiento de 30 días: lo reciente pesa más) de una sección
     * («g:» géneros, «o:» idiomas de origen, «k:» palabras clave). Solo pesos positivos.
     */
    @Synchronized
    fun cima(prefijo: String, n: Int): List<Pair<String, Double>> {
        val ahora = System.currentTimeMillis()
        return (pesos.keys + semillas.keys).filter { it.startsWith(prefijo) }.map { it.substring(prefijo.length) to peso(it, ahora) }
            .filter { it.second > 0.3 }.sortedByDescending { it.second }.take(n)
    }

    @Synchronized
    fun nombreClave(id: String): String? = pesos["k:$id"]?.nombre

    @Synchronized
    fun encender(si: Boolean) { activo = si; cambio() }

    @Synchronized
    fun reiniciar() {
        pesos.clear(); titulos.clear(); impresiones.clear()
        cambio()
    }

    class Resumen(val secciones: List<Pair<String, List<Pair<String, Double>>>>)

    /** Lo aprendido, legible: por sección, los pesos mayores (positivos arriba, negativos al final). */
    @Synchronized
    fun resumen(nombres: Map<String, String>): Resumen {
        val ahora = System.currentTimeMillis()
        val claves = (pesos.keys + semillas.keys).toSet()
        fun seccion(pref: String, nombre: (String) -> String): List<Pair<String, Double>> {
            val l = claves.filter { it.startsWith(pref) }.map { nombre(it.substring(2)) to peso(it, ahora) }.filter { abs(it.second) >= 0.05 }
            val pos = l.filter { it.second > 0 }.sortedByDescending { it.second }.take(6)
            val neg = l.filter { it.second < 0 }.sortedBy { it.second }.take(2)
            return pos + neg
        }
        val ts = titulos.values.map { it.titulo + (if (it.anio.isNotBlank()) " (${it.anio})" else "") to decae(it.v, it.t, ahora) }
            .filter { abs(it.second) >= 0.5 }.sortedByDescending { it.second }.take(6)
        return Resumen(listOf(
            "Géneros" to seccion("g:") { NombresGenero[it.toIntOrNull()] ?: it },
            "Origen" to seccion("o:") { NombresOrigen[it] ?: it },
            "Servicios" to seccion("s:") { nombres[it] ?: it },
            "Décadas" to seccion("d:") { "Años $it" },
            "Palabras clave" to seccion("k:") { id -> pesos["k:$id"]?.nombre ?: id },
            "Títulos con más señal" to ts,
        ).filter { it.second.isNotEmpty() })
    }

    private fun cambio() { guardar(); _version.value++ }

    private val escritor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aprendizaje-guardar").apply { isDaemon = true } }
    private val guardadoPendiente = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Pide guardar: se junta lo de los próximos 1,5 s en una sola escritura, en un hilo aparte (nunca en el principal). */
    @Synchronized
    private fun guardar() {
        if (guardadoPendiente.compareAndSet(false, true)) escritor.schedule({ guardadoPendiente.set(false); guardarYa() }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun guardarYa() {
        try {
            val texto = synchronized(this) { serializar() }
            Archivos.escribir(archivo, texto)
        } catch (_: Exception) {}
    }

    /** Llamar con el cerrojo de este objeto tomado. */
    private fun serializar(): String {
        val p = JSONObject()
        pesos.forEach { (k, w) -> p.put(k, JSONObject().put("v", w.v).put("t", w.t).apply { w.nombre?.let { put("n", it) } }) }
        val ti = JSONObject()
        titulos.forEach { (k, s) -> ti.put(k, JSONObject().put("titulo", s.titulo).put("anio", s.anio).put("v", s.v).put("t", s.t).put("abiertos", s.abiertos).put("dia", s.dia)
            .put("vf", s.vis.frac).put("ve", s.vis.ep).put("vt", s.vis.t).put("vd", s.vis.dia).put("vs", s.vis.previoDia).put("vn", s.vis.vistaNivel).put("tt", s.vis.terminadoT)) }
        val im = JSONObject(); impresiones.forEach { (k, n) -> im.put(k, n) }
        return JSONObject().put("version", VERSION_ARCHIVO).put("activo", activo)
            .put("_nota", "Pesos aprendidos en el aparato. v = peso en el instante t (ms); decae a la mitad cada 30 días. Las semillas de gustos.json no se guardan aquí.")
            .put("pesos", p).put("titulos", ti).put("mostrados", im).toString(2)
    }

    /** Carga tolerante: una entrada mala se salta y el resto se conserva; un archivo ilegible no tira la app. */
    private fun cargar() {
        try {
            if (!archivo.exists()) return
            val texto = archivo.readText()
            val o = JSONObject(texto)
            activo = o.optBoolean("activo", true)
            o.optJSONObject("pesos")?.let { p -> p.keys().forEach { k -> try { p.getJSONObject(k).let { w -> pesos[k] = Peso(w.getDouble("v"), w.getLong("t"), w.optString("n").ifBlank { null }) } } catch (_: Exception) {} } }
            o.optJSONObject("titulos")?.let { p ->
                p.keys().forEach { k ->
                    try {
                        val w = p.getJSONObject(k)
                        val (m, id) = k.split(":").let { it[0] to it[1].toInt() }
                        titulos[k] = Senal(m, id, w.optString("titulo"), w.optString("anio"), w.getDouble("v"), w.getLong("t"), w.optInt("abiertos")).also {
                            it.dia = w.optLong("dia", -1L)
                            it.vis = Visionado.Estado(w.optDouble("vf", -1.0), w.optString("ve"), w.optLong("vt", -1L), w.optLong("vd", -1L), w.optDouble("vs", 0.0), w.optDouble("vn", 0.0), w.optLong("tt", -1L))
                        }
                    } catch (_: Exception) {}
                }
            }
            o.optJSONObject("mostrados")?.let { p -> p.keys().forEach { k -> try { impresiones[k] = p.getInt(k) } catch (_: Exception) {} } }
            // Migración 0.14: los pesos de la versión 1 no tenían techo (g:18 llegó a 207): se guarda una copia aparte.
            // Migración 0.15: la versión 2 guardaba el peso ya comprimido y lo volvía a comprimir en cada señal (un +1 sobre 5,8 lo bajaba a 3,2; un -1 a 2,7),
            // así que drama pasó de 5,8 a -1,85 con siete rechazos. Ahora se guarda sin comprimir y se comprime al leer: la versión 2 se descomprime (inversa exacta del techo).
            val version = o.optInt("version", 1)
            if (version < VERSION_ARCHIVO) {
                try { File(carpeta, if (version < 2) "aprendizaje.v1.json" else "aprendizaje.v2.json").let { if (!it.exists()) Archivos.escribir(it, texto) } } catch (_: Exception) {}
                if (version == 2 && techoActivo) {
                    pesos.values.forEach { if (abs(it.v) < K_TECHO) it.v = descomprimir(it.v) }
                    reparar2a3()
                }
                migrado = true
            }
        } catch (_: Exception) {}
        podar()
        if (migrado) guardarYa()
    }

    private var migrado = false

    /**
     * El primer repaso semanal tras migrar (22 nov) restó -1 por título de «No recomendar» a sus géneros, orígenes, décadas y servicios, sobre pesos ya
     * comprimidos: drama pasó de 5,8 a -1,85 y el inglés de 5,9 a 1,5. Se toma, si es mayor, el acumulado de la copia `aprendizaje.v1.json` (el estado
     * justo antes de migrar) en esas claves; solo sube pesos, nunca baja ninguno, y las palabras clave no se tocan.
     */
    private fun reparar2a3() {
        try {
            val v1 = JSONObject(File(carpeta, "aprendizaje.v1.json").readText()).optJSONObject("pesos") ?: return
            for ((k, p) in pesos) {
                if (!(k.startsWith("g:") || k.startsWith("o:") || k.startsWith("d:") || k.startsWith("s:"))) continue
                val w = v1.optJSONObject(k) ?: continue
                p.v = repararAmplio(p.v, p.t, w.optDouble("v", 0.0), w.optLong("t", p.t))
            }
        } catch (_: Exception) {}
    }

    init { Doblaje.iniciar(ctx.applicationContext); cargar(); refrescarAfinidad() }

    /** Los mapas no crecían nunca: se quitan los pesos que ya valen casi cero y los títulos viejos sin señal. */
    private fun podar() {
        val ahora = System.currentTimeMillis()
        pesos.entries.removeAll { it.key !in semillas && kotlin.math.abs(decae(it.value.v, it.value.t, ahora)) < 0.02 }
        if (titulos.size > 1500) titulos.entries.sortedBy { abs(decae(it.value.v, it.value.t, ahora)) }.take(titulos.size - 1500).forEach { titulos.remove(it.key) }
    }

    companion object {
        const val CLAVE_TECHO = "aprend_techo"
        const val VERSION_ARCHIVO = 3
        /** Tope del acumulado sin comprimir (solo evita desbordes). */
        const val ACUMULADO_MAX = 1000.0

        /** Peso del servicio aprendido por clics en el puntaje (antes 0,5). */
        const val COEF_SERVICIO = 0.1
        /** Vida media del peso: 30 días. */
        const val VIDA_MS = 30.0 * 86_400_000.0
        /** Ningún peso (género, origen, servicio...) pasa de este valor en magnitud. Con 6, la nota (hasta 1,5) y la novedad vuelven a pesar. */
        const val K_TECHO = 6.0

        /** Peso decaído a `ahora`. Un reloj atrasado (ahora < t) no hace crecer el peso. */
        fun decae(v: Double, t: Long, ahora: Long): Double = v * 0.5.pow(maxOf(0L, ahora - t) / VIDA_MS)

        /** Comprime un peso a (-K, K): w / (1 + |w|/K). Creciente, impar, casi w cerca de 0 y nunca NaN. */
        fun techo(w: Double, k: Double = K_TECHO): Double = when {
            w.isNaN() -> 0.0
            w.isInfinite() -> if (w > 0) k else -k
            else -> w / (1.0 + abs(w) / k)
        }

        /** Acumulado nuevo: el anterior decaído más el delta, sin comprimir (así una señal positiva nunca baja el peso leído), entre ±ACUMULADO_MAX y nunca NaN. */
        fun acumular(v: Double, t: Long, delta: Double, ahora: Long): Double =
            (decae(v, t, ahora) + delta).let { if (it.isNaN()) 0.0 else it.coerceIn(-ACUMULADO_MAX, ACUMULADO_MAX) }

        /** Acumulado reparado de un peso: el mayor entre el actual (en el instante t2) y el de la copia anterior decaído hasta t2. */
        fun repararAmplio(actual: Double, t2: Long, anterior: Double, t1: Long): Double = maxOf(actual, decae(anterior, t1, t2))

        /** Inversa de `techo` para |v| < k (v / (1 - |v|/k)), acotada a ±ACUMULADO_MAX: recupera el acumulado de un peso ya comprimido. */
        fun descomprimir(v: Double, k: Double = K_TECHO): Double = when {
            v.isNaN() -> 0.0
            abs(v) >= k -> if (v > 0) ACUMULADO_MAX else -ACUMULADO_MAX
            else -> (v / (1.0 - abs(v) / k)).coerceIn(-ACUMULADO_MAX, ACUMULADO_MAX)
        }

        /** Fracción vista de 0 a 1: la posición donde se quedó (si la app la dio) o el tiempo visto, sobre la duración. null si no se sabe la duración. */
        fun fraccionVista(posicionMs: Long, duracionMs: Long, vistoMs: Long): Double? {
            if (duracionMs <= 0) return null
            val base = if (posicionMs >= 0) posicionMs else vistoMs
            return (base.toDouble() / duracionMs).coerceIn(0.0, 1.0)
        }

        /** Señal de una sesión: abandono (<10 %) -0,5; probada (10 a 50 %) +1; seguida (50 a 80 %) +2,5; completa (80 % o más) +4. Sin duración: 10 minutos o más cuenta como seguida. */
        fun nivelSesion(fraccion: Double?, vistoMs: Long): Double = when {
            fraccion == null -> if (vistoMs >= 10 * 60_000L) 2.5 else 1.0
            fraccion < 0.10 -> -0.5
            fraccion < 0.50 -> 1.0
            fraccion < 0.80 -> 2.5
            else -> 4.0
        }

        /**
         * Puntaje de un título con los pesos dados (parte pura de `puntaje`): promedio de sus géneros, 0,7 del origen, 0,4 de la década,
         * `COEF_SERVICIO` (0,1) del mejor servicio y 0,15 de la nota, menos 0,2 por cada vez (hasta 5) que se mostró sin abrirse, más el bono de afinidad.
         * El peso `s:` sale sobre todo de abrir fichas y «Ver en…» (s:prime llegó a 40 con 7 sesiones y 4 horas vistas) y antes pesaba 0,5; el servicio lo
         * decide ahora el bono de `Afinidad`, que sale de lo realmente visto (hasta ±2).
         */
        fun puntajeDe(t: Titulo, peso: (String) -> Double, abierto: Boolean, impresiones: Int, bono: Double): Double {
            val gs = if (t.generos.isEmpty()) 0.0 else t.generos.sumOf { peso("g:$it") } / t.generos.size
            val o = if (t.idioma.isBlank()) 0.0 else peso("o:" + t.idioma)
            val d = t.anio.toIntOrNull()?.let { peso("d:" + (it / 10 * 10)) } ?: 0.0
            val s = t.servicios.maxOfOrNull { peso("s:$it") } ?: 0.0
            val pen = if (abierto) 0.0 else 0.2 * min(impresiones, 5)
            return gs + 0.7 * o + 0.4 * d + COEF_SERVICIO * s + 0.15 * t.nota - pen + bono
        }

        /** Día local (contado desde 1970) de un instante. */
        fun diaDe(ms: Long, zona: java.util.TimeZone = java.util.TimeZone.getDefault()): Long = Math.floorDiv(ms + zona.getOffset(ms), 86_400_000L)

        /** Un título suma una vez por día. */
        fun sumaHoy(ultimoDia: Long, hoy: Long) = ultimoDia != hoy

        /** Fila de anime: la de gusto «gusto_anime» o aquella donde la mayoría de títulos es de origen japonés. */
        fun esFilaAnime(id: String, items: List<Titulo>) = id.startsWith("gusto_anime") || (items.size >= 4 && items.count { it.idioma == "ja" } * 2 > items.size)

        val NombresGenero = mapOf(
            28 to "Acción", 12 to "Aventura", 16 to "Animación", 35 to "Comedia", 80 to "Crimen", 99 to "Documental", 18 to "Drama",
            10751 to "Familia", 14 to "Fantasía", 36 to "Historia", 27 to "Terror", 10402 to "Música", 9648 to "Misterio", 10749 to "Romance",
            878 to "Ciencia ficción", 10770 to "Película de TV", 53 to "Suspenso", 10752 to "Bélica", 37 to "Western",
            10759 to "Acción y aventura", 10762 to "Infantil", 10763 to "Noticias", 10764 to "Reality", 10765 to "Ciencia ficción y fantasía",
            10766 to "Telenovela", 10767 to "Talk show", 10768 to "Guerra y política",
        )
        val NombresOrigen = mapOf(
            "ja" to "Japón", "en" to "Inglés (EE. UU./Reino Unido)", "ko" to "Corea", "es" to "Habla hispana", "fr" to "Francia", "de" to "Alemania",
            "it" to "Italia", "zh" to "China", "pt" to "Portugal/Brasil", "hi" to "India", "ru" to "Rusia", "sv" to "Suecia", "da" to "Dinamarca", "th" to "Tailandia",
        )

        /** Base fija desde gustos.json: géneros de las filas de descubrimiento, idioma original y servicios de las anclas. */
        fun semillas(g: Gustos): Map<String, Double> {
            val m = HashMap<String, Double>()
            g.filas.forEach { f ->
                f.discover?.generos?.forEach { m.merge("g:$it", 1.0, Double::plus) }
                f.discover?.idiomaOriginal?.let { m.merge("o:$it", 1.5, Double::plus) }
            }
            g.anclas.filter { it.servicio.isNotBlank() }.forEach { m.merge("s:" + it.servicio, it.peso * 0.2, Double::plus) }
            return m
        }
    }
}

/** Últimas 10 búsquedas del buscador. */
class Recientes(ctx: Context) {
    private val archivo = File(ctx.filesDir, "busquedas.json")
    private val _lista = MutableStateFlow(leer())
    val lista: StateFlow<List<String>> = _lista

    // Palabras de las pruebas del filtro de adultos (26 oct 2026): no deben salir como búsquedas recientes.
    private val deLasPruebas = setOf("sexo", "erotico", "erótico", "adultos", "travestis", "locas", "piratas", "terror", "hentai", "porno", "anime")
    private fun leer(): List<String> = try { JSONArray(archivo.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }.filter { it.trim().lowercase() !in deLasPruebas } } catch (e: Exception) { emptyList() }

    fun guardar(texto: String) {
        val t = texto.trim()
        if (t.length < 2) return
        _lista.value = (listOf(t) + _lista.value.filter { !it.equals(t, ignoreCase = true) }).take(10)
        Archivos.escribirOk(archivo, JSONArray(_lista.value).toString())
    }
}
