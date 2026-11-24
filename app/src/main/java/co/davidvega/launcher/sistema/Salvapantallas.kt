package co.davidvega.launcher.sistema

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import co.davidvega.launcher.App
import co.davidvega.launcher.data.Fila
import co.davidvega.launcher.data.Titulo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Salvapantallas propio (0.11.0): el fondo (backdrop) de un título a pantalla completa, como el reposo del inicio, con el
 * título y datos breves abajo a la izquierda. Fundido cruzado lento entre dos escenas y un zoom muy lento (efecto Ken Burns)
 * para que ningún píxel quede fijo. Los títulos salen de `filas.json` (la caché de filas del Catálogo); no consulta TMDB.
 * Solo baja cada fondo (w1280) por Coil, que lo guarda en su caché de disco. Sin vídeo: ver LEEME (tráilers).
 * Se activa con `settings put secure screensaver_components co.davidvega.launcher/co.davidvega.launcher.sistema.Salvapantallas`.
 */
class Salvapantallas : DreamService() {
    private var alcance: CoroutineScope? = null
    private var ciclo: Job? = null

    /** Una escena: fondo + degradado + bloque de texto. Dos de ellas se alternan. */
    private class Escena(val raiz: FrameLayout, val imagen: ImageView, val video: TextureView, val titulo: TextView, val datos: TextView, val sinopsis: TextView)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun escena(): Escena {
        val raiz = FrameLayout(this).apply { alpha = 0f }
        val imagen = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        raiz.addView(imagen, FrameLayout.LayoutParams(-1, -1))
        // El tráiler (si se resuelve) va encima del fondo y debajo del degradado y del texto; empieza invisible.
        val video = TextureView(this).apply { alpha = 0f }
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1))
        // Degradado inferior para que el texto se lea sobre cualquier fondo (el mismo criterio del reposo).
        val velo = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(40, 0, 0, 0), Color.argb(230, 0, 0, 0)))
        }
        raiz.addView(velo, FrameLayout.LayoutParams(-1, -1))
        fun texto(sp: Float, color: Int, negrita: Boolean) = TextView(this).apply {
            setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = if (negrita) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val titulo = texto(38f, Color.WHITE, true).apply { maxLines = 2 }
        val datos = texto(17f, Color.parseColor("#C8C8C8"), false).apply { setPadding(0, dp(8), 0, 0) }
        val sinopsis = texto(16f, Color.parseColor("#A8A8A8"), false).apply { maxLines = 3; setPadding(0, dp(12), 0, 0) }
        val bloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titulo); addView(datos); addView(sinopsis)
        }
        // Margen de zona segura de tele (6 % a la izquierda, 9 % abajo) y bloque de 60 % del ancho como máximo.
        val m = resources.displayMetrics
        val lp = FrameLayout.LayoutParams(m.widthPixels * 6 / 10, -2, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = m.widthPixels * 6 / 100; lp.bottomMargin = m.heightPixels * 9 / 100
        raiz.addView(bloque, lp)
        return Escena(raiz, imagen, video, titulo, datos, sinopsis)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val marco = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val a = escena(); val b = escena()
        marco.addView(a.raiz, FrameLayout.LayoutParams(-1, -1)); marco.addView(b.raiz, FrameLayout.LayoutParams(-1, -1))
        setContentView(marco)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        alcance = scope
        ciclo = scope.launch { reproducir(a, b) }
    }

    private suspend fun reproducir(a: Escena, b: Escena) {
        val titulos = withContext(Dispatchers.IO) { leerTitulos() }.shuffled()
        if (titulos.isEmpty()) return
        var i = 0
        var fallos = 0 // descargas seguidas que fallaron: espera creciente (0,5 s a 30 s) para no martillar la red
        var delante = a; var detras = b
        while (true) {
            val t = titulos[i % titulos.size]; i++
            val dibujo = bajar(t.fondo ?: continue)
            if (dibujo == null) { fallos++; delay(minOf(500L shl minOf(fallos, 7), 30_000L)); continue }
            fallos = 0
            // Se prepara la escena de atrás (invisible) y se funde por encima de la de delante.
            detras.imagen.setImageDrawable(dibujo)
            detras.titulo.text = t.titulo
            detras.datos.text = listOf(if (t.medio == "tv") "Serie" else "Película", t.anio, if (t.nota > 0) "★ %.1f".format(Locale.US, t.nota) else "")
                .filter { it.isNotBlank() }.joinToString("   ·   ")
            detras.sinopsis.text = t.sinopsisCorta
            detras.raiz.bringToFront()
            // Zoom lento: del 100 % al 108 % durante toda la escena, desde un punto distinto cada vez.
            detras.imagen.animate().cancel()
            detras.imagen.scaleX = 1f; detras.imagen.scaleY = 1f
            detras.imagen.pivotX = detras.imagen.width * (if (i % 2 == 0) 0.3f else 0.7f); detras.imagen.pivotY = detras.imagen.height * 0.4f
            detras.imagen.animate().scaleX(1.08f).scaleY(1.08f).setDuration(26_000).setInterpolator(LinearInterpolator()).start()
            detras.raiz.animate().alpha(1f).setDuration(3_500).start()
            delay(3_600)
            delante.raiz.alpha = 0f
            delante.imagen.animate().cancel(); delante.imagen.setImageDrawable(null)
            val x = delante; delante = detras; detras = x
            // Tras unos segundos de fondo, el tráiler arranca en silencio a pantalla completa; si no se resuelve, se queda el fondo.
            delay(5_000)
            val conVideo = try { trailerMudo(delante, t) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { false }
            if (!conVideo) delay(13_000)
        }
    }

    /** Reproduce el tráiler de `t` sin sonido sobre la escena `e` hasta 45 s o hasta que acabe. Devuelve falso si no hubo vídeo. El jugador se libera siempre. */
    private suspend fun trailerMudo(e: Escena, t: Titulo): Boolean {
        val clave = (application as App).catalogo.trailer(t) ?: return false
        val f = TrailerStream.fuentes(clave, conSonido = false) ?: return false
        val p = TrailerStream.crearJugador(this, f)
        try {
            p.volume = 0f
            p.setVideoTextureView(e.video)
            val primero = CompletableDeferred<Boolean>(); val fin = CompletableDeferred<Unit>()
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { primero.complete(true) }
                override fun onPlayerError(error: PlaybackException) { primero.complete(false); fin.complete(Unit) }
                override fun onPlaybackStateChanged(estado: Int) { if (estado == Player.STATE_ENDED) fin.complete(Unit) }
            })
            if (withTimeoutOrNull(15_000) { primero.await() } != true) return false
            e.video.animate().alpha(1f).setDuration(1_500).start()
            withTimeoutOrNull(45_000) { fin.await() }
            e.video.animate().alpha(0f).setDuration(1_500).start()
            delay(1_600)
            return true
        } finally {
            p.release()
            e.video.animate().cancel(); e.video.alpha = 0f
        }
    }

    private suspend fun bajar(url: String) = try {
        withContext(Dispatchers.IO) {
            imageLoader.execute(ImageRequest.Builder(this@Salvapantallas).data(url).size(Size(1280, 720)).allowHardware(true).build()).drawable
        }
    } catch (_: Exception) { null }

    /** Títulos de `filas.json` con fondo, sin repetir. Vacío si todavía no hay caché de filas. */
    private fun leerTitulos(): List<Titulo> = try {
        val filas = JSONObject(File(filesDir, "filas.json").readText()).getJSONArray("filas")
        val vistos = LinkedHashMap<Int, Titulo>()
        for (f in 0 until filas.length()) for (t in Fila.deJson(filas.getJSONObject(f)).items) if (t.fondo != null) vistos.putIfAbsent(t.id, t)
        vistos.values.toList()
    } catch (_: Exception) { emptyList() }

    override fun onDetachedFromWindow() {
        parar()
        super.onDetachedFromWindow()
    }

    override fun onDreamingStopped() {
        parar()
        super.onDreamingStopped()
    }

    private fun parar() {
        ciclo?.cancel(); ciclo = null
        alcance?.cancel(); alcance = null
        try { imageLoader.memoryCache?.clear() } catch (_: Exception) {}
    }
}
