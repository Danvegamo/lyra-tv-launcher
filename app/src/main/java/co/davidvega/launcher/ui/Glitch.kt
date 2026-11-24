package co.davidvega.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.random.Random

/**
 * Efecto glitch breve en rojo vigía: separación RGB y bandas desplazadas. Se dibuja solo mientras dura (120 a 200 ms): el contenido
 * se graba una vez por fotograma en una capa de la GPU (`GraphicsLayer`) y se pinta con desplazamientos y filtros de color, sin bitmaps
 * ni sombreadores. No participa en la medición ni en el foco, así que el D-pad responde igual. Se apaga en Ajustes > «Efectos glitch».
 */
internal object Glitch {
    @Volatile var activo = true
    /** Verdadero mientras se mantiene una flecha pulsada (repeatCount > 0): el texto no se decodifica, se pone directo. */
    @Volatile var repetido = false
    /** Sube en cada cambio real de foco entre títulos (aunque el título sea el mismo que el anterior): es la clave de los efectos. */
    val pulso = androidx.compose.runtime.mutableIntStateOf(0)
    /** Toda la imagen al aparecer el inicio tras la intro. */
    val global = GlitchAnim()
    /** Fondo y hero al cambiar de título enfocado. */
    val hero = GlitchAnim()
}

internal class GlitchAnim {
    val progreso = Animatable(0f)
    var semilla = 7
    /** Dispara el efecto; si ya estaba en marcha lo reinicia. No hace nada si el ajuste está apagado. */
    suspend fun disparar(ms: Int = 150) {
        if (!Glitch.activo) return
        semilla = (semilla * 1103515245 + 12345) and 0x7fffffff
        progreso.snapTo(1f)
        progreso.animateTo(0f, tween(ms, easing = LinearEasing))
    }
}

private val RojoVigia = Color(0xFFFF3D4D)
private val SoloVerdeAzul = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
private val SoloRojo = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

@Composable
internal fun Modifier.glitch(anim: GlitchAnim, fuerza: Float = 1f): Modifier {
    val capa = rememberGraphicsLayer()
    return this.drawWithContent {
        val v = anim.progreso.value
        if (v <= 0.001f || !Glitch.activo) { drawContent(); return@drawWithContent }
        capa.record { this@drawWithContent.drawContent() }
        val d = 8f * v * fuerza * density
        // Verde y azul algo a la derecha, rojo a la izquierda y sumado encima: donde coinciden se ve el color original.
        capa.colorFilter = SoloVerdeAzul; capa.blendMode = BlendMode.SrcOver
        translate(left = d) { drawLayer(capa) }
        capa.colorFilter = SoloRojo; capa.blendMode = BlendMode.Plus
        translate(left = -d) { drawLayer(capa) }
        // Dos o tres bandas horizontales desplazadas, con un velo rojo muy tenue.
        capa.colorFilter = null; capa.blendMode = BlendMode.SrcOver
        val azar = Random(anim.semilla)
        repeat(2 + azar.nextInt(2)) {
            val y = azar.nextFloat() * size.height * 0.9f
            val h = size.height * (0.03f + azar.nextFloat() * 0.07f)
            val desp = (azar.nextFloat() - 0.5f) * 2f * 36f * v * fuerza * density
            clipRect(0f, y, size.width, y + h) { translate(left = desp) { drawLayer(capa) } }
            drawRect(RojoVigia.copy(alpha = 0.16f * v), Offset(0f, y), Size(size.width, h))
        }
    }
}

private const val Glifos = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ01<>/\\"

/**
 * Texto que se «decodifica» al enfocarse: los caracteres no resueltos son katakana o glifos al azar en rojo vigía y se fijan de izquierda a
 * derecha en `ms` ms; los dos primeros pasos llevan además un desplazamiento horizontal de 1,5 dp (separación RGB aproximada). Un solo
 * `Animatable` por texto, sin bitmaps; si cambian el texto o el pulso a mitad, la animación se cancela y arranca la nueva. Sin efecto con el
 * ajuste apagado, con la flecha mantenida (`Glitch.repetido`) o si `activo` es falso: el texto final se pinta directo.
 */
@Composable
internal fun TextoDecodificado(texto: String, style: TextStyle, modifier: Modifier = Modifier, activo: Boolean = true, maxLines: Int = 1, ms: Int = 260, clave: Any = texto) {
    val pulso = Glitch.pulso.intValue
    val prog = remember { Animatable(1f) }
    LaunchedEffect(clave, pulso, activo) {
        if (!activo || !Glitch.activo || Glitch.repetido || texto.isBlank()) { prog.snapTo(1f); return@LaunchedEffect }
        prog.snapTo(0f)
        prog.animateTo(1f, tween(ms, easing = LinearEasing))
    }
    // Solo se recompone cada ~35 ms (un paso), no en cada fotograma de la animación.
    val paso by remember { androidx.compose.runtime.derivedStateOf { if (prog.value >= 1f) -1 else (prog.value * ms / 35f).toInt() } }
    val p = if (paso < 0) 1f else paso * 35f / ms
    if (p >= 1f) { BasicText(texto, style = style, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis); return }
    val fijos = (p * texto.length).toInt()
    val azar = Random(texto.hashCode() * 31 + paso * 7919)
    val mostrado = buildAnnotatedString {
        append(texto.substring(0, fijos))
        withStyle(SpanStyle(color = RojoVigia)) {
            for (i in fijos until texto.length) append(if (texto[i] == ' ') ' ' else Glifos[azar.nextInt(Glifos.length)])
        }
    }
    val d = if (paso < 2) 1.5f else 0f
    BasicText(mostrado, style = style, modifier = modifier.graphicsLayer { translationX = d * density }, maxLines = maxLines, overflow = TextOverflow.Clip)
}
