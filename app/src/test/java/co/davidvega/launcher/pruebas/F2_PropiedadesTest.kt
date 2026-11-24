package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.Aprendizaje
import co.davidvega.launcher.data.Discover
import co.davidvega.launcher.data.FilaSpec
import co.davidvega.launcher.data.RecomendadorPuro
import co.davidvega.launcher.data.RecomendadorPuro.Cand
import co.davidvega.launcher.data.Reproducciones
import co.davidvega.launcher.data.TextoRetomar
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.data.Vistos
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.random.Random

/**
 * Paquete F2: propiedades sobre las funciones puras extraídas por G, A y el cierre (plan 0.14, secciones 6.1 y 6.3).
 * Misma infraestructura que F_Base: semilla fija en el perfil normal, `-Plargo` para la tanda larga.
 */
class F2_PropiedadesTest {
    // ---------------------------------------------------------------- 6.1 Recomendador (RecomendadorPuro)

    private fun json(r: Random, nota: Double, votos: Int, imagen: Boolean, generos: List<Int>): JSONObject = JSONObject()
        .put("id", r.nextInt(1, 1000)).put("vote_average", nota).put("vote_count", votos)
        .put("poster_path", if (imagen) "/p.jpg" else JSONObject.NULL).put("genre_ids", JSONArray(generos))

    private fun spec(r: Random, filtroGeneros: Boolean = r.nextBoolean()) = FilaSpec(
        "f", "F", "discover", listOf("tv", "movie", "ambos")[r.nextInt(3)], emptyList(),
        Discover(listOf(18, 28, 16).take(r.nextInt(0, 4)), false, emptyList(), null, emptyList(), emptyList(), "popularity.desc", 100, r.nextInt(50, 80) / 10.0, null, 1),
        r.nextBoolean(), r.nextInt(1, 25), 1, filtroGeneros,
    )

    private class Entrada(val cands: LinkedHashMap<String, Cand>, val spec: FilaSpec, val excluir: Set<String>, val notaGlobal: Double)

    private val ENTRADAS: Arb<Entrada> = arbitrary { rs ->
        val r = rs.random
        val cands = LinkedHashMap<String, Cand>()
        repeat(r.nextInt(0, 60)) {
            val medio = if (r.nextBoolean()) "tv" else "movie"
            val j = json(r, r.nextInt(0, 101) / 10.0, r.nextInt(0, 80), r.nextInt(5) != 0, (1..r.nextInt(0, 4)).map { listOf(18, 28, 16, 35, 9648, 10759)[r.nextInt(6)] })
            // puntajes con repeticiones a propósito (empates)
            cands[medio + ":" + r.nextInt(1, 200)] = Cand(j, medio, r.nextInt(0, 10) / 2.0, null)
        }
        Entrada(cands, spec(r), (1..r.nextInt(0, 4)).map { "tv:" + r.nextInt(1, 200) }.toSet(), r.nextInt(50, 75) / 10.0)
    }

    // Regla (invariante): todo candidato devuelto cumple nota >= min(minNota, notaMinima), 30 votos y alguna imagen; no son más de min(max*2, 40).
    @Test fun filtrarCumpleLosMinimos() {
        F_Base.propiedad("rec-filtrar-minimos", ENTRADAS) { e ->
            val salida = RecomendadorPuro.filtrarCandidatos(e.cands, e.spec, e.excluir, e.notaGlobal)
            val minNota = minOf(e.spec.discover?.minNota ?: e.notaGlobal, e.notaGlobal)
            assertTrue(salida.size <= minOf(e.spec.max * 2, 40))
            assertEquals(salida.size, salida.map { it.first }.distinct().size)
            for ((k, c) in salida) {
                assertTrue(k !in e.excluir)
                assertTrue(c.json.optDouble("vote_average") >= minNota)
                assertTrue(c.json.optInt("vote_count") >= RecomendadorPuro.VOTOS_MINIMOS)
                assertTrue(c.json.optString("poster_path").let { it.isNotBlank() && it != "null" })
            }
        }
    }

    // Regla (metamórfica): subir el puntaje de un candidato que ya salía nunca lo baja de posición ni lo saca de la lista.
    @Test fun subirPuntajeNoBajaDePosicion() {
        F_Base.propiedad("rec-subir-puntaje", ENTRADAS) { e ->
            val antes = RecomendadorPuro.filtrarCandidatos(e.cands, e.spec, e.excluir, e.notaGlobal)
            if (antes.isEmpty()) return@propiedad
            val (clave, cand) = antes[antes.size / 2]
            val posAntes = antes.indexOfFirst { it.first == clave }
            val copia = LinkedHashMap<String, Cand>()
            e.cands.forEach { (k, c) -> copia[k] = if (k == clave) Cand(c.json, c.medio, c.puntaje + 3.0, c.motivo) else Cand(c.json, c.medio, c.puntaje, c.motivo) }
            val despues = RecomendadorPuro.filtrarCandidatos(copia, e.spec, e.excluir, e.notaGlobal)
            val posDespues = despues.indexOfFirst { it.first == clave }
            assertTrue("$clave salió de la lista (${cand.puntaje})", posDespues >= 0)
            assertTrue("$clave bajó de $posAntes a $posDespues", posDespues <= posAntes)
        }
    }

    // Regla (metamórfica): permutar el orden de entrada no cambia el conjunto (ni el orden) de resultados. Con empates en el corte, el desempate por clave lo garantiza.
    @Test fun permutarEntradaNoCambiaElConjunto() {
        F_Base.propiedad("rec-permutar", ENTRADAS) { e ->
            val normal = RecomendadorPuro.filtrarCandidatos(e.cands, e.spec, e.excluir, e.notaGlobal).map { it.first }
            val barajada = LinkedHashMap<String, Cand>().also { m -> e.cands.entries.shuffled(Random(e.cands.size)).forEach { m[it.key] = it.value } }
            val otra = RecomendadorPuro.filtrarCandidatos(barajada, e.spec, e.excluir, e.notaGlobal).map { it.first }
            assertEquals(normal, otra)
        }
    }

    private fun titulo(id: Int) = Titulo(id, "tv", "t$id", "", null, null, "2020", 7.0, emptyList())

    // Regla (invariante e idempotencia): la fila final tiene como mucho spec.max títulos, sin repetir `medio:id`; quitar los usados dos veces es igual que una.
    @Test fun armarFilaYUsadosIdempotente() {
        val arb = arbitrary { rs ->
            val r = rs.random
            val lista = (1..r.nextInt(0, 50)).map { val id = r.nextInt(1, 30); "tv:$id" to titulo(id) }
            Triple(lista, spec(r), (1..r.nextInt(0, 20)).map { "tv:" + r.nextInt(1, 30) }.toSet())
        }
        F_Base.propiedad("rec-armar-fila", arb) { (lista, spec, usados) ->
            val fila = RecomendadorPuro.armarFila(lista, spec, usados)
            assertTrue(fila.size <= spec.max)
            assertEquals(fila.size, fila.map { it.first }.distinct().size)
            val una = RecomendadorPuro.sinUsados(lista, usados)
            assertEquals(una, RecomendadorPuro.sinUsados(una, usados))
        }
    }

    // Regla («no explota»): generoTv devuelve un entero para cualquier entero; comparteGenero con genre_ids de cualquier tipo no lanza.
    @Test fun generoTvYComparteGeneroNoExplotan() {
        val arb = arbitrary { rs -> val r = rs.random; r.nextInt() to F_Json.valor(r) }
        F_Base.propiedad("rec-no-explota", arb) { (g, v) ->
            RecomendadorPuro.generoTv(g)
            val spec = FilaSpec("f", "F", "discover", "ambos", emptyList(), Discover(listOf(18, 28), false, emptyList(), null, emptyList(), emptyList(), "p", 1, 1.0, null, 1), false, 5, 1, true)
            listOf(JSONObject(), JSONObject().put("genre_ids", v), JSONObject().put("genre_ids", JSONArray().put(v).put(18)), JSONObject().put("genre_ids", JSONObject.NULL)).forEach {
                RecomendadorPuro.comparteGenero(it, "tv", spec); RecomendadorPuro.comparteGenero(it, "movie", spec)
            }
        }
    }

    // ---------------------------------------------------------------- 6.3 Reproducciones: tiempo visto

    private val SECUENCIAS: Arb<Pair<List<Pair<Boolean, Long>>, Long>> = arbitrary { rs ->
        val r = rs.random
        var t = r.nextLong(0, 1_000_000)
        val ev = (1..r.nextInt(0, 30)).map { t += r.nextLong(0, 900_000); r.nextBoolean() to t }
        ev to (t + r.nextLong(0, 900_000))
    }

    // Regla: tiempoVisto es la suma de los tramos en PLAYING (modelo independiente), nunca negativa y no mayor al tiempo transcurrido.
    @Test fun tiempoVistoEsLaSumaDeLosTramos() {
        F_Base.propiedad("repro-tiempo-visto", SECUENCIAS) { (ev, fin) ->
            val visto = Reproducciones.tiempoVisto(ev, fin)
            assertTrue(visto >= 0)
            if (ev.isNotEmpty()) assertTrue(visto <= fin - ev.first().second)
            // Modelo: recorre los instantes y suma el trozo hasta el siguiente evento (o fin) cuando el estado vigente es PLAYING.
            var modelo = 0L
            var jugando = false
            ev.forEachIndexed { i, (j, t) ->
                jugando = j
                val siguiente = if (i + 1 < ev.size) ev[i + 1].second else fin
                if (jugando) modelo += siguiente - t
            }
            assertEquals(modelo, visto)
        }
    }

    // Regla: con relojes que retroceden el resultado sigue sin ser negativo.
    @Test fun tiempoVistoNoEsNegativoConRelojAtrasado() {
        val arb = arbitrary { rs -> val r = rs.random; (1..r.nextInt(0, 12)).map { r.nextBoolean() to r.nextLong(0, 5_000_000) } to r.nextLong(0, 5_000_000) }
        F_Base.propiedad("repro-reloj-atrasado", arb) { (ev, fin) -> assertTrue(Reproducciones.tiempoVisto(ev, fin) >= 0) }
    }

    @Test fun pausasCortasSeUnenYLasSesionesDeUnMinutoSeDescartan() {
        assertTrue(Reproducciones.seUnen(Reproducciones.UNION_MS - 1))
        assertFalse(Reproducciones.seUnen(Reproducciones.UNION_MS))
        assertTrue(Reproducciones.descartada(59_999))
        assertFalse(Reproducciones.descartada(60_000))
    }

    // ---------------------------------------------------------------- 6.3 Vistos.tocaRepasoSemanal (clave de semana)

    private fun cal(d: LocalDate): Calendar = GregorianCalendar(d.year, d.monthValue - 1, d.dayOfMonth, 12, 0)

    // Regla: dos fechas de semanas (lunes a domingo) distintas nunca dan la misma clave y dos de la misma semana siempre la misma; fechas de 2020 a 2035.
    // Hallazgo F2-1: con YEAR + WEEK_OF_YEAR el 30 dic 2024 (semana 1) y el 1 ene 2024 daban la misma clave.
    @Test fun claveSemanaDistingueCadaSemana() {
        val inicio = LocalDate.of(2020, 1, 1)
        val dias = (0..(LocalDate.of(2035, 12, 31).toEpochDay() - inicio.toEpochDay()).toInt()).map { inicio.plusDays(it.toLong()) }
        val porLunes = dias.groupBy { it.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }
        val clavePorLunes = porLunes.mapValues { (_, ds) -> ds.map { Vistos.claveSemana(cal(it)) }.toSet() }
        clavePorLunes.forEach { (lunes, claves) -> assertEquals("la semana de $lunes tiene varias claves: $claves", 1, claves.size) }
        val claves = clavePorLunes.values.map { it.single() }
        assertEquals("hay semanas distintas con la misma clave", claves.size, claves.toSet().size)
    }

    @Test fun claveSemanaUltimaSemanaDeDiciembreNoChocaConEnero() {
        assertNotEquals(Vistos.claveSemana(cal(LocalDate.of(2024, 12, 30))), Vistos.claveSemana(cal(LocalDate.of(2024, 1, 1))))
        assertNotEquals(Vistos.claveSemana(cal(LocalDate.of(2025, 12, 29))), Vistos.claveSemana(cal(LocalDate.of(2025, 1, 1))))
        assertEquals(Vistos.claveSemana(cal(LocalDate.of(2024, 12, 30))), Vistos.claveSemana(cal(LocalDate.of(2025, 1, 5))))
    }

    // ---------------------------------------------------------------- 6.3 Aprendizaje.decae y techo

    private val DIA = 86_400_000L

    // Regla: decae es monótona decreciente en el tiempo (para v > 0), vale v/2 a los 30 días y nunca es NaN ni infinito.
    @Test fun decaeEsMonotonaYMitadALos30Dias() {
        val arb = arbitrary { rs -> val r = rs.random; Triple(r.nextDouble(0.0, 500.0), r.nextLong(0, 400 * DIA), r.nextLong(0, 400 * DIA)) }
        F_Base.propiedad("apr-decae", arb) { (v, a, b) ->
            val (menor, mayor) = minOf(a, b) to maxOf(a, b)
            assertTrue(Aprendizaje.decae(v, 0, menor) >= Aprendizaje.decae(v, 0, mayor))
            assertTrue(Aprendizaje.decae(v, 0, mayor).isFinite())
            assertEquals(v / 2, Aprendizaje.decae(v, 0, 30 * DIA), 1e-9 * (1 + v))
            assertEquals(v, Aprendizaje.decae(v, mayor, menor), 1e-12) // reloj atrasado: no crece
        }
    }

    // Regla: acumular señales con techo nunca da NaN ni infinito y nunca pasa de K en magnitud; el techo es creciente e impar.
    @Test fun acumularConTechoNoSePasaNiDaNaN() {
        val arb = arbitrary { rs -> val r = rs.random; (1..r.nextInt(1, 200)).map { r.nextDouble(-4.0, 4.0) to r.nextLong(0, 5 * DIA) } }
        F_Base.propiedad("apr-techo-acumulado", arb) { pasos ->
            var v = 0.0; var t = 0L
            for ((delta, avance) in pasos) {
                t += avance
                v = Aprendizaje.techo(Aprendizaje.decae(v, t - avance, t) + delta)
                assertTrue(v.isFinite())
                assertTrue(kotlin.math.abs(v) < Aprendizaje.K_TECHO)
            }
        }
        val arb2 = arbitrary { rs -> val r = rs.random; r.nextDouble(-1e6, 1e6) to r.nextDouble(-1e6, 1e6) }
        F_Base.propiedad("apr-techo-monotono", arb2) { (a, b) ->
            if (a <= b) assertTrue(Aprendizaje.techo(a) <= Aprendizaje.techo(b))
            assertEquals(-Aprendizaje.techo(a), Aprendizaje.techo(-a), 1e-12)
        }
    }

    @Test fun techoCasosFijos() {
        assertEquals(0.0, Aprendizaje.techo(Double.NaN), 0.0)
        assertEquals(Aprendizaje.K_TECHO, Aprendizaje.techo(Double.POSITIVE_INFINITY), 0.0)
        assertTrue(Aprendizaje.techo(207.7) < Aprendizaje.K_TECHO) // el peso de drama que medimos en el aparato (plan 0.14, 1.2)
    }

    // Regla: más fracción vista nunca da una señal menor.
    @Test fun nivelDeSesionEsMonotono() {
        val arb = arbitrary { rs -> val r = rs.random; r.nextDouble() to r.nextDouble() }
        F_Base.propiedad("apr-nivel-monotono", arb) { (a, b) ->
            assertTrue(Aprendizaje.nivelSesion(minOf(a, b), 0) <= Aprendizaje.nivelSesion(maxOf(a, b), 0))
        }
        assertEquals(-0.5, Aprendizaje.nivelSesion(0.03, 0), 0.0)
        assertEquals(1.0, Aprendizaje.nivelSesion(0.10, 0), 0.0)
        assertEquals(2.5, Aprendizaje.nivelSesion(0.50, 0), 0.0)
        assertEquals(4.0, Aprendizaje.nivelSesion(0.80, 0), 0.0)
    }

    // ---------------------------------------------------------------- 6.3 SeguirViendo.motivo

    // Regla: «hoy», «ayer» o «hace N días» con N >= 2; nunca un número negativo, para cualquier entero de días.
    @Test fun motivoDeCuandoNuncaEsNegativo() {
        val arb = arbitrary { rs -> rs.random.nextInt() }
        F_Base.propiedad("seguir-cuando", arb) { dias ->
            val c = TextoRetomar.cuando(dias)
            when {
                dias <= 0 -> assertEquals("hoy", c)
                dias == 1 -> assertEquals("ayer", c)
                else -> assertEquals("hace $dias días", c)
            }
            assertFalse(c.contains("-"))
        }
        assertEquals("Seguir viendo · E7 · 12:38 de 23:40 · ayer", TextoRetomar.motivo("E7", "Nombre", 758_000, 1420, TextoRetomar.cuando(1)))
    }
}
