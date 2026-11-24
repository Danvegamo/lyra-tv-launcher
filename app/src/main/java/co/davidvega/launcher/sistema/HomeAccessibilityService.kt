package co.davidvega.launcher.sistema

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import co.davidvega.launcher.MainActivity

/**
 * OPCIONAL. Fire OS no deja cambiar el launcher, así que este servicio hace lo mismo que Projectivy:
 * cuando aparece la pantalla de inicio de Amazon (com.amazon.tv.launcher), abre este launcher encima.
 * No lee contenido de pantalla (canRetrieveWindowContent = false) y solo mira ese paquete.
 * Se activa y se quita en Ajustes > Accesibilidad (ver README).
 */
class HomeAccessibilityService : AccessibilityService() {

    private var ultimo = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        android.util.Log.i("LauncherHome", "evento ${e.packageName} ${e.className}")
        if (e.packageName?.toString() != AMAZON_LAUNCHER) return
        val clase = e.className?.toString() ?: ""
        // Voz: el micrófono del control lo atiende Alexa, que abre sus resultados en esta actividad del launcher de Amazon.
        // Dos actividades de resultados según el caso: DirectiveSearchResultsActivityDI y SearchResultsDeepLinkActivityDI.
        if (clase.contains("DirectiveSearchResults") || clase.contains("SearchResultsDeepLink")) {
            vozDesde = System.currentTimeMillis()
            co.davidvega.launcher.data.Diagnostico.registrar("voz", "Fire OS abrió ${clase.substringAfterLast('.')}")
            leerVoz(0); return
        }
        // Solo la pantalla de inicio; los menús del launcher (p. ej. diálogos) no cuentan.
        if (!clase.contains("Home", ignoreCase = true)) return
        abrirNuestro()
    }

    // No consume la tecla: solo avisa. Así, si algo falla, Home sigue funcionando como siempre.
    override fun onServiceConnected() {
        android.util.Log.i("LauncherHome", "servicio conectado")
        instancia = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        liberarTodo()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        liberarTodo()
        super.onDestroy()
    }

    /** Suelta reproductor y cortina por cualquier camino en que muera el servicio (unbind o destroy); se puede llamar dos veces. */
    private fun liberarTodo() {
        manejador.removeCallbacksAndMessages(null)
        IntroVideo.soltar(reproductor); reproductor = null
        cortina?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }; cortina = null }
        if (instancia === this) instancia = null
    }

    /** Ciclo de la búsqueda guiada (BusquedaAccesible): solo corre mientras haya una tarea armada por el launcher. */
    private val ciclo = object : Runnable {
        override fun run() {
            if (BusquedaAccesible.tick(this@HomeAccessibilityService)) manejador.postDelayed(this, 350)
        }
    }

    private fun arrancarCiclo() { manejador.removeCallbacks(ciclo); manejador.postDelayed(ciclo, 600) }

    // La tecla Home del control no llega aquí: Fire OS la consume antes (comprobado con el control real el 22 oct 2026).
    // Las demás teclas sí llegan; mientras la cortina está puesta se descartan para que no actúen sobre el inicio de Amazon.
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (cortina == null) return false
        if (event?.action == KeyEvent.ACTION_DOWN) saltarVideo()
        return true
    }

    /**
     * Cortina: al pulsar Home, Fire OS llama a stopAppSwitches() y retiene durante 5 s cualquier actividad de terceros,
     * así que el launcher no puede ponerse delante antes. Mientras tanto se tapa el inicio de Amazon en negro puro #000
     * (sin imagen) en una ventana de accesibilidad, que no necesita permisos extra.
     * Se quita al volver el launcher (MainActivity.onResume) o a los 7 s.
     */
    private var cortina: android.view.View? = null
    private var reproductor: android.media.MediaPlayer? = null
    private var tEvento = 0L
    private var tCortina = 0L
    private var tCuadro = 0L
    private val quitarCortinaR = Runnable { quitarCortinaYa() }

    private fun ponerCortina() {
        if (cortina != null || MainActivity.enPrimerPlano) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        // Negro puro con la intro encima. TextureView: al acabar el vídeo se queda el último cuadro hasta que vuelva el launcher.
        val raiz = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val tv = android.view.TextureView(this)
        tv.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                IntroVideo.cortinaDesde = android.os.SystemClock.elapsedRealtime()
                // Intro corta: la cortina no puede quitarse antes (Fire OS retiene al launcher ~3 s tras Home), así que en vez de negro
                // muestra la cola de la intro en silencio. «Ninguna» sí deja la cortina en negro.
                val ctxS = this@HomeAccessibilityService
                val completa = IntroVideo.completaAhora(ctxS, !co.davidvega.launcher.ui.IntroEstado.yaArranco)
                if (!completa && IntroVideo.modo(ctxS) == 2) return
                reproductor = IntroVideo.preparar(ctxS, android.view.Surface(st), alPrimerCuadro = {
                    tCuadro = android.os.SystemClock.elapsedRealtime()
                    android.util.Log.i("LauncherHome", "intro: cortina a primer cuadro ${tCuadro - tCortina} ms (${if (completa) "completa" else "cola"})")
                }, soloCola = !completa) {}
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        raiz.addView(tv, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.graphics.PixelFormat.TRANSLUCENT, // translúcida para poder desvanecerla al volver el launcher
        )
        try {
            wm.addView(raiz, lp)
            cortina = raiz
            tCortina = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("LauncherHome", "intro: evento a cortina ${tCortina - tEvento} ms")
            Medidas.homeACortina(tCortina - tEvento)
            manejador.postDelayed(quitarCortinaR, 7000)
        } catch (e: Exception) { android.util.Log.w("LauncherHome", "cortina: $e") }
    }

    /** Quita la cortina con un fundido de 300 ms (el glitch de entrada lo da el launcher) y suelta el reproductor. */
    private fun quitarCortinaYa() {
        manejador.removeCallbacks(quitarCortinaR)
        val v = cortina ?: return
        cortina = null
        val aLauncher = android.os.SystemClock.elapsedRealtime() - tCortina
        android.util.Log.i("LauncherHome", "intro: cortina a launcher $aLauncher ms")
        Medidas.cortinaALauncher(aLauncher)
        val mp = reproductor; reproductor = null
        v.animate().alpha(0f).setDuration(300).withEndAction {
            IntroVideo.soltar(mp)
            runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }
        }.start()
    }

    /** Una tecla del control salta solo el vídeo: la cortina negra se queda hasta que vuelva el launcher. */
    private fun saltarVideo() {
        val mp = reproductor ?: return
        reproductor = null
        IntroVideo.soltar(mp)
        (cortina as? android.view.ViewGroup)?.getChildAt(0)?.visibility = android.view.View.INVISIBLE
    }

    private fun abrirNuestro() {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimo < 600 || ahora < pausadoHasta) return // evita bucles y respeta «Todos los ajustes»
        ultimo = ahora
        tEvento = android.os.SystemClock.elapsedRealtime()
        BusquedaAccesible.cancelar() // Home corta cualquier búsqueda guiada en curso
        ponerCortina()
        lanzar()
        // Amazon a veces vuelve a ponerse delante: se reintenta mientras no estemos en primer plano.
        for (espera in longArrayOf(900, 1900, 3200)) manejador.postDelayed({
            if (!MainActivity.enPrimerPlano && System.currentTimeMillis() >= pausadoHasta) lanzar()
        }, espera)
    }

    /**
     * Búsqueda por voz. Fire OS entrega el micrófono a Alexa y no a terceros, pero Alexa muestra lo que entendió en
     * com.amazon.tv.launcher:id/search_text (comprobado el 22 oct 2026: «frieren», «pedro pascal»). Se lee ese texto
     * y se abre el buscador del launcher con él. El texto puede tardar en pintarse: hasta 6 intentos.
     */
    private fun leerVoz(intento: Int) {
        val texto = textoDeVoz()
        if (texto.isNullOrBlank()) {
            if (intento < 16) manejador.postDelayed({ leerVoz(intento + 1) }, 250)
            else {
                // Sin texto en 4 s: no se deja al usuario en la pantalla de Amazon; se vuelve al launcher y se anota.
                android.util.Log.w("LauncherVoz", "sin texto en los resultados de Alexa")
                co.davidvega.launcher.data.Diagnostico.registrar("voz", "no se pudo leer la consulta; se vuelve al launcher")
                lanzar()
            }
            return
        }
        co.davidvega.launcher.data.Diagnostico.registrar("voz", "recuperada «$texto» en ${System.currentTimeMillis() - vozDesde} ms")
        android.util.Log.i("LauncherVoz", "Alexa entendió «$texto»")
        BusquedaAccesible.cancelar()
        val abrir = {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).putExtra("buscador_texto", texto).putExtra("voz", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (e: Exception) { android.util.Log.w("LauncherVoz", "no se pudo abrir el buscador: $e") }
        }
        abrir()
        // Igual que con Home, Amazon a veces se vuelve a poner delante.
        for (espera in longArrayOf(900, 1900)) manejador.postDelayed({ if (!MainActivity.enPrimerPlano) abrir() }, espera)
    }

    @Volatile private var vozDesde = 0L

    /** Consulta de voz: primero los ids conocidos de Amazon; si no, el primer texto de la pantalla cuyo id habla de búsqueda o consulta. */
    private fun textoDeVoz(): String? {
        val raiz = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        for (id in listOf("search_text", "search_query", "query_text", "query", "search_term")) {
            val t = runCatching { raiz.findAccessibilityNodeInfosByViewId("$AMAZON_LAUNCHER:id/$id")?.firstOrNull()?.text?.toString()?.trim() }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        var hallado: String? = null
        fun recorrer(n: android.view.accessibility.AccessibilityNodeInfo?, prof: Int) {
            if (n == null || hallado != null || prof > 12) return
            val id = n.viewIdResourceName ?: ""
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrBlank() && t.length in 2..80 && (id.contains("search", true) || id.contains("query", true))) { hallado = t; return }
            for (i in 0 until n.childCount) recorrer(n.getChild(i), prof + 1)
        }
        runCatching { recorrer(raiz, 0) }
        return hallado
    }

    internal val manejador = android.os.Handler(android.os.Looper.getMainLooper())

    private fun lanzar() {
        android.util.Log.i("LauncherHome", "abriendo launcher")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) { android.util.Log.w("LauncherHome", "no se pudo abrir: $e") }
    }

    override fun onInterrupt() {}

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"

        /** «Todos los ajustes» abre el inicio de Amazon a propósito: el servicio se aparta unos segundos. */
        @Volatile var pausadoHasta = 0L
        @Volatile private var instancia: HomeAccessibilityService? = null

        /** Lo llama BusquedaAccesible.iniciar: pone en marcha el ciclo si el servicio está activo. */
        fun despertar() { instancia?.let { it.manejador.post { it.arrancarCiclo() } } }
        val activo: Boolean get() = instancia != null
        fun pausar(ms: Long) { pausadoHasta = System.currentTimeMillis() + ms }

        /** Lo llama MainActivity.onResume: el launcher ya está delante y la cortina sobra. */
        fun launcherDelante() { instancia?.let { s -> s.manejador.postDelayed({ s.quitarCortinaYa() }, 150) } }

        /** Acción global del servicio: «energia» abre el diálogo Apagar / Reiniciar / Bloqueo (atajo de Ajustes y pruebas por adb). */
        fun accionGlobal(nombre: String): Boolean {
            val accion = when (nombre) { "energia" -> GLOBAL_ACTION_POWER_DIALOG; "inicio" -> GLOBAL_ACTION_HOME; else -> return false }
            return instancia?.performGlobalAction(accion) ?: false
        }
    }
}
