package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class A_SeguirViendoTest {
    @Test fun relojConMinutosYHoras() {
        assertEquals("12:38", TextoRetomar.reloj(758_000))
        assertEquals("1:02:03", TextoRetomar.reloj(3_723_000))
        assertEquals("0:00", TextoRetomar.reloj(-5))
    }

    @Test fun minutoConYSinDuracion() {
        assertEquals("12:38 de 23:40", TextoRetomar.minuto(758_000, 1420))
        assertEquals("12:38", TextoRetomar.minuto(758_000, 0))
        assertNull(TextoRetomar.minuto(-1, 1420))
        assertEquals("23:40 de 23:40", TextoRetomar.minuto(9_999_000, 1420)) // se acota a la duración
    }

    @Test fun motivoConNumeroYMinuto() =
        assertEquals("Seguir viendo · E7 · 12:38 de 23:40 · ayer", TextoRetomar.motivo("E7", "Un nombre", 758_000, 1420, "ayer"))

    @Test fun motivoSinNumeroMuestraElNombreDelEpisodio() =
        assertEquals("Seguir viendo · La botica · hoy", TextoRetomar.motivo(null, "La botica", -1, 0, "hoy"))

    @Test fun motivoSinNadaDeEpisodio() =
        assertEquals("Seguir viendo · hoy", TextoRetomar.motivo(null, "", -1, 0, "hoy"))

    @Test fun nombreLargoSeRecorta() {
        val m = TextoRetomar.motivo(null, "x".repeat(80), -1, 0, "hoy")
        assertEquals(true, m.contains("…") && m.length < 60)
    }

    // Reproducciones: posición y registro
    @Test fun posicionEstimadaAvanzaSoloJugando() {
        assertEquals(15_000L, Reproducciones.posicionEstimada(10_000, true, 1_000, 6_000, 1f))
        assertEquals(10_000L, Reproducciones.posicionEstimada(10_000, false, 1_000, 6_000, 1f))
        assertEquals(20_000L, Reproducciones.posicionEstimada(10_000, true, 1_000, 6_000, 2f))
        assertEquals(-1L, Reproducciones.posicionEstimada(-1, true, 1_000, 6_000, 1f))
    }

    @Test fun acotarPosicion() {
        assertEquals(1_000_000L, Reproducciones.acotar(2_000_000, 1_000_000))
        assertEquals(5L, Reproducciones.acotar(5, 0))
        assertEquals(-1L, Reproducciones.acotar(-1, 1_000_000))
    }

    // AniList.numero: número del episodio
    @Test fun numeroDeEpisodio() {
        assertEquals(7, AniList.numero("E7 - Título"))
        assertEquals(7, AniList.numero("Episodio 7"))
        assertEquals(7, AniList.numero("Episode 7: algo"))
        assertEquals(7, AniList.numero("Capítulo 7"))
        assertEquals(7, AniList.numero("S1 E7"))
        assertEquals(12, AniList.numero("T2 E12"))
        assertEquals(7, AniList.numero("7"))
        assertNull(AniList.numero("El día que cambió todo"))
        assertNull(AniList.numero(null))
    }
}
