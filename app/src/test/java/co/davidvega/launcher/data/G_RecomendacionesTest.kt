package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Paquete G (recomendaciones, 0.14): afinidad, caché de proveedores, reintentos, número de episodio y señal por fracción vista. */
class G_RecomendacionesTest {
    private val DIA = 86_400_000L

    private fun t(id: Int, servicio: String?, medio: String = "tv", idioma: String = "") =
        Titulo(id, medio, "t$id", "", null, null, "2020", 7.0, listOfNotNull(servicio), idioma = idioma)

    // ---- R1
    @Test fun fraccionVistaUsaLaPosicionYSinDuracionDevuelveNull() {
        assertEquals(0.5, Aprendizaje.fraccionVista(600_000, 1_200_000, 0)!!, 1e-9)
        assertEquals(0.25, Aprendizaje.fraccionVista(-1, 1_200_000, 300_000)!!, 1e-9) // sin posición: tiempo visto
        assertEquals(1.0, Aprendizaje.fraccionVista(9_999_999, 1_200_000, 0)!!, 0.0)
        assertNull(Aprendizaje.fraccionVista(1000, 0, 1000))
        assertEquals(2.5, Aprendizaje.nivelSesion(null, 11 * 60_000L), 0.0) // sin duración y con 10 minutos o más
        assertEquals(1.0, Aprendizaje.nivelSesion(null, 2 * 60_000L), 0.0)
    }

    @Test fun unTituloSumaUnaVezPorDia() {
        val zona = TimeZone.getTimeZone("America/Bogota")
        val manana = 1_800_000_000_000L // un instante cualquiera
        val hoy = Aprendizaje.diaDe(manana, zona)
        assertFalse(Aprendizaje.sumaHoy(hoy, hoy))
        assertTrue(Aprendizaje.sumaHoy(hoy, Aprendizaje.diaDe(manana + DIA, zona)))
        assertTrue(Aprendizaje.sumaHoy(-1L, hoy)) // nunca había sumado
        // El día cambia a medianoche local (UTC-5), no a medianoche UTC.
        assertEquals(hoy + 1, Aprendizaje.diaDe(manana + 24 * 3_600_000L, zona))
    }

    @Test fun filaDeAnimeSeReconoce() {
        assertTrue(Aprendizaje.esFilaAnime("gusto_anime", emptyList()))
        assertTrue(Aprendizaje.esFilaAnime("x", (1..5).map { t(it, "crunchyroll", idioma = "ja") }))
        assertFalse(Aprendizaje.esFilaAnime("x", (1..5).map { t(it, "hbomax", idioma = "en") }))
    }

    // ---- R2
    private fun registro(servicio: String, tipo: String, fraccion: Double?, ahora: Long, hace: Long = 0) = Afinidad.Registro(servicio, tipo, fraccion, 1_500_000, ahora - hace)

    @Test fun afinidadSubeElServicioQueVeDavidYAcotaElPeso() {
        val ahora = 1_800_000_000_000L
        val regs = (1..40).map { registro("crunchyroll", "anime", 0.95, ahora, hace = it * DIA) } + registro("hbomax", "serie", 0.3, ahora)
        val m = Afinidad.calcular(regs, ahora)
        assertTrue(m.porServicio["crunchyroll"]!! > m.porServicio["hbomax"]!!)
        assertTrue(m.porServicio["crunchyroll"]!! < Aprendizaje.K_TECHO)
        val anime = t(1, "crunchyroll", idioma = "ja")
        val serie = t(2, "hbomax")
        assertTrue(Afinidad.bono(m, anime) > Afinidad.bono(m, serie))
        assertTrue(Afinidad.bono(m, anime) <= Afinidad.BONO_MAXIMO)
        assertEquals(listOf("crunchyroll", "hbomax"), Afinidad.ordenarServicios(listOf("hbomax", "crunchyroll"), m))
        assertEquals(0.0, Afinidad.bono(Afinidad.VACIA, anime), 0.0)
    }

    @Test fun elAbandonoRestaAfinidad() {
        val ahora = 1_800_000_000_000L
        val m = Afinidad.calcular(listOf(registro("prime", "pelicula", 0.02, ahora), registro("prime", "pelicula", 0.02, ahora)), ahora)
        assertTrue(m.porServicio["prime"]!! < 0)
    }

    @Test fun limitarPorServicioDejaComoMuchoElSesentaPorCiento() {
        val fila = (1..20).map { t(it, if (it <= 17) "crunchyroll" else "hbomax") } // 17 de 20 del mismo servicio
        val l = Afinidad.limitarPorServicio(fila)
        assertEquals(12, l.count { it.servicios.first() == "crunchyroll" })
        assertEquals(3, l.count { it.servicios.first() == "hbomax" })
        assertEquals(l, l.filter { it in fila }) // mismo orden relativo
        // Si recortar dejara menos de 6 títulos, la fila queda intacta (no hay otro servicio de dónde sacar).
        val soloUno = (1..8).map { t(it, "prime") }
        assertEquals(soloUno, Afinidad.limitarPorServicio(soloUno))
    }

    @Test fun parsearReproduccionesSaltaLoMalo() {
        val l = listOf(
            """{"servicio":"crunchyroll","medio":"tv","visto_s":1300,"duracion_s":1400,"posicion_ms":1300000,"fin":1}""",
            "no es json", """{"servicio":"prime","sin_titulo":true}""", """{"servicio":"hbomax","visto_s":100,"fin":2}""",
        )
        val r = Afinidad.parsear(l)
        assertEquals(listOf("crunchyroll", "hbomax"), r.map { it.servicio })
        assertEquals("anime", r[0].tipo)
        assertNull(r[1].fraccion)
    }

    // ---- R3
    @Test fun soloTimeoutYCincoCientosSeReintentan() {
        assertTrue(Tmdb.reintentable(0, java.net.SocketTimeoutException()))
        assertTrue(Tmdb.reintentable(503, null))
        assertTrue(Tmdb.reintentable(500, null))
        assertFalse(Tmdb.reintentable(404, null))
        assertFalse(Tmdb.reintentable(401, null))
        assertFalse(Tmdb.reintentable(0, java.net.UnknownHostException()))
        assertEquals(listOf(1000L, 3000L), Tmdb.ESPERAS_MS.toList())
    }

    @Test fun cacheDeProveedoresVale24HorasYUsaLaViejaSiFallaLaRed() {
        val c = CacheProveedores()
        val ahora = 1_800_000_000_000L
        c.poner("tv:1", "a,b", listOf("netflix"), ahora)
        assertEquals(listOf("netflix"), c.obtener("tv:1", "a,b", ahora + 23 * 3_600_000L))
        assertNull(c.obtener("tv:1", "a,b", ahora + 25 * 3_600_000L))
        assertEquals(listOf("netflix"), c.obtener("tv:1", "a,b", ahora + 25 * 3_600_000L, aceptarVieja = true))
        assertNull(c.obtener("tv:1", "otro perfil", ahora)) // cambió el perfil de proveedores
        // «No está en tus servicios» (lista vacía) también se recuerda.
        c.poner("tv:2", "a,b", emptyList(), ahora)
        assertEquals(emptyList<String>(), c.obtener("tv:2", "a,b", ahora))
    }

    @Test fun cacheDeProveedoresIdaYVueltaYSoloEscribeSiCambio() {
        val c = CacheProveedores()
        assertNull(c.serializarSiCambio())
        c.poner("movie:9", "h", listOf("prime", "hbomax"), 1000)
        val texto = c.serializarSiCambio()!!
        assertNull(c.serializarSiCambio())
        val d = CacheProveedores()
        d.cargar(texto)
        assertEquals(listOf("prime", "hbomax"), d.obtener("movie:9", "h", 2000))
        d.cargar("""{"x":{"t":"mala"}}""") // entrada mala: se salta sin lanzar
    }

    // ---- R4
    @Test fun elFiltroSeAnotaUnaVezPorTituloYMotivo() {
        val u = Diagnostico.Unicos()
        assertTrue(u.nuevo("movie:530254 cert:JP R18+"))
        repeat(7) { assertFalse(u.nuevo("movie:530254 cert:JP R18+")) }
        assertEquals(8, u.veces("movie:530254 cert:JP R18+"))
        assertTrue(u.nuevo("movie:1 adult"))
    }

    // ---- Episodios (Crunchyroll)
    @Test fun numeroDeEpisodioPorNombre() {
        val eps = listOf(1 to "El comienzo", 2 to "Un viaje de mil millas", 3 to "¡Hasta pronto!")
        assertEquals(2, Episodios.numeroPorNombre("Un viaje de mil millas", eps))
        assertEquals(3, Episodios.numeroPorNombre("hasta pronto", eps)) // sin signos ni mayúsculas
        assertNull(Episodios.numeroPorNombre("Otro nombre", eps))
        assertNull(Episodios.numeroPorNombre("", eps))
        // Empate: el mismo nombre en dos episodios se descarta.
        assertNull(Episodios.numeroPorNombre("Capítulo final", eps + listOf(12 to "Capitulo final", 24 to "Capítulo final")))
    }
}
