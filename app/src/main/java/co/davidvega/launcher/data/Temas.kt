package co.davidvega.launcher.data

import kotlin.math.pow

/**
 * Temas de color de Lyra. Lógica pura (sin Android ni Compose) para poder probarla en la JVM:
 * paletas predefinidas, lectura de colores `#RRGGBB`, contraste WCAG y persistencia del tema elegido.
 *
 * Los colores van como ARGB en [Int]. `ui/Tema.kt` los convierte a colores de Compose.
 */
data class Paleta(
    val id: String,
    val nombre: String,
    val fondo: Int,
    val superficie: Int,
    val superficie2: Int,
    val borde: Int,
    val acento: Int,
    val texto: Int,
    val secundario: Int,
    val terciario: Int,
    /** Fondo de la pieza o botón enfocado (en los temas oscuros, blanco). */
    val foco: Int,
    /** Texto sobre [foco]. */
    val textoSobreFoco: Int,
    /** Texto secundario sobre [foco]. */
    val secundarioSobreFoco: Int,
    /** Tema claro: sin sombra negra bajo el texto. */
    val claro: Boolean = false,
)

object Temas {
    const val CLAVE = "tema"
    const val POR_DEFECTO = "lyra"

    /** Mínimos WCAG 2.1 AA: 4,5:1 para texto normal y 3:1 para indicadores (borde de foco, acento). */
    const val MIN_TEXTO = 4.5
    const val MIN_FOCO = 3.0

    val LYRA = Paleta(
        "lyra", "Lyra",
        fondo = 0xFF000000.toInt(), superficie = 0xFF0D0D0D.toInt(), superficie2 = 0xFF1A1A1A.toInt(), borde = 0xFF262626.toInt(),
        acento = 0xFFFF3D4D.toInt(), texto = 0xFFFFFFFF.toInt(), secundario = 0xFFA1A1A6.toInt(), terciario = 0xFF8E8E93.toInt(),
        foco = 0xFFFFFFFF.toInt(), textoSobreFoco = 0xFF000000.toInt(), secundarioSobreFoco = 0xFF4A4A4F.toInt(),
    )
    val MEDIANOCHE = Paleta(
        "medianoche", "Medianoche",
        fondo = 0xFF0A0F1E.toInt(), superficie = 0xFF111830.toInt(), superficie2 = 0xFF1A2340.toInt(), borde = 0xFF2A3558.toInt(),
        acento = 0xFF4CC9F0.toInt(), texto = 0xFFF2F5FF.toInt(), secundario = 0xFFA9B4D0.toInt(), terciario = 0xFF8D99B8.toInt(),
        foco = 0xFFF2F5FF.toInt(), textoSobreFoco = 0xFF0A0F1E.toInt(), secundarioSobreFoco = 0xFF3A4566.toInt(),
    )
    val OLED = Paleta(
        "oled", "OLED negro",
        fondo = 0xFF000000.toInt(), superficie = 0xFF000000.toInt(), superficie2 = 0xFF0A0A0A.toInt(), borde = 0xFF1F1F1F.toInt(),
        acento = 0xFFFFFFFF.toInt(), texto = 0xFFE6E6E6.toInt(), secundario = 0xFF9A9A9F.toInt(), terciario = 0xFF85858A.toInt(),
        foco = 0xFFE6E6E6.toInt(), textoSobreFoco = 0xFF000000.toInt(), secundarioSobreFoco = 0xFF3F3F44.toInt(),
    )
    val CONTRASTE = Paleta(
        "contraste", "Alto contraste",
        fondo = 0xFF000000.toInt(), superficie = 0xFF000000.toInt(), superficie2 = 0xFF141414.toInt(), borde = 0xFFBDBDBD.toInt(),
        acento = 0xFFFFD400.toInt(), texto = 0xFFFFFFFF.toInt(), secundario = 0xFFE6E6E6.toInt(), terciario = 0xFFCCCCCC.toInt(),
        foco = 0xFFFFD400.toInt(), textoSobreFoco = 0xFF000000.toInt(), secundarioSobreFoco = 0xFF1A1A1A.toInt(),
    )
    val CALIDO = Paleta(
        "calido", "Cálido",
        fondo = 0xFF140E0A.toInt(), superficie = 0xFF1E1611.toInt(), superficie2 = 0xFF2A1F18.toInt(), borde = 0xFF3D2E23.toInt(),
        acento = 0xFFFF9F43.toInt(), texto = 0xFFFFF4E6.toInt(), secundario = 0xFFCDB9A5.toInt(), terciario = 0xFFAD9A88.toInt(),
        foco = 0xFFFFF4E6.toInt(), textoSobreFoco = 0xFF140E0A.toInt(), secundarioSobreFoco = 0xFF5A4636.toInt(),
    )
    val PAPEL = Paleta(
        "papel", "Papel (claro)",
        fondo = 0xFFF4F3EF.toInt(), superficie = 0xFFFFFFFF.toInt(), superficie2 = 0xFFE7E5DF.toInt(), borde = 0xFFC9C6BD.toInt(),
        acento = 0xFFC8102E.toInt(), texto = 0xFF141414.toInt(), secundario = 0xFF4A4A4A.toInt(), terciario = 0xFF5C5C5C.toInt(),
        foco = 0xFF141414.toInt(), textoSobreFoco = 0xFFFFFFFF.toInt(), secundarioSobreFoco = 0xFFD6D6D6.toInt(), claro = true,
    )

    val TODOS = listOf(LYRA, MEDIANOCHE, OLED, CONTRASTE, CALIDO, PAPEL)

    fun porId(id: String?): Paleta? = TODOS.firstOrNull { it.id == id?.trim()?.lowercase() }

    /** El siguiente tema de la lista (el botón del panel Ajustes los recorre en ciclo). */
    fun siguiente(id: String?): Paleta {
        val i = TODOS.indexOfFirst { it.id == id }
        return TODOS[(i + 1).mod(TODOS.size)]
    }

    /** `#RRGGBB`, `RRGGBB` o `#AARRGGBB` (el alfa se ignora: los colores de tema son opacos). Devuelve null si no es válido. */
    fun parsearColor(texto: String?): Int? {
        val t = texto?.trim()?.removePrefix("#") ?: return null
        if (t.length != 6 && t.length != 8) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val rgb = t.takeLast(6).toLong(16).toInt()
        return rgb or 0xFF000000.toInt()
    }

    fun aHex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    /** Luminancia relativa WCAG 2.1. */
    fun luminancia(c: Int): Double {
        fun canal(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * canal((c shr 16) and 0xFF) + 0.7152 * canal((c shr 8) and 0xFF) + 0.0722 * canal(c and 0xFF)
    }

    /** Razón de contraste WCAG entre dos colores (1 a 21). */
    fun contraste(a: Int, b: Int): Double {
        val la = luminancia(a); val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Mezcla lineal de dos colores opacos; t = 0 da [a], t = 1 da [b]. */
    fun mezclar(a: Int, b: Int, t: Double): Int {
        fun m(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Lista de fallos de contraste de una paleta (vacía si cumple WCAG AA). */
    fun fallos(p: Paleta): List<String> {
        val f = mutableListOf<String>()
        fun texto(nombre: String, c: Int, sobre: Int, donde: String) {
            val r = contraste(c, sobre); if (r < MIN_TEXTO) f += "$nombre sobre $donde: %.2f".format(r)
        }
        for ((n, fondo) in listOf("fondo" to p.fondo, "superficie2" to p.superficie2)) {
            texto("texto", p.texto, fondo, n); texto("secundario", p.secundario, fondo, n); texto("terciario", p.terciario, fondo, n)
        }
        texto("textoSobreFoco", p.textoSobreFoco, p.foco, "foco")
        texto("secundarioSobreFoco", p.secundarioSobreFoco, p.foco, "foco")
        if (contraste(p.acento, p.fondo) < MIN_FOCO) f += "acento sobre fondo: %.2f".format(contraste(p.acento, p.fondo))
        if (contraste(p.foco, p.fondo) < MIN_FOCO) f += "foco sobre fondo: %.2f".format(contraste(p.foco, p.fondo))
        return f
    }

    /**
     * Aplica los colores personalizados (THEME_ACCENT, THEME_BACKGROUND) sobre un tema. Si el resultado no cumple
     * WCAG AA, se descarta ese color y se anota el motivo en [avisos]. El tema de alto contraste no se personaliza.
     */
    fun personalizar(base: Paleta, acento: String?, fondo: String?, avisos: MutableList<String> = mutableListOf()): Paleta {
        if (base.id == CONTRASTE.id) {
            if (!acento.isNullOrBlank() || !fondo.isNullOrBlank()) avisos += "El tema de alto contraste no admite colores personalizados"
            return base
        }
        var p = base
        val bg = fondo?.takeIf { it.isNotBlank() }?.let { parsearColor(it) ?: run { avisos += "THEME_BACKGROUND no es un color #RRGGBB: $it"; null } }
        if (bg != null) {
            val nueva = p.copy(
                fondo = bg,
                superficie = mezclar(bg, p.texto, 0.04),
                superficie2 = mezclar(bg, p.texto, 0.09),
                borde = mezclar(bg, p.texto, 0.16),
            )
            val malos = fallos(nueva)
            if (malos.isEmpty()) p = nueva else avisos += "THEME_BACKGROUND ${aHex(bg)} ignorado: ${malos.first()}"
        }
        val ac = acento?.takeIf { it.isNotBlank() }?.let { parsearColor(it) ?: run { avisos += "THEME_ACCENT no es un color #RRGGBB: $it"; null } }
        if (ac != null) {
            val r = contraste(ac, p.fondo)
            if (r >= MIN_FOCO) p = p.copy(acento = ac) else avisos += "THEME_ACCENT ${aHex(ac)} ignorado: contraste %.2f con el fondo (mínimo 3)".format(r)
        }
        return p
    }

    /**
     * Resuelve el tema activo. Orden: el elegido en Ajustes (guardado con [CLAVE]) > `THEME` del archivo de configuración > Lyra.
     * Encima se aplican `THEME_ACCENT` y `THEME_BACKGROUND` si son válidos.
     */
    fun resolver(guardado: String?, config: Map<String, String?>, avisos: MutableList<String> = mutableListOf()): Paleta {
        val desdeConfig = config["THEME"]?.takeIf { it.isNotBlank() }
        if (desdeConfig != null && porId(desdeConfig) == null) avisos += "THEME desconocido: $desdeConfig (usa ${TODOS.joinToString { it.id }})"
        val base = porId(guardado) ?: porId(desdeConfig) ?: LYRA
        return personalizar(base, config["THEME_ACCENT"], config["THEME_BACKGROUND"], avisos)
    }

    /** Guarda el tema elegido con el mando. [poner] escribe en el almacén de ajustes (SharedPreferences en la app). */
    fun guardar(id: String, poner: (String, String) -> Unit) {
        poner(CLAVE, porId(id)?.id ?: POR_DEFECTO)
    }
}
