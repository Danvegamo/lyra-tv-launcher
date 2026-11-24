package co.davidvega.launcher.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.davidvega.launcher.data.Comprobacion
import co.davidvega.launcher.data.Diagnostico
import co.davidvega.launcher.data.Incidencia
import co.davidvega.launcher.data.Titulo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import co.davidvega.launcher.sistema.Abrir
import co.davidvega.launcher.sistema.Ajustes
import co.davidvega.launcher.sistema.Atajo
import co.davidvega.launcher.sistema.Control
import co.davidvega.launcher.sistema.Instantanea
import co.davidvega.launcher.sistema.Salud
import co.davidvega.launcher.sistema.SaludCalculo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FondoPanel get() = Tema.Fondo // opaco: sin transparencia sobre el inicio
internal val Pieza get() = Tema.Superficie2
private val PiezaFoco get() = Tema.Foco

/** Ventana modal a pantalla completa: captura el foco del D-pad y se cierra con Atrás. */
@Composable
fun PanelDialogo(alCerrar: () -> Unit, contenido: @Composable () -> Unit) {
    // La tecla OK que abre el panel suelta su KEY_UP ya dentro del diálogo y pulsaba la primera pieza (en Ajustes abría «Red»
    // de Fire OS al instante). Durante los primeros 400 ms se tragan las teclas, salvo Atrás.
    val abiertoEn = remember { android.os.SystemClock.uptimeMillis() }
    Dialog(onDismissRequest = alCerrar, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(FondoPanel).onPreviewKeyEvent { e ->
            e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK &&
                android.os.SystemClock.uptimeMillis() - abiertoEn < 400
        }) { contenido() }
    }
}

/** Panel "Ajustes": accesos directos al sistema y sección «Recomendaciones» (aprendizaje). */
@Composable
fun PanelAjustes(vm: co.davidvega.launcher.LauncherVM, nombres: Map<String, String>, alCerrar: () -> Unit, verAprendido: () -> Unit, verVistos: () -> Unit = {}, verEstado: () -> Unit = {}, verNoRecomendar: () -> Unit = {}) {
    val ctx = LocalContext.current
    val version by vm.aprend.version.collectAsState()
    val vistos by vm.vistos.collectAsState()
    val noRec by vm.noRecomendados.collectAsState()
    var seguro by remember { mutableStateOf(false) }
    LaunchedEffect(seguro) { if (seguro) { delay(4000); seguro = false } }
    PanelDialogo(alCerrar = alCerrar) {
        val primero = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 48.dp, vertical = 27.dp)) {
            BasicText("Control del Fire TV", style = txt(30, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText("Atajos a los ajustes del sistema. Atrás para volver.", style = txt(14, color = Tema.Secundario))
            Spacer(Modifier.height(18.dp))
            Ajustes.ATAJOS.chunked(3).forEachIndexed { r, fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    fila.forEachIndexed { c, a ->
                        Pieza(a.titulo, a.detalle, Modifier.let { if (r == 0 && c == 0) it.focusRequester(primero) else it }) {
                            if (Ajustes.abrir(ctx, a) < 0) Toast.makeText(ctx, "${a.titulo}: no se pudo abrir", Toast.LENGTH_LONG).show() else alCerrar()
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BasicText("TEMA DE COLOR", style = txt(12, FontWeight.Bold, Tema.Secundario))
            val temaActivo = Tema.paleta
            co.davidvega.launcher.data.Temas.TODOS.chunked(3).forEach { fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                    fila.forEach { p ->
                        val elegido = temaActivo.id == p.id
                        Pieza((if (elegido) "● " else "") + p.nombre, if (elegido) "Tema activo" else "Pulsa OK para usarlo", Modifier, acento = Color(p.acento)) {
                            Tema.elegir(ctx, p.id)
                        }
                    }
                }
            }
            if (Tema.avisos.isNotEmpty()) BasicText(Tema.avisos.joinToString("  ·  "), style = txt(13, color = Tema.Secundario), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            BasicText("RECOMENDACIONES", style = txt(12, FontWeight.Bold, Tema.Secundario))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                val activo = vm.aprend.activo
                Pieza("Aprender de lo que veo: " + if (activo) "Sí" else "No", if (activo) "Las filas se ordenan con tus gustos" else "Pausado: no se anota nada nuevo", Modifier, acento = if (activo) Tema.Acento else Tema.Terciario) { vm.aprend.encender(!activo) }
                var techo by remember { mutableStateOf(vm.aprend.techoActivo) }
                Pieza("Aprendizaje con techo: " + if (techo) "Sí" else "No", if (techo) "Pesos acotados, señal por fracción vista, un título suma una vez al día" else "Como antes (0.13): pesos sin tope", Modifier, acento = if (techo) Tema.Acento else Tema.Terciario) { techo = !techo; vm.aprend.usarTecho(techo) }
                Pieza("Ver lo que aprendí", "Géneros, origen, servicios y más", Modifier) { verAprendido() }
                Pieza("Vistos (${vistos.size})", "Lo que marcaste con «Ya lo vi»; aquí lo quitas", Modifier) { verVistos() }
            }
            var registrar by remember { mutableStateOf(co.davidvega.launcher.data.Preferencias.registrar(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Registrar lo que veo: " + if (registrar) "Sí" else "No", if (registrar) "Reproducción y eventos van al servidor de eventos" else "No se anota ni se envía nada", Modifier, acento = if (registrar) Tema.Acento else Tema.Terciario) {
                    registrar = !registrar; co.davidvega.launcher.data.Preferencias.registrar(ctx, registrar)
                }
                Pieza("No recomendar (${noRec.size})", "Lo que no quieres ver; se repasa cada semana", Modifier) { verNoRecomendar() }
                Pieza("Estado del sistema", "Qué funciona, qué falla y cómo arreglarlo", Modifier) { verEstado() }
            }
            var glitch by remember { mutableStateOf(co.davidvega.launcher.data.Preferencias.efectosGlitch(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Efectos glitch: " + if (glitch) "Sí" else "No", if (glitch) "Separación de color breve al cambiar de título y al entrar" else "Sin efectos al moverte", Modifier, acento = if (glitch) Tema.Acento else Tema.Terciario) {
                    glitch = !glitch; co.davidvega.launcher.data.Preferencias.efectosGlitch(ctx, glitch); Glitch.activo = glitch
                }
            }
            var liberar by remember { mutableStateOf(co.davidvega.launcher.data.Preferencias.liberarMemoria(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Liberar memoria al abrir una app: " + if (liberar) "Sí" else "No", if (liberar) "Cierra las otras apps de video que sigan en segundo plano" else "Las apps de video no se tocan al abrir otra", Modifier, acento = if (liberar) Tema.Acento else Tema.Terciario) {
                    liberar = !liberar; co.davidvega.launcher.data.Preferencias.liberarMemoria(ctx, liberar)
                }
            }
            var mant by remember { mutableStateOf(co.davidvega.launcher.data.Preferencias.mantenimiento(ctx)) }
            var mantEstado by remember { mutableStateOf(co.davidvega.launcher.sistema.Mantenimiento.ultimaEntrada()) }
            var mantOcupado by remember { mutableStateOf(false) }
            val scopeMant = rememberCoroutineScope()
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Mantenimiento nocturno (4:30): " + if (mant) "Sí" else "No", if (mant) "Recorta cachés y reinicia si nadie usa la tele" else "No se reinicia sola", Modifier, acento = if (mant) Tema.Acento else Tema.Terciario) {
                    mant = !mant; co.davidvega.launcher.data.Preferencias.mantenimiento(ctx, mant); co.davidvega.launcher.sistema.Mantenimiento.programar(ctx) // arma o cancela la alarma según el ajuste
                }
                Pieza("Autorizar mantenimiento", if (mantOcupado) "Trabajando…" else if (co.davidvega.launcher.sistema.Mantenimiento.faltaAutorizar(ctx)) "FALTA AUTORIZAR: acepta «Permitir depuración» (Siempre) en la tele" else "Una vez: acepta «Permitir depuración» (Siempre) en la tele", Modifier) {
                    if (!mantOcupado) { mantOcupado = true; mantEstado = "Autorizando… mira la tele"
                        scopeMant.launch { val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { co.davidvega.launcher.sistema.Mantenimiento.autorizar(ctx) }
                            mantEstado = (if (p.ok) "Autorizado: " else "No autorizado: ") + p.texto; mantOcupado = false } }
                }
                Pieza("Probar ahora (sin reiniciar)", "Revisa condiciones, recorta cachés y comprueba adb", Modifier) {
                    if (!mantOcupado) { mantOcupado = true; mantEstado = "Probando…"
                        scopeMant.launch { val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { co.davidvega.launcher.sistema.Mantenimiento.ejecutar(ctx, reiniciar = false) }
                            mantEstado = p.texto; mantOcupado = false } }
                }
            }
            BasicText(mantEstado, style = txt(13, color = Tema.Secundario), modifier = Modifier.padding(top = 6.dp))
            var verSalud by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Salud y control", "RAM, swap, disco, segundo plano de apps y adb", Modifier) { verSalud = true }
            }
            var adbRed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { adbRed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Salud.adbPorRed(ctx) } }
            if (adbRed) BasicText(Salud.AVISO_ADB, style = txt(13, color = Tema.Secundario), modifier = Modifier.padding(top = 6.dp))
            if (verSalud) PanelSalud(alCerrar = { verSalud = false })
            var modo by remember { mutableStateOf(co.davidvega.launcher.data.Preferencias.inicioModo(ctx)) }
            val nombresInicio = listOf("Con sonido", "Sin sonido")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza(if (seguro) "¿Seguro? Pulsa otra vez" else "Reiniciar aprendizaje", if (seguro) "Borra todo lo aprendido" else "Vuelve a las semillas de gustos.json", Modifier, acento = Tema.Secundario) {
                    if (seguro) { vm.aprend.reiniciar(); seguro = false; Toast.makeText(ctx, "Aprendizaje reiniciado", Toast.LENGTH_SHORT).show() } else seguro = true
                }
                Pieza("Sonido de inicio: " + nombresInicio[modo], if (modo == 1) "La entrada se ve igual, pero en silencio" else "Pulsa para silenciar la entrada", Modifier, acento = if (modo == 1) Tema.Terciario else Tema.Acento) {
                    modo = (modo + 1) % 2; co.davidvega.launcher.data.Preferencias.inicioModo(ctx, modo)
                }
            }
            var intro by remember { mutableStateOf(co.davidvega.launcher.sistema.IntroVideo.modo(ctx).coerceIn(0, 2)) }
            val nombresIntro = listOf("completa", "corta", "ninguna")
            val detalleIntro = listOf("Siempre el vídeo de 5 s", "Completa en frío o tras 30 min; si no, sus últimos 3 s", "Solo glitch y fundido, sin vídeo")
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pieza("Intro: " + nombresIntro[intro], detalleIntro[intro], Modifier) {
                    intro = (intro + 1) % 3
                    ctx.getSharedPreferences("ajustes", android.content.Context.MODE_PRIVATE).edit().putInt(co.davidvega.launcher.sistema.IntroVideo.CLAVE_MODO, intro).apply()
                }
            }
            if (version < 0) BasicText("", style = txt(1)) // lee la versión para refrescar el estado del interruptor
        }
    }
}

/** «Salud y control»: RAM, swap, disco, apps pesadas, segundo plano por app, límite de procesos y aviso de adb por red. Se lee al abrir. */
@Composable
private fun PanelSalud(alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var inst by remember { mutableStateOf<Instantanea?>(null) }
    var pesadas by remember { mutableStateOf<List<Salud.Fila>?>(null) }
    var datos by remember { mutableStateOf<List<Salud.Fila>?>(null) }
    var llaves by remember { mutableStateOf<List<String>?>(null) }
    var aviso by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var recarga by remember { mutableStateOf(0) }
    var limite by remember { mutableStateOf(Control.limite(ctx)) }
    val bloqueos = remember { mutableStateMapOf<String, Boolean>().apply { Control.APPS.forEach { put(it.paquete, Control.bloqueada(ctx, it)) } } }
    LaunchedEffect(recarga) {
        withContext(Dispatchers.IO) {
            inst = Salud.instantanea()
            datos = Salud.masDatos(ctx)
            pesadas = Salud.masPesadas(ctx)
        }
    }
    PanelDialogo(alCerrar = alCerrar) {
        val primero = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 48.dp, vertical = 27.dp)) {
            BasicText("Salud y control", style = txt(30, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText("Se lee al abrir. Atrás para volver.", style = txt(14, color = Tema.Secundario))
            Spacer(Modifier.height(14.dp))
            val i = inst
            if (i == null) BasicText("Leyendo…", style = txt(15, color = Tema.Secundario)) else {
                BasicText("RAM libre: ${SaludCalculo.mb(i.ramDispKb)} de ${SaludCalculo.mb(i.ramTotalKb)}", style = txt(16))
                BasicText("Swap usado: ${SaludCalculo.mb(i.swapTotalKb - i.swapLibreKb)} de ${SaludCalculo.mb(i.swapTotalKb)}", style = txt(16))
                val nivel = SaludCalculo.nivelAlmacen(i.libreBytes, i.totalBytes)
                val color = when (nivel) { SaludCalculo.Nivel.ROJO -> Color(0xFFE5484D); SaludCalculo.Nivel.AMARILLO -> Color(0xFFF5C518); else -> Tema.Texto }
                val pct = if (i.totalBytes > 0) i.libreBytes * 100 / i.totalBytes else 0
                BasicText("Almacenamiento libre: ${i.libreBytes / 1_048_576} MB de ${i.totalBytes / 1_048_576} MB ($pct %)" + when (nivel) { SaludCalculo.Nivel.ROJO -> " · CRÍTICO"; SaludCalculo.Nivel.AMARILLO -> " · poco"; else -> "" }, style = txt(16, color = color))
                BasicText("Encendida desde hace: ${Salud.dias(i.uptimeMs)}", style = txt(16))
                BasicText("Último mantenimiento: " + co.davidvega.launcher.sistema.Mantenimiento.ultimaEntrada(), style = txt(14, color = Tema.Secundario))
            }
            Spacer(Modifier.height(10.dp))
            BasicText("CINCO APPS MÁS PESADAS (RAM)", style = txt(12, FontWeight.Bold, Tema.Secundario))
            (pesadas ?: emptyList()).forEach { BasicText("${it.nombre}: ${it.detalle}", style = txt(14)) }
            Spacer(Modifier.height(8.dp))
            BasicText("TRES APPS CON MÁS DATOS", style = txt(12, FontWeight.Bold, Tema.Secundario))
            (datos ?: emptyList()).forEach { BasicText("${it.nombre}: ${it.detalle}", style = txt(14)) }
            Spacer(Modifier.height(14.dp))
            BasicText("SEGUNDO PLANO POR APP (cmd appops RUN_IN_BACKGROUND)", style = txt(12, FontWeight.Bold, Tema.Secundario))
            Control.APPS.chunked(3).forEachIndexed { r, fila ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                    fila.forEachIndexed { c, a ->
                        val bloq = bloqueos[a.paquete] == true
                        Pieza(a.nombre + ": segundo plano " + if (bloq) "No" else "Sí", a.nota, Modifier.let { if (r == 0 && c == 0) it.focusRequester(primero) else it }, acento = if (bloq) Tema.Terciario else Tema.Acento) {
                            if (!ocupado) { ocupado = true; aviso = "Aplicando…"
                                scope.launch {
                                    val p = withContext(Dispatchers.IO) { Control.fijarSegundoPlano(ctx, a, !bloq) }
                                    bloqueos[a.paquete] = Control.bloqueada(ctx, a)
                                    aviso = (if (p.ok) "Listo: " else "No se pudo: ") + a.nombre + (if (p.ok) "" else " (${p.texto})"); ocupado = false
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BasicText("PROCESOS EN SEGUNDO PLANO DEL SISTEMA", style = txt(12, FontWeight.Bold, Tema.Secundario))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                Pieza("Procesos: " + Control.LIMITES.first { it.first == limite }.second, "Menos procesos libera RAM pero hace arranques en frío; pulsa para cambiar", Modifier) {
                    val sig = Control.LIMITES[(Control.LIMITES.indexOfFirst { it.first == limite } + 1) % Control.LIMITES.size].first
                    val p = Control.fijarLimite(ctx, sig)
                    limite = Control.limite(ctx)
                    aviso = if (p.ok) p.texto else "No se pudo: ${p.texto}"
                }
                Pieza("Actualizar lecturas", "Vuelve a leer RAM, disco y apps", Modifier) { recarga++ }
            }
            if (Salud.adbPorRed(ctx)) {
                Spacer(Modifier.height(14.dp))
                BasicText(Salud.AVISO_ADB, style = txt(14, color = Color(0xFFF5C518)))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Pieza("Ver llaves autorizadas", "Equipos que pueden entrar por adb", Modifier) {
                        scope.launch { llaves = withContext(Dispatchers.IO) { Salud.llavesAutorizadas(ctx) } }
                    }
                }
                llaves?.forEach { BasicText(it, style = txt(13, color = Tema.Secundario)) }
            }
            if (aviso.isNotEmpty()) BasicText(aviso, style = txt(14, color = Tema.Acento), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** «Ver lo que aprendí»: los pesos principales, en texto legible. */
@Composable
fun PanelAprendido(vm: co.davidvega.launcher.LauncherVM, nombres: Map<String, String>, alCerrar: () -> Unit) {
    val version by vm.aprend.version.collectAsState()
    val resumen = remember(version) { vm.aprend.resumen(nombres) }
    val primero = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }
    PanelDialogo(alCerrar = alCerrar) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            BasicText("Lo que aprendí", style = txt(30, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText("Pesos con vida media de 30 días. Las semillas de gustos.json son la base. Atrás para volver.", style = txt(13, color = Tema.Secundario))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(1.dp).focusRequester(primero).clickable { alCerrar() })
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                resumen.secciones.chunked(3).forEach { col ->
                    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        col.forEach { (nombre, lineas) ->
                            Column {
                                BasicText(nombre.uppercase(), style = txt(11, FontWeight.Bold, Tema.Secundario))
                                lineas.forEach { (k, v) ->
                                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width((6 + (kotlin.math.abs(v).coerceAtMost(8.0) * 12).toInt()).dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (v >= 0) Tema.Texto else Tema.Terciario))
                                        Spacer(Modifier.width(8.dp))
                                        BasicText("$k  " + (if (v >= 0) "+" else "") + "%.1f".format(java.util.Locale.US, v), style = txt(13, color = Tema.Texto), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (resumen.secciones.isEmpty()) BasicText("Todavía no hay nada aprendido: abre fichas, usa «Ver en…» o añade títulos a Mi lista.", style = txt(16, color = Tema.Secundario))
        }
    }
}

/** «Vistos (N)»: lo marcado con «Ya lo vi». Pulsar un título lo quita de los vistos. */
@Composable
fun PanelVistos(vm: co.davidvega.launcher.LauncherVM, alCerrar: () -> Unit) {
    val vistos by vm.vistos.collectAsState()
    PanelQuitables(
        "Vistos (${vistos.size})", "Pulsa un título para quitarlo de los vistos. Los vistos no salen en las recomendaciones. Atrás para volver.",
        "Todavía no marcaste nada: en la ficha de un título está «Ya lo vi».",
        vistos.map { Quitable(it.clave, it.titulo, it.medio, it.fecha) }, alCerrar,
    ) { c -> vistos.firstOrNull { it.clave == c }?.let { vm.quitarVisto(it.medio, it.id) } }
}

/** «No recomendar (N)»: lo marcado con la tecla Menú. Pulsar un título lo devuelve a las recomendaciones. */
@Composable
fun PanelNoRecomendar(vm: co.davidvega.launcher.LauncherVM, alCerrar: () -> Unit) {
    val lista by vm.noRecomendados.collectAsState()
    PanelQuitables(
        "No recomendar (${lista.size})", "No salen en el inicio y cada semana restan otra vez a lo parecido (géneros, origen). Pulsa uno para devolverlo. Atrás para volver.",
        "Nada todavía: en el inicio, tecla Menú (☰) sobre un título > «No recomendar».",
        lista.map { Quitable(it.clave, it.t.titulo, it.t.medio, it.fecha) }, alCerrar,
    ) { c -> lista.firstOrNull { it.clave == c }?.let { vm.quitarNoRecomendar(it) } }
}

private class Quitable(val clave: String, val titulo: String, val medio: String, val fecha: String)

/** Lista de títulos donde pulsar uno lo quita (Vistos, No recomendar). */
@Composable
private fun PanelQuitables(titulo: String, ayuda: String, vacio: String, items: List<Quitable>, alCerrar: () -> Unit, quitar: (String) -> Unit) {
    val vistos = items
    val primero = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }
    PanelDialogo(alCerrar = alCerrar) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            BasicText(titulo, style = txt(30, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText(ayuda, style = txt(13, color = Tema.Secundario))
            Spacer(Modifier.height(14.dp))
            if (vistos.isEmpty()) {
                Box(Modifier.size(1.dp).focusRequester(primero).clickable { alCerrar() })
                BasicText(vacio, style = txt(16, color = Tema.Secundario))
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vistos, key = { it.clave }) { v ->
                    val esElPrimero = v == vistos.first()
                    var foco by remember { mutableStateOf(false) }
                    Row(
                        Modifier.width(760.dp).height(48.dp).let { if (esElPrimero) it.focusRequester(primero) else it }
                            .clip(RoundedCornerShape(12.dp)).background(if (foco) Tema.Foco else Tema.Superficie2)
                            .onFocusChanged { foco = it.isFocused }.clickable { quitar(v.clave) }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(v.titulo.ifBlank { "(sin título)" }, style = txt(17, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        BasicText(listOf(if (v.medio == "tv") "Serie" else "Película", v.fecha, "Quitar").filter { it.isNotBlank() }.joinToString("  ·  "), style = txt(13, color = if (foco) Tema.SecundarioSobreFoco else Tema.Secundario).copy(shadow = null))
                    }
                }
            }
        }
    }
}

/** «Estado del sistema»: comprobaciones en vivo, últimas incidencias y cómo arreglarlo. */
@Composable
fun PanelEstado(vm: co.davidvega.launcher.LauncherVM, alCerrar: () -> Unit) {
    var vuelta by remember { mutableStateOf(0) }
    var checks by remember { mutableStateOf<List<Comprobacion>?>(null) }
    var incidencias by remember { mutableStateOf<List<Incidencia>>(emptyList()) }
    LaunchedEffect(vuelta) {
        checks = null
        incidencias = withContext(Dispatchers.IO) { Diagnostico.recientes(10) }
        checks = vm.comprobar()
        incidencias = withContext(Dispatchers.IO) { Diagnostico.recientes(10) }
    }
    val primero = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(8) { n -> delay(80L * (n + 1)); if (runCatching { primero.requestFocus() }.isSuccess) return@LaunchedEffect } }
    PanelDialogo(alCerrar = alCerrar) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            BasicText("Estado del sistema", style = txt(30, FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText("● funciona   ○ falla. Atrás para volver.", style = txt(13, color = Tema.Secundario))
            Spacer(Modifier.height(12.dp))
            Boton("Comprobar de nuevo", Modifier.focusRequester(primero), ancho = 240.dp) { vuelta++ }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    BasicText("COMPROBACIONES", style = txt(11, FontWeight.Bold, Tema.Secundario))
                    val lista = checks
                    if (lista == null) BasicText("Comprobando…", style = txt(14, color = Tema.Secundario))
                    else lista.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(if (c.bien) "●" else "○", style = txt(16, color = if (c.bien) Tema.Texto else Tema.Terciario), modifier = Modifier.width(24.dp))
                            BasicText(c.nombre + (if (c.bien) "" else "  falla") + (if (c.detalle.isNotBlank()) "  (${c.detalle})" else ""), style = txt(14, color = if (c.bien) Tema.Texto else Tema.Secundario), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    BasicText("ÚLTIMAS INCIDENCIAS", style = txt(11, FontWeight.Bold, Tema.Secundario))
                    if (incidencias.isEmpty()) BasicText("Ninguna anotada.", style = txt(14, color = Tema.Secundario))
                    incidencias.forEach { i ->
                        BasicText(Diagnostico.hora(i.t) + "  " + Diagnostico.legible(i), style = txt(13, color = Tema.Texto), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            BasicText("CÓMO ARREGLARLO", style = txt(11, FontWeight.Bold, Tema.Secundario))
            Spacer(Modifier.height(4.dp))
            BasicText("Desde el PC: revisar.ps1 -Arreglar reactiva el servicio de accesibilidad y la escucha de medios.", style = txt(13, color = Tema.Texto))
            BasicText("A mano: Ajustes del Fire TV > Accesibilidad > Lyra: botón Home.", style = txt(13, color = Tema.Texto))
            BasicText("El registro completo está en diagnostico.jsonl (adb pull, ver LEEME).", style = txt(12, color = Tema.Secundario))
        }
    }
}

@Composable
internal fun Boton(texto: String, modifier: Modifier, ancho: Dp = 200.dp, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (foco) 1.08f else 1f, tween(140), label = "b")
    Box(
        modifier.size(ancho, 56.dp).graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(RoundedCornerShape(14.dp)).background(if (foco) Tema.Foco else Tema.Superficie2)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar),
        contentAlignment = Alignment.Center,
    ) { BasicText(texto, style = txt(18, FontWeight.SemiBold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null)) }
}

@Composable
private fun Pieza(titulo: String, detalle: String, modifier: Modifier, acento: Color? = null, alPulsar: () -> Unit) {
    var foco by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (foco) 1.06f else 1f, tween(140), label = "p")
    val forma = RoundedCornerShape(16.dp)
    Box(
        modifier.size(272.dp, 92.dp).graphicsLayer { scaleX = escala; scaleY = escala }
            .clip(forma).background(if (foco) PiezaFoco else Pieza)
            .onFocusChanged { foco = it.isFocused }.clickable(onClick = alPulsar),
    ) {
        if (acento != null) Box(Modifier.align(Alignment.CenterStart).width(5.dp).height(48.dp).background(acento))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 12.dp)) {
            BasicText(titulo, style = txt(19, FontWeight.Bold, if (foco) Tema.TextoSobreBlanco else Tema.Texto).copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            BasicText(detalle, style = txt(12, color = if (foco) Tema.SecundarioSobreFoco else Tema.Secundario).copy(shadow = null), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
