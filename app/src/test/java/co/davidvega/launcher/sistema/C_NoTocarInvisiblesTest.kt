package co.davidvega.launcher.sistema

import org.junit.Assert.assertFalse
import org.junit.Test

/** Regresión de la segunda revisión adversarial: invisibles y texto suelto no desprotegen. */
class C_NoTocarInvisiblesTest {
    @Test fun wordJoinerNoDesprotege() {
        val nt = NoTocar.parsear("com.amazon.tv.launcher\ncom.amazon.venezia\u2060\n")
        assertFalse(Ordenes.puedeMatar("com.amazon.venezia", nt))
    }
    @Test fun softHyphenNoDesprotege() {
        val nt = NoTocar.parsear("com.amazon.tv.launcher\ncom.amazon.venezia\u00AD\n")
        assertFalse(Ordenes.puedeMatar("com.amazon.venezia", nt))
    }
    @Test fun textoSueltoNoDesprotege() {
        val nt = NoTocar.parsear("com.amazon.tv.launcher\ncom.amazon.venezia (tienda)\n")
        assertFalse(Ordenes.puedeMatar("com.amazon.venezia", nt))
    }
}

class C_NoTocarFormatosTest {
    @Test fun bomYEspacioDuroNoDesprotegen() {
        val nt = NoTocar.parsear("﻿com.amazon.tv.launcher\r\n com.amazon.venezia​\r\n# comentario\n")
        org.junit.Assert.assertEquals(setOf("com.amazon.tv.launcher", "com.amazon.venezia"), nt)
    }
}
