package co.davidvega.launcher.sistema

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import co.davidvega.launcher.App
import co.davidvega.launcher.data.Reproducciones

/**
 * Escucha las sesiones de reproducción (MediaSession) de todas las apps y se las pasa a Reproducciones.
 * Un NotificationListenerService activo es lo que permite a la app llamar a MediaSessionManager.getActiveSessions.
 * No lee el contenido de las notificaciones. Se activa por adb (ver LEEME) y se apaga con «Registrar lo que veo» en Ajustes.
 */
class EscuchaMedios : NotificationListenerService() {
    /** Lo último que se vio de cada sesión: si el estado no cambió, un aviso de posición (cada ~3 s en Crunchyroll) no hace nada. */
    private class Ultimo(var meta: Map<String, String> = emptyMap(), var estado: Int = -1)

    private val controladores = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val ultimos = HashMap<String, Ultimo>()
    private lateinit var reproducciones: Reproducciones
    private lateinit var msm: MediaSessionManager
    private val componente by lazy { ComponentName(this, EscuchaMedios::class.java) }

    private val h = Handler(Looper.getMainLooper())
    /** Cada 60 s anota en memoria la posición de lo que suena (no escribe en disco): si la tele se apaga, el registro queda con un minuto cercano. */
    private val tic = object : Runnable {
        override fun run() {
            controladores.values.forEach { (c, _) -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) reproducciones.posicion(c.packageName, posicionDe(c)) }
            h.postDelayed(this, TIC_MS)
        }
    }

    private val cambioSesiones = MediaSessionManager.OnActiveSessionsChangedListener { lista -> sincronizar(lista ?: emptyList()) }

    override fun onListenerConnected() {
        val app = application as App
        reproducciones = Reproducciones(this, app)
        instancia = reproducciones
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            msm.addOnActiveSessionsChangedListener(cambioSesiones, componente)
            sincronizar(msm.getActiveSessions(componente))
            Log.i(ETIQUETA, "conectado: ${controladores.size} sesiones activas")
        } catch (e: SecurityException) {
            Log.w(ETIQUETA, "sin permiso para leer sesiones: $e")
            co.davidvega.launcher.data.Diagnostico.registrar("escucha_medios", "sin permiso para leer sesiones")
        }
        app.eventos.reintentar()
        reproducciones.iniciarCaptura()
        h.removeCallbacks(tic); h.postDelayed(tic, TIC_MS)
    }

    override fun onListenerDisconnected() {
        h.removeCallbacks(tic)
        try { msm.removeOnActiveSessionsChangedListener(cambioSesiones) } catch (_: Exception) {}
        runCatching { reproducciones.cerrarTodas() } // vuelca las sesiones en curso: no se pierden registros al desconectar
        controladores.values.forEach { it.first.unregisterCallback(it.second) }
        controladores.clear()
        instancia = null
        // Al reinstalar la app el sistema desconecta el escucha siempre: no es una incidencia, solo queda en el log.
        android.util.Log.i("LauncherMedios", "escucha desconectada")
    }

    private fun clave(c: MediaController) = c.packageName + "#" + c.sessionToken.hashCode()

    private fun sincronizar(lista: List<MediaController>) {
        val vivas = lista.map { clave(it) }.toSet()
        controladores.keys.filter { it !in vivas }.toList().forEach { k ->
            ultimos.remove(k)
            controladores.remove(k)?.let { (c, cb) -> c.unregisterCallback(cb); reproducciones.sesionDestruida(c.packageName) }
        }
        for (c in lista) {
            val k = clave(c)
            if (k in controladores) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = procesar(c, soloEstado = true)
                override fun onMetadataChanged(metadata: MediaMetadata?) = procesar(c)
                override fun onSessionDestroyed() { reproducciones.sesionDestruida(c.packageName) }
            }
            controladores[k] = c to cb
            c.registerCallback(cb)
            procesar(c)
        }
    }

    /**
     * `soloEstado`: aviso de PlaybackState. Los metadatos no se vuelven a leer (se usan los últimos), y si el estado
     * (reproduciendo, pausa...) es el mismo no se hace nada: antes cada aviso de posición releía y volcaba todos los metadatos.
     */
    private fun procesar(c: MediaController, soloEstado: Boolean = false) {
        val u = ultimos.getOrPut(clave(c)) { Ultimo() }
        val estado = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val pos = posicionDe(c)
        if (soloEstado && u.estado == estado) { reproducciones.posicion(c.packageName, pos); return }
        val anterior = u.meta
        if (!soloEstado || u.meta.isEmpty()) u.meta = reproducciones.volcado(c.metadata)
        val cambioEstado = u.estado != estado
        val cambioMeta = u.meta != anterior
        u.estado = estado
        // Solo se anota en el log cuando algo cambió de verdad (antes salía el volcado completo en cada aviso de posición).
        if ((cambioEstado || cambioMeta) && (u.meta.isNotEmpty() || estado == PlaybackState.STATE_PLAYING)) Log.i(ETIQUETA, "${c.packageName} estado=$estado meta=${u.meta}")
        reproducciones.cambio(c.packageName, u.meta, estado, pos)
    }

    private fun posicionDe(c: MediaController): Long {
        val p = c.playbackState ?: return -1L
        return Reproducciones.posicionEstimada(p.position, p.state == PlaybackState.STATE_PLAYING, p.lastPositionUpdateTime, SystemClock.elapsedRealtime(), p.playbackSpeed)
    }

    companion object {
        const val TIC_MS = 60_000L
        const val ETIQUETA = "LauncherMedios"
        @Volatile var instancia: Reproducciones? = null
            private set
    }
}
