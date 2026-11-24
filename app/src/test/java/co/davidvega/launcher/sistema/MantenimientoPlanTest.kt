package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class MantenimientoPlanTest {
    private val bogota = TimeZone.getTimeZone("America/Bogota")
    private val z = ZoneId.of("America/Bogota")

    private fun ms(d: Int, h: Int, m: Int, s: Int = 0, mes: Int = 11) =
        ZonedDateTime.of(2026, mes, d, h, m, s, 0, z).toInstant().toEpochMilli()

    @Test fun antesDeLas430EsHoy() = assertEquals(ms(21, 4, 30), MantenimientoPlan.proxima(ms(21, 1, 0), bogota))

    @Test fun medianocheEsHoyAlas430() = assertEquals(ms(21, 4, 30), MantenimientoPlan.proxima(ms(21, 0, 0), bogota))

    @Test fun justoAntesDeLas430SigueSiendoHoy() {
        // Antes (BAJA): entre 4:29:00 y 4:30:00 se adelantaba a mañana y se perdía la ejecución del día.
        assertEquals(ms(21, 4, 30), MantenimientoPlan.proxima(ms(21, 4, 29, 0), bogota))
        assertEquals(ms(21, 4, 30), MantenimientoPlan.proxima(ms(21, 4, 29, 30), bogota))
        assertEquals(ms(21, 4, 30), MantenimientoPlan.proxima(ms(21, 4, 29, 59), bogota))
    }

    @Test fun apartirDeLas430EsManana() {
        assertEquals(ms(22, 4, 30), MantenimientoPlan.proxima(ms(21, 4, 30, 0), bogota)) // la alarma acaba de sonar
        assertEquals(ms(22, 4, 30), MantenimientoPlan.proxima(ms(21, 4, 30, 1), bogota))
        assertEquals(ms(22, 4, 30), MantenimientoPlan.proxima(ms(21, 6, 30), bogota))
        assertEquals(ms(22, 4, 30), MantenimientoPlan.proxima(ms(21, 23, 59, 59), bogota))
    }

    @Test fun cambioDeMesYAnio() {
        assertEquals(ms(1, 4, 30, mes = 12), MantenimientoPlan.proxima(ms(30, 12, 0), bogota))
        assertEquals(ZonedDateTime.of(2027, 1, 1, 4, 30, 0, 0, z).toInstant().toEpochMilli(),
            MantenimientoPlan.proxima(ZonedDateTime.of(2026, 12, 31, 20, 0, 0, 0, z).toInstant().toEpochMilli(), bogota))
    }

    @Test fun cambioDeHoraSeAvanzaPorDiaDeCalendario() {
        val ny = ZoneId.of("America/New_York")
        val tz = TimeZone.getTimeZone(ny)
        // La noche del 7 al 8 de marzo de 2026 se adelanta la hora (2:00 -> 3:00): la 4:30 del 8 sigue siendo 4:30 local.
        val esperado = ZonedDateTime.of(2026, 3, 8, 4, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(esperado, MantenimientoPlan.proxima(ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, ny).toInstant().toEpochMilli(), tz))
        val manana = ZonedDateTime.of(2026, 3, 9, 4, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(manana, MantenimientoPlan.proxima(ZonedDateTime.of(2026, 3, 8, 5, 0, 0, 0, ny).toInstant().toEpochMilli(), tz))
    }

    @Test fun reintentoDe30MinutosDentroDeLaVentana() {
        assertEquals(ms(21, 5, 0, 5), MantenimientoPlan.reintento(ms(21, 4, 30, 5), bogota))
        assertEquals(ms(21, 6, 30), MantenimientoPlan.reintento(ms(21, 6, 0), bogota)) // justo a las 6:30: permitido
        assertEquals(ms(21, 0, 40), MantenimientoPlan.reintento(ms(21, 0, 10), bogota))
    }

    @Test fun reintentoNoPasaDeLas630() {
        assertNull(MantenimientoPlan.reintento(ms(21, 6, 1), bogota))
        assertNull(MantenimientoPlan.reintento(ms(21, 6, 10), bogota))
        assertNull(MantenimientoPlan.reintento(ms(21, 12, 0), bogota))
    }

    @Test fun reintentoNuncaCruzaLaMedianoche() {
        assertNull(MantenimientoPlan.reintento(ms(21, 23, 45), bogota)) // las 00:15 de mañana no valen
        assertNull(MantenimientoPlan.reintento(ms(21, 23, 59, 59), bogota))
    }

    @Test fun presupuestoReparteElTiempoYNoSeDesborda() {
        var t = 1_000L
        val p = MantenimientoPlan.Presupuesto(50_000L) { t }
        assertEquals(10_000L, p.limite(10_000L))
        t += 40_000L // pasaron 40 s
        assertEquals(10_000L, p.restante())
        assertEquals(10_000L, p.limite(25_000L)) // trim-caches no puede usar más de lo que queda
        t += 20_000L
        assertEquals(0L, p.restante())
        assertEquals(0L, p.limite(8_000L))
    }
}
