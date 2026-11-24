package co.davidvega.launcher.sistema

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import co.davidvega.launcher.data.Diagnostico
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL

/** Lo que hace falta para reproducir un tráiler: un solo flujo (con audio y vídeo) o vídeo y audio por separado. */
class Fuentes(val video: String, val audio: String?)

/**
 * Tráilers en tiempo real y sin servidor: NewPipeExtractor (en el aparato) saca la URL de stream del vídeo de YouTube que da TMDB y
 * Media3 ExoPlayer la reproduce de forma progresiva. Nada se guarda en disco, no hay WebView ni se descarga el vídeo entero.
 * Si NewPipe falla (YouTube cambia su web cada pocas semanas), `fuentes` devuelve null y quien llama se queda con el fondo del título.
 */
object TrailerStream {
    private var iniciado = false

    /** Cliente HTTP mínimo para NewPipe (sin OkHttp, para no sumar otra librería). */
    private object Descargador : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.requestMethod = request.httpMethod()
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            for ((k, v) in request.headers()) { c.setRequestProperty(k, null); v.forEach { c.addRequestProperty(k, it) } }
            request.dataToSend()?.let { c.doOutput = true; c.outputStream.use { o -> o.write(it) } }
            val codigo = c.responseCode
            if (codigo == 429) { c.disconnect(); throw ReCaptchaException("429", request.url()) }
            val cuerpo = (if (codigo >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val cabeceras = c.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            val fin = c.url.toString()
            c.disconnect()
            return Response(codigo, c.responseMessage ?: "", cabeceras, cuerpo, fin)
        }
    }

    /** Resuelve la clave de YouTube a URLs de stream. Con sonido se admite hasta 720p; en silencio, hasta 480p (menos datos y memoria). */
    suspend fun fuentes(clave: String, conSonido: Boolean): Fuentes? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(25_000) {
                if (!iniciado) { NewPipe.init(Descargador, Localization("es", "CO")); iniciado = true }
                val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$clave")
                val tope = if (conSonido) 720 else 480
                android.util.Log.i("LauncherTrailer", "info ok: ${info.videoStreams.size} completos, ${info.videoOnlyStreams.size} solo vídeo, ${info.audioStreams.size} audio")
                // 1.º un flujo completo (audio y vídeo juntos), el más alto hasta el tope.
                val completo = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..tope }.maxByOrNull { it.height }
                if (completo != null) return@withTimeoutOrNull Fuentes(completo.content, null)
                // 2.º vídeo solo (preferiblemente MP4) y, si hay sonido, el mejor audio por separado.
                val solo = info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..tope }
                    .sortedWith(compareBy({ it.format?.suffix != "mp4" }, { -it.height })).firstOrNull() ?: return@withTimeoutOrNull null
                if (!conSonido) return@withTimeoutOrNull Fuentes(solo.content, null)
                val audio = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .sortedWith(compareBy({ it.format?.suffix != "m4a" }, { -it.averageBitrate })).firstOrNull()
                Fuentes(solo.content, audio?.content)
            }
        } catch (e: Throwable) {
            android.util.Log.e("LauncherTrailer", "NewPipe falló con $clave", e)
            Diagnostico.registrar("trailer_stream", "NewPipe ${e.javaClass.simpleName} @ ${e.stackTrace.take(3).joinToString(" < ") { it.className.substringAfterLast(".") + "." + it.methodName }}")
            null
        }
    }

    /** ExoPlayer con búferes cortos (unos segundos) para que el tráiler no ocupe memoria; hay que llamar a `release()` al terminar. */
    fun crearJugador(ctx: Context, f: Fuentes): ExoPlayer {
        android.util.Log.i("LauncherTrailer", "jugador: ${f.video.take(60)} audio=${f.audio != null}")
        val carga = DefaultLoadControl.Builder().setBufferDurationsMs(2_500, 8_000, 1_500, 2_000).setTargetBufferBytes(6 * 1024 * 1024).build()
        val p = ExoPlayer.Builder(ctx.applicationContext).setLoadControl(carga).build()
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/120.0 Safari/537.36").setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val fabrica = ProgressiveMediaSource.Factory(http)
        val v: MediaSource = fabrica.createMediaSource(MediaItem.fromUri(f.video))
        p.setMediaSource(if (f.audio != null) MergingMediaSource(v, fabrica.createMediaSource(MediaItem.fromUri(f.audio))) else v)
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.playWhenReady = true
        p.prepare()
        return p
    }
}
