package co.davidvega.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Lenguaje de puntos del launcher del S25 llevado a la tele. Todo es barato a propósito (Fire TV Stick 4K Max, 1,7 GB de RAM):
 *  - la rejilla se dibuja con una sola pasada de dibujo (no se redibuja mientras nada cambia debajo);
 *  - la onda de puntos solo existe ~0,5 s al abrir una ficha o un panel y lee su avance en la fase de dibujo (sin recomponer);
 *  - la entrada de las filas ocurre una sola vez por fila y por proceso.
 */

/** Rejilla de puntos muy tenue, como la del S25. */
internal fun Modifier.rejilla(paso: Int = 28, color: Color = Color(0x12FFFFFF)): Modifier = drawBehind {
    val p = paso.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = p / 2
    while (y < size.height) {
        var x = p / 2
        while (x < size.width) { drawCircle(color, r, Offset(x, y)); x += p }
        y += p
    }
}

/**
 * Onda de puntos (la transición «Abrir» del S25): del origen sale un anillo de puntos que se agranda y se apaga mientras
 * cruza la pantalla. `disparo` es un contador: cada cambio lanza una onda. No recibe toques ni teclas.
 */
@Composable
internal fun OndaPuntos(disparo: Int, origen: Offset? = null, modifier: Modifier = Modifier) {
    val avance = remember { Animatable(1f) }
    LaunchedEffect(disparo) {
        if (disparo == 0) return@LaunchedEffect
        avance.snapTo(0f)
        avance.animateTo(1f, tween(900, easing = LinearEasing))
    }
    Canvas(modifier.graphicsLayer { alpha = if (avance.value >= 1f) 0f else 1f }) {
        val t = avance.value
        if (t >= 1f) return@Canvas
        val c = origen ?: Offset(size.width * 0.42f, size.height * 0.55f)
        val paso = 34.dp.toPx()
        val maxD = max(hypot(c.x, c.y), hypot(size.width - c.x, size.height - c.y))
        val frente = t * (maxD + 300.dp.toPx())
        var y = paso / 2
        while (y < size.height) {
            var x = paso / 2
            while (x < size.width) {
                val d = hypot(x - c.x, y - c.y)
                val dif = frente - d
                // El punto se enciende cuando llega el frente de la onda y se apaga en unos 160 dp.
                if (dif > 0f && dif < 300.dp.toPx()) {
                    val k = dif / 300.dp.toPx()
                    val a = sin(k * Math.PI).toFloat()
                    drawCircle(Color.White.copy(alpha = 0.95f * a), (1.5f + 6f * a).dp.toPx(), Offset(x, y))
                }
                x += paso
            }
            y += paso
        }
    }
}

private val yaEntraron = HashSet<String>()

/**
 * Entrada de una fila la primera vez que aparece (en el proceso): sube unos dp y se hace visible con un muelle suave,
 * escalonada por `retardo`. Si la fila ya entró antes, aparece sin animar.
 */
@Composable
internal fun Modifier.entrada(clave: String, retardo: Int = 0): Modifier {
    val nuevo = remember { yaEntraron.add(clave) }
    val a = remember { Animatable(if (nuevo) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (nuevo) { delay(retardo.toLong()); a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 140f)) }
    }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 36.dp.toPx() }
}

/** Estado compartido de la entrada: la Activity lo consulta para saltarla con cualquier tecla. */
internal object IntroEstado {
    @Volatile var activa = false
    @Volatile var yaArranco = false
    val saltar = kotlinx.coroutines.flow.MutableStateFlow(0)
}

