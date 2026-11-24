package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Temas de color 0.16: lectura de colores, contraste WCAG AA de cada tema, personalización y persistencia. */
class TemasTest {

    @Test fun `todos los temas cumplen WCAG AA`() {
        for (p in Temas.TODOS) assertTrue("${p.id}: ${Temas.fallos(p)}", Temas.fallos(p).isEmpty())
    }

    @Test fun `ids unicos y en minusculas`() {
        val ids = Temas.TODOS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(it, it.lowercase()) }
        assertEquals("lyra", Temas.TODOS.first().id)
    }

    @Test fun `parsear colores validos`() {
        assertEquals(0xFFFF3D4D.toInt(), Temas.parsearColor("#FF3D4D"))
        assertEquals(0xFFFF3D4D.toInt(), Temas.parsearColor("ff3d4d"))
        assertEquals(0xFF00FF00.toInt(), Temas.parsearColor(" #8000FF00 ")) // alfa ignorado: opaco
    }

    @Test fun `parsear colores invalidos da null`() {
        listOf(null, "", "#", "#FFF", "#GGGGGG", "rojo", "#1234567", "#12 456").forEach { assertNull(it, Temas.parsearColor(it)) }
    }

    @Test fun `contraste conocido`() {
        assertEquals(21.0, Temas.contraste(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Temas.contraste(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
    }

    @Test fun `resolver sin nada da Lyra`() {
        assertEquals(Temas.LYRA, Temas.resolver(null, emptyMap()))
    }

    @Test fun `el tema guardado manda sobre THEME del archivo`() {
        assertEquals("oled", Temas.resolver("oled", mapOf("THEME" to "calido")).id)
        assertEquals("calido", Temas.resolver(null, mapOf("THEME" to " Calido ")).id)
        assertEquals("papel", Temas.resolver("no-existe", mapOf("THEME" to "papel")).id)
    }

    @Test fun `THEME desconocido avisa y cae en Lyra`() {
        val av = mutableListOf<String>()
        assertEquals("lyra", Temas.resolver(null, mapOf("THEME" to "neon"), av).id)
        assertTrue(av.single().contains("neon"))
    }

    @Test fun `acento personalizado valido se aplica`() {
        val p = Temas.resolver(null, mapOf("THEME_ACCENT" to "#4CC9F0"))
        assertEquals(0xFF4CC9F0.toInt(), p.acento)
        assertTrue(Temas.fallos(p).isEmpty())
    }

    @Test fun `acento sin contraste con el fondo se ignora`() {
        val av = mutableListOf<String>()
        val p = Temas.resolver(null, mapOf("THEME_ACCENT" to "#111111"), av)
        assertEquals(Temas.LYRA.acento, p.acento)
        assertTrue(av.single().contains("THEME_ACCENT"))
    }

    @Test fun `fondo personalizado deriva superficies y mantiene AA`() {
        val p = Temas.resolver("medianoche", mapOf("THEME_BACKGROUND" to "#101820"))
        assertEquals(0xFF101820.toInt(), p.fondo)
        assertNotEquals(p.fondo, p.superficie2)
        assertTrue(Temas.fallos(p).isEmpty())
    }

    @Test fun `fondo claro en tema oscuro se descarta`() {
        val av = mutableListOf<String>()
        val p = Temas.resolver("lyra", mapOf("THEME_BACKGROUND" to "#F0F0F0"), av)
        assertEquals(Temas.LYRA.fondo, p.fondo)
        assertTrue(av.single().contains("THEME_BACKGROUND"))
    }

    @Test fun `alto contraste no se personaliza`() {
        val av = mutableListOf<String>()
        val p = Temas.resolver("contraste", mapOf("THEME_ACCENT" to "#4CC9F0", "THEME_BACKGROUND" to "#101820"), av)
        assertEquals(Temas.CONTRASTE, p)
        assertEquals(1, av.size)
    }

    @Test fun `color mal escrito avisa`() {
        val av = mutableListOf<String>()
        Temas.resolver(null, mapOf("THEME_ACCENT" to "azul"), av)
        assertTrue(av.single().contains("azul"))
    }

    @Test fun `guardar y volver a leer conserva el tema`() {
        val almacen = mutableMapOf<String, String>()
        for (p in Temas.TODOS) {
            Temas.guardar(p.id) { k, v -> almacen[k] = v }
            assertEquals(p.id, Temas.resolver(almacen[Temas.CLAVE], emptyMap()).id)
        }
        Temas.guardar("inventado") { k, v -> almacen[k] = v }
        assertEquals(Temas.POR_DEFECTO, almacen[Temas.CLAVE])
    }

    @Test fun `siguiente recorre en ciclo`() {
        var id: String? = null
        val vistos = mutableListOf<String>()
        repeat(Temas.TODOS.size) { id = Temas.siguiente(id).id; vistos += id!! }
        assertEquals(Temas.TODOS.map { it.id }, vistos)
        assertEquals("lyra", Temas.siguiente("papel").id)
    }

    @Test fun `mezclar extremos`() {
        val a = 0xFF000000.toInt(); val b = 0xFFFFFFFF.toInt()
        assertEquals(a, Temas.mezclar(a, b, 0.0))
        assertEquals(b, Temas.mezclar(a, b, 1.0))
    }
}
