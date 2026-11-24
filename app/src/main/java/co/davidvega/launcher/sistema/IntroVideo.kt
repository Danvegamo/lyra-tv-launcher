package co.davidvega.launcher.sistema

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import co.davidvega.launcher.R
import co.davidvega.launcher.data.Preferencias

/**
 * Reproductor único de la intro `res/raw/intro_vigia.mp4`. Lo usan la cortina del servicio de accesibilidad (que cubre los 5 s que Fire OS
 * retiene a las apps de terceros tras Home) y la entrada de MainActivity (arranque en frío o Home con el launcher ya delante).
 * La completa solo se usa en frío o tras 30 min sin usar el launcher (ver `completa`); en los demás Home la entrada es la corta (~1,2 s).
 */
internal object IntroVideo {
    /** Marca de reloj en que la cortina empezó a mostrar la intro; MainActivity no la repite si fue hace poco. 0 = nada pendiente. */
    @Volatile var cortinaDesde = 0L

    /** Mínimo sin usar el launcher para que la intro vuelva a ser la completa (ajuste «corta»). */
    const val INACTIVIDAD_MS = 30 * 60_000L

    /** Última vez que se usó el launcher (reloj de arranque); 0 = nunca en este proceso. Lo marcan MainActivity (al salir y con cada tecla). */
    @Volatile var ultimoUso = 0L

    fun marcarUso() { ultimoUso = android.os.SystemClock.elapsedRealtime() }

    /**
     * Ajuste «Intro» (SharedPreferences «ajustes», clave `intro_modo`): 0 completa siempre, 1 corta (completa solo en frío o tras
     * 30 min sin usar el launcher; por defecto), 2 ninguna (sin vídeo, solo glitch y fundido).
     */
    const val CLAVE_MODO = "intro_modo"
    fun modo(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getInt(CLAVE_MODO, 1)

    /** Decide si esta entrada es el vídeo completo (5,2 s). `frio`: primera entrada del proceso. */
    fun completa(modo: Int, frio: Boolean, ultimoUso: Long, ahora: Long): Boolean = when (modo) {
        0 -> true
        2 -> false
        else -> frio || ultimoUso == 0L || ahora - ultimoUso > INACTIVIDAD_MS
    }

    fun completaAhora(ctx: Context, frio: Boolean) = completa(modo(ctx), frio, ultimoUso, android.os.SystemClock.elapsedRealtime())

    /** Verdadero (y gasta la marca) si la cortina acaba de mostrar la intro y MainActivity no debe repetirla. */
    fun cortinaYaLaMostro(): Boolean {
        val hace = android.os.SystemClock.elapsedRealtime() - cortinaDesde
        val si = cortinaDesde != 0L && hace in 0..12_000
        cortinaDesde = 0L
        return si
    }

    /**
     * Prepara el vídeo sobre la superficie y lo arranca al estar listo. Sin sonido si Ajustes > «Sonido de inicio» está en «Sin sonido»
     * o si ya suena música en la tele. `alPrimerCuadro` avisa cuando se pinta el primero; `alTerminar` al acabar o fallar.
     */
    /** Lo que dura la cola de la intro que se ve en la versión corta: cubre los ~3,2 s que Fire OS retiene al launcher tras Home. */
    const val COLA_CORTA_MS = 3_000

    fun preparar(ctx: Context, superficie: Surface, alPrimerCuadro: () -> Unit = {}, soloCola: Boolean = false, alTerminar: () -> Unit): MediaPlayer? = runCatching {
        val app = ctx.applicationContext
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        app.resources.openRawResourceFd(R.raw.intro_vigia).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        mp.setSurface(superficie)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // La cola corta va siempre en silencio: el sonido empezado a mitad suena cortado.
        if (soloCola || Preferencias.inicioModo(app) == 1 || audio.isMusicActive) mp.setVolume(0f, 0f)
        mp.setOnInfoListener { _, que, _ -> if (que == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) alPrimerCuadro(); false }
        mp.setOnPreparedListener {
            if (soloCola) { it.setOnSeekCompleteListener { p -> p.start() }; it.seekTo((it.duration - COLA_CORTA_MS).coerceAtLeast(0)) }
            else it.start()
        }
        mp.setOnCompletionListener { alTerminar() }
        mp.setOnErrorListener { _, _, _ -> alTerminar(); true }
        mp.prepareAsync()
        mp
    }.getOrNull()

    fun soltar(mp: MediaPlayer?) { runCatching { mp?.release() } }
}
