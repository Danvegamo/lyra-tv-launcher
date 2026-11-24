package co.davidvega.launcher.pruebas

import co.davidvega.launcher.data.AniList
import co.davidvega.launcher.data.JustWatch
import co.davidvega.launcher.sistema.MantenimientoPlan
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Calendar
import java.util.TimeZone

/** JustWatch.aRutas/normalizar (privadas: se llaman por reflexión), AniList.numero y MantenimientoPlan (0.13.1). */
class F_ServiciosTest {
    // ---- JustWatch ----
    private val aRutasM = JustWatch::class.java.getDeclaredMethod("aRutas", JSONArray::class.java).apply { isAccessible = true }
    private val normalizarM = JustWatch::class.java.getDeclaredMethod("normalizar", String::class.java, String::class.java).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun aRutas(a: JSONArray?): Map<String, String> = try { aRutasM.invoke(JustWatch, a) as Map<String, String> } catch (e: InvocationTargetException) { throw e.targetException }
    private fun normalizar(s: String, w: String): String? = try { normalizarM.invoke(JustWatch, s, w) as String? } catch (e: InvocationTargetException) { throw e.targetException }

    private val PREFIJOS = mapOf(
        "prime" to Regex("^https://app\\.primevideo\\.com/detail\\?gti=amzn1\\.dv\\.gti\\.[0-9a-f-]+$"),
        "hbomax" to Regex("^https://play\\.hbomax\\.com/video/watch/[0-9a-f-]+$"),
        "crunchyroll" to Regex("^crunchyroll://episode\\?id=[A-Z0-9]+$"),
        "netflix" to Regex("^https://www\\.netflix\\.com/watch/\\d+$"),
    )

    private fun ofertas(modo: F_Json.Modo) = arbitrary(F_ReduceTexto) { rs -> F_Json.ofertas(rs.random, modo).toString() }

    // Regla: cada ruta cumple la forma de su servicio, el esquema solo es https o crunchyroll y no hay servicios inventados.
    @Test fun aRutasProduceSoloFormasConocidas() {
        F_Base.propiedad("jw-arutas-formas", ofertas(F_Json.Modo.NULOS)) { t ->
            val r = aRutas(JSONArray(t))
            for ((s, uri) in r) {
                assertTrue("$s -> $uri en $t", PREFIJOS[s]!!.matches(uri))
                assertTrue(uri.startsWith("https://") || uri.startsWith("crunchyroll://"))
            }
        }
        assertEquals(emptyMap<String, String>(), aRutas(null))
    }

    // Regla: solo cuentan las ofertas de suscripción o gratis (FLATRATE, FREE, ADS); comprar o alquilar nunca produce ruta.
    @Test fun aRutasIgnoraCompraYAlquiler() {
        F_Base.propiedad("jw-arutas-gratis", ofertas(F_Json.Modo.BIEN)) { t ->
            val a = JSONArray(t)
            val comprables = JSONArray().also { n -> for (i in 0 until a.length()) n.put(JSONObject(a.getJSONObject(i).toString()).put("monetizationType", "BUY")) }
            assertTrue(t, aRutas(comprables).isEmpty())
        }
    }

    // Regla: ver más ofertas nunca quita rutas (metamórfica): las rutas de un prefijo del arreglo están contenidas en las del arreglo completo.
    @Test fun aRutasEsMonotonaAlAnadirOfertas() {
        F_Base.propiedad("jw-arutas-monotona", ofertas(F_Json.Modo.BIEN)) { t ->
            val a = JSONArray(t)
            val prefijo = JSONArray().also { n -> for (i in 0 until a.length() / 2) n.put(a.get(i)) }
            val p = aRutas(prefijo); val c = aRutas(a)
            p.forEach { (k, v) -> assertEquals(t, v, c[k]) }
        }
    }

    // Regla (más débil): con ofertas de cualquier tipo, aRutas no lanza. (F-3, corregido).
    @Test fun aRutasNoLanzaConTiposInesperados() {
        F_Base.propiedad("jw-arutas-malos", ofertas(F_Json.Modo.MALOS)) { t -> aRutas(JSONArray(t)) }
    }

    // Regla: el identificador extraído de la URL es exactamente el que traía, y nunca queda esquema distinto de https/crunchyroll.
    @Test fun normalizarExtraeElIdentificadorQueTraiaLaUrl() {
        val hex = arbitrary { rs -> (1..rs.random.nextInt(1, 40)).map { "0123456789abcdef-"[rs.random.nextInt(17)] }.joinToString("") }
        F_Base.propiedad("jw-normalizar-ids", hex) { id ->
            assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.$id", normalizar("prime", "https://x/detail?gti=amzn1.dv.gti.$id&a=1"))
            assertEquals("https://play.hbomax.com/video/watch/$id", normalizar("hbomax", "https://play.hbomax.com/video/watch/$id?x"))
            // idempotencia en los servicios cuyo destino vuelve a contener la forma de origen
            val p = normalizar("prime", "gti=amzn1.dv.gti.$id")!!
            assertEquals(p, normalizar("prime", p))
            val h = normalizar("hbomax", "play.hbomax.com/video/watch/$id")!!
            assertEquals(h, normalizar("hbomax", h))
        }
    }

    // Regla: para cualquier texto, normalizar devuelve null o una ruta con la forma del servicio; un servicio desconocido da siempre null.
    @Test fun normalizarSoloDevuelveFormasValidas() {
        val textos = arbitrary { rs -> F_Json.url(rs.random) }
        F_Base.propiedad("jw-normalizar-forma", textos, 400) { w ->
            for ((s, re) in PREFIJOS) normalizar(s, w)?.let { assertTrue("$s $w -> $it", re.matches(it)) }
            assertNull(normalizar("desconocido", w))
        }
    }

    // ---- AniList.numero ----

    // Regla (oráculo): para cada formato que publica la app, numero devuelve el entero escrito; nunca negativo ni lanza.
    @Test fun numeroLeeElEpisodio() {
        val n = arbitrary { rs -> rs.random.nextInt(0, 5000) }
        F_Base.propiedad("anilist-numero", n) { k ->
            assertEquals(k, AniList.numero("E$k - Título"))
            assertEquals(k, AniList.numero("  e$k"))
            assertEquals(k, AniList.numero("Ep. $k"))
            assertEquals(k, AniList.numero("Ep $k: algo"))
            assertEquals(k, AniList.numero("Episodio $k"))
            assertEquals(k, AniList.numero("$k"))
            assertEquals(k, AniList.numero("$k - Título"))
        }
        assertNull(AniList.numero(null))
    }

    // Regla: con cualquier texto, numero devuelve null o un entero no negativo, y sin lanzar (incluye números que no caben en Int).
    @Test fun numeroNuncaLanzaNiDevuelveNegativos() {
        val textos = arbitrary { rs ->
            val r = rs.random
            listOf("E", "Ep.", "Episodio", "", " ", "-", "99999999999999999999", "٣", "E 12").let { l -> (1..r.nextInt(1, 4)).joinToString("") { l[r.nextInt(l.size)] + (if (r.nextBoolean()) r.nextInt(0, 100000).toString() else "") } }
        }
        F_Base.propiedad("anilist-numero-robusto", textos, 400) { t -> AniList.numero(t)?.let { assertTrue(t, it >= 0) } }
        assertNull(AniList.numero("E99999999999999999999"))
    }

    // ---- MantenimientoPlan ----
    private val ZONAS = Arb.element(listOf("UTC", "America/Bogota", "America/Mexico_City", "America/New_York", "Europe/Madrid", "Asia/Kolkata", "Australia/Lord_Howe", "Pacific/Apia", "America/Sao_Paulo", "Africa/Cairo"))
    private val AHORA = Arb.long(1_577_836_800_000L, 2_051_222_400_000L) // 2020 a 2035

    private fun local(ms: Long, z: TimeZone) = Calendar.getInstance(z).apply { timeInMillis = ms }

    // Regla: la próxima alarma está en el futuro, a las 4:30:00.000 locales y a menos de 25 h 30 min (cambios de hora incluidos).
    @Test fun proximaEsFuturaALas430() {
        F_Base.propiedad("plan-proxima", arbitrary { rs -> Triple(AHORA.sample(rs).value, ZONAS.sample(rs).value, 0) }, 400) { (ahora, id, _) ->
            val z = TimeZone.getTimeZone(id)
            val p = MantenimientoPlan.proxima(ahora, z)
            assertTrue("$id $ahora", p > ahora && p - ahora <= 25 * 3_600_000L + 30 * 60_000L)
            val c = local(p, z)
            assertEquals("$id $ahora", listOf(4, 30, 0, 0), listOf(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND)))
        }
    }

    // Regla (idempotencia y monotonía): proxima(proxima(x) - 1) == proxima(x), y x <= y implica proxima(x) <= proxima(y).
    @Test fun proximaEsIdempotenteYMonotona() {
        F_Base.propiedad("plan-proxima-monotona", arbitrary { rs -> Triple(AHORA.sample(rs).value, AHORA.sample(rs).value, ZONAS.sample(rs).value) }, 400) { (a, b, id) ->
            val z = TimeZone.getTimeZone(id)
            val p = MantenimientoPlan.proxima(a, z)
            assertEquals("$id $a", p, MantenimientoPlan.proxima(p - 1, z))
            assertTrue("$id $a $b", MantenimientoPlan.proxima(minOf(a, b), z) <= MantenimientoPlan.proxima(maxOf(a, b), z))
        }
    }

    // Regla (zonas de desfase fijo, para poder decirlo exacto): reintento es null si ya pasaron las 6:00 locales, y si no, ahora + 30 min.
    @Test fun reintentoNuncaPasaDeLas630() {
        val fijas = Arb.element(listOf("UTC", "America/Bogota", "Asia/Kolkata", "Asia/Tokyo"))
        F_Base.propiedad("plan-reintento", arbitrary { rs -> AHORA.sample(rs).value to fijas.sample(rs).value }, 400) { (ahora, id) ->
            val z = TimeZone.getTimeZone(id)
            val c = local(ahora, z)
            val msDelDia = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60 + c.get(Calendar.SECOND)) * 1000 + c.get(Calendar.MILLISECOND)
            val r = MantenimientoPlan.reintento(ahora, z)
            if (msDelDia <= 6 * 3_600_000L) assertEquals("$id $ahora", ahora + MantenimientoPlan.REINTENTO_MS, r) else assertNull("$id $ahora", r)
            if (r != null) {
                val rc = local(r, z)
                assertTrue(rc.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR))
                assertTrue(rc.get(Calendar.HOUR_OF_DAY) * 60 + rc.get(Calendar.MINUTE) <= 6 * 60 + 30)
            }
        }
    }

    // Regla (zonas con cambio de hora): un reintento no nulo siempre es 30 min después, en el mismo día local y antes de las 6:30.
    @Test fun reintentoConCambioDeHoraSigueAcotado() {
        F_Base.propiedad("plan-reintento-dst", arbitrary { rs -> AHORA.sample(rs).value to ZONAS.sample(rs).value }, 400) { (ahora, id) ->
            val z = TimeZone.getTimeZone(id)
            val r = MantenimientoPlan.reintento(ahora, z) ?: return@propiedad
            assertEquals(MantenimientoPlan.REINTENTO_MS, r - ahora)
            val c = local(ahora, z); val rc = local(r, z)
            assertTrue("$id $ahora", rc.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && rc.get(Calendar.HOUR_OF_DAY) * 60 + rc.get(Calendar.MINUTE) <= 6 * 60 + 30)
        }
    }

    // Regla: el presupuesto nunca es negativo, no crece con el tiempo y limite(m) == min(m, restante).
    @Test fun presupuestoNuncaNegativoYDecreciente() {
        F_Base.propiedad("plan-presupuesto", arbitrary { rs -> val r = rs.random; Triple(r.nextLong(0, 120_000), (1..r.nextInt(1, 8)).map { r.nextLong(0, 40_000) }, r.nextLong(0, 200_000)) }) { (total, pasos, maximo) ->
            var t = 1_000L
            val p = MantenimientoPlan.Presupuesto(total) { t }
            var anterior = p.restante()
            assertEquals(total, anterior)
            for (d in pasos) {
                t += d
                val r = p.restante()
                assertTrue("$total $pasos", r in 0..anterior)
                assertEquals(minOf(maximo, r), p.limite(maximo))
                anterior = r
            }
        }
    }

    @Test fun ejemploFijoProximaTras0430() {
        val z = TimeZone.getTimeZone("UTC")
        val a = Calendar.getInstance(z).apply { clear(); set(2026, Calendar.NOVEMBER, 21, 4, 29, 30) }.timeInMillis
        assertEquals(a + 30_000, MantenimientoPlan.proxima(a, z))
        assertNotNull(MantenimientoPlan.reintento(a, z))
    }
}
