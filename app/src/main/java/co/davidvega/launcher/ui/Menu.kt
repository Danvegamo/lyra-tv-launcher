package co.davidvega.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.Doblaje
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.sistema.Abrir
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Menú ☰ de un título, con el mismo lenguaje visual que la ficha: panel lateral que se desliza desde la izquierda sobre la portada, y las opciones
 * como filas grandes con ícono (Ver en…, tráiler, Mi lista, Ya lo vi, No recomendar, Quitar de Seguir viendo). Es el mismo desde cualquier fila,
 * desde la búsqueda y desde la propia ficha.
 */
@Composable
fun MenuTitulo(t: Titulo, vm: LauncherVM, alCerrar: () -> Unit, abrirFicha: (() -> Unit)? = null, nombres: Map<String, String> = emptyMap()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vistos by vm.vistos.collectAsState()
    val lista by vm.milista.collectAsState()
    val visto = vistos.any { it.medio == t.medio && it.id == t.id }
    val enLista = lista.any { it.medio == t.medio && it.id == t.id }
    var aviso by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Doblaje.iniciar(ctx) }
    var revDoblaje by remember { mutableStateOf(0) }
    val manualDoblaje = revDoblaje.let { Doblaje.manual(t.medio, t.id) }
    val primero = remember { FocusRequester() }
    val inst = Abrir.instalados(ctx, t, vm.paquetes)
    val deSeguir = t.motivo?.startsWith("Seguir viendo") == true
    val entra = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entra.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }

    PanelDialogo(alCerrar = alCerrar) {
        Box(Modifier.fillMaxSize()) {
            (t.fondo ?: t.poster)?.let { img ->
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(img).size(1280, 720).allowRgb565(false).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tema.Fondo.copy(alpha = 0.97f), 0.5f to Tema.Fondo.copy(alpha = 0.8f), 1f to Tema.Fondo.copy(alpha = 0.2f))).graphicsLayer { alpha = entra.value })
            t.poster?.let { img ->
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(img).size(342, 513).build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 120.dp).size(240.dp, 360.dp).clip(RoundedCornerShape(14.dp)).graphicsLayer { alpha = entra.value },
                )
            }
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 64.dp).width(430.dp).graphicsLayer { alpha = entra.value; translationX = -(1f - entra.value) * 120.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(t.titulo, style = txt(26, FontWeight.Bold).copy(shadow = null), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(listOf(if (t.medio == "tv") "Serie" else "Película", t.anio).filter { it.isNotBlank() }.joinToString("  ·  "), style = txt(14, color = Tema.Secundario).copy(shadow = null))
                Spacer(Modifier.height(8.dp))
                inst.forEachIndexed { i, sv ->
                    FilaMenu("▶", "Ver en ${nombres[sv] ?: sv}", if (i == 0) Modifier.focusRequester(primero) else Modifier) {
                        alCerrar(); verEn(vm.viewModelScope, ctx, vm, t, false, {}, {}, servicio = sv)
                    }
                }
                if (t.id > 0) FilaMenu("▷", "Ver tráiler", if (inst.isEmpty()) Modifier.focusRequester(primero) else Modifier) {
                    scope.launch {
                        aviso = "Buscando el tráiler…"
                        val clave = vm.trailer(t)
                        if (clave == null) aviso = "TMDB no tiene tráiler de este título" else { TrailerEnCurso.ver.value = t to clave; alCerrar() }
                    }
                }
                FilaMenu(if (enLista) "−" else "+", if (enLista) "Quitar de Mi lista" else "Añadir a Mi lista", if (t.id > 0 || inst.isNotEmpty()) Modifier else Modifier.focusRequester(primero)) { vm.alternarLista(t); alCerrar() }
                if (t.id > 0) FilaMenu("✓", if (visto) "No lo he visto" else "Ya lo vi", Modifier) { vm.alternarVisto(t); alCerrar() }
                // Doblaje: lo que el usuario vio de verdad manda sobre JustWatch; pulsar la opción activa la quita.
                if (t.id > 0 && t.medio == "tv") {
                    FilaMenu(if (manualDoblaje == true) "✓" else "ES", "Lo vi doblado", Modifier) { Doblaje.marcar(t.medio, t.id, if (manualDoblaje == true) null else true); revDoblaje++; alCerrar() }
                    FilaMenu(if (manualDoblaje == false) "✓" else "SUB", "Lo vi subtitulado", Modifier) { Doblaje.marcar(t.medio, t.id, if (manualDoblaje == false) null else false); revDoblaje++; alCerrar() }
                }
                FilaMenu("⊘", "No recomendar", Modifier) { vm.noRecomendar(t); alCerrar() }
                if (deSeguir) FilaMenu("✕", "Quitar de Seguir viendo", Modifier) { vm.quitarDeSeguir(t); alCerrar() }
                abrirFicha?.let { abrir -> FilaMenu("▤", "Abrir ficha", Modifier) { alCerrar(); abrir() } }
                aviso?.let { BasicText(it, style = txt(14, color = Tema.Secundario).copy(shadow = null)) }
            }
        }
    }
}

/** Fila grande del menú: ícono en círculo y texto; con el foco, fondo claro, borde blanco y un poco más grande. */
@Composable
private fun FilaMenu(icono: String, texto: String, modifier: Modifier, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (foco) 1.05f else 1f, spring(dampingRatio = 0.75f, stiffness = 420f), label = "fm")
    val forma = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().height(46.dp).graphicsLayer { scaleX = escala; scaleY = escala; transformOrigin = TransformOrigin(0f, 0.5f) }
            .clip(forma).background(if (foco) Color.White else Color.White.copy(alpha = 0.10f))
            .border(if (foco) 2.dp else 1.dp, if (foco) Color.White else Color.White.copy(alpha = 0.14f), forma)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(if (foco) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            BasicText(icono, style = txt(15, FontWeight.Bold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null))
        }
        Spacer(Modifier.width(14.dp))
        BasicText(texto, style = txt(18, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null), maxLines = 1)
    }
}
