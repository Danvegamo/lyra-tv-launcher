package co.davidvega.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import co.davidvega.launcher.LauncherVM
import co.davidvega.launcher.data.Doblaje
import co.davidvega.launcher.data.Fila
import co.davidvega.launcher.data.Modo
import co.davidvega.launcher.data.Titulo
import co.davidvega.launcher.MainActivity
import co.davidvega.launcher.sistema.Abrir
import co.davidvega.launcher.sistema.Apps
import co.davidvega.launcher.sistema.AppItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// Margen de zona segura de TV: 5 % del ancho (48 dp de 960) y del alto (27 dp de 540).
private val MargenX = 48.dp
private val MargenY = 27.dp

private val Degradados get() = Tema.Placeholders

private val NombreServicio = mapOf("netflix" to "Netflix", "prime" to "Prime Video", "hbomax" to "HBO Max", "crunchyroll" to "Crunchyroll")

// Jerarquía tipográfica: título 32 / etiqueta de fila 18 / cuerpo 15 / meta 14 / pie de tarjeta 12. Sombra suave sobre imágenes.
private val Sombra get() = Shadow(Tema.SombraTexto, Offset(0f, 2f), 8f)
internal fun txt(size: Int, peso: FontWeight = FontWeight.Normal, color: Color = Tema.Texto) =
    TextStyle(fontSize = size.sp, fontWeight = peso, color = color, shadow = Sombra)

private sealed interface Panel {
    data object Ajustes : Panel
    data object Buscar : Panel
    data object Aprendido : Panel
    data object Vistos : Panel
    data object Estado : Panel
    data object NoRecomendar : Panel
}

// El desplazamiento vertical lo gobierna el pivote de Pantalla (la fila enfocada sube a un punto fijo).
@OptIn(ExperimentalFoundationApi::class)
private val SinSaltoVertical = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

// Horizontal: alinea la tarjeta enfocada a la izquierda con el margen de la fila.
@OptIn(ExperimentalFoundationApi::class)
private fun saltoHorizontal(margen: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < margen -> offset - margen
        offset + size > containerSize - margen -> offset + size - (containerSize - margen)
        else -> 0f
    }
}

/** Pantalla principal tipo tvOS: fondo del contenido enfocado, hero, fila de apps y filas de recomendaciones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pantalla(vm: LauncherVM) {
    val estado by vm.estado.collectAsState()
    val appsSistema by vm.apps.collectAsState()
    // «Buscar» es una tarjeta propia al principio de la fila de apps.
    val apps = remember(appsSistema) { listOf(AppItem(Apps.BUSCAR, "Buscar", null, false, 0)) + appsSistema }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lista = rememberLazyListState()
    val pivote = with(LocalDensity.current) { 8.dp.roundToPx() }
    val margenFila = with(LocalDensity.current) { 56.dp.toPx() }

    // Servicios cuya app está instalada (Netflix no lo está en este Fire TV).
    val instalados = remember(apps, estado.filas) {
        vm.paquetes.filterValues { ctx.packageManager.getLaunchIntentForPackage(it) != null || ctx.packageManager.getLeanbackLaunchIntentForPackage(it) != null }.keys
    }

    // «Mi lista» va primero cuando no está vacía.
    val miLista by vm.milista.collectAsState()
    // Aprendizaje: las filas se reordenan con lo aprendido (y una fila «Porque viste…» con el título de más señal).
    // Se recalcula al volver al inicio, no mientras hay una ficha o un panel abierto encima.
    val versionAprende by vm.aprend.version.collectAsState()
    val filaAncla by vm.filaAncla.collectAsState()
    val seguir by vm.seguir.collectAsState()
    val tendencias by vm.tendencias.collectAsState()
    val respaldo by vm.respaldo.collectAsState()
    val noRec by vm.ocultosNo.collectAsState()
    val vistos by vm.vistos.collectAsState()
    var fichaSobre by remember { mutableStateOf<Titulo?>(null) }
    var menuSobre by remember { mutableStateOf<Titulo?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var versionVista by remember { mutableIntStateOf(versionAprende) }
    LaunchedEffect(versionAprende, panel, fichaSobre) { if (panel == null && fichaSobre == null) versionVista = versionAprende }
    val textoPrueba by vm.buscadorTexto.collectAsState()
    LaunchedEffect(textoPrueba) { if (textoPrueba != null) panel = Panel.Buscar }
    val dia = remember { System.currentTimeMillis() / 86_400_000L }
    // Los vistos salen de las filas de recomendaciones y de «Porque viste», pero no de Mi lista (allí llevan marca).
    // Como el aprendizaje, el filtro se aplica al volver al inicio, no con una ficha o un panel encima.
    var vistosVista by remember { mutableStateOf(vistos) }
    LaunchedEffect(vistos, panel, fichaSobre) { if (panel == null && fichaSobre == null) vistosVista = vistos }
    val clavesVistos = remember(vistos) { vistos.map { it.clave }.toSet() }
    val filas = remember(miLista, estado.filas, versionVista, filaAncla, vistosVista, seguir, noRec, respaldo) {
        val ocultos = vistosVista.map { it.clave }.toSet() + vm.vistosExternos + noRec
        fun sinVistos(f: Fila) = f.copy(items = f.items.filter { (it.medio + ":" + it.id) !in ocultos })
        // «Seguir viendo» va primero; lo que ya se está viendo no se recomienda en las demás filas.
        val retomar = seguir?.let(::sinVistos)?.takeIf { it.items.isNotEmpty() }
        val enCurso = retomar?.items?.map { it.medio + ":" + it.id }?.toSet().orEmpty()
        fun limpia(f: Fila) = sinVistos(f).let { s -> s.copy(items = s.items.filter { (it.medio + ":" + it.id) !in enCurso }) }
        val aprendidas = estado.filas.map { limpia(vm.aprend.ordenar(it, dia)) }.filter { it.items.isNotEmpty() }
        val anclas = filaAncla.map { limpia(vm.aprend.ordenar(it, dia)) }.filter { it.items.isNotEmpty() }
        // Orden de las filas por afinidad con lo aprendido (géneros, origen y décadas con su decaimiento): cambia conforme cambia lo que ves.
        fun afinidad(f: Fila) = f.items.take(8).map { vm.aprend.puntaje(it) }.average().let { if (it.isNaN()) 0.0 else it }
        // Respaldo: si tras los filtros quedan menos de 12 títulos recomendados, se añade «Para empezar» para no dejar el inicio vacío.
        val recomendados = (anclas + aprendidas).sumOf { it.items.size }
        val apoyo = if (recomendados < 12) respaldo?.let(::limpia)?.takeIf { it.items.isNotEmpty() }?.let { listOf(it) }.orEmpty() else emptyList()
        listOfNotNull(retomar) + (if (miLista.isEmpty()) emptyList() else listOf(Fila("mi_lista", "Mi lista", miLista))) + (anclas + aprendidas).sortedByDescending(::afinidad) + apoyo
    }
    // Rastro para diagnóstico: qué filas quedan en pantalla y cuántos títulos (adb logcat -s LauncherFilas).
    LaunchedEffect(filas, estado.cargando) {
        if (estado.cargando) return@LaunchedEffect
        android.util.Log.i("LauncherFilas", "pantalla: modo=${estado.modo} " + filas.joinToString(" ") { it.id + "=" + it.items.size })
        if (filas.none { it.id != "seguir" && it.id != "mi_lista" }) co.davidvega.launcher.data.Diagnostico.registrar("recomendaciones", "inicio sin filas recomendadas (modo ${estado.modo}, filas de la carga ${estado.filas.size}, respaldo ${respaldo?.items?.size ?: 0})")
    }

    // Franja de arriba de las apps: tendencia de la semana en Colombia con 5 títulos al azar de las filas (sin anime),
    // que cambian cada vez que se vuelve al inicio con Home.
    val reinicioN by vm.reinicio.collectAsState()
    val arriba = remember(tendencias, filas, reinicioN, noRec) {
        fun k(t: Titulo) = t.medio + ":" + t.id
        val ya = filas.filter { it.id == "seguir" || it.id == "mi_lista" }.flatMap { it.items }.map(::k).toSet() + clavesVistos + vm.vistosExternos + noRec
        val base = tendencias?.items.orEmpty().filter { k(it) !in ya }
        val azar = filas.filter { it.id != "seguir" && it.id != "mi_lista" }.flatMap { it.items }
            .filter { it.idioma != "ja" && !it.noDisponible && it.nota >= 7.0 }.distinctBy(::k)
            .filter { a -> base.none { k(it) == k(a) } }
            .shuffled(java.util.Random(dia * 31 + reinicioN)).take(5).map { it.copy(motivo = "Al azar para la casa") }
        val out = base.toMutableList()
        azar.forEachIndexed { j, t -> out.add(minOf(1 + 3 * j, out.size), t) }
        if (out.size < 4) null else Fila("arriba", if (base.isEmpty()) "Al azar para la casa" else "Esta semana en Colombia", out)
    }
    val desfase = if (arriba != null) 2 else 1 // índice de la primera fila de recomendaciones en la LazyColumn

    var enfocado by remember { mutableStateOf<Titulo?>(null) }
    var pendiente by remember { mutableStateOf<Titulo?>(null) }
    var appEnfocada by remember { mutableStateOf<AppItem?>(null) }
    var filaFoco by remember { mutableIntStateOf(-1) } // fila de recomendaciones con foco; -1 = fila de apps
    var focoN by remember { mutableIntStateOf(0) } // cada foco en un título, aunque sea el mismo que el anterior
    LaunchedEffect(focoN) { if (focoN > 0) { delay(160); enfocado = pendiente; Glitch.pulso.intValue++ } }
    val primero = filas.firstOrNull()?.items?.firstOrNull()
    LaunchedEffect(primero) { if (enfocado == null && primero != null) enfocado = primero }

    val progreso by remember { derivedStateOf { if (lista.firstVisibleItemIndex > 0) 1f else (lista.firstVisibleItemScrollOffset / 220f).coerceIn(0f, 1f) } }
    val primerApp = remember { FocusRequester() }
    val reinicio by vm.reinicio.collectAsState()
    LaunchedEffect(reinicio) {
        if (reinicio > 0) { panel = null; fichaSobre = null; filaFoco = -1; appEnfocada = null; lista.scrollToItem(0); delay(150); runCatching { primerApp.requestFocus() } }
    }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) repeat(10) { n -> delay(120L * (n + 1)); if (runCatching { primerApp.requestFocus() }.isSuccess) return@LaunchedEffect }
    }

    // Hero: sigue al foco de las filas; con el foco en las apps rota entre las recomendaciones principales (se congela con el foco en los botones).
    var chipsFoco by remember { mutableStateOf(false) }
    val primeraChip = remember { FocusRequester() }
    var rotT by remember { mutableStateOf<Titulo?>(null) }
    var rotIdx by remember { mutableIntStateOf(0) }
    var rotCambio by remember { mutableIntStateOf(0) }
    var avisoHero by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(avisoHero) { if (avisoHero != null) { delay(3500); avisoHero = null } }
    val candidatosRot = remember(filas, arriba, noRec) {
        fun k(t: Titulo) = t.medio + ":" + t.id
        (listOfNotNull(arriba).flatMap { it.items.take(3) } + filas.filter { it.id != "seguir" }.flatMap { it.items.take(2) })
            .filter { it.fondo != null && k(it) !in noRec }.distinctBy(::k).take(8)
    }
    // Con el foco en una app, el hero recomienda para esa app: lo de las filas que está en ese servicio (IPTV: lo que no está en ningún servicio).
    // Si hay menos de 3, se cae a la rotación general.
    val servicioApp = appEnfocada?.let { a -> vm.paquetes.entries.firstOrNull { it.value == a.paquete }?.key ?: if (a.paquete == Abrir.IPTV) "iptv" else null }
    val nombreApp = appEnfocada?.etiqueta
    val candidatosApp = remember(servicioApp, filas, arriba, noRec) {
        if (servicioApp == null) null else {
            fun k(t: Titulo) = t.medio + ":" + t.id
            val todos = (listOfNotNull(arriba).flatMap { it.items } + filas.filter { it.id != "seguir" }.flatMap { it.items }).filter { it.fondo != null && k(it) !in noRec }.distinctBy(::k)
            val de = if (servicioApp == "iptv") todos.filter { it.servicios.isEmpty() || it.noDisponible } else todos.filter { servicioApp in it.servicios }
            de.take(8).takeIf { it.size >= 3 }
        }
    }
    val candidatos = candidatosApp ?: candidatosRot
    LaunchedEffect(appEnfocada?.paquete) { rotIdx = 0 }
    val sinSuperpuesto = panel == null && fichaSobre == null && menuSobre == null
    // Con el foco en las apps el hero rota solo cada 8 s; Rebobinar y Avanzar eligen el anterior o el siguiente (rotCambio reinicia el reloj).
    val delante by MainActivity.delante
    LaunchedEffect(candidatos, filaFoco, sinSuperpuesto, rotCambio, chipsFoco, delante) {
        if (!delante) return@LaunchedEffect
        if (appEnfocada != null || candidatos.isEmpty() || filaFoco != -1 || !sinSuperpuesto || chipsFoco) return@LaunchedEffect
        rotT = candidatos[rotIdx % candidatos.size]
        while (true) { delay(8_000); rotIdx = (rotIdx + 1) % candidatos.size; rotT = candidatos[rotIdx] }
    }
    // Con el foco en la fila de apps el hero es de la app (HeroApp), no un título: no se puede enfocar y no debe aparentarlo.
    val heroApp = filaFoco == -1 && appEnfocada != null
    val heroT = if (heroApp) null else (if (filaFoco == -1) rotT ?: enfocado else enfocado)?.takeIf { (it.medio + ":" + it.id) !in noRec }
        ?.let { h -> if (filaFoco == -1 && candidatosApp != null && nombreApp != null && candidatosApp.any { it.id == h.id && it.medio == h.medio }) h.copy(motivo = "Recomendado para $nombreApp") else h }
    // Glitch breve del hero al cambiar de título con el foco en las filas.
    // Se dispara en cada cambio de foco entre títulos (también en la fila superior y con el mismo título repetido en otra fila).
    LaunchedEffect(Glitch.pulso.intValue) { if (Glitch.pulso.intValue > 0 && heroT != null) Glitch.hero.disparar(150) }
    LaunchedEffect(Unit) { Glitch.activo = co.davidvega.launcher.data.Preferencias.efectosGlitch(ctx) }
    // Reposo: tras 2,5 min sin tocar nada, tráilers a pantalla completa (ver Trailer.kt).
    var reposo by remember { mutableStateOf(false) }
    val ctxR = LocalContext.current
    // El bucle lee la lista actual (las filas llegan después de arrancar el efecto); sin esto se quedaba con la primera, casi vacía.
    val candidatosRotAhora by androidx.compose.runtime.rememberUpdatedState(candidatosRot)
    LaunchedEffect(reposo, sinSuperpuesto, delante) {
        if (reposo || !delante) return@LaunchedEffect
        while (true) {
            delay(5000)
            if (sinSuperpuesto && !IntroEstado.activa && MainActivity.enPrimerPlano && android.os.SystemClock.elapsedRealtime() - Reposo.ultima > Reposo.ESPERA_MS &&
                candidatosRotAhora.size >= 3 && !Reposo.sonando(ctxR) && Reposo.memoriaHolgada(ctxR)) { reposo = true; Reposo.activo = true; break }
        }
    }
    val salidasReposo by Reposo.salir.collectAsState()
    LaunchedEffect(salidasReposo) { if (salidasReposo > 0 && reposo) { reposo = false; Reposo.activo = false; Reposo.ultima = android.os.SystemClock.elapsedRealtime() } }
    var introN by remember { mutableIntStateOf(1) }
    LaunchedEffect(reinicio) { if (reinicio > 0) introN++ }

    Box(Modifier.fillMaxSize().background(Tema.Fondo).glitch(Glitch.global).onPreviewKeyEvent { e ->
        // Teclas del control sobre el título del hero: Play/Pausa reproduce directo, Rebobinar y Avanzar cambian el título que rota,
        // Menú (con el foco en las apps, donde las tarjetas no lo atienden) abre el menú del título.
        val h = heroT
        if (!sinSuperpuesto || h == null || e.type != androidx.compose.ui.input.key.KeyEventType.KeyUp) {
            e.key in listOf(androidx.compose.ui.input.key.Key.MediaPlayPause, androidx.compose.ui.input.key.Key.MediaRewind, androidx.compose.ui.input.key.Key.MediaFastForward) && sinSuperpuesto
        } else when (e.key) {
            androidx.compose.ui.input.key.Key.MediaPlayPause -> { verEn(scope, ctx, vm, h, true, { avisoHero = it }, { fichaSobre = it }); true }
            androidx.compose.ui.input.key.Key.MediaRewind, androidx.compose.ui.input.key.Key.MediaFastForward -> {
                if (filaFoco == -1 && candidatos.isNotEmpty()) {
                    val n = candidatos.size
                    rotIdx = (rotIdx + (if (e.key == androidx.compose.ui.input.key.Key.MediaRewind) -1 else 1) + n) % n
                    rotT = candidatos[rotIdx]; rotCambio++
                }
                true
            }
            androidx.compose.ui.input.key.Key.Menu -> if (filaFoco == -1 || chipsFoco) { menuSobre = h; true } else false
            else -> false
        }
    }) {
        // Tema.Fondo del contenido enfocado.
        Box(Modifier.fillMaxSize().glitch(Glitch.hero)) {
        Crossfade(targetState = heroT, animationSpec = tween(350), label = "fondo") { t ->
            if (t?.fondo != null) {
                AsyncImage(model = ImageRequest.Builder(ctx).data(t.fondo).size(1280, 720).allowRgb565(false).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (t == null) {
                // Sin título (carga): negro; la entrada de puntos ocupa ese momento.
                Box(Modifier.fillMaxSize().background(Tema.Fondo))
            } else {
                val g = Degradados[t.gradiente % Degradados.size]
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(g)))
            }
        }
        }
        // Veladuras: legibilidad del texto (más fuerte a la izquierda) y fundido hacia las filas.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tema.Fondo.copy(alpha = 0.96f), 0.5f to Tema.Fondo.copy(alpha = 0.72f), 0.9f to Tema.Fondo.copy(alpha = 0.15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 0.72f to Tema.Fondo.copy(alpha = 0.9f), 1f to Tema.Fondo)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Tema.Fondo.copy(alpha = 0.6f), 0.2f to Color.Transparent))) // reloj legible sobre fondos claros
        Box(Modifier.fillMaxSize().background(Tema.Fondo.copy(alpha = 0.55f * progreso)))
        // Rejilla de puntos tenue (como en el launcher del S25).
        Box(Modifier.fillMaxSize().rejilla())

        // Hero grande: el título enfocado; con el foco en las apps rota solo entre las recomendaciones principales cada 8 s.
        HeroGrande(heroT, estado.cargando && !heroApp, vm, instalados, NombreServicio, avisoHero, modifier = Modifier.glitch(Glitch.hero),
            primeraChip = primeraChip, alFocoChips = { chipsFoco = it },
            alBajar = { if (!runCatching { MemoriaFoco.ultima?.requestFocus() }.isSuccess) runCatching { primerApp.requestFocus() } },
            alSubir = { runCatching { primerApp.requestFocus() } },
            alElegir = { ti, sv ->
                if (sv == "iptv") { vm.aprend.senal(ti, 3.0); vm.eventos.registrar("buscar_en", ti, servicio = "iptv"); Abrir.enIptv(ctx, ti) }
                else if (sv !in instalados) avisoHero = (NombreServicio[sv] ?: sv) + " no está instalado"
                else verEn(scope, ctx, vm, ti, false, { avisoHero = it }, { fichaSobre = it }, servicio = sv)
            })

        if (heroApp) HeroApp(appEnfocada)

        CompositionLocalProvider(LocalBringIntoViewSpec provides SinSaltoVertical) {
            // El visor de las filas empieza bajo el hero (296 dp) y recorta ahí: sin capa fuera de pantalla ni degradado DstIn
            // por fotograma, que en el Fire TV Stick costaban varios milisegundos.
            LazyColumn(
                state = lista,
                modifier = Modifier.fillMaxSize().padding(top = 272.dp).clipToBounds(),
            ) {
                item {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides saltoHorizontal(margenFila)) {
                        FilaApps(Modifier.entrada("apps", 40), apps, primerApp,
                            onFoco = { appEnfocada = it; filaFoco = -1; scope.launch { lista.animateScrollToItem(0) } },
                            onAbrir = { when (it.paquete) { Apps.AJUSTES -> panel = Panel.Ajustes; Apps.BUSCAR -> panel = Panel.Buscar; else -> Abrir.app(ctx, it.paquete) } })
                    }
                }
                arriba?.let { fila ->
                    item(key = "arriba") {
                        Column(Modifier.padding(top = 6.dp).entrada("arriba", 0)) {
                            val opacidad by animateFloatAsState(if (filaFoco == -1 || filaFoco == -2) 1f else 0.55f, tween(200), label = "arriba")
                            BasicText(fila.titulo.uppercase(), style = txtMono(16), modifier = Modifier.padding(start = MargenX, bottom = 2.dp).graphicsLayer { alpha = opacidad })
                            CompositionLocalProvider(LocalBringIntoViewSpec provides saltoHorizontal(margenFila)) {
                                LazyRow(contentPadding = PaddingValues(horizontal = MargenX, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    itemsIndexed(fila.items, key = { _, t -> t.medio + t.id + t.titulo }) { _, t ->
                                        TarjetaTitulo(t, instalados, visto = false,
                                            onFoco = { appEnfocada = null; filaFoco = -2; pendiente = t; focoN++; scope.launch { lista.animateScrollToItem(1, -pivote) } },
                                            onAbrir = { fichaSobre = t }, onMenu = { menuSobre = t })
                                    }
                                }
                            }
                        }
                    }
                }
                if (filas.isEmpty()) {
                    item { if (estado.cargando) Esqueleto() else EstadoVacio() }
                }
                itemsIndexed(filas, key = { _, f -> f.id }) { i, fila ->
                    Column(Modifier.padding(top = 6.dp).entrada("fila_" + fila.id, 90 + minOf(i, 4) * 80)) {
                        // La fila con foco se lee más clara que las demás: siempre se sabe dónde se está.
                        val opacidad by animateFloatAsState(if (filaFoco == -1 || filaFoco == i) 1f else 0.55f, tween(200), label = "fila")
                        BasicText(fila.titulo.uppercase(), style = txtMono(16), modifier = Modifier.padding(start = MargenX, bottom = 2.dp).graphicsLayer { alpha = opacidad })
                        CompositionLocalProvider(LocalBringIntoViewSpec provides saltoHorizontal(margenFila)) {
                            LazyRow(contentPadding = PaddingValues(horizontal = MargenX, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                itemsIndexed(fila.items, key = { _, t -> t.medio + t.id + t.titulo }) { _, t ->
                                    TarjetaTitulo(t, instalados, visto = (t.medio + ":" + t.id) in clavesVistos,
                                        onFoco = { appEnfocada = null; filaFoco = i; pendiente = t; focoN++; scope.launch { lista.animateScrollToItem(desfase + i, -pivote) } },
                                        onAbrir = { fichaSobre = t }, onMenu = { menuSobre = t }, marcaApp = fila.id == "seguir")
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(320.dp)) }
            }
        }

        // Reloj y estado de los datos (dentro de la zona segura).
        Reloj(Modifier.align(Alignment.TopEnd).padding(top = MargenY, end = MargenX))
        val aviso = estado.aviso ?: if (estado.modo == Modo.MUESTRA && !estado.cargando) "DATOS DE EJEMPLO" else null
        if (aviso != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 126.dp, end = MargenX).clip(RoundedCornerShape(8.dp)).background(Tema.VeloAviso).padding(horizontal = 10.dp, vertical = 4.dp)) {
                BasicText(aviso, style = txt(12, FontWeight.Bold))
            }
        }
        // Aviso discreto si el servicio de accesibilidad está apagado (se vuelve a mirar cada 5 s).
        var accApagada by remember { mutableStateOf(!co.davidvega.launcher.sistema.HomeAccessibilityService.activo) }
        LaunchedEffect(delante) { if (delante) while (true) { accApagada = !co.davidvega.launcher.sistema.HomeAccessibilityService.activo; delay(5000) } }
        if (accApagada) {
            BasicText("Búsqueda guiada y botón Home apagados — Ajustes > Estado del sistema", style = txt(12, color = Tema.Secundario), modifier = Modifier.align(Alignment.TopEnd).padding(top = if (aviso != null) 158.dp else 126.dp, end = MargenX)) // debajo del reloj: a 72 dp tapaba la fecha
        }

        when (panel) {
            is Panel.Ajustes -> PanelAjustes(vm, NombreServicio, alCerrar = { panel = null }, verAprendido = { panel = Panel.Aprendido }, verVistos = { panel = Panel.Vistos }, verEstado = { panel = Panel.Estado }, verNoRecomendar = { panel = Panel.NoRecomendar })
            is Panel.Buscar -> Buscador(vm, NombreServicio, textoPrueba ?: "", alCerrar = { panel = null; vm.buscadorTexto.value = null }, abrirFicha = { fichaSobre = it })
            is Panel.Aprendido -> PanelAprendido(vm, NombreServicio, alCerrar = { panel = Panel.Ajustes })
            is Panel.Vistos -> PanelVistos(vm, alCerrar = { panel = Panel.Ajustes })
            is Panel.Estado -> PanelEstado(vm, alCerrar = { panel = Panel.Ajustes })
            is Panel.NoRecomendar -> PanelNoRecomendar(vm, alCerrar = { panel = Panel.Ajustes })
            null -> {}
        }
        // Al cerrar la ficha el foco vuelve a la tarjeta de origen.
        var fichaAbierta by remember { mutableStateOf(false) }
        LaunchedEffect(fichaSobre != null) {
            if (fichaSobre == null && fichaAbierta) { delay(120); runCatching { MemoriaFoco.ultima?.requestFocus() } }
            fichaAbierta = fichaSobre != null
        }
        // La ficha va encima de lo que haya (inicio o buscador): al cerrarla se vuelve donde se estaba.
        fichaSobre?.let { Ficha(it, vm, NombreServicio, alCerrar = { fichaSobre = null }) }
        menuSobre?.let { t -> MenuTitulo(t, vm, alCerrar = { menuSobre = null }, abrirFicha = { fichaSobre = t }, nombres = NombreServicio) }
        // Onda de puntos al abrir una ficha, un panel o el menú de un título (transición «Abrir» del S25).
        var ondas by remember { mutableIntStateOf(0) }
        LaunchedEffect(fichaSobre != null, panel, menuSobre != null) { if (fichaSobre != null || panel != null || menuSobre != null) ondas++ }
        OndaPuntos(ondas, modifier = Modifier.fillMaxSize())
        if (reposo) ReposoTrailers(candidatosRot, vm) { reposo = false; Reposo.activo = false; Reposo.ultima = android.os.SystemClock.elapsedRealtime() }
        // Tráiler del menú ☰ dentro del launcher (con sonido); se libera el jugador al cerrarlo.
        val enCurso by TrailerEnCurso.ver.collectAsState()
        enCurso?.let { (t, clave) -> TrailerAdentro(t, clave) { TrailerEnCurso.ver.value = null } }
        IntroPuntos(introN, Modifier.fillMaxSize())
    }
}

@Composable
private fun Reloj(modifier: Modifier) {
    val delante by MainActivity.delante
    val ahora by produceState(Date(), delante) { value = Date(); if (delante) while (true) { delay(15_000); value = Date() } }
    Column(modifier, horizontalAlignment = Alignment.End) {
        BasicText(SimpleDateFormat("h:mm", Locale("es", "CO")).format(ahora), style = txtPuntos(52))
        BasicText(SimpleDateFormat("EEE d MMM · a", Locale("es", "CO")).format(ahora).uppercase().replace(".", ""), style = txtMono(23, Tema.Texto).copy(shadow = androidx.compose.ui.graphics.Shadow(Tema.SombraTexto, androidx.compose.ui.geometry.Offset(0f, 2f), 8f)))
    }
}

/** Mientras cargan las recomendaciones: dos filas de tarjetas vacías que laten despacio. */
@Composable
private fun Esqueleto() {
    val pulso by rememberInfiniteTransition(label = "esq").animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Column(Modifier.padding(top = 6.dp)) {
        repeat(2) {
            Box(Modifier.padding(start = MargenX, top = 6.dp, bottom = 8.dp).size(220.dp, 18.dp).clip(RoundedCornerShape(6.dp)).background(Tema.Texto.copy(alpha = pulso * 0.35f)))
            Row(Modifier.padding(horizontal = MargenX, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(4) { Box(Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(14.dp)).background(Tema.Texto.copy(alpha = pulso * 0.25f))) }
            }
        }
    }
}

@Composable
private fun EstadoVacio() {
    Column(Modifier.padding(start = MargenX, top = 24.dp).width(560.dp)) {
        BasicText("No hay recomendaciones por ahora", style = txt(22, FontWeight.SemiBold))
        Spacer(Modifier.height(6.dp))
        BasicText("Revisa la conexión: Ajustes > Wi-Fi y red. Se vuelve a intentar al reabrir el inicio.", style = txt(15, color = Tema.Secundario))
    }
}

@Composable
private fun FilaApps(modifier: Modifier, apps: List<AppItem>, primero: FocusRequester, onFoco: (AppItem) -> Unit, onAbrir: (AppItem) -> Unit) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = MargenX, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(apps, key = { _, a -> a.paquete }) { i, a ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tarjeta(
                    modifier = Modifier, requester = if (i == 0) primero else null,
                    ancho = 144.dp, alto = 81.dp, radio = 16.dp, escalaFoco = 1.14f, zoomInterior = 1.04f,
                    onFoco = { onFoco(a) }, onClick = { onAbrir(a) },
                ) { _ ->
                    if (a.paquete == Apps.BUSCAR) {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tema.Superficie2, Tema.Superficie2)))) {
                            androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
                                val w = size.width
                                drawCircle(Tema.Texto, radius = w * 0.30f, center = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f))
                                drawLine(Tema.Texto, androidx.compose.ui.geometry.Offset(w * 0.64f, w * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.92f, w * 0.92f), strokeWidth = w * 0.10f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            }
                        }
                    } else if (a.imagen != null) {
                        Image(a.imagen.asImageBitmap(), a.etiqueta, contentScale = if (a.esBanner) ContentScale.Crop else ContentScale.Fit,
                            modifier = if (a.esBanner) Modifier.fillMaxSize() else Modifier.align(Alignment.Center).size(56.dp))
                    } else BasicText(a.etiqueta, style = txt(15, FontWeight.Bold), modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.height(6.dp))
                BasicText(a.etiqueta, style = txt(12, color = Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
            }
        }
    }
}

/** Servicio con el que se abrirá el título; si ninguno está instalado, lo dice. */
private fun etiquetaServicio(t: Titulo, instalados: Set<String>): String {
    if (t.servicios.isEmpty()) return ""
    val usable = t.servicios.firstOrNull { it in instalados }
    if (usable != null) return NombreServicio[usable] ?: usable
    return (t.servicios.firstOrNull()?.let { NombreServicio[it] ?: it } ?: "") + " (no instalado)"
}

/** Marca discreta «✓ Visto» para las tarjetas de Mi lista y del buscador. */
@Composable
internal fun MarcaVisto(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Tema.VeloInsignia).padding(horizontal = 7.dp, vertical = 2.dp)) {
        BasicText("✓ Visto", style = txt(12, FontWeight.Bold, Tema.Texto))
    }
}

@Composable
internal fun TarjetaTitulo(t: Titulo, instalados: Set<String>, onFoco: () -> Unit, onAbrir: () -> Unit, visto: Boolean = false, onMenu: (() -> Unit)? = null, marcaApp: Boolean = false) {
    Tarjeta(Modifier, 224.dp, 126.dp, 14.dp, 1.10f, 1.08f, onFoco, onAbrir, onMenu = onMenu, pie = { foco ->
        // La nota de TMDB se ve siempre, sin entrar a la ficha.
        if (t.nota > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Tema.VeloInsignia).padding(horizontal = 7.dp, vertical = 2.dp)) {
                BasicText("★ %.1f".format(Locale.US, t.nota), style = txt(12, FontWeight.Bold, Tema.Texto))
            }
        }
        if (visto) MarcaVisto(Modifier.align(Alignment.TopStart).padding(8.dp))
        // Anime: marca de idioma («ES» doblaje, «SUB» subtitulado) abajo a la derecha, solo si ya se sabe.
        if (Doblaje.mostrable(t)) Doblaje.estado(t).marca?.let { m ->
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Tema.VeloInsignia).padding(horizontal = 6.dp, vertical = 1.dp)) {
                BasicText(m, style = txt(11, FontWeight.Bold, Tema.Texto))
            }
        }
        // Solo en «Seguir viendo»: la app donde se retoma el título, visible siempre.
        if (marcaApp && !visto) etiquetaServicio(t, instalados).takeIf { it.isNotEmpty() }?.let { app ->
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Tema.VeloInsignia).padding(horizontal = 7.dp, vertical = 2.dp)) {
                BasicText(app, style = txt(12, FontWeight.Bold, Tema.Texto), maxLines = 1)
            }
        }
        // Menos texto sobre las imágenes: título y servicio solo aparecen en la tarjeta enfocada (y no se acercan con el zoom).
        val texto by animateFloatAsState(if (foco) 1f else 0f, tween(160), label = "texto")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = texto }.background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tema.Fondo.copy(alpha = 0.9f))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp).graphicsLayer { alpha = texto }) {
            TextoDecodificado(t.titulo, txt(15, FontWeight.SemiBold), activo = foco)
            val s = if (t.noDisponible) "Sin servicio en CO" else etiquetaServicio(t, instalados)
            if (s.isNotEmpty()) BasicText(s, style = txt(12, color = Tema.Secundario))
        }
    }) { _ ->
        if (t.fondoTarjeta != null) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(t.fondoTarjeta).size(384, 216).build(), contentDescription = t.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(Degradados[t.gradiente % Degradados.size])))
        }
    }
}

/**
 * Tarjeta enfocable. Foco = escala + sombra + borde claro + brillo; el resto de tarjetas se atenúan un poco.
 * El contenido se acerca ligeramente (zoomInterior) para dar un parallax suave dentro del marco.
 */
@Composable
internal fun Tarjeta(
    modifier: Modifier, ancho: Dp, alto: Dp, radio: Dp, escalaFoco: Float, zoomInterior: Float,
    onFoco: () -> Unit, onClick: () -> Unit, pie: (@Composable BoxScope.(Boolean) -> Unit)? = null, onMenu: (() -> Unit)? = null, requester: FocusRequester? = null, contenido: @Composable BoxScope.(Boolean) -> Unit,
) {
    val fr = requester ?: remember { FocusRequester() }
    var foco by remember { mutableStateOf(false) }
    var largo by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (foco) escalaFoco else 1f, spring(dampingRatio = 0.72f, stiffness = 420f), label = "escala")
    val zoom by animateFloatAsState(if (foco) zoomInterior else 1f, tween(260), label = "zoom")
    val elev by animateDpAsState(if (foco) 28.dp else 1.dp, tween(170), label = "sombra")
    val atenuar by animateFloatAsState(if (foco) 0f else 0.22f, tween(170), label = "atenuar")
    val forma = RoundedCornerShape(radio)
    Box(
        modifier
            .size(ancho, alto)
            .graphicsLayer { scaleX = escala; scaleY = escala }
            .shadow(elev, forma, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(forma)
            .background(Tema.Superficie2)
            .focusRequester(fr)
            .onFocusChanged { foco = it.isFocused; if (it.isFocused) { MemoriaFoco.ultima = fr; onFoco() } }
            // Tecla Menú (☰) del control: menú del título. Se consume la pulsación entera y se abre al soltar.
            // Mantener OK pulsado hace lo mismo que ☰: abre el menú y la pulsación larga no activa el clic al soltar.
            .onPreviewKeyEvent { e ->
                val k = e.key
                val centro = k == androidx.compose.ui.input.key.Key.DirectionCenter || k == androidx.compose.ui.input.key.Key.Enter || k == androidx.compose.ui.input.key.Key.NumPadEnter
                when {
                    onMenu == null -> false
                    k == androidx.compose.ui.input.key.Key.Menu -> { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) onMenu(); true }
                    centro && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> { if (!largo) { largo = true; onMenu() }; true }
                    centro && e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && largo -> { largo = false; true }
                    else -> false
                }
            }
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = (1f - zoom) * 40f }) { contenido(foco) }
        // Atenuado de las no enfocadas y brillo (reflejo) de la enfocada.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = atenuar)))
        pie?.invoke(this, foco)
        if (foco) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), start = Offset(0f, 0f), end = Offset(400f, 400f))))
        Box(Modifier.fillMaxSize().border(if (foco) 3.dp else 1.dp, if (foco) Tema.Acento else Tema.Borde, forma))
    }
}
