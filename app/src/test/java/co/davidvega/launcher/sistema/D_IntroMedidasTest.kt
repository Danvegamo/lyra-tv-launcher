package co.davidvega.launcher.sistema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class D_IntroMedidasTest {
    private val min = 60_000L

    @Test fun enFrioEsCompleta() = assertTrue(IntroVideo.completa(1, true, 100, 200))

    @Test fun conUsoRecienteEsCorta() = assertFalse(IntroVideo.completa(1, false, 100 * min, 110 * min))

    @Test fun tras30MinutosEsCompleta() {
        assertTrue(IntroVideo.completa(1, false, 100 * min, 131 * min))
        assertFalse(IntroVideo.completa(1, false, 100 * min, 130 * min))
    }

    @Test fun sinUsoPrevioEsCompleta() = assertTrue(IntroVideo.completa(1, false, 0L, 5 * min))

    @Test fun ajusteCompletaYNinguna() {
        assertTrue(IntroVideo.completa(0, false, 100 * min, 101 * min))
        assertFalse(IntroVideo.completa(2, true, 0L, 500 * min))
    }

    @Test fun medidasEscribenTipoTiempos() {
        val vistas = ArrayList<Pair<String, String>>()
        val antes = Medidas.salida
        Medidas.salida = { t, d -> vistas.add(t to d) }
        try {
            Medidas.homeACortina(120)
            Medidas.cortinaALauncher(4800)
            Medidas.primeraFila(50) // sin launcherVisible: no cuenta
            Medidas.launcherVisible(1000)
            Medidas.primeraFila(1350)
            Medidas.primeraFila(1500) // ya gastada
            Medidas.apertura("com.x", 900)
            Medidas.homeACortina(-5) // reloj raro: se ignora
        } finally { Medidas.salida = antes }
        assertEquals(
            listOf("home_a_cortina=120 ms", "cortina_a_launcher=4800 ms", "launcher_a_fila=350 ms", "apertura com.x=900 ms"),
            vistas.map { it.second },
        )
        assertTrue(vistas.all { it.first == "tiempos" })
    }
}
