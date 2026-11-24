package co.davidvega.launcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import co.davidvega.launcher.R
import co.davidvega.launcher.sistema.IntroVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entrada del launcher. En cada Home (y en frío) reproduce un vídeo prerrenderizado (MP4 H.264 1080p con el audio de marca dentro, `res/raw/intro_vigia.mp4`,
 * generado por `diseno/intro/generar.py`) con decodificación por hardware sobre un SurfaceView: nada se anima en Compose, así que no hay tirones.
 * Al terminar, el vídeo (que ya acaba en negro) se aclara 400 ms hacia el inicio con un glitch de 200 ms, que ya estaba compuesto debajo.
 * El vídeo completo solo va en frío o tras 30 min sin usar el launcher (o con el ajuste «completa»); en los demás Home entra la versión corta (~1,2 s):
 * negro que se aclara en 400 ms con el glitch de 200 ms, sin vídeo. Con el ajuste «ninguna» siempre es la corta. Una tecla del control salta cualquiera.
 * Un negro opaco cubre la pantalla desde el primer fotograma, así que el primer cuadro aparece al instante aunque el vídeo tarde en preparar.
 */
@Composable
internal fun IntroPuntos(disparo: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var modo by remember { mutableIntStateOf(0) } // 0 nada, 1 vídeo, 2 corta (sin vídeo)
    var jugada by remember { mutableIntStateOf(0) }
    val alfa = remember { Animatable(0f) }
    val vozFin = remember { kotlinx.coroutines.flow.MutableStateFlow(0) }

    LaunchedEffect(disparo) {
        if (disparo == 0) return@LaunchedEffect
        val frio = !IntroEstado.yaArranco
        IntroEstado.yaArranco = true
        // La cortina del servicio ya la mostró mientras Fire OS retenía el launcher: aquí solo entra el glitch.
        if (IntroVideo.cortinaYaLaMostro()) { delay(150); Glitch.global.disparar(200); return@LaunchedEffect }
        val completa = IntroVideo.completaAhora(ctx, frio)
        IntroEstado.activa = true
        alfa.snapTo(1f)
        if (completa) { jugada++; modo = 1; return@LaunchedEffect }
        // Corta: el negro opaco ya cubre; glitch de 200 ms y fundido de 400 ms (~1,2 s con la pausa inicial), sin vídeo.
        modo = 2
        delay(600)
        if (modo != 2) return@LaunchedEffect // la saltaron durante la pausa
        kotlinx.coroutines.coroutineScope {
            launch { Glitch.global.disparar(200) }
            alfa.animateTo(0f, tween(400))
        }
        modo = 0; IntroEstado.activa = false
    }
    // Fin del vídeo (que ya acaba en negro con su propio glitch): fundido de negro a la app de 400 ms y, a la vez, un glitch de 200 ms sobre toda la imagen.
    val fin by vozFin.collectAsState()
    LaunchedEffect(fin) {
        if (fin == 0 || modo != 1) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch { Glitch.global.disparar(200) }
            alfa.animateTo(0f, tween(400))
        }
        modo = 0; IntroEstado.activa = false
    }
    val saltos by IntroEstado.saltar.collectAsState()
    LaunchedEffect(saltos) {
        if (saltos == 0 || !IntroEstado.activa) return@LaunchedEffect
        alfa.animateTo(0f, tween(180))
        modo = 0; IntroEstado.activa = false
    }
    if (modo == 0) return
    Box(modifier.fillMaxSize().background(Color.Black).graphicsLayer { this.alpha = alfa.value }) {
        if (modo == 1) {
            androidx.compose.runtime.key(jugada) {
            val reproductor = remember(jugada) { arrayOfNulls<MediaPlayer>(1) }
            DisposableEffect(jugada) { onDispose { IntroVideo.soltar(reproductor[0]); reproductor[0] = null } }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    // SurfaceView: el vídeo lo compone el sistema sin pasar por el hilo de la interfaz, que a esa hora está ocupado componiendo el inicio.
                    android.view.SurfaceView(c).apply {
                        setZOrderOnTop(true)
                        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                                reproductor[0] = IntroVideo.preparar(c, h.surface) { vozFin.value++ }
                            }
                            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
                        })
                    }
                },
            )
            }
        }
    }
}
