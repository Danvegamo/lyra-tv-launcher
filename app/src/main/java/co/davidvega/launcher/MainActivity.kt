package co.davidvega.launcher

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import co.davidvega.launcher.data.Aprendizaje
import co.davidvega.launcher.data.Catalogo
import co.davidvega.launcher.data.Fila
import co.davidvega.launcher.data.Recientes
import co.davidvega.launcher.data.DatosFicha
import co.davidvega.launcher.data.Estado
import co.davidvega.launcher.data.MiLista
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.data.TemaEntendido
import co.davidvega.launcher.data.FiltrosTema
import co.davidvega.launcher.data.ResultadoTema
import co.davidvega.launcher.sistema.AppItem
import co.davidvega.launcher.sistema.Apps
import co.davidvega.launcher.ui.Pantalla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherVM(app: Application) : AndroidViewModel(app) {
    private val catalogo = (app as App).catalogo
    val eventos = (app as App).eventos
    private val almacenLista = MiLista(app)
    val aprend = (app as App).aprendizaje
    private val almacenVistos = (app as App).vistos
    /** Los vistos de verdad (sin los «en curso»), más recientes primero. */
    val vistos: StateFlow<List<co.davidvega.launcher.data.Visto>> = almacenVistos.lista
    val recientes = Recientes(app)
    private val _ancla = MutableStateFlow<List<Fila>>(emptyList())
    /** Filas «Porque viste X» calculadas con los dos títulos de más señal. */
    val filaAncla: StateFlow<List<Fila>> = _ancla
    /** «Seguir viendo»: historiales importados más lo reproducido en el aparato. */
    val seguir: StateFlow<Fila?> = (app as App).seguir.fila
    private val _respaldo = MutableStateFlow<Fila?>(null)
    /** Fila de respaldo «Para empezar»: sale solo si las filas de gustos dan poco, para que el inicio nunca quede vacío. */
    val respaldo: StateFlow<Fila?> = _respaldo
    private val _tendencias = MutableStateFlow<Fila?>(null)
    /** «Esta semana en Colombia» (JustWatch), arriba de las apps. */
    val tendencias: StateFlow<Fila?> = _tendencias
    /** Vistos en otras cuentas (AniList): no se recomiendan. */
    val vistosExternos: Set<String> get() = almacenVistos.externos
    /** Títulos marcados «No recomendar». */
    val ocultosNo: StateFlow<Set<String>> = almacenVistos.noRecomendar
    /** La lista «No recomendar» completa (Ajustes > No recomendar). */
    val noRecomendados: StateFlow<List<co.davidvega.launcher.data.NoRecomendado>> = almacenVistos.noLista

    /** Quitar de «No recomendar»: vuelve a salir y se devuelve lo que se había restado. */
    fun quitarNoRecomendar(n: co.davidvega.launcher.data.NoRecomendado) {
        almacenVistos.quitarNo(n.t.medio, n.t.id)
        aprend.senal(n.t, 3.0)
        eventos.registrar("no_recomendar_quitar", n.t)
    }

    /** «No recomendar»: se oculta y resta como quitar de Mi lista, un poco más. */
    fun noRecomendar(t: Titulo) {
        almacenVistos.ocultar(t)
        aprend.senal(t, -3.0)
        eventos.registrar("no_recomendar", t)
    }

    fun quitarDeSeguir(t: Titulo) { getApplication<App>().seguir.ocultar(t); eventos.registrar("seguir_quitar", t) }

    suspend fun trailer(t: Titulo) = catalogo.trailer(t)
    private var anclaClave: String? = null
    /** Se incrementa cuando Home vuelve a traer el launcher: la pantalla se reinicia (cierra paneles, sube arriba). */
    val reinicio = MutableStateFlow(0)
    /** Solo pruebas: abre el buscador con un texto ya escrito (--es buscador_texto "gold rush"). */
    val buscadorTexto = MutableStateFlow<String?>(null)
    private val _milista = MutableStateFlow(almacenLista.cargar())
    val milista: StateFlow<List<Titulo>> = _milista
    val estado = MutableStateFlow(Estado())
    private val _apps = MutableStateFlow<List<AppItem>>(emptyList())
    val apps: StateFlow<List<AppItem>> = _apps
    var paquetes: Map<String, String> = emptyMap()
        private set

    init {
        viewModelScope.launch {
            aprend.version.collect {
                // Filas por género aprendido: se recalculan solo si cambia el perfil (géneros, origen y palabra clave de más peso).
                val gen = aprend.cima("g:", 8).map { (when (val g = it.first.toInt()) { 10759 -> 28; 10765 -> 878; 10768 -> 10752; 10762 -> 10751; else -> g }) to it.second }.groupBy({ it.first }, { it.second }).map { (g, w) -> g to w.sum() }.sortedByDescending { it.second }.take(4)
                val ori = aprend.cima("o:", 3)
                val kw = aprend.cima("k:", 1).mapNotNull { (id, _) -> aprend.nombreClave(id)?.let { id to it } }
                // «Porque viste X» (0.15): anclada en lo último que el usuario terminó (80 % o más), no en lo que solo abrió o probó.
                val ancla = aprend.anclasTerminadas(1).firstOrNull()
                val k = gen.joinToString { it.first.toString() } + "|" + ori.filter { it.second >= 1.0 }.joinToString { it.first } + "|" + kw.joinToString { it.first } + "|" + (ancla?.let { it.medio + it.id } ?: "")
                if (k != anclaClave) {
                    anclaClave = k
                    val porGusto = runCatching { catalogo.filasPorGusto(gen, ori, kw) }.getOrDefault(emptyList())
                    val porqueViste = ancla?.let { a -> runCatching { catalogo.recomendarDe(a) }.getOrNull() }
                    _ancla.value = listOfNotNull(porqueViste) + porGusto
                }
            }
        }
        viewModelScope.launch {
            // Cuando las filas ya están, se anotan los títulos mostrados (para restar a los que nunca se abren).
            var contado = false
            estado.collect { e ->
                if (!contado && !e.cargando && e.modo != co.davidvega.launcher.data.Modo.MUESTRA && e.filas.isNotEmpty()) {
                    contado = true
                    aprend.mostrados(e.filas.flatMap { it.items.take(8) })
                }
            }
        }
        viewModelScope.launch {
            paquetes = withContext(Dispatchers.IO) { runCatching { catalogo.paquetes() }.getOrDefault(emptyMap()) }
            co.davidvega.launcher.sistema.Abrir.plantillas = withContext(Dispatchers.IO) { runCatching { catalogo.gustos().proveedores.filter { it.busqueda.isNotBlank() }.associate { it.clave to it.busqueda } }.getOrDefault(emptyMap()) }
            launch {
                val pm = getApplication<App>().packageManager
                val instalados = paquetes.filterValues { p -> pm.getLaunchIntentForPackage(p) != null || pm.getLeanbackLaunchIntentForPackage(p) != null }.keys
                _tendencias.value = runCatching { catalogo.tendencias(instalados) }.getOrNull()
            }
            launch {
                // Respaldo: TMDB popular en tus servicios; si TMDB no responde, la última guardada y, en último caso, los datos de ejemplo.
                _respaldo.value = runCatching { catalogo.respaldo() }.getOrNull()
                    ?: runCatching { catalogo.muestra().firstOrNull()?.copy(id = "respaldo", titulo = "Para empezar") }.getOrNull()
            }
            // Un fallo al cargar (JSON de gustos raro, TMDB con respuesta inesperada) no debe matar la app: queda un aviso en pantalla.
            try { catalogo.cargar().collect { estado.value = it; if (it.filas.isNotEmpty()) co.davidvega.launcher.sistema.Medidas.primeraFila() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                co.davidvega.launcher.data.Diagnostico.registrar("recomendaciones", "la carga falló (${e.javaClass.simpleName})")
                estado.value = Estado(emptyList(), co.davidvega.launcher.data.Modo.MUESTRA, "No se pudieron cargar las recomendaciones", false)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Repaso semanal de «No recomendar»: el aprendizaje se olvida con vida media de 30 días, así que cada semana se
            // vuelve a restar −1 por título y lo rechazado no reaparece en lo parecido. Los de la semilla, sin géneros, se
            // completan antes con TMDB.
            if (!almacenVistos.tocaRepasoSemanal()) return@launch
            almacenVistos.noLista.value.forEach { n ->
                val t = if (n.t.generos.isEmpty()) runCatching { catalogo.detalle(n.t.medio, n.t.id) }.getOrNull()?.also { almacenVistos.actualizarNo(it) } ?: n.t else n.t
                aprend.senal(t, -1.0, repaso = true)
            }
            android.util.Log.i("LauncherNoRec", "repaso semanal: ${almacenVistos.noLista.value.size} títulos")
        }
        recargarApps()
        (app as App).seguir.refrescar()
    }

    private fun clave(t: Titulo) = t.medio + ":" + t.id

    fun enLista(t: Titulo) = _milista.value.any { clave(it) == clave(t) }

    /** Añade o quita de «Mi lista» y guarda en disco. */
    fun alternarLista(t: Titulo) {
        if (enLista(t)) { aprend.senal(t, -2.0); eventos.registrar("milista_quitar", t) } else { aprend.senal(t, 4.0); eventos.registrar("milista_anadir", t) }
        _milista.value = if (enLista(t)) _milista.value.filter { clave(it) != clave(t) } else listOf(t) + _milista.value
        viewModelScope.launch(Dispatchers.IO) { almacenLista.guardar(_milista.value) }
    }

    /** «Ya lo vi» / «No lo he visto». Marcar suma +3 como «Ver en…» a géneros y claves; desmarcar no resta. */
    fun alternarVisto(t: Titulo, palabras: List<Pair<Int, String>>? = null) {
        if (t.id <= 0) return
        if (almacenVistos.esVisto(t)) { almacenVistos.quitar(t.medio, t.id); eventos.registrar("visto_quitar", t); return }
        if (almacenVistos.marcar(t)) {
            aprend.senal(t, 3.0)
            palabras?.let { aprend.palabras(it, 3.0) }
            eventos.registrar("visto", t)
        }
    }

    fun quitarVisto(medio: String, id: Int) = almacenVistos.quitar(medio, id)

    /** Comprobaciones en vivo para «Estado del sistema». */
    suspend fun comprobar() = co.davidvega.launcher.data.Diagnostico.comprobar(getApplication(), catalogo::tmdbResponde)

    suspend fun buscar(texto: String) = catalogo.buscar(texto)
    val tematica get() = catalogo.tematica

    /** Filas de la búsqueda por temática con lo que sabe el aprendizaje y lo ya visto. */
    suspend fun filasTema(e: TemaEntendido, f: FiltrosTema, coincidencias: List<Titulo>): ResultadoTema {
        val ultimo = almacenVistos.lista.value.maxByOrNull { it.fecha }?.let { Titulo(it.id, it.medio, it.titulo, "", null, null, "", 0.0, emptyList()) }
        val vistas = almacenVistos.lista.value.map { it.clave }.toSet() + almacenVistos.externos
        return tematica.filas(e, f, coincidencias.sortedByDescending { it.nota }, vistas, almacenVistos.noRecomendar.value, { aprend.puntaje(it) }, ultimo)
    }

    suspend fun ficha(t: Titulo): DatosFicha = catalogo.ficha(t)

    suspend fun detalle(t: Titulo) = catalogo.detalle(t)

    fun recargarApps() {
        viewModelScope.launch {
            val nuevas = withContext(Dispatchers.IO) { Apps.cargar(getApplication()) }
            // Con la caché de bitmaps (Apps) la lista es igual si nada cambió: no se republica y la interfaz no se recompone.
            if (nuevas != _apps.value) _apps.value = nuevas
        }
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Lo lee el servicio de Home para saber si hay que reintentar. */
        @Volatile var enPrimerPlano = false

        /** Igual que enPrimerPlano, pero observable desde Compose: los bucles periódicos de la interfaz se paran cuando es false. */
        val delante = androidx.compose.runtime.mutableStateOf(false)

        /** Suelta lo regenerable (caché de imágenes en memoria de Coil). Se vuelve a llenar sola al volver. */
        fun soltarMemoria(ctx: android.content.Context) {
            runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) soltarMemoria(this)
    }

    override fun onStop() {
        super.onStop()
        soltarMemoria(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        co.davidvega.launcher.ui.Tema.cargar(this) // tema de color: el elegido en Ajustes o el de config.local.properties
        setContent {
            val vm: LauncherVM = viewModel()
            BackHandler { /* Es el inicio: Atrás no hace nada. */ }
            Pantalla(vm)
        }
        programarCaptura(intent)
        probarAjuste(intent)
        if (!co.davidvega.launcher.sistema.HomeAccessibilityService.activo) co.davidvega.launcher.data.Diagnostico.registrar("accesibilidad", "servicio no conectado al abrir el launcher")
    }

    /** Las teclas se consumen mientras dura la entrada; la primera la salta (se consume para que no actúe sobre el inicio de abajo). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (co.davidvega.launcher.ui.IntroEstado.activa) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) co.davidvega.launcher.ui.IntroEstado.saltar.value++
            return true
        }
        co.davidvega.launcher.sistema.IntroVideo.marcarUso()
        if (event.action == android.view.KeyEvent.ACTION_DOWN) co.davidvega.launcher.ui.Glitch.repetido = event.repeatCount > 0
        co.davidvega.launcher.ui.Reposo.ultima = android.os.SystemClock.elapsedRealtime()
        // Reposo con tráilers: consume todas las teclas; OK abre el tráiler y cualquier otra vuelve al inicio.
        if (co.davidvega.launcher.ui.Reposo.activo) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A)) co.davidvega.launcher.ui.Reposo.ver.value++
                else co.davidvega.launcher.ui.Reposo.salir.value++
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("intro_frio")) co.davidvega.launcher.ui.IntroEstado.yaArranco = false // prueba por adb: --ez intro_frio true repite la entrada completa
        if (!intent.hasExtra("captura_ms") && !intent.hasExtra("accion") && !intent.hasExtra("buscar_iptv") && !intent.hasExtra("buscar_cr") && !intent.hasExtra("buscar_hbo") && !intent.hasExtra("cerrar_sesiones") && !intent.hasExtra("buscador_texto")) androidx.lifecycle.ViewModelProvider(this)[LauncherVM::class.java].reinicio.value++
        programarCaptura(intent)
        probarAjuste(intent)
    }

    /** Prueba por adb de los atajos con el uid de la app: am start ... --es accion wifi (ids de Ajustes.ATAJOS, o dormir). */
    private fun probarAjuste(i: Intent?) {
        // Pruebas por adb de la búsqueda guiada: --es buscar_iptv "Titulo"  /  --es buscar_cr "Titulo"
        i?.getStringExtra("buscar_iptv")?.let { co.davidvega.launcher.sistema.Abrir.enIptv(this, Titulo(1, "tv", it, "", null, null, "", 0.0, emptyList())) }
        i?.getStringExtra("buscar_cr")?.let {
            val t = Titulo(1, "tv", it, "", null, null, "", 0.0, listOf("crunchyroll"))
            co.davidvega.launcher.sistema.Abrir.enServicio(this, t, "crunchyroll", mapOf("crunchyroll" to "com.crunchyroll.crunchyroid"))
        }
        i?.getStringExtra("buscador_texto")?.let {
            val vm = androidx.lifecycle.ViewModelProvider(this)[LauncherVM::class.java]
            // Lo dictado a Alexa entra en «Recientes» al llegar, aunque no se abra ningún resultado.
            if (i.getBooleanExtra("voz", false)) { vm.recientes.guardar(it); vm.eventos.registrar("busqueda_voz", null, texto = it) }
            vm.buscadorTexto.value = it
        }
        if (i?.getBooleanExtra("cerrar_sesiones", false) == true) co.davidvega.launcher.sistema.EscuchaMedios.instancia?.cerrarTodas()
        i?.getStringExtra("buscar_hbo")?.let {
            val t = Titulo(1, "tv", it, "", null, null, "", 0.0, listOf("hbomax"))
            co.davidvega.launcher.sistema.Abrir.enServicio(this, t, "hbomax", mapOf("hbomax" to "com.hbo.hbonow"))
        }
        // Simulación de clics para verificar el aprendizaje: --es sim_aprendizaje anime|reiniciar
        // Acción global del servicio de accesibilidad: --es accion_global energia|inicio
        i?.getStringExtra("accion_global")?.let { android.util.Log.i("LauncherHome", "acción global $it: ${co.davidvega.launcher.sistema.HomeAccessibilityService.accionGlobal(it)}") }
        val id = i?.getStringExtra("accion") ?: return
        co.davidvega.launcher.sistema.Ajustes.ATAJOS.firstOrNull { it.id == id }?.let { co.davidvega.launcher.sistema.Ajustes.abrir(this, it) }
    }

    /**
     * Captura de la propia ventana (Fire OS devuelve negro con `adb screencap`).
     * Uso: adb shell am start -n co.davidvega.launcher/.MainActivity --ei captura_ms 15000 --es captura_nombre inicio
     * Guarda <carpeta externa de la app>/cap_inicio.png (el prefijo cap_ marca las capturas propias que la limpieza puede borrar).
     */
    private fun programarCaptura(i: Intent?) {
        val ms = i?.getIntExtra("captura_ms", -1) ?: -1
        if (ms < 0) return
        val nombre = (i?.getStringExtra("captura_nombre") ?: "captura").replace(Regex("[^A-Za-z0-9_-]"), "_").take(40) // sin «../» y con tope: solo un nombre de archivo corto
        window.decorView.postDelayed({
            val v = window.decorView
            if (v.width <= 0 || v.height <= 0) return@postDelayed
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bmp, { r ->
                // Disco lleno o error de escritura: se anota y la home sigue viva.
                if (r == PixelCopy.SUCCESS) try {
                    getExternalFilesDir(null)?.let { d ->
                        File(d, "cap_$nombre.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LauncherHome", "captura: $e")
                }
                bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        }, ms.toLong())
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
        delante.value = false
        co.davidvega.launcher.sistema.IntroVideo.marcarUso()
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        delante.value = true
        co.davidvega.launcher.ui.Reposo.ultima = android.os.SystemClock.elapsedRealtime()
        co.davidvega.launcher.sistema.Medidas.launcherVisible()
        // Vuelta con las filas ya cargadas: la primera fila está lista en el primer cuadro; en frío la marca la da el VM al llegar la caché.
        if (androidx.lifecycle.ViewModelProvider(this)[LauncherVM::class.java].estado.value.filas.isNotEmpty()) window.decorView.post { co.davidvega.launcher.sistema.Medidas.primeraFila() }
        if (!co.davidvega.launcher.sistema.Arranque.diferido) {
            co.davidvega.launcher.sistema.Autocuracion.asegurar(this, "inicio")
            co.davidvega.launcher.sistema.Mantenimiento.programar(this)
        }
        co.davidvega.launcher.sistema.HomeAccessibilityService.launcherDelante()
        // Reordena apps por uso al volver.
        runCatching { androidx.lifecycle.ViewModelProvider(this)[LauncherVM::class.java].recargarApps() }
    }
}
