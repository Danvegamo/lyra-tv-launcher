package co.davidvega.launcher.ui

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.asImageBitmap
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.sistema.Abrir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Hero TV-first: solo información, sin botones. Póster, por qué se recomienda, título grande, una línea de datos (tipo, año,
 * duración o temporadas, nota, servicio) y la sinopsis en tres líneas. Las acciones van por el control del Fire TV:
 * OK ver, Menú opciones, Play/Pausa reproducir directo, Rebobinar y Avanzar cambian el título cuando rota.
 * Una línea de pistas muy discreta aparece al cambiar de título y se desvanece a los pocos segundos.
 */
@Composable
internal fun HeroGrande(
    t: Titulo?, cargando: Boolean, vm: LauncherVM, instalados: Set<String>, nombres: Map<String, String>, aviso: String?,
    primeraChip: androidx.compose.ui.focus.FocusRequester, alFocoChips: (Boolean) -> Unit, alBajar: () -> Unit, alSubir: () -> Unit, alElegir: (Titulo, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxWidth()) {
        Crossfade(targetState = t, animationSpec = tween(300), label = "hero") { x ->
            if (x == null) {
                Column(Modifier.padding(start = 48.dp, top = 27.dp)) {
                    if (cargando) BasicText("Preparando tus recomendaciones", style = txt(24, FontWeight.SemiBold))
                }
            } else {
                // La duración o las temporadas llegan de TMDB un momento después (con caché en memoria).
                val det by androidx.compose.runtime.produceState<co.davidvega.launcher.data.Detalle?>(null, x.medio, x.id) { value = null; delay(250); value = vm.detalle(x) }
                Row(Modifier.padding(start = 48.dp, top = 27.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.size(104.dp, 156.dp).clip(RoundedCornerShape(12.dp)).background(Tema.Superficie2)) {
                        (x.poster ?: x.fondo)?.let { img ->
                            AsyncImage(model = ImageRequest.Builder(ctx).data(img).size(342, 513).build(), contentDescription = x.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.width(460.dp)) { // no entra en la zona del reloj y la fecha (arriba a la derecha)
                        x.motivo?.let { BasicText(it.uppercase(), style = txtMono(13, Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)) }
                        TextoDecodificado(x.titulo, th(36, FontWeight.Bold))
                        Spacer(Modifier.height(6.dp))
                        val nota = if (x.nota > 0) "★ %.1f".format(Locale.US, x.nota) else ""
                        val serv = if (x.noDisponible) "No está en tus servicios" else x.servicios.firstOrNull { it in instalados }?.let { nombres[it] ?: it } ?: x.servicios.firstOrNull()?.let { nombres[it] ?: it }.orEmpty()
                        TextoDecodificado(listOf(if (x.medio == "tv") "Serie" else "Película", x.anio, det?.duracion.orEmpty(), nota, serv).filter { it.isNotBlank() }.joinToString("   ·   "), th(16, color = Tema.Secundario), clave = x.medio + x.id)
                        Spacer(Modifier.height(10.dp))
                        BasicText(x.sinopsis.ifBlank { "Sin sinopsis." }, style = th(16, color = Tema.Texto), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Pistas del control: aparecen al cambiar de título
        // y se apagan a los 5 s. Un aviso (p. ej. «sin enlace directo») va en su lugar.
        var pista by remember { mutableStateOf(true) }
        LaunchedEffect(t?.id, t?.medio) { pista = true; delay(5000); pista = false }
        val alfa by animateFloatAsState(if (aviso != null) 1f else if (pista && t != null) 0.7f else 0f, tween(600), label = "pistas")
        // Sin chips en el hero: las plataformas viven solo dentro de la ficha. Aquí solo hay texto, nada enfocable.
        if (t != null) BasicText(
            aviso?.uppercase() ?: "OK  FICHA   ·   MENÚ  OPCIONES   ·   PLAY  REPRODUCIR",
            style = txtMono(12, if (aviso != null) Tema.Texto else Tema.Terciario), maxLines = 1,
            modifier = Modifier.padding(start = 48.dp + 128.dp, top = 27.dp + 190.dp).graphicsLayer { alpha = alfa },
        )
    }
}

/** Texto del hero sin sombra: la sombra con desenfoque se redibuja en cada fotograma del fundido y en el Fire TV Stick pesa. El velo del fondo da la legibilidad. */
internal fun th(size: Int, peso: FontWeight = FontWeight.Normal, color: Color = Tema.Texto) = txt(size, peso, color).copy(shadow = null)

/**
 * Abre un título en su servicio (el preferido o el pedido). Con `exacto` (Play/Pausa) solo abre si hay enlace directo al título;
 * sin él se abre con la búsqueda del servicio. Sin app instalada, abre la ficha (o avisa si `exacto`).
 */
internal fun verEn(scope: CoroutineScope, ctx: Context, vm: LauncherVM, t: Titulo, exacto: Boolean, aviso: (String) -> Unit, alFicha: (Titulo) -> Unit, servicio: String? = null) {
    val s = servicio ?: Abrir.instalados(ctx, t, vm.paquetes).firstOrNull()
    if (s == null) { if (exacto) aviso("Ninguna app instalada lo tiene") else alFicha(t); return }
    scope.launch {
        val d = vm.ficha(t)
        val ruta = d.rutas[s]
        if (exacto && ruta == null) { aviso("Sin enlace directo: pulsa OK para buscarlo"); return@launch }
        vm.aprend.senal(t.copy(servicios = listOf(s)), 3.0); vm.eventos.registrar("ver_en", t, servicio = s); vm.eventos.pista(t, 2)
        vm.aprend.palabras(d.palabras, 3.0)
        Abrir.enServicio(ctx, t, s, vm.paquetes, ruta)
    }
}

/** Color de marca de cada app para el fondo sobrio del hero (sin imágenes: solo un degradado). */
private fun colorMarca(a: co.davidvega.launcher.sistema.AppItem): Color {
    val k = (a.paquete + " " + a.etiqueta).lowercase()
    return when {
        "prime" in k || "amazon.avod" in k -> Color(0xFF00A8E1)
        "hbo" in k || "wbd" in k -> Color(0xFF5B2BBE)
        "crunchy" in k -> Color(0xFFF47521)
        "smarttube" in k || "youtube" in k -> Color(0xFFD32F2F)
        "iptv" in k || "xuper" in k -> Color(0xFF2A4BC8)
        "moonlight" in k -> Color(0xFF8A8F98)
        "downloader" in k -> Color(0xFFE07B1A)
        "plore" in k -> Color(0xFF1E88C9)
        "appstore" in k || "apps" in k -> Color(0xFFF2A21A)
        else -> Color(0xFF6E6E73)
    }
}

private val BusquedasSugeridas = listOf("anime de acción", "crimen y gánsteres", "terror psicológico", "documentales de naturaleza")

/**
 * Hero de la fila de apps: representa la app enfocada (o la lupa), nunca películas. Nada se mueve solo y nada es enfocable,
 * así que no hay nada que parezca seleccionable y no lo sea. El cambio entre apps es un fundido corto.
 */
@Composable
internal fun HeroApp(app: co.davidvega.launcher.sistema.AppItem?, modifier: Modifier = Modifier) {
    Crossfade(targetState = app, animationSpec = tween(220), label = "heroApp", modifier = modifier.fillMaxSize()) { a ->
        if (a == null) return@Crossfade
        val lupa = a.paquete == co.davidvega.launcher.sistema.Apps.BUSCAR
        val marca = if (lupa) Color(0xFF8A8F98) else colorMarca(a)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to marca.copy(alpha = 0.40f), 0.5f to Color.Transparent))) {
            Row(Modifier.padding(start = 48.dp, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(240.dp, 135.dp).clip(RoundedCornerShape(18.dp)).background(Tema.Superficie2)) {
                    if (lupa) {
                        androidx.compose.foundation.Canvas(Modifier.align(androidx.compose.ui.Alignment.Center).size(72.dp)) {
                            val w = size.width
                            drawCircle(Tema.Texto, radius = w * 0.30f, center = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f))
                            drawLine(Tema.Texto, androidx.compose.ui.geometry.Offset(w * 0.64f, w * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.92f), strokeWidth = w * 0.10f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    } else if (a.imagen != null) {
                        androidx.compose.foundation.Image(a.imagen.asImageBitmap(), a.etiqueta, contentScale = if (a.esBanner) ContentScale.Crop else ContentScale.Fit,
                            modifier = if (a.esBanner) Modifier.fillMaxSize() else Modifier.align(androidx.compose.ui.Alignment.Center).size(88.dp))
                    } else BasicText(a.etiqueta, style = th(24, FontWeight.Bold), modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                }
                Column(Modifier.width(900.dp)) {
                    BasicText(a.etiqueta, style = th(44, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        if (lupa) "Un título, un tema o tu voz" else "Abre ${a.etiqueta} directamente",
                        style = th(18, color = Tema.Secundario), maxLines = 1,
                    )
                    if (lupa) {
                        Spacer(Modifier.height(14.dp))
                        BasicText("PRUEBA CON UN TEMA", style = txtMono(12, Tema.Terciario))
                        Spacer(Modifier.height(6.dp))
                        BasicText(BusquedasSugeridas.joinToString("   ·   ") { "«$it»" }, style = th(17, color = Tema.Texto), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(18.dp))
                    BasicText(if (lupa) "OK  BUSCAR" else "OK  ABRIR", style = txtMono(13, Tema.Secundario))
                }
            }
        }
    }
}
