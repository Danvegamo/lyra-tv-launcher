package co.davidvega.launcher.sistema

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** Cómo encontrar un nodo en la pantalla de otra app. Todos los campos presentes deben cumplirse. */
data class Selector(
    val id: String? = null, // resource-id: coincide por el final («mIvSearch» o «com.pkg:id/mIvSearch»)
    val texto: String? = null, // texto del nodo, sin distinguir mayúsculas (igual)
    val desc: String? = null, // contentDescription contiene
    val clase: String? = null, // nombre de clase termina en
    val editable: Boolean = false,
    val focoEntrada: Boolean = false, // el campo que tiene el foco de entrada (findFocus FOCUS_INPUT)
    val indice: Int = 0,
)

data class Paso(
    val nombre: String,
    val buscar: Selector?,
    val accion: String, // click | escribir | teclas | esperar
    val omitirSi: Selector?, // si este nodo ya está, el paso se da por hecho
    val esperaMs: Long,
    val opcional: Boolean,
    val pausaMs: Long,
)

data class Perfil(val paquete: String, val nombre: String, val pasos: List<Paso>, val mapaTeclas: Map<String, String>)

/** Perfiles de búsqueda por app: datos/perfiles_busqueda.json (assets) o el mismo archivo en la carpeta externa de la app. */
object Perfiles {
    fun cargar(ctx: Context): Map<String, Perfil> {
        val externo = ctx.getExternalFilesDir(null)?.let { File(it, "perfiles_busqueda.json") }?.takeIf { it.exists() }?.readText()
        val texto = externo ?: ctx.assets.open("perfiles_busqueda.json").bufferedReader().use { it.readText() }
        val apps = JSONObject(texto).getJSONObject("apps")
        val out = HashMap<String, Perfil>()
        for (pkg in apps.keys()) {
            val a = apps.getJSONObject(pkg)
            val pasos = a.optJSONArray("pasos") ?: JSONArray()
            val mapa = a.optJSONObject("mapa_teclas")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
            out[pkg] = Perfil(pkg, a.optString("nombre", pkg), (0 until pasos.length()).map { aPaso(pasos.getJSONObject(it)) }, mapa)
        }
        return out
    }

    private fun aSelector(o: JSONObject?): Selector? = o?.let {
        Selector(it.optString("id").ifBlank { null }, it.optString("texto").ifBlank { null }, it.optString("desc").ifBlank { null },
            it.optString("clase").ifBlank { null }, it.optBoolean("editable", false), it.optBoolean("foco_entrada", false), it.optInt("indice", 0))
    }

    private fun aPaso(o: JSONObject) = Paso(
        o.optString("nombre"), aSelector(o.optJSONObject("buscar")), o.optString("accion", "click"), aSelector(o.optJSONObject("omitir_si")),
        o.optLong("espera_ms", 8000), o.optBoolean("opcional", false), o.optLong("pausa_ms", 400),
    )
}

/**
 * Búsqueda guiada por accesibilidad. El launcher la arma al pulsar «Buscar en X»; el HomeAccessibilityService la ejecuta
 * paso a paso durante unos segundos y luego se desactiva sola. Fuera de esa ventana no hace nada.
 * Si algo no cuadra (nodo que no aparece, texto que no se escribe, plazo agotado), el título ya está en el portapapeles.
 */
object BusquedaAccesible {
    private const val ETIQUETA = "LauncherBusqueda"
    private const val PLAZO_MS = 45_000L
    private const val IME = "com.amazon.tv.ime"

    class Tarea(val perfil: Perfil, val texto: String, val hasta: Long) {
        var paso = 0
        var pasoDesde = System.currentTimeMillis()
        var siguienteTick = 0L
        var ultimoClic = 0L
    }

    @Volatile var tarea: Tarea? = null
        private set

    /** Devuelve true si hay perfil para esa app y se armó la tarea. El servicio la recoge en su siguiente ciclo. */
    fun iniciar(ctx: Context, paquete: String, texto: String): Boolean {
        val perfil = try { Perfiles.cargar(ctx)[paquete] } catch (e: Exception) { Log.w(ETIQUETA, "perfiles: $e"); null }
        if (perfil == null || perfil.pasos.isEmpty()) return false
        tarea = Tarea(perfil, texto, System.currentTimeMillis() + PLAZO_MS)
        Log.i(ETIQUETA, "tarea ${perfil.nombre}: «$texto» (${perfil.pasos.size} pasos)")
        HomeAccessibilityService.despertar()
        return true
    }

    fun cancelar() { tarea = null }

    /** Un ciclo de la máquina de pasos. Devuelve true si hay que volver a llamar. */
    fun tick(s: AccessibilityService): Boolean {
        val t = tarea ?: return false
        val ahora = System.currentTimeMillis()
        if (ahora > t.hasta) { terminar(s, t, "plazo agotado", false); return false }
        if (ahora < t.siguienteTick) return true
        val paso = t.perfil.pasos.getOrNull(t.paso)
        if (paso == null) { terminar(s, t, "listo", true); return false }

        val raices = raices(s, t.perfil.paquete)
        if (ahora - t.pasoDesde in 1500..1900 || ahora - t.pasoDesde in 3000..3400 || ahora - t.pasoDesde in 9000..9400) depurar(s, raices)
        if (paso.omitirSi != null && raices.any { buscar(it, paso.omitirSi) != null }) return avanzar(t, paso, ahora, "omitido")
        if (paso.accion == "esperar" && paso.buscar == null) return avanzar(t, paso, ahora, "espera")

        val sel = paso.buscar
        val nodo = if (sel == null) null else raices.firstNotNullOfOrNull { buscar(it, sel) }
        // «atras»: mientras el nodo siga en pantalla (reproductor, ficha de serie…) pulsa Atrás cada 1,5 s; si no está, el paso no hacía falta.
        if (paso.accion == "atras") {
            if (nodo == null) return if (ahora - t.pasoDesde > paso.esperaMs) avanzar(t, paso, ahora, "no hacía falta") else true
            if (ahora - t.ultimoClic >= 1500) {
                t.ultimoClic = ahora
                Log.i(ETIQUETA, "atrás en «${paso.nombre}»: ${s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)}")
                t.pasoDesde = ahora // cuenta desde la última pulsación: avanza cuando el nodo deja de verse
            }
            return true
        }
        if (nodo == null) {
            if (ahora - t.pasoDesde > paso.esperaMs) {
                if (paso.opcional) return avanzar(t, paso, ahora, "no apareció (opcional)")
                terminar(s, t, "no apareció el nodo del paso «${paso.nombre}»", false)
                return false
            }
            return true
        }
        // Un clic con «omitir_si» solo cuenta cuando el nodo esperado aparece: se repite cada 2,5 s hasta entonces.
        if (paso.accion == "click" && paso.omitirSi != null) {
            if (ahora - t.ultimoClic >= 2500) {
                t.ultimoClic = ahora
                // Si el nodo está fuera de la pantalla (la app ya bajó el foco), se sube desplazando antes de pulsar.
                if (!nodo.isVisibleToUser) raices.forEach { subir(it) }
                Log.i(ETIQUETA, "clic en «${paso.nombre}»: ${pulsar(nodo)} (${nodo.viewIdResourceName} vis=${nodo.isVisibleToUser} enab=${nodo.isEnabled})")
            }
            if (ahora - t.pasoDesde > paso.esperaMs) { terminar(s, t, "no cambió la pantalla tras pulsar «${paso.nombre}»", false); return false }
            return true
        }
        val ok = when (paso.accion) {
            "esperar" -> true // el nodo ya apareció: la pantalla está lista
            "click" -> pulsar(nodo)
            "escribir" -> escribir(nodo, t.texto)
            "teclas" -> teclear(raices, t)
            else -> false
        }
        if (!ok) {
            if (ahora - t.pasoDesde > paso.esperaMs) { terminar(s, t, "falló la acción «${paso.accion}» en «${paso.nombre}»", false); return false }
            return true
        }
        return avanzar(t, paso, ahora, "ok")
    }

    private fun avanzar(t: Tarea, paso: Paso, ahora: Long, motivo: String): Boolean {
        Log.i(ETIQUETA, "paso «${paso.nombre}»: $motivo")
        t.paso++
        t.pasoDesde = ahora
        t.siguienteTick = ahora + paso.pausaMs
        return true
    }

    private fun terminar(s: AccessibilityService, t: Tarea, motivo: String, exito: Boolean) {
        Log.i(ETIQUETA, "fin ${t.perfil.nombre}: $motivo")
        tarea = null
        if (!exito) co.davidvega.launcher.data.Diagnostico.registrar("busqueda_guiada", "${t.perfil.nombre}: $motivo")
        if (!exito) Toast.makeText(s, "No pude escribir en ${t.perfil.nombre} ($motivo). «${t.texto}» está copiado: pégalo o escríbelo.", Toast.LENGTH_LONG).show()
    }

    /** Raíces de las ventanas de la app y del teclado de Fire TV (solo esas dos). */
    private fun raices(s: AccessibilityService, paquete: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { s.windows?.forEach { w -> w.root?.let { r -> if (r.packageName == paquete || r.packageName == IME) out.add(r) } } } catch (_: Exception) {}
        if (out.isEmpty()) s.rootInActiveWindow?.let { if (it.packageName == paquete || it.packageName == IME) out.add(it) }
        return out
    }

    /** Volcado de depuración (solo en el log): ventanas vistas y nodos con id de la app. */
    private fun depurar(s: AccessibilityService, raices: List<AccessibilityNodeInfo>) {
        val ventanas = try { s.windows?.map { w -> "${w.type}:${w.root?.packageName}:${w.title}" } } catch (e: Exception) { listOf("err $e") }
        Log.i(ETIQUETA, "ventanas=$ventanas raices=${raices.map { it.packageName }} activa=${s.rootInActiveWindow?.packageName}")
        raices.forEach { r ->
            var n = 0
            val pila = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
            while (pila.isNotEmpty() && n < 400) { val x = pila.removeFirst(); n++; if (x.viewIdResourceName != null || x.packageName == IME) Log.i(ETIQUETA, "  nodo id=${x.viewIdResourceName} txt=${x.text} desc=${x.contentDescription} ${x.className} click=${x.isClickable} edit=${x.isEditable}"); for (i in 0 until x.childCount) x.getChild(i)?.let { pila.add(it) } }
            Log.i(ETIQUETA, "  $n nodos")
        }
    }

    private fun buscar(raiz: AccessibilityNodeInfo, sel: Selector): AccessibilityNodeInfo? {
        if (sel.focoEntrada) return raiz.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        var n = 0
        val pila = ArrayDeque<AccessibilityNodeInfo>().apply { add(raiz) }
        while (pila.isNotEmpty()) {
            val x = pila.removeFirst()
            if (coincide(x, sel)) { if (n == sel.indice) return x; n++ }
            for (i in 0 until x.childCount) x.getChild(i)?.let { pila.add(it) }
        }
        return null
    }

    private fun coincide(x: AccessibilityNodeInfo, s: Selector): Boolean {
        if (s.id != null) { val r = x.viewIdResourceName ?: return false; if (!(r == s.id || r.endsWith(":id/" + s.id) || r.endsWith("/" + s.id))) return false }
        if (s.texto != null && !(x.text?.toString() ?: "").equals(s.texto, ignoreCase = true)) return false
        if (s.desc != null && !(x.contentDescription?.toString() ?: "").contains(s.desc, ignoreCase = true)) return false
        if (s.clase != null && !(x.className?.toString() ?: "").endsWith(s.clase)) return false
        if (s.editable && !x.isEditable) return false
        return s.id != null || s.texto != null || s.desc != null || s.clase != null || s.editable
    }

    /** Desplaza hacia arriba todos los contenedores desplazables (ACTION_SCROLL_UP / BACKWARD). */
    private fun subir(raiz: AccessibilityNodeInfo) {
        val pila = ArrayDeque<AccessibilityNodeInfo>().apply { add(raiz) }
        var n = 0
        while (pila.isNotEmpty() && n++ < 300) {
            val x = pila.removeFirst()
            if (x.isScrollable) {
                val a = x.actionList.map { it.id }
                if (android.R.id.accessibilityActionScrollUp in a) x.performAction(android.R.id.accessibilityActionScrollUp)
                else if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in a) x.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let { pila.add(it) }
        }
    }

    private fun pulsar(nodo: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = nodo
        var saltos = 0
        while (x != null && saltos < 5) {
            if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            x = x.parent; saltos++
        }
        return nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun escribir(nodo: AccessibilityNodeInfo, texto: String): Boolean {
        nodo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto) }
        if (!nodo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        nodo.refresh()
        val escrito = nodo.text?.toString() ?: ""
        return escrito.contains(texto, ignoreCase = true) || escrito.isNotBlank()
    }

    /** Teclea pulsando los botones del teclado en pantalla de la app (texto del botón = carácter). */
    private fun teclear(raices: List<AccessibilityNodeInfo>, t: Tarea): Boolean {
        for (c in normalizar(t.texto, t.perfil.mapaTeclas)) {
            val sel = if (c == ' ') t.perfil.mapaTeclas["espacio"]?.let { Selector(id = it) } else Selector(texto = c.toString())
            val nodo = sel?.let { s -> raices.firstNotNullOfOrNull { buscar(it, s) } } ?: continue
            pulsar(nodo)
        }
        return true
    }

    private fun normalizar(texto: String, mapa: Map<String, String>): String {
        val sin = Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return sin.uppercase()
    }
}
