package co.davidvega.launcher.sistema

import java.util.Calendar
import java.util.TimeZone

/** Cálculos del mantenimiento nocturno sin dependencias de Android (se prueban en la JVM). */
internal object MantenimientoPlan {
    const val HORA = 4
    const val MINUTO = 30
    const val REINTENTO_MS = 30 * 60_000L
    private const val LIMITE_HORA = 6
    private const val LIMITE_MINUTO = 30

    private fun a(ahora: Long, hora: Int, minuto: Int, zona: TimeZone): Calendar = Calendar.getInstance(zona).apply {
        timeInMillis = ahora
        set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /**
     * La próxima 4:30 local estrictamente posterior a `ahora`. A las 4:29:30 sigue siendo la de hoy (antes se adelantaba a mañana
     * si faltaba menos de un minuto y se perdía la ejecución del día). Se avanza por día de calendario, no por 24 h, para el cambio de hora.
     */
    fun proxima(ahora: Long, zona: TimeZone = TimeZone.getDefault()): Long {
        val c = a(ahora, HORA, MINUTO, zona)
        if (c.timeInMillis <= ahora) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Momento del reintento (30 min después) o null si pasaría de las 6:30 del mismo día (nunca cruza la medianoche). */
    fun reintento(ahora: Long, zona: TimeZone = TimeZone.getDefault()): Long? {
        val t = ahora + REINTENTO_MS
        return if (t > a(ahora, LIMITE_HORA, LIMITE_MINUTO, zona).timeInMillis) null else t
    }

    /**
     * Presupuesto de tiempo de toda la ejecución: el receptor usa goAsync y Android mata el proceso (ANR de broadcast) pasado
     * ~60 s, así que cada paso recibe como mucho lo que queda de `totalMs`.
     */
    class Presupuesto(private val totalMs: Long, private val reloj: () -> Long = System::currentTimeMillis) {
        private val inicio = reloj()
        fun restante(): Long = (totalMs - (reloj() - inicio)).coerceAtLeast(0L)

        /** El menor entre `maximo` y lo que queda. */
        fun limite(maximo: Long): Long = minOf(maximo, restante())
    }
}
