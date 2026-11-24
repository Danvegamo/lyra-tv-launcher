package co.davidvega.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.DatosFicha
import co.davidvega.launcher.data.Detalle
import co.davidvega.launcher.data.Doblaje
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.sistema.Abrir
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Ficha a pantalla completa, tipo carátula: fondo grande con degradado, logo del título (TMDB) o su nombre, póster, una línea de datos
 * (año, géneros, duración o temporadas, nota), sinopsis, dirección y reparto, y los chips de plataforma con el primero enfocado.
 * OK en un chip abre el título en esa app; Menú abre Mi lista, Ya lo vi y No recomendar; Atrás vuelve a la tarjeta de origen.
 * Una fila de títulos parecidos queda debajo (Abajo desde los chips).
 */
@Composable
fun Ficha(inicial: Titulo, vm: LauncherVM, nombres: Map<String, String>, alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    remember { Doblaje.iniciar(ctx) }
    // Los parecidos de la ficha no deben pisar la tarjeta de origen: se restaura al cerrar.
    val origen = remember { MemoriaFoco.ultima }
    DisposableEffect(Unit) { onDispose { MemoriaFoco.ultima = origen } }
    var pila by remember { mutableStateOf(listOf(inicial)) }
    val t = pila.last()
    val vistosLista by vm.vistos.collectAsState()
    val visto = vistosLista.any { it.medio == t.medio && it.id == t.id }
    var datos by remember(t) { mutableStateOf<DatosFicha?>(null) }
    var det by remember(t) { mutableStateOf<Detalle?>(null) }
    LaunchedEffect(t) { datos = vm.ficha(t) }
    val base = datos?.resuelto?.takeIf { t.id <= 0 } ?: t
    LaunchedEffect(t, datos) { det = vm.detalle(base) }
    LaunchedEffect(t) { vm.aprend.senal(t, 1.0, abre = true); vm.eventos.registrar("ficha", t); vm.eventos.pista(t, 1) }
    LaunchedEffect(datos) { datos?.palabras?.let { vm.aprend.palabras(it, 1.0) } }

    val servicios = datos?.servicios ?: t.servicios
    // «Seguir viendo»: el servicio donde se estaba viendo es la primera chip, con el episodio («Continuar T2·E5 en Crunchyroll»).
    val seguir = t.motivo?.startsWith("Seguir viendo") == true
    val origen1 = if (seguir) t.servicios.firstOrNull() else null
    val serviciosChips = if (origen1 != null) listOf(origen1) + servicios.filter { it != origen1 } else servicios
    val episodio = t.motivo?.split(" · ")?.filter { Regex("T\\d+|E\\d+").matches(it) }?.joinToString("·").orEmpty()
    val etiquetas = if (origen1 != null) mapOf(origen1 to "Continuar " + (if (episodio.isNotEmpty()) "$episodio " else "") + "en " + (nombres[origen1] ?: origen1)) else emptyMap()
    val primero = remember(t) { FocusRequester() }
    val primerParecido = remember(t) { FocusRequester() }
    LaunchedEffect(t, datos != null) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }

    // Entrada: el contenido sube unos puntos y aparece; el fondo se asienta con un zoom suave.
    val entra = remember(t) { Animatable(0f) }
    LaunchedEffect(t) { entra.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    var aviso by remember(t) { mutableStateOf<String?>(null) }
    LaunchedEffect(aviso) { if (aviso != null) { delay(3500); aviso = null } }
    var menu by remember { mutableStateOf(false) }
    var revDoblaje by remember { mutableStateOf(0) }
    if (menu) MenuTitulo(t, vm, alCerrar = { menu = false; revDoblaje++ }, nombres = nombres)

    val glitchFicha = remember { GlitchAnim() }
    LaunchedEffect(t.medio, t.id) { glitchFicha.disparar(160) }
    PanelDialogo(alCerrar = { if (pila.size > 1) pila = pila.dropLast(1) else alCerrar() }) {
        Box(Modifier.fillMaxSize().glitch(glitchFicha).onPreviewKeyEvent { e -> if (e.key == Key.Menu) { if (e.type == KeyEventType.KeyUp) menu = true; true } else false }) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = entra.value; scaleX = 1.04f - 0.04f * entra.value; scaleY = 1.04f - 0.04f * entra.value }) {
                (t.fondo ?: t.poster)?.let { img ->
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(img).size(1280, 720).allowRgb565(false).build(),
                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tema.Fondo.copy(alpha = 0.97f), 0.55f to Tema.Fondo.copy(alpha = 0.78f), 1f to Tema.Fondo.copy(alpha = 0.25f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 0.85f to Tema.Fondo.copy(alpha = 0.95f), 1f to Tema.Fondo)))

            Column(Modifier.fillMaxSize().padding(top = 30.dp).graphicsLayer { alpha = entra.value; translationY = (1f - entra.value) * 36f }) {
                Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Box(Modifier.size(150.dp, 225.dp).clip(RoundedCornerShape(12.dp)).background(Tema.Superficie2)) {
                        (t.poster ?: t.fondo)?.let { img ->
                            AsyncImage(model = ImageRequest.Builder(ctx).data(img).size(342, 513).build(), contentDescription = t.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.width(640.dp)) {
                        t.motivo?.let { BasicText(it.uppercase(), style = txtMono(12, Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(6.dp)) }
                        val logo = det?.logo
                        if (logo != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(logo).build(), contentDescription = t.titulo, contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 72.dp).width(340.dp),
                            )
                        } else BasicText(t.titulo, style = th(38, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        val nota = if (t.nota > 0) "★ %.1f".format(Locale.US, t.nota) else ""
                        val gen = det?.generos.orEmpty().take(3).joinToString(", ")
                        val nTemp = det?.temporadas?.size ?: 0
                        val dur = det?.duracion.orEmpty().ifBlank { if (nTemp > 0) "$nTemp temporada" + (if (nTemp > 1) "s" else "") else "" }
                        BasicText(listOf(t.anio, gen, dur, nota).filter { it.isNotBlank() }.joinToString("   ·   "), style = th(16, color = Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Idioma de audio (anime o marcado a mano): se vuelve a leer al llegar JustWatch (datos) y al cerrar el menú.
                        val doblaje = remember(t, datos, revDoblaje) { if (Doblaje.mostrable(t)) Doblaje.resultado(t.medio, t.id) else null }
                        doblaje?.estado?.etiqueta?.let { et ->
                            Spacer(Modifier.height(4.dp))
                            BasicText(et.uppercase() + (doblaje.nota?.let { "  ·  $it" } ?: ""), style = txtMono(12, Tema.Texto), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (visto) { Spacer(Modifier.height(4.dp)); BasicText("✓ VISTA", style = txtMono(13, Tema.Texto)) }
                        Spacer(Modifier.height(10.dp))
                        BasicText(t.sinopsis.ifBlank { "Sin sinopsis." }, style = th(16, color = Tema.Texto), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        det?.direccion?.let { BasicText("Dirección: $it", style = th(14, color = Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        det?.reparto?.takeIf { it.isNotEmpty() }?.let { BasicText("Reparto: " + it.take(5).joinToString(", "), style = th(14, color = Tema.Secundario), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        det?.temporadas?.takeIf { it.size > 1 }?.let { BasicText("Temporadas: " + it.joinToString("  ") { (n, e) -> "T$n·$e ep" }, style = txtMono(12, Tema.Terciario), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.padding(start = 48.dp)) {
                    // Los chips salen de inmediato con lo que ya se sabe (servicio de origen, TMDB en caché); el resto se suma al llegar.
                    ChipsPlataforma(
                        t, serviciosChips, vm, nombres, primero = primero, etiquetas = etiquetas,
                        alBajar = { runCatching { primerParecido.requestFocus() } },
                        alElegir = { sv ->
                            val instalados = Abrir.instalados(ctx, t.copy(servicios = servicios), vm.paquetes)
                            if (sv == "iptv") { vm.aprend.senal(t, 3.0); vm.eventos.registrar("buscar_en", t, servicio = "iptv"); vm.eventos.pista(t, 2); Abrir.enIptv(ctx, t) }
                            else if (sv !in instalados) aviso = (nombres[sv] ?: sv) + " no está instalado"
                            else {
                                vm.aprend.senal(t.copy(servicios = listOf(sv)), 3.0); vm.eventos.registrar("ver_en", t, servicio = sv); vm.eventos.pista(t, 2)
                                datos?.palabras?.let { vm.aprend.palabras(it, 3.0) }
                                Abrir.enServicio(ctx, t, sv, vm.paquetes, datos?.rutas?.get(sv))
                            }
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    BasicText(
                        aviso?.uppercase() ?: ("OK  ABRIR   ·   MENÚ  OPCIONES" + if (datos == null) "   ·   COMPLETANDO…" else ""),
                        style = txtMono(12, if (aviso != null) Tema.Texto else Tema.Terciario), maxLines = 1,
                    )
                }
                val parecidos = datos?.parecidos.orEmpty()
                if (parecidos.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    BasicText("Títulos parecidos", style = txt(16, FontWeight.SemiBold, Tema.Texto), modifier = Modifier.padding(start = 48.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        itemsIndexed(parecidos, key = { _, p -> p.medio + p.id }) { i, p ->
                            Box(if (i == 0) Modifier.focusRequester(primerParecido) else Modifier) {
                                TarjetaTitulo(p, vm.paquetes.keys, onFoco = {}, onAbrir = { pila = pila + p })
                            }
                        }
                    }
                }
            }
        }
    }
}
