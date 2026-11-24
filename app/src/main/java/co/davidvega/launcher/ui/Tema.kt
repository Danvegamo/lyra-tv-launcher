package co.davidvega.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import co.davidvega.launcher.R

/**
 * Paleta única del launcher (estilo tvOS, negro y blanco). Todo color de la interfaz sale de aquí:
 * no hay acentos de color; el foco se marca con blanco (borde o tarjeta/botón blanco con texto negro).
 */
internal object Tema {
    val Fondo = Color(0xFF000000)
    val Superficie = Color(0xFF0D0D0D)
    val Superficie2 = Color(0xFF1A1A1A)

    /** Borde fino de reposo de las tarjetas (el mismo gris que usa el launcher del S25). */
    val Borde = Color(0xFF262626)

    /**
     * Tipografía traída del launcher del S25: matriz de puntos (Doto) para la hora y cifras grandes, y monoespaciada
     * (Space Mono) para etiquetas en mayúsculas con espaciado. Doto es una fuente variable (peso 500 y 800).
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Puntos = FontFamily(
        Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
        Font(R.font.doto, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    )
    val Mono = FontFamily(Font(R.font.spacemono))

    /** Rojo vigía: acento del foco y de los detalles. */
    val Acento = Color(0xFFFF3D4D)
    val Texto = Color(0xFFFFFFFF)
    val Secundario = Color(0xFFA1A1A6)
    val Terciario = Color(0xFF6E6E73)
    val TextoSobreBlanco = Color(0xFF000000)

    /** Velo negro con alfa para avisos sobre imágenes. */
    val VeloAviso = Color(0xCC000000)
    /** Velo negro con alfa para insignias (nota) sobre pósters. */
    val VeloInsignia = Color(0xB3000000)

    /** Pares de grises para tarjetas sin imagen (el índice sigue siendo t.gradiente). */
    val Placeholders = listOf(
        listOf(Color(0xFF1A1A1A), Color(0xFF0D0D0D)), listOf(Color(0xFF222222), Color(0xFF111111)),
        listOf(Color(0xFF181818), Color(0xFF0A0A0A)), listOf(Color(0xFF202020), Color(0xFF101010)),
        listOf(Color(0xFF1D1D1D), Color(0xFF0F0F0F)), listOf(Color(0xFF252525), Color(0xFF121212)),
    )
}

/** Etiqueta en mayúsculas, monoespaciada y con espaciado (títulos de fila, motivos, fecha). A 16 sp o más para leerse desde el sofá. */
internal fun txtMono(size: Int, color: Color = Tema.Texto, peso: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, color = color, fontWeight = peso, fontFamily = Tema.Mono, letterSpacing = 1.4.sp)

/** Cifras y titulares en matriz de puntos (la hora). */
internal fun txtPuntos(size: Int, color: Color = Tema.Texto) =
    TextStyle(fontSize = size.sp, color = color, fontWeight = FontWeight.Bold, fontFamily = Tema.Puntos)
