package co.davidvega.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.sistema.Abrir

/** Color de marca de cada servicio (los chips son lo único con color de la interfaz). */
private val ColorMarca = mapOf(
    "netflix" to Color(0xFFE50914), "prime" to Color(0xFF00A8E1), "hbomax" to Color(0xFF5B2EA6),
    "crunchyroll" to Color(0xFFF47521), "iptv" to Color(0xFF3A3A3C),
)

/** Última tarjeta que tuvo el foco: «Abajo» desde los chips vuelve a ella. */
internal object MemoriaFoco { var ultima: FocusRequester? = null }

/**
 * Chips de plataforma de un título: uno por servicio donde se puede ver (más «IPTV» para la búsqueda guiada), con el icono de la app
 * y el color de la marca. Los servicios sin app instalada salen apagados. Son lo único enfocable del hero y de la ficha:
 * Izquierda y Derecha se mueven entre ellos, OK abre el título en esa app, Abajo vuelve a la tarjeta de antes y Arriba sube
 * (`alSubir`). `primero` recibe el foco con «Arriba» desde las filas (o al abrir la ficha).
 */
@Composable
internal fun ChipsPlataforma(
    t: Titulo, servicios: List<String>, vm: LauncherVM, nombres: Map<String, String>, modifier: Modifier = Modifier,
    primero: FocusRequester? = null, alFoco: (Boolean) -> Unit = {}, alBajar: (() -> Unit)? = null, alSubir: (() -> Unit)? = null,
    alElegir: (String) -> Unit, etiquetas: Map<String, String> = emptyMap(),
) {
    val ctx = LocalContext.current
    val foco = LocalFocusManager.current
    val lista = (if (t.noDisponible) emptyList() else servicios) + "iptv"
    Row(
        modifier.onFocusChanged { alFoco(it.hasFocus) }.onPreviewKeyEvent { e ->
            val dir = e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
            if (!dir) return@onPreviewKeyEvent false
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            when (e.key) {
                Key.DirectionLeft -> foco.moveFocus(FocusDirection.Left)
                Key.DirectionRight -> foco.moveFocus(FocusDirection.Right)
                Key.DirectionDown -> alBajar?.invoke()
                else -> alSubir?.invoke()
            }
            true
        },
        horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        lista.forEachIndexed { i, s ->
            val pkg = vm.paquetes[s]
            val icono = remember(pkg) { pkg?.let { runCatching { ctx.packageManager.getApplicationIcon(it).toBitmap(64, 64).asImageBitmap() }.getOrNull() } }
            val instalado = s == "iptv" || (pkg != null && (ctx.packageManager.getLaunchIntentForPackage(pkg) != null || ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) != null))
            ChipPlataforma(
                if (s == "iptv") "Buscar en IPTV" else etiquetas[s] ?: nombres[s] ?: s, ColorMarca[s] ?: Tema.Superficie2, icono, instalado,
                if (i == 0 && primero != null) Modifier.focusRequester(primero) else Modifier,
            ) { alElegir(s) }
        }
    }
}

@Composable
private fun ChipPlataforma(texto: String, color: Color, icono: androidx.compose.ui.graphics.ImageBitmap?, activo: Boolean, modifier: Modifier, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (foco) 1.1f else 1f, spring(dampingRatio = 0.7f, stiffness = 420f), label = "chip")
    val brillo by animateFloatAsState(if (foco) 1f else 0f, spring(stiffness = 300f), label = "chipb")
    val forma = RoundedCornerShape(12.dp)
    Row(
        modifier.graphicsLayer { scaleX = escala; scaleY = escala }
            .shadow((22 * brillo).dp, forma, clip = false, ambientColor = Color.White, spotColor = Color.White)
            .clip(forma).background(color.copy(alpha = if (activo) 1f else 0.35f))
            .border(if (foco) 3.dp else 1.dp, if (foco) Tema.Foco else Color.White.copy(alpha = 0.18f), forma)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar)
            .height(40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono != null) { Image(icono, null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))); Spacer(Modifier.width(8.dp)) }
        BasicText(texto + if (activo) "" else " (no instalado)", style = th(15, FontWeight.Bold, Color.White), maxLines = 1)
    }
}
