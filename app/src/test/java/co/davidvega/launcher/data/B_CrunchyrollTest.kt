package co.davidvega.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class B_CrunchyrollTest {
    private val cat = listOf(
        Crunchyroll.Fila("Frieren: Beyond Journey's End", 2023, "crunchyroll://series?id=A"),
        Crunchyroll.Fila("Attack on Titan", 2013, "crunchyroll://series?id=B"),
        Crunchyroll.Fila("進撃の巨人", 2013, "crunchyroll://series?id=C"),
        Crunchyroll.Fila("Mushoku Tensei", 2021, "crunchyroll://series?id=D"),
    )

    @Test fun nombreEnInglesCoincide() = assertEquals("crunchyroll://series?id=B", Crunchyroll.buscar(cat, listOf("Ataque a los Titanes", "Attack on Titan"), "2013"))
    @Test fun elOriginalNoLatinoTambienCuenta() = assertEquals("crunchyroll://series?id=C", Crunchyroll.buscar(cat, listOf("Ataque a los Titanes", "進撃の巨人"), "2013"))
    @Test fun sufijoDeTemporadaSeIgnora() = assertEquals("crunchyroll://series?id=D", Crunchyroll.buscar(cat, listOf("Mushoku Tensei Season 2"), "2021"))
    @Test fun sinCoincidenciaEsNull() {
        assertNull(Crunchyroll.buscar(cat, listOf("Otra cosa"), "2020"))
        assertNull(Crunchyroll.buscar(cat, emptyList(), "2020"))
        assertNull(Crunchyroll.buscar(emptyList(), listOf("Attack on Titan"), "2013"))
    }
}
