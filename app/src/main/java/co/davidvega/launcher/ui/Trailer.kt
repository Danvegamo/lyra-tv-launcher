package co.davidvega.launcher.ui

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import co.davidvega.launcher.sistema.TrailerStream
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.MainActivity
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.sistema.Abrir
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Estado compartido del reposo: la última tecla (la anota MainActivity), si el reposo con tráilers está activo y las
 * señales de «salir» y «ver» que el activity manda mientras el reposo consume las teclas.
 */
internal object Reposo {
    @Volatile var ultima = SystemClock.elapsedRealtime()
    @Volatile var activo = false
    val salir = MutableStateFlow(0)
    val ver = MutableStateFlow(0)
    const val ESPERA_MS = 150_000L // sin tocar nada en el inicio: tras 2,5 min entra el reposo

    /** Hay música o vídeo sonando en la tele: no se arranca nada. */
    fun sonando(ctx: Context) = runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }.getOrDefault(false)

    /** Con poca memoria libre no se abre un WebView (el aparato tiene 1,7 GB y el WebView cuesta unos 100 MB). */
    fun memoriaHolgada(ctx: Context): Boolean = runCatching {
        val i = ActivityManager.MemoryInfo(); (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(i)
        i.availMem > 260L * 1024 * 1024 && !i.lowMemory
    }.getOrDefault(false)
}

/**
 * Reposo del inicio: tras un rato sin tocar nada, tráilers a pantalla completa (sin sonido) de lo que hay en las filas,
 * uno cada 40 s. OK abre el tráiler en el reproductor con sonido; cualquier otra tecla vuelve al inicio.
 * Si un título no tiene tráiler o el WebView no carga, pasa al siguiente; si ninguno sirve, queda su fondo con el título.
 */
@Composable
internal fun ReposoTrailers(candidatos: List<Titulo>, vm: LauncherVM, alSalir: () -> Unit) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    var clave by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    val t = candidatos.getOrNull(idx % maxOf(1, candidatos.size))
    val alfa by animateFloatAsState(if (visible) 1f else 0f, tween(700), label = "reposo")
    // Siguiente título cada 40 s (o antes, si no hay tráiler).
    LaunchedEffect(idx, t) {
        visible = false; clave = null
        if (t == null) return@LaunchedEffect
        clave = vm.trailer(t)
        if (clave == null) { delay(1500); idx++; return@LaunchedEffect }
        delay(40_000); idx++
    }
    // OK activa el sonido del tráiler que está sonando (no se sale del launcher).
    val ver0 = remember { Reposo.ver.value }
    val ver by Reposo.ver.collectAsState()
    var conSonido by remember { mutableStateOf(false) }
    LaunchedEffect(idx) { conSonido = false }
    LaunchedEffect(ver) { if (ver > ver0 && clave != null) conSonido = !conSonido }
    // Si el launcher deja de estar delante (se abrió una app), el reposo se apaga. No se mira «sonando» aquí: el propio
    // tráiler, aunque vaya en silencio, hace que isMusicActive sea true y cerraba el reposo al arrancar el vídeo.
    LaunchedEffect(Unit) { while (true) { delay(1000); if (!MainActivity.enPrimerPlano) { alSalir(); break } } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        t?.fondo?.let { f ->
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(ctx).data(f).size(1280, 720).build(), contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - alfa },
            )
        }
        // Tráiler en vivo con NewPipeExtractor + Media3 (sin WebView). Arranca en silencio a los 5 s; si no se resuelve, queda el fondo del título.
        var verVideo by remember(clave) { mutableStateOf(false) }
        var listoVideo by remember(clave) { mutableStateOf(false) }
        LaunchedEffect(clave) { verVideo = false; if (clave != null) { delay(5_000); verVideo = true } }
        val vc = clave
        if (verVideo && vc != null) key(vc) {
            VideoTrailer(vc, silenciado = !conSonido, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (listoVideo) 1f else 0f },
                alFallo = { verVideo = false }, alPrimerFotograma = { listoVideo = true }, alTerminar = { idx++ })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        if (t != null) Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 52.dp)) {
            BasicText(t.titulo, style = th(34, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            BasicText(listOf(if (t.medio == "tv") "Serie" else "Película", t.anio, if (t.nota > 0) "★ %.1f".format(java.util.Locale.US, t.nota) else "").filter { it.isNotBlank() }.joinToString("   ·   "), style = th(16, color = Tema.Secundario))
            Spacer(Modifier.height(14.dp))
            BasicText(if (conSonido) "OK  SILENCIAR   ·   CUALQUIER OTRA TECLA  SALIR" else "OK  ACTIVAR SONIDO   ·   CUALQUIER OTRA TECLA  SALIR", style = txtMono(12, Tema.Terciario))
        }
    }
}


/**
 * Tráiler de YouTube dentro del launcher: NewPipeExtractor saca el stream, Media3 lo reproduce en un TextureView.
 * Un solo jugador por clave; se libera al salir de la composición. `alFallo` se llama si no se pudo resolver o reproducir.
 */
@Composable
internal fun VideoTrailer(clave: String, silenciado: Boolean, modifier: Modifier, alFallo: () -> Unit, alPrimerFotograma: () -> Unit = {}, alTerminar: () -> Unit = {}) {
    val ctx = LocalContext.current
    val tv = remember { TextureView(ctx) }
    val jugador = remember { arrayOfNulls<ExoPlayer>(1) }
    val estaSilenciado by rememberUpdatedState(silenciado)
    val fallo by rememberUpdatedState(alFallo)
    val primero by rememberUpdatedState(alPrimerFotograma)
    val termina by rememberUpdatedState(alTerminar)
    LaunchedEffect(clave, silenciado) {
        // Con sonido hace falta el flujo con audio; si ya había uno en marcha solo se cambia el volumen.
        val actual = jugador[0]
        if (actual != null) { actual.volume = if (silenciado) 0f else 1f; return@LaunchedEffect }
        val f = TrailerStream.fuentes(clave, conSonido = true) ?: run { fallo(); return@LaunchedEffect }
        val p = TrailerStream.crearJugador(ctx, f)
        jugador[0] = p
        p.volume = if (estaSilenciado) 0f else 1f
        p.setVideoTextureView(tv)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { primero() }
            override fun onPlayerError(error: PlaybackException) { android.util.Log.e("LauncherTrailer", "ExoPlayer", error); fallo() }
            override fun onPlaybackStateChanged(estado: Int) { if (estado == Player.STATE_ENDED) termina() }
        })
    }
    DisposableEffect(clave) { onDispose { jugador[0]?.release(); jugador[0] = null } }
    AndroidView(factory = { tv }, modifier = modifier)
}

/** «Ver tráiler» del menú ☰: pantalla completa dentro del launcher, con sonido. Atrás o el fin del vídeo la cierran; si falla, se ofrece SmartTube. */
@Composable
internal fun TrailerAdentro(t: Titulo, clave: String, alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    var listo by remember { mutableStateOf(false) }
    BackHandler { alCerrar() }
    // El overlay se queda el foco y se traga las teclas (Atrás lo gestiona BackHandler): así OK no toca lo que hay debajo.
    val foco = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { foco.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black).androidx_foco(foco)) {
        t.fondo?.let { f ->
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(ctx).data(f).size(1280, 720).build(), contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (listo) 0f else 0.5f },
            )
        }
        VideoTrailer(clave, silenciado = false, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (listo) 1f else 0f },
            alFallo = { Abrir.trailer(ctx, clave); alCerrar() }, alPrimerFotograma = { listo = true }, alTerminar = alCerrar)
        if (!listo) BasicText("Cargando el tráiler…", style = th(16, color = Tema.Secundario), modifier = Modifier.align(Alignment.Center))
    }
}

/** Tráiler abierto desde el menú ☰ (título y clave de YouTube); lo pinta Pantalla encima de todo. */
internal object TrailerEnCurso { val ver = MutableStateFlow<Pair<Titulo, String>?>(null) }

private fun Modifier.androidx_foco(f: androidx.compose.ui.focus.FocusRequester): Modifier = this
    .focusRequester(f)
    .focusable()
    .onPreviewKeyEvent { e -> e.key != Key.Back }
