package co.davidvega.launcher.sistema

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbKeyPair
import dadb.AdbTimeoutException
import dadb.Dadb
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Resultado de un paso: ok y un texto corto para mostrar y anotar. `reintentable`: no se ejecutó por una condición pasajera. */
class PasoMant(val ok: Boolean, val texto: String, val reintentable: Boolean = false)

/**
 * Cliente adb hacia el adbd de la propia tele, con tiempos de verdad. dadb conecta con tiempo 0 (infinito) por omisión: con el
 * diálogo «¿Permitir la depuración?» sin contestar, el apretón de manos se quedaba bloqueado para siempre (un hilo y un socket
 * colgados por cada noche y por cada «Autorizar»). Aquí el socket lleva tiempo máximo (`connectTimeout` y `socketTimeout`), así el hilo
 * termina solo aunque `close()` no pueda cerrar nada, y el hilo es demonio.
 */
internal object AdbLocal {
    private const val PRUEBA_CONEXION_MS = 5_000

    /**
     * Ejecuta `cmd` con un tiempo máximo `ms`. Con `esperaCorte` (reboot) solo cuenta como éxito que la conexión se corte DESPUÉS
     * de conectar (ver `corteEsperado`); no conectar, no estar autorizado o no recibir nada no son un reinicio.
     */
    fun ejecutar(host: String, puerto: Int, llaves: () -> AdbKeyPair, cmd: String, ms: Long, esperaCorte: Boolean = false): PasoMant {
        if (ms <= 0) return PasoMant(false, "sin tiempo restante")
        val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "adb-mant").apply { isDaemon = true } }
        val dadb = AtomicReference<Dadb?>()
        return try {
            val f = hilo.submit(Callable {
                // El socket vence junto con el paso (un poco después, para que el límite de arriba sea el que mande).
                val d = Dadb.create(host, puerto, llaves(), minOf(ms, PRUEBA_CONEXION_MS.toLong()).toInt(), (ms + 1_000L).toInt())
                dadb.set(d)
                d.shell(cmd)
            })
            val r = f.get(ms, TimeUnit.MILLISECONDS)
            if (r.exitCode == 0) PasoMant(true, r.allOutput.trim().ifBlank { "ok" }) else PasoMant(false, "salió con ${r.exitCode}: ${r.allOutput.trim().take(80)}")
        } catch (e: TimeoutException) {
            PasoMant(false, if (esperaCorte) "sin confirmar el reinicio (tiempo agotado)" else "tiempo agotado (¿diálogo de depuración sin aceptar?)")
        } catch (e: ExecutionException) {
            val causa = e.cause ?: e
            if (esperaCorte && corteEsperado(causa)) PasoMant(true, "conexión cerrada (esperado al reiniciar)")
            else PasoMant(false, "${causa.javaClass.simpleName}: ${causa.message.orEmpty().take(80)}")
        } catch (e: Exception) {
            PasoMant(false, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        } finally {
            runCatching { dadb.get()?.close() }
            hilo.shutdownNow()
        }
    }

    /**
     * ¿Esta excepción es lo que se espera cuando adbd se va por un `reboot`? Sí: la conexión se cierra o se corta estando ya
     * conectados. No: no poder conectar, no estar autorizado, o un tiempo agotado (eso sería no haber recibido nada).
     */
    fun corteEsperado(t: Throwable): Boolean {
        val cadena = generateSequence(t) { e -> e.cause?.takeIf { it !== e } }.take(10).toList()
        if (cadena.any { it is AdbConnectException || it is AdbAuthException || it is AdbTimeoutException || it is ConnectException || it is SocketTimeoutException }) return false
        return cadena.any { it is AdbConnectionClosedException || it is EOFException || it is SocketException }
    }
}
