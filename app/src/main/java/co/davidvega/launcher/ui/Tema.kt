package co.davidvega.launcher.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import co.davidvega.launcher.BuildConfig
import co.davidvega.launcher.data.Paleta
import co.davidvega.launcher.data.Temas
import java.io.File
import java.util.Properties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import co.davidvega.launcher.R

/**
 * Colores del launcher. Todo color de la interfaz sale de aquí. La paleta activa viene de [Temas] (data/Temas.kt):
 * el tema elegido en Ajustes, o el de `THEME` en config.local.properties, con acento y fondo opcionales.
 * Los valores se leen de un estado de Compose: al cambiar de tema, la interfaz se recompone sola.
 */
internal object Tema {
    private val actual = mutableStateOf(Temas.LYRA)
    val paleta: Paleta get() = actual.value

    val Fondo get() = Color(actual.value.fondo)
    val Superficie get() = Color(actual.value.superficie)
    val Superficie2 get() = Color(actual.value.superficie2)

    /** Borde fino de reposo de las tarjetas. */
    val Borde get() = Color(actual.value.borde)

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

    /** Acento del foco y de los detalles (en Lyra, rojo vigía). */
    val Acento get() = Color(actual.value.acento)
    val Texto get() = Color(actual.value.texto)
    val Secundario get() = Color(actual.value.secundario)
    val Terciario get() = Color(actual.value.terciario)
    /** Fondo de la pieza o botón enfocado (blanco en los temas oscuros). */
    val Foco get() = Color(actual.value.foco)
    /** Texto sobre [Foco]. Se mantiene el nombre antiguo porque en los temas oscuros el foco es blanco. */
    val TextoSobreBlanco get() = Color(actual.value.textoSobreFoco)
    val SecundarioSobreFoco get() = Color(actual.value.secundarioSobreFoco)
    /** Sombra bajo el texto: negra en temas oscuros, ninguna en el claro. */
    val SombraTexto get() = if (actual.value.claro) Color.Transparent else Color.Black.copy(alpha = 0.65f)

    /** Velo del color de fondo con alfa para avisos sobre imágenes (negro en los temas oscuros). */
    val VeloAviso get() = Fondo.copy(alpha = 0.8f)
    /** Velo del color de fondo con alfa para insignias (nota) sobre pósters. */
    val VeloInsignia get() = Fondo.copy(alpha = 0.7f)

    /**
     * Pares de tonos para tarjetas sin imagen (el índice sigue siendo t.gradiente): la superficie del tema con un toque
     * distinto de acento en cada uno, para que las filas sin póster no sean un muro gris.
     */
    val Placeholders: List<List<Color>> get() {
        val p = actual.value
        return listOf(0.10, 0.04, 0.16, 0.07, 0.13, 0.02).map { k ->
            val alto = Temas.mezclar(p.superficie2, p.acento, k)
            listOf(Color(alto), Color(Temas.mezclar(alto, p.fondo, 0.6)))
        }
    }

    /** Aviso del último [cargar] (colores personalizados descartados, tema desconocido...). */
    var avisos: List<String> = emptyList(); private set

    /** Lee el tema guardado y la configuración (BuildConfig y config.local.properties externo) y lo aplica. */
    fun cargar(ctx: Context) {
        val cfg = mutableMapOf<String, String?>(
            "THEME" to BuildConfig.THEME, "THEME_ACCENT" to BuildConfig.THEME_ACCENT, "THEME_BACKGROUND" to BuildConfig.THEME_BACKGROUND,
        )
        runCatching {
            ctx.getExternalFilesDir(null)?.let { File(it, "config.local.properties") }?.takeIf { it.exists() }?.inputStream()?.use {
                val p = Properties().apply { load(it) }
                for (k in listOf("THEME", "THEME_ACCENT", "THEME_BACKGROUND")) p.getProperty(k)?.trim()?.takeIf { v -> v.isNotEmpty() }?.let { v -> cfg[k] = v }
            }
        }
        val guardado = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString(Temas.CLAVE, null)
        val av = mutableListOf<String>()
        actual.value = Temas.resolver(guardado, cfg, av)
        avisos = av
        av.forEach { Log.w("Launcher", "tema: $it") }
    }

    /** Elige un tema con el mando: lo guarda y lo aplica al momento. */
    fun elegir(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
        Temas.guardar(id) { k, v -> prefs.edit().putString(k, v).apply() }
        cargar(ctx)
    }
}

/** Etiqueta en mayúsculas, monoespaciada y con espaciado (títulos de fila, motivos, fecha). A 16 sp o más para leerse desde el sofá. */
internal fun txtMono(size: Int, color: Color = Tema.Texto, peso: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, color = color, fontWeight = peso, fontFamily = Tema.Mono, letterSpacing = 1.4.sp)

/** Cifras y titulares en matriz de puntos (la hora). */
internal fun txtPuntos(size: Int, color: Color = Tema.Texto) =
    TextStyle(fontSize = size.sp, color = color, fontWeight = FontWeight.Bold, fontFamily = Tema.Puntos)
