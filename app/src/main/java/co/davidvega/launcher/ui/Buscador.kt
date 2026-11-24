package co.davidvega.launcher.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.data.FiltrosTema
import co.davidvega.launcher.data.ResultadoTema
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import co.davidvega.launcher.sistema.Abrir
import kotlinx.coroutines.delay
import java.util.Locale

private val Filas = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
private val TeclaFondo = Tema.Superficie2
private val TeclaFoco = Tema.Texto

/**
 * Buscador: teclado en pantalla propio (A-Z, 0-9, espacio, borrar) cómodo con el D-pad, o el teclado del sistema, o voz si el
 * aparato tiene un reconocedor. Resultados en vivo de TMDB (/search/multi, es-CO) con debounce de 300 ms, y una fila
 * «Buscar directo en…» que manda el texto tal cual a HBO Max, Crunchyroll, IPTV y Prime Video.
 */
@Composable
fun Buscador(vm: LauncherVM, nombres: Map<String, String>, inicial: String, alCerrar: () -> Unit, abrirFicha: (Titulo) -> Unit) {
    val ctx = LocalContext.current
    // Con clave: una búsqueda por voz con el buscador ya abierto reemplaza la consulta.
    var consulta by remember(inicial) { mutableStateOf(inicial) }
    var resultados by remember { mutableStateOf<List<Titulo>>(emptyList()) }
    var estado by remember { mutableStateOf("") } // "", "buscando", "vacio"
    var sistema by remember { mutableStateOf(false) }
    // Búsqueda por temática: filtros que se recuerdan, filas armadas y las páginas extra que se piden al final de una fila.
    var filtros by remember { mutableStateOf(FiltrosTema.cargar(ctx)) }
    var tema by remember { mutableStateOf<ResultadoTema?>(null) }
    var masMejores by remember { mutableStateOf<List<Titulo>>(emptyList()) }
    var masGusto by remember { mutableStateOf<List<Titulo>>(emptyList()) }
    var pagMejores by remember { mutableStateOf(2) }
    var pagGusto by remember { mutableStateOf(2) }
    var finMejores by remember { mutableStateOf(false) }
    var finGusto by remember { mutableStateOf(false) }
    var pidiendoMas by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recientes by vm.recientes.lista.collectAsState()
    val vistos by vm.vistos.collectAsState()
    val noRec by vm.ocultosNo.collectAsState()
    var menuSobre by remember { mutableStateOf<Titulo?>(null) }
    val primera = remember { FocusRequester() }
    val campo = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    val hayVoz = remember { ctx.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null }
    val voz = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { consulta = it }
    }

    LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primera.requestFocus() }.isSuccess) return@LaunchedEffect } }
    LaunchedEffect(sistema) { if (sistema) { delay(150); runCatching { campo.requestFocus(); teclado?.show() } } }
    // Debounce de 300 ms: cada tecla (o cambio de filtro) cancela la búsqueda anterior. En paralelo van la búsqueda de títulos y personas
    // y el intento de entender un tema; si hay tema salen sus filas, si no la cuadrícula de siempre.
    LaunchedEffect(consulta, filtros) {
        if (consulta.isBlank()) { resultados = emptyList(); estado = ""; tema = null; return@LaunchedEffect }
        estado = "buscando"
        val t0 = System.nanoTime()
        delay(300)
        coroutineScope {
            val coincidencias = async { vm.buscar(consulta) }
            val entendido = vm.tematica.entender(consulta)
            if (entendido == null) {
                val r = coincidencias.await()
                tema = null; resultados = r
                estado = if (r.isEmpty()) "vacio" else ""
            } else {
                // Las filas del tema salen primero; las coincidencias de título (más lentas, piden servicios) se suman al llegar.
                var t = vm.filasTema(entendido, filtros, emptyList())
                masMejores = emptyList(); masGusto = emptyList(); pagMejores = 2; pagGusto = 2; finMejores = false; finGusto = false
                tema = t; resultados = t.mejores
                android.util.Log.i("LauncherTema", "«$consulta» coincidencias=${t.coincidencias.size} mejores=${t.mejores.size} gusto=${t.gusto.size} inspirado(${t.inspiradoEn})=${t.inspirado.size} vistas=${t.vistas.size} ms=${(System.nanoTime() - t0) / 1_000_000} palabras=${entendido.palabras.size} generos=${entendido.generosPelicula.size}/${entendido.generosSerie.size}")
                estado = if (t.coincidencias.isEmpty() && t.mejores.isEmpty() && t.gusto.isEmpty() && t.inspirado.isEmpty()) "vacio" else ""
                val c = coincidencias.await().take(12).filter { x -> (x.medio + ":" + x.id) !in vm.ocultosNo.value }
                if (c.isNotEmpty()) { t = ResultadoTema(t.tema, c, t.mejores, t.gusto, t.inspiradoEn, t.inspirado, t.vistas); tema = t }
            }
        }
    }

    PanelDialogo(alCerrar = alCerrar) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            // Izquierda: campo y teclado.
            Column(Modifier.width(346.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(Tema.Superficie).border(2.dp, Tema.Texto, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (sistema) {
                        BasicTextField(
                            value = consulta, onValueChange = { consulta = it }, singleLine = true,
                            textStyle = txt(20, FontWeight.SemiBold),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { teclado?.hide(); sistema = false; runCatching { primera.requestFocus() } }),
                            modifier = Modifier.fillMaxWidth().focusRequester(campo),
                        )
                    } else {
                        BasicText(if (consulta.isEmpty()) "Escribe un título…" else consulta, style = txt(20, FontWeight.SemiBold, if (consulta.isEmpty()) Tema.Secundario else Tema.Texto), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Filas.forEachIndexed { r, fila ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        fila.forEachIndexed { c, ch ->
                            Tecla(ch.toString(), 52.dp, if (r == 0 && c == 0) Modifier.focusRequester(primera) else Modifier) { consulta += ch.lowercaseChar() }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tecla("Espacio", 110.dp) { if (consulta.isNotEmpty() && !consulta.endsWith(" ")) consulta += " " }
                    Tecla("Borrar", 110.dp) { consulta = consulta.dropLast(1) }
                    Tecla("Limpiar", 110.dp) { consulta = "" }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tecla("Teclado", 110.dp) { sistema = true }
                    if (hayVoz) Tecla("Voz", 110.dp) {
                        try { voz.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")) }
                        catch (e: Exception) { Toast.makeText(ctx, "No hay reconocimiento de voz", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            // Derecha: búsqueda directa y resultados.
            Column(Modifier.weight(1f)) {
                BasicText("BUSCAR DIRECTO EN", style = txt(11, FontWeight.Bold, Tema.Secundario))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp, bottom = 12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) { // con más de 4 apps, la fila se desplaza con el foco
                    remember { Abrir.destinosBusqueda(ctx, vm.paquetes, nombres) }.forEach { (k, n) ->
                        Boton(n, Modifier, ancho = 118.dp) {
                            if (consulta.isBlank()) Toast.makeText(ctx, "Escribe algo para buscar", Toast.LENGTH_SHORT).show()
                            else { vm.recientes.guardar(consulta); vm.eventos.registrar("busqueda_directa", null, servicio = k, texto = consulta.trim()); Abrir.buscarDirecto(ctx, k, consulta.trim(), vm.paquetes); alCerrar() }
                        }
                    }
                }
                if (tema != null && consulta.isNotBlank()) FiltrosBarra(filtros) { filtros = it; it.guardar(ctx) }
                when {
                    consulta.isBlank() -> {
                        BasicText("BÚSQUEDAS RECIENTES", style = txt(11, FontWeight.Bold, Tema.Secundario))
                        if (recientes.isEmpty()) BasicText("Aquí aparecerán tus últimas 10 búsquedas.", style = txt(14, color = Tema.Secundario), modifier = Modifier.padding(top = 8.dp))
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            recientes.forEach { r -> Reciente(r) { consulta = r } }
                        }
                    }
                    estado == "buscando" && resultados.isEmpty() -> BasicText("Buscando «$consulta»…", style = txt(16, color = Tema.Secundario))
                    estado == "vacio" -> BasicText("Sin resultados para «$consulta». Prueba «Buscar directo en…».", style = txt(16, color = Tema.Secundario))
                    tema != null -> FilasTema(
                        tema!!, nombres, vm, vistos.map { it.clave }.toSet() + vm.vistosExternos, masMejores, masGusto,
                        alAbrir = { t -> vm.recientes.guardar(consulta); vm.eventos.registrar("busqueda", t, texto = consulta.trim()); vm.aprend.soloGeneros(t, 1.0); abrirFicha(t) },
                        alMenu = { menuSobre = it }, ocultos = noRec,
                        masDeMejores = { if (!pidiendoMas && !finMejores) scope.launch {
                            pidiendoMas = true
                            val ya = (tema!!.mejores + masMejores).map { it.medio + it.id }.toSet()
                            var nuevos = emptyList<Titulo>()
                            // Si una página no trae nada nuevo (repetidos o filtrada), se prueban las siguientes antes de rendirse.
                            for (i in 0 until 3) { val n = pagMejores; pagMejores++; nuevos = vm.tematica.pagina(tema!!.tema, filtros, true, n).filter { (it.medio + it.id) !in ya }; if (nuevos.isNotEmpty()) break }
                            if (nuevos.isEmpty()) finMejores = true else masMejores = (masMejores + nuevos).distinctBy { it.medio + it.id }
                            pidiendoMas = false
                        } },
                        masDeGusto = { if (!pidiendoMas && !finGusto) scope.launch {
                            pidiendoMas = true
                            val ya = (tema!!.gusto + masGusto).map { it.medio + it.id }.toSet() + tema!!.mejores.map { it.medio + it.id } + masMejores.map { it.medio + it.id }
                            var nuevos = emptyList<Titulo>()
                            for (i in 0 until 3) { val n = pagGusto; pagGusto++; nuevos = vm.tematica.pagina(tema!!.tema, filtros, false, n).filter { (it.medio + it.id) !in ya }; if (nuevos.isNotEmpty()) break }
                            if (nuevos.isEmpty()) finGusto = true else masGusto = (masGusto + nuevos).distinctBy { it.medio + it.id }
                            pidiendoMas = false
                        } },
                        finMejores = finMejores, finGusto = finGusto, pidiendoMas = pidiendoMas,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize(),
                    ) {
                        items(resultados.filter { (it.medio + ":" + it.id) !in noRec }, key = { it.medio + it.id }) { t ->
                            Resultado(t, nombres, visto = vistos.any { it.medio == t.medio && it.id == t.id }, onMenu = { menuSobre = t }) { vm.recientes.guardar(consulta); vm.eventos.registrar("busqueda", t, texto = consulta.trim()); vm.aprend.soloGeneros(t, 1.0); abrirFicha(t) }
                        }
                    }
                }
            }
        }
    }
    // ☰ o mantener OK sobre un resultado: el mismo menú que en las filas (Ver en…, Mi lista, Ya lo vi, No recomendar).
    menuSobre?.let { t -> MenuTitulo(t, vm, alCerrar = { menuSobre = null }, abrirFicha = { menuSobre = null; abrirFicha(t) }, nombres = nombres) }
}

@Composable
private fun Reciente(texto: String, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (foco) TeclaFoco else TeclaFondo).onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { BasicText(texto, style = txt(15, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null)) }
}

@Composable
private fun Tecla(texto: String, ancho: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    Box(
        modifier.size(ancho, 44.dp).clip(RoundedCornerShape(8.dp)).background(if (foco) TeclaFoco else TeclaFondo)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar),
        contentAlignment = Alignment.Center,
    ) { BasicText(texto, style = txt(if (texto.length == 1) 20 else 14, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null)) }
}

/** Resultado: póster, ★ nota, y debajo título, año y servicio (siempre visibles). */
@Composable
private fun Resultado(t: Titulo, nombres: Map<String, String>, visto: Boolean = false, onMenu: (() -> Unit)? = null, alAbrir: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.width(112.dp)) {
        Tarjeta(Modifier, 112.dp, 168.dp, 10.dp, 1.08f, 1.04f, onFoco = {}, onClick = alAbrir, onMenu = onMenu) { _ ->
            val url = t.poster ?: t.fondoTarjeta
            if (url != null) AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(224, 336).build(), contentDescription = t.titulo,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            ) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(Tema.Placeholders[0])))
            if (visto) MarcaVisto(Modifier.align(Alignment.BottomStart).padding(5.dp))
            if (t.nota > 0) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Tema.VeloInsignia).padding(horizontal = 5.dp, vertical = 1.dp)) {
                BasicText("★ %.1f".format(Locale.US, t.nota), style = txt(11, FontWeight.Bold, Tema.Texto))
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(t.titulo, style = txt(12, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val serv0 = if (t.servicios.isEmpty()) (if (t.noDisponible) "Sin servicio en CO" else "") else t.servicios.joinToString(" · ") { nombres[it] ?: it }
        val serv = t.motivo?.takeIf { it.startsWith("Con ") }?.let { m -> if (serv0.isBlank()) m else "$serv0 · $m" } ?: serv0
        BasicText(listOf(t.anio, serv).filter { it.isNotBlank() }.joinToString(" · "), style = txt(10, color = Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Filtros de la búsqueda por temática: cada uno es una pieza que se cambia con OK (Ocultar vistas, tipo, mis servicios, época, nota). */
@Composable
private fun FiltrosBarra(f: FiltrosTema, alCambiar: (FiltrosTema) -> Unit) {
    val epocas = listOf("Todas las épocas", "2020 en adelante", "Años 2010", "Años 2000", "Antes del 2000")
    val tipos = listOf("Películas y series", "Solo películas", "Solo series")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
        FiltroPieza("Ocultar vistas: " + if (f.ocultarVistas) "sí" else "no", f.ocultarVistas) { alCambiar(f.copy(ocultarVistas = !f.ocultarVistas)) }
        FiltroPieza(tipos[f.medio], f.medio != 0) { alCambiar(f.copy(medio = (f.medio + 1) % 3)) }
        FiltroPieza("En mis servicios: " + if (f.misServicios) "sí" else "no", f.misServicios) { alCambiar(f.copy(misServicios = !f.misServicios)) }
        FiltroPieza(epocas[f.epoca], f.epoca != 0) { alCambiar(f.copy(epoca = (f.epoca + 1) % 5)) }
        FiltroPieza("Nota 7 o más: " + if (f.notaAlta) "sí" else "no", f.notaAlta) { alCambiar(f.copy(notaAlta = !f.notaAlta)) }
    }
}

@Composable
private fun FiltroPieza(texto: String, activo: Boolean, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(18.dp)
    Box(
        Modifier.clip(forma).background(if (foco) TeclaFoco else if (activo) Color.White.copy(alpha = 0.22f) else TeclaFondo)
            .border(if (foco) 2.dp else 1.dp, if (foco) Color.White else Color.White.copy(alpha = 0.15f), forma)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { BasicText(texto, style = txt(13, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null), maxLines = 1) }
}

/** Las filas de una búsqueda por temática, una debajo de otra; las dos primeras terminan con una pieza «Ver más» que pide otra página. */
@Composable
private fun FilasTema(
    t: ResultadoTema, nombres: Map<String, String>, vm: LauncherVM, vistas: Set<String>, masMejores: List<Titulo>, masGusto: List<Titulo>,
    alAbrir: (Titulo) -> Unit, alMenu: (Titulo) -> Unit, ocultos: Set<String>, masDeMejores: () -> Unit, masDeGusto: () -> Unit,
    finMejores: Boolean, finGusto: Boolean, pidiendoMas: Boolean,
) {
    data class F(val titulo: String, val items: List<Titulo>, val mas: (() -> Unit)?, val fin: Boolean = false)
    val filas = listOf(
        F("COINCIDENCIAS CON «" + t.tema.etiqueta.uppercase() + "»", t.coincidencias, null),
        F("MEJORES PARA «" + t.tema.etiqueta.uppercase() + "»", (t.mejores + masMejores).distinctBy { it.medio + it.id }, masDeMejores, finMejores),
        F("POR TU GUSTO", (t.gusto + masGusto).distinctBy { it.medio + it.id }, masDeGusto, finGusto),
        F("INSPIRADO EN " + (t.inspiradoEn ?: "").uppercase(), t.inspirado, null),
        F("YA LAS VISTE", t.vistas, null),
    ).map { f -> f.copy(items = f.items.filter { (it.medio + ":" + it.id) !in ocultos }) }.filter { it.items.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(filas.size) { i ->
            val f = filas[i]
            Column {
                BasicText(f.titulo, style = txt(11, FontWeight.Bold, Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(f.items, key = { _, x -> f.titulo + x.medio + x.id }) { _, x ->
                        Resultado(x, nombres, visto = (x.medio + ":" + x.id) in vistas, onMenu = { alMenu(x) }) { alAbrir(x) }
                    }
                    f.mas?.let { mas ->
                        item(key = "mas_" + f.titulo) {
                            VerMas(if (f.fin) "No hay más" else if (pidiendoMas) "Buscando…" else "Ver más", mas)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerMas(texto: String, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    Box(
        Modifier.size(112.dp, 168.dp).clip(RoundedCornerShape(10.dp)).background(if (foco) TeclaFoco else TeclaFondo)
            .border(if (foco) 2.dp else 1.dp, if (foco) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar),
        contentAlignment = Alignment.Center,
    ) { BasicText(texto, style = txt(15, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null)) }
}
