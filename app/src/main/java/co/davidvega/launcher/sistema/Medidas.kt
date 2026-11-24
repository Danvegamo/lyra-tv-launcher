package co.davidvega.launcher.sistema

import android.os.SystemClock
import co.davidvega.launcher.data.Diagnostico

/**
 * Marcas de tiempo del arranque y de las aperturas, guardadas en diagnostico.jsonl con tipo «tiempos» (el archivo propio sobrevive; logcat no).
 * Cada marca es una línea `<nombre>=<ms> ms`. Sin esto cualquier recorte de la entrada sería a ciegas (plan 0.14, E1).
 *
 * Marcas de arranque: `home_a_cortina` (evento de Home a cortina puesta), `cortina_a_launcher` (cortina puesta a launcher delante)
 * y `launcher_a_fila` (launcher delante a primera fila pintada). Las dos últimas se piden con `launcherVisible()` y `primeraFila()`.
 * Aperturas de apps: `apertura(paquete, ms)` (lo llama el paquete A desde Abrir.kt; ver NOTAS-D.md).
 */
object Medidas {
    const val TIPO = "tiempos"

    /** Dónde se escribe; las pruebas lo sustituyen. */
    @Volatile internal var salida: (String, String) -> Unit = { tipo, detalle -> Diagnostico.registrar(tipo, detalle) }

    /** Instante en que el launcher quedó delante y aún no se ha pintado la primera fila; 0 = nada pendiente. */
    @Volatile private var launcherDesde = 0L

    internal fun linea(marca: String, ms: Long) = "$marca=$ms ms"

    private fun marcar(marca: String, ms: Long) {
        if (ms < 0) return // reloj raro: mejor no anotar que anotar basura
        runCatching { salida(TIPO, linea(marca, ms)) }
    }

    fun homeACortina(ms: Long) = marcar("home_a_cortina", ms)

    fun cortinaALauncher(ms: Long) = marcar("cortina_a_launcher", ms)

    /** MainActivity.onResume: el launcher está delante; arranca el cronómetro hasta la primera fila. */
    fun launcherVisible(ahora: Long = SystemClock.elapsedRealtime()) { launcherDesde = ahora }

    /** Primera fila pintada (o ya presente) tras `launcherVisible()`; solo cuenta una vez por vuelta al launcher. */
    fun primeraFila(ahora: Long = SystemClock.elapsedRealtime()) {
        val desde = launcherDesde
        if (desde == 0L) return
        launcherDesde = 0L
        marcar("launcher_a_fila", ahora - desde)
    }

    /** Toque a actividad de la app abierta, en ms. La llama Abrir.kt (paquete A). */
    fun apertura(paquete: String, ms: Long) = marcar("apertura $paquete", ms)
}
