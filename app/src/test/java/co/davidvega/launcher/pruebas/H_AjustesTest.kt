package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.Afinidad
import co.davidvega.launcher.data.Aprendizaje
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.data.Visionado
import io.kotest.property.arbitrary.arbitrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ajustes 0.15 al recomendador, calibrados con un mes de uso real: visionado acumulado, abandono,
 * géneros amplios, piso negativo, afinidad proporcional y exclusión de tráileres. Propiedades con la misma infraestructura que F2.
 */
class H_AjustesTest {
    private val H = 3_600_000L
    private val DIA = 86_400_000L

    private fun nivel(f: Double) = Visionado.nivelVisionado(f, 0)

    // ---- Visionado acumulado
    // Regla: partir una vista en varias sesiones (mismo episodio, dentro de 48 h) suma exactamente el nivel de la fracción total, en cualquier orden y reparto.
    @Test fun lasSesionesDeUnaVistaSumanElNivelDeLaFraccionTotal() {
        val arb = arbitrary { rs -> val r = rs.random; (1..r.nextInt(1, 6)).map { r.nextDouble(0.0, 0.5) to r.nextLong(0, 10 * H) } }
        F_Base.propiedad("vis-acumulado", arb) { sesiones ->
            var e = Visionado.NINGUNO; var t = 1_800_000_000_000L; var suma = 0.0; var total = 0.0
            for ((f, avance) in sesiones) {
                t += avance
                val r = Visionado.aplicar(e, null, f, 0, t, Aprendizaje.diaDe(t))
                e = r.estado; suma += r.delta; total = minOf(1.0, total + f)
            }
            assertEquals(nivel(total), suma, 1e-9)
        }
    }

    @Test fun elConjuro2FueUnaVistaNoTresPruebas() {
        // 22 % + 52 % + 21 % del 9 y el 10 de nov: antes +1 y +1 (primera de cada día); ahora una sola vista casi completa (95 %: +4).
        var e = Visionado.NINGUNO; var suma = 0.0
        for ((f, t) in listOf(0.22 to 0L, 0.52 to 20 * H, 0.21 to 21 * H)) {
            val r = Visionado.aplicar(e, null, f, 0, 1_800_000_000_000L + t, Aprendizaje.diaDe(1_800_000_000_000L + t)); e = r.estado; suma += r.delta
        }
        assertEquals(4.0, suma, 1e-9)
        assertTrue(e.terminadoT >= 0)
    }

    @Test fun pasadas48HorasEmpiezaOtraVistaYOtroEpisodioTambien() {
        val t0 = 1_800_000_000_000L
        val a = Visionado.aplicar(Visionado.NINGUNO, "ep1", 0.9, 0, t0, 1)
        val mismoEp = Visionado.aplicar(a.estado, "ep1", 0.05, 0, t0 + 49 * H, 3)
        assertEquals(nivel(0.05), mismoEp.delta, 1e-9) // 49 h después: vista nueva (abandono)
        val otroEp = Visionado.aplicar(a.estado, "ep2", 0.5, 0, t0 + H, 1)
        assertEquals(minOf(nivel(0.5), Visionado.TOPE_DIA - a.delta).coerceAtLeast(0.0), otroEp.delta, 1e-9) // otro episodio, mismo día: tope diario
    }

    // Regla: un título nunca suma más de TOPE_DIA positivo en un día (el maratón de Solo Leveling no infla los pesos).
    @Test fun unTituloNoSumaMasDelTopeAlDia() {
        val arb = arbitrary { rs -> val r = rs.random; (1..r.nextInt(1, 30)).map { r.nextDouble(0.0, 1.0) to r.nextInt(0, 6) } }
        F_Base.propiedad("vis-tope-dia", arb) { sesiones ->
            var e = Visionado.NINGUNO; var t = 1_800_000_000_000L; var suma = 0.0
            for ((f, ep) in sesiones) { t += H / 4; val r = Visionado.aplicar(e, "ep$ep", f, 0, t, 1); e = r.estado; suma += r.delta }
            assertTrue("suma $suma", suma <= Visionado.TOPE_DIA + 1e-9)
        }
    }

    // ---- Abandono
    @Test fun abandonarAnulaLaIntencionDeAbrir() {
        // «Mother Mary»: ficha +1, «Ver en» +3 y 1 % visto. Antes quedaba en 3,5 (con la regla de la 0.14); ahora en 1,5.
        assertEquals(-2.5, nivel(0.01), 0.0)
        assertEquals(1.5, 1.0 + 3.0 + nivel(0.01), 1e-9)
        assertEquals(Aprendizaje.nivelSesion(0.10, 0), nivel(0.10), 0.0) // desde 10 % ya no se anula
    }

    @Test fun elNivelDeVisionadoEsCrecienteEnLaFraccion() {
        val arb = arbitrary { rs -> rs.random.nextDouble(0.0, 1.0) to rs.random.nextDouble(0.0, 1.0) }
        F_Base.propiedad("vis-nivel-monotono", arb) { (a, b) -> assertTrue(nivel(minOf(a, b)) <= nivel(maxOf(a, b))) }
    }

    // ---- Géneros amplios y piso
    @Test fun rechazarUnaTelenovelaNoHundeElDrama() {
        assertEquals(-0.25, Visionado.deltaGenero(18, -1.0), 1e-12)
        assertEquals(-1.0, Visionado.deltaGenero(10766, -1.0), 0.0) // telenovela: específico, entero
        assertEquals(0.0, Visionado.deltaGenero(18, -1.0, repaso = true), 0.0) // el repaso semanal no toca los amplios
        assertEquals(-1.0, Visionado.deltaGenero(10766, -1.0, repaso = true), 0.0)
        assertEquals(3.0, Visionado.deltaGenero(18, 3.0), 0.0)
    }

    @Test fun sieteTitulosRechazadosYaNoVuelvenNegativoElDrama() {
        // Caso real del 22 nov: drama en 5,8 (migrado de 207,7) y repaso de 7 títulos. Antes: 5,8 - 7 = -1,85. Ahora: sin cambio.
        val ahora = 1_800_000_000_000L
        var raw = Aprendizaje.descomprimir(Aprendizaje.techo(207.7))
        repeat(7) { raw = Aprendizaje.acumular(raw, ahora, Visionado.deltaGenero(18, -1.0, repaso = true), ahora) }
        assertTrue(Visionado.acotarAsimetrico(raw) > 5.0)
        var viejo = Aprendizaje.techo(207.7)
        repeat(7) { viejo = Aprendizaje.techo(viejo - 1.0) }
        assertTrue(viejo < 0)
    }

    // Regla: con el acumulado sin comprimir, una señal positiva nunca baja el peso leído (en la versión 2, +1 sobre 5,8 lo dejaba en 3,2) y una negativa nunca lo sube.
    @Test fun unaSenalPositivaNuncaBajaElPesoLeido() {
        val ahora = 1_800_000_000_000L
        assertTrue(Aprendizaje.techo(Aprendizaje.techo(207.7) + 1.0) < Aprendizaje.techo(207.7)) // el defecto de la 0.14
        val arb = arbitrary { rs -> val r = rs.random; Triple(r.nextDouble(-500.0, 500.0), r.nextDouble(0.0, 4.0), r.nextLong(0, 60 * DIA)) }
        F_Base.propiedad("apr-acumulado-monotono", arb) { (v, d, pasado) ->
            val antes = Visionado.acotarAsimetrico(Aprendizaje.decae(v, ahora - pasado, ahora))
            assertTrue(Visionado.acotarAsimetrico(Aprendizaje.acumular(v, ahora - pasado, d, ahora)) >= antes - 1e-12)
            assertTrue(Visionado.acotarAsimetrico(Aprendizaje.acumular(v, ahora - pasado, -d, ahora)) <= antes + 1e-12)
        }
    }

    // Regla: descomprimir es la inversa del techo (migración 2 a 3) y el acumulado no pasa de ACUMULADO_MAX ni da NaN.
    @Test fun descomprimirEsLaInversaDelTecho() {
        val arb = arbitrary { rs -> rs.random.nextDouble(-500.0, 500.0) }
        F_Base.propiedad("apr-descomprimir", arb) { w -> assertEquals(w, Aprendizaje.descomprimir(Aprendizaje.techo(w)), 1e-6 * (1 + kotlin.math.abs(w) * kotlin.math.abs(w))) }
        assertEquals(Aprendizaje.ACUMULADO_MAX, Aprendizaje.descomprimir(6.0), 0.0)
        assertEquals(0.0, Aprendizaje.descomprimir(Double.NaN), 0.0)
        assertEquals(Aprendizaje.ACUMULADO_MAX, Aprendizaje.acumular(Aprendizaje.ACUMULADO_MAX, 0, 1e9, 0), 0.0)
        assertEquals(0.0, Aprendizaje.acumular(Double.NaN, 0, 1.0, 0), 1.0) // NaN previo no contamina: queda en el delta
    }

    @Test fun repararAmplioNuncaBajaElAcumuladoYRecuperaLoPerdido() {
        // Drama: antes de migrar 207,7 (t1); tras la migración y el repaso quedó en -2,7 (t2, 15 h después): se recupera lo de antes, decaído 15 h.
        val t1 = 1_800_000_000_000L; val t2 = t1 + 15 * H
        val r = Aprendizaje.repararAmplio(-2.7, t2, 207.7, t1)
        assertEquals(207.7 * Math.pow(0.5, 15.0 * H / (30.0 * DIA)), r, 1e-9)
        assertTrue(Visionado.acotarAsimetrico(r) > 5.0)
        assertEquals(5.0, Aprendizaje.repararAmplio(5.0, t2, 1.0, t1), 0.0) // si lo actual ya es mayor, no cambia
    }

    // Regla: el acotado asimétrico es creciente, nunca NaN, queda en (-3, 6) y coincide con el techo en lo positivo.
    @Test fun acotarAsimetricoEsCrecienteYTieneElPisoEnMenos3() {
        val arb = arbitrary { rs -> rs.random.nextDouble(-1e6, 1e6) to rs.random.nextDouble(-1e6, 1e6) }
        F_Base.propiedad("vis-asimetrico", arb) { (a, b) ->
            if (a <= b) assertTrue(Visionado.acotarAsimetrico(a) <= Visionado.acotarAsimetrico(b))
            assertTrue(Visionado.acotarAsimetrico(a) > -Visionado.K_PISO && Visionado.acotarAsimetrico(a) < Aprendizaje.K_TECHO)
            if (a >= 0) assertEquals(Aprendizaje.techo(a), Visionado.acotarAsimetrico(a), 0.0)
        }
        assertEquals(0.0, Visionado.acotarAsimetrico(Double.NaN), 0.0)
        assertEquals(-Visionado.K_PISO, Visionado.acotarAsimetrico(Double.NEGATIVE_INFINITY), 0.0)
    }

    // ---- Clips, duración
    @Test fun lostrailersYClipsNoSonVistas() {
        assertTrue(Visionado.esClip("Never Let Go - Trailer", 0))
        assertTrue(Visionado.esClip("Avance de la temporada", 1500))
        assertTrue(Visionado.esClip("Cualquiera", 150))
        assertTrue(!Visionado.esClip("Frieren: Tras finalizar el viaje", 1559))
        assertTrue(!Visionado.esClip("Cómo funciona el universo", 0))
        val lineas = listOf(
            """{"servicio":"hbomax","medio":"tv","titulo":"Never Let Go - Trailer","visto_s":150,"duracion_s":150,"fin":1}""",
            """{"servicio":"hbomax","medio":"movie","titulo":"Matilda","visto_s":5500,"duracion_s":5700,"tmdb_id":10830,"fin":1}""",
        )
        assertEquals(1, Afinidad.parsear(lineas).size)
    }

    @Test fun elPesoDeDuracionEstaAcotado() {
        val arb = arbitrary { rs -> rs.random.nextLong(-10, 100_000_000) }
        F_Base.propiedad("vis-duracion", arb) { d -> assertTrue(Visionado.pesoDuracion(d) in 0.5..2.0 || d <= 0) }
        assertEquals(1.0, Visionado.pesoDuracion(0), 0.0)
        assertTrue(Visionado.pesoDuracion(24 * 60_000L) < Visionado.pesoDuracion(120 * 60_000L))
    }

    // ---- Afinidad proporcional
    private fun reg(servicio: String, tipo: String, f: Double, ahora: Long, hace: Long = 0, clave: String = "", dur: Long = 1_500_000) =
        Afinidad.Registro(servicio, tipo, f, (f * dur).toLong(), ahora - hace, clave, dur)

    @Test fun lasSesionesDeUnaVistaCuentanComoUna() {
        val ahora = 1_800_000_000_000L + 4 * H // mediodía UTC: las tres sesiones caen el mismo día local en casi cualquier zona
        val min = 60_000L
        val partida = Afinidad.calcular(listOf(reg("hbomax", "pelicula", 0.22, ahora, 30 * min, "movie:259693:"), reg("hbomax", "pelicula", 0.52, ahora, 20 * min, "movie:259693:"), reg("hbomax", "pelicula", 0.21, ahora, 10 * min, "movie:259693:")), ahora)
        val entera = Afinidad.calcular(listOf(reg("hbomax", "pelicula", 0.95, ahora, 10 * min, "movie:259693:")), ahora)
        assertEquals(entera.porServicio["hbomax"]!!, partida.porServicio["hbomax"]!!, 0.01) // difieren solo por el decaimiento de minutos
        assertTrue(partida.porServicio["hbomax"]!! > 0)
    }

    @Test fun laAfinidadSigueLasProporcionesYNoSeSatura() {
        val ahora = 1_800_000_000_000L
        // 40 vistas completas de anime en 40 días frente a 20 de series: antes ambos servicios quedaban en ~5,5 (techo); ahora el segundo vale la mitad.
        val a = (1..40).map { reg("crunchyroll", "anime", 1.0, ahora, 0, "tv:$it:e") }
        val b = (1..20).map { reg("hbomax", "serie", 1.0, ahora, 0, "tv:${100 + it}:e") }
        val m = Afinidad.calcular(a + b, ahora)
        assertEquals(Afinidad.TOPE_AFINIDAD, m.porServicio["crunchyroll"]!!, 1e-9)
        assertEquals(Afinidad.TOPE_AFINIDAD / 2, m.porServicio["hbomax"]!!, 1e-9)
    }

    // Regla: la afinidad nunca pasa de TOPE_AFINIDAD ni baja de -3; con pocas vistas no llega al tope.
    @Test fun laAfinidadQuedaAcotada() {
        val ahora = 1_800_000_000_000L
        val arb = arbitrary { rs -> val r = rs.random; (1..r.nextInt(1, 60)).map { reg(listOf("crunchyroll", "hbomax", "prime")[r.nextInt(3)], "serie", r.nextDouble(0.0, 1.0), ahora, r.nextLong(0, 90 * DIA), "tv:${r.nextInt(1, 20)}:e${r.nextInt(3)}", r.nextLong(0, 9_000_000)) } }
        F_Base.propiedad("afi-acotada", arb) { regs ->
            val m = Afinidad.calcular(regs, ahora)
            (m.porServicio.values + m.porServicioTipo.values).forEach { assertTrue("$it", it.isFinite() && it >= -Visionado.K_PISO - 1e-9 && it <= Afinidad.TOPE_AFINIDAD + 1e-9) }
        }
        val pocas = Afinidad.calcular(listOf(reg("prime", "pelicula", 1.0, ahora)), ahora)
        assertTrue(pocas.porServicio["prime"]!! < Afinidad.TOPE_AFINIDAD / 2)
    }

    @Test fun laRecienciaPesaMasQueLoViejo() {
        val ahora = 1_800_000_000_000L
        val m = Afinidad.calcular(listOf(reg("crunchyroll", "anime", 1.0, ahora, 2 * DIA, "tv:1:e"), reg("hbomax", "serie", 1.0, ahora, 40 * DIA, "tv:2:e")), ahora)
        assertTrue(m.porServicio["crunchyroll"]!! > m.porServicio["hbomax"]!! * 2)
    }

    @Test fun elServicioMasVistoGanaAunqueOtroTengaMasClics() {
        // Prime con peso de clics alto (5,8) pero casi sin reproducción frente a HBO con peso menor y mucha reproducción real: HBO debe quedar por delante.
        val ahora = 1_800_000_000_000L
        val regs = (1..20).map { reg("hbomax", "pelicula", 0.95, ahora, 0, "movie:$it:", 6_000_000) } + reg("prime", "pelicula", 0.2, ahora, 0, "movie:99:", 6_000_000)
        val m = Afinidad.calcular(regs, ahora)
        fun t(s: String) = Titulo(1, "movie", "x", "", null, null, "2020", 7.0, listOf(s))
        val pesos = mapOf("s:prime" to 5.8, "s:hbomax" to 3.0)
        val hbo = Aprendizaje.puntajeDe(t("hbomax"), { pesos[it] ?: 0.0 }, true, 0, Afinidad.bono(m, t("hbomax")))
        val prime = Aprendizaje.puntajeDe(t("prime"), { pesos[it] ?: 0.0 }, true, 0, Afinidad.bono(m, t("prime")))
        assertTrue("hbo $hbo prime $prime", hbo > prime)
        assertTrue(Afinidad.bono(m, t("hbomax")) <= Afinidad.BONO_MAXIMO)
    }

    // ---- Puntaje puro
    @Test fun puntajeDeCoincideConLaFormulaDocumentada() {
        val t = Titulo(1, "tv", "x", "", null, null, "2024", 8.0, listOf("crunchyroll"), generos = listOf(16, 18), idioma = "ja")
        val pesos = mapOf("g:16" to 4.0, "g:18" to 2.0, "o:ja" to 3.0, "d:2020" to 1.0, "s:crunchyroll" to 2.0)
        val p = Aprendizaje.puntajeDe(t, { pesos[it] ?: 0.0 }, abierto = false, impresiones = 9, bono = 0.5)
        assertEquals(3.0 + 0.7 * 3.0 + 0.4 * 1.0 + Aprendizaje.COEF_SERVICIO * 2.0 + 0.15 * 8.0 - 1.0 + 0.5, p, 1e-12)
        assertEquals(p + 1.0, Aprendizaje.puntajeDe(t, { pesos[it] ?: 0.0 }, abierto = true, impresiones = 9, bono = 0.5), 1e-12)
    }
}
