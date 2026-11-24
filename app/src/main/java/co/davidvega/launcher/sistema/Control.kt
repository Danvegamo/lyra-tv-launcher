package co.davidvega.launcher.sistema

import android.content.Context
import android.provider.Settings
import co.davidvega.launcher.data.Diagnostico

/**
 * Lista de paquetes que el launcher nunca toca (asset `no_tocar.txt`, generado por `generar_no_tocar.py` desde
 * firetv-debloat\no-tocar.txt). Si el asset no se puede leer devuelve un conjunto vacío y `Ordenes` rechaza todo (falla cerrado).
 */
internal object NoTocar {
    fun parsear(texto: String): Set<String> =
        texto.lineSequence().map { linea -> linea.substringBefore('#').split(Regex("[\\s(]+")).map(::quitarInvisibles).firstOrNull { it.isNotEmpty() }.orEmpty() }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()

    /**
     * Quita en toda la línea los espacios (ASCII y Unicode) y los caracteres de formato invisibles (categoría Cf: BOM U+FEFF,
     * U+200B/C/D, U+2060, guion blando U+00AD…). Un nombre de paquete nunca los lleva, así que no altera ninguno real.
     */
    private fun quitarInvisibles(s: String): String =
        s.filterNot { c -> c.isWhitespace() || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT.toInt() }

    @Volatile private var cache: Set<String>? = null

    fun cargar(ctx: Context): Set<String> = cache ?: try {
        ctx.applicationContext.assets.open("no_tocar.txt").bufferedReader().use { parsear(it.readText()) }.also { cache = it }
    } catch (e: Exception) { emptySet() }
}

/**
 * ÚNICO punto que decide si una orden del launcher se puede ejecutar. Reglas:
 *  - solo valen las formas de la lista blanca (`FORMAS`): nada de `pm disable-user`, `pm clear`, `am force-stop` ni `settings put`;
 *  - sin metacaracteres de shell (no se pueden encadenar ni construir órdenes);
 *  - ningún token puede ser un paquete de `no_tocar.txt` (ni empezar por «paquete.»), ni siquiera dentro de un componente `paquete/clase`;
 *  - con la lista vacía (asset ilegible) no se permite nada.
 * Todo `adb` pasa por `Mantenimiento.adb`, que llama a `permitida`; las órdenes de cerrar procesos pasan por `puedeMatar`.
 */
internal object Ordenes {
    private val PAQUETE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val PROHIBIDOS = Regex("[;&|`$()<>\\n\\r\\\\*?\"'{}\\[\\]!~]")
    private val SEPARADOR = Regex("[^A-Za-z0-9_.]+")
    private const val PKG = "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"
    private val FORMAS = listOf(
        Regex("^echo ok$"),
        Regex("^reboot$"),
        Regex("^pm trim-caches 2G$"),
        Regex("^dumpsys meminfo$"),
        Regex("^cat /data/misc/adb/adb_keys$"),
        Regex("^cmd appops (?:set $PKG RUN_IN_BACKGROUND (?:ignore|default)|get $PKG RUN_IN_BACKGROUND)$"),
    )

    /** Primer paquete protegido al que apunta `cmd` (como token o como prefijo de token), o null. */
    fun apunta(cmd: String, noTocar: Set<String>): String? {
        val tokens = cmd.split(SEPARADOR).filter { it.isNotEmpty() }.map { it.lowercase() }
        return noTocar.firstOrNull { p -> tokens.any { it == p || it.startsWith("$p.") } }
    }

    /** `cmd appops set|get <paquete> ...` solo vale para los paquetes de `Control.APPS` (lista blanca además de la negra). */
    private val APPOPS = Regex("^cmd appops (?:set|get) ($PKG) .*$")

    private fun appopsDeControl(cmd: String): Boolean {
        val pkg = APPOPS.matchEntire(cmd)?.groupValues?.get(1) ?: return true // no es appops: lo decide FORMAS
        return Control.APPS.any { it.paquete == pkg }
    }

    fun permitida(cmd: String, noTocar: Set<String>): Boolean =
        noTocar.isNotEmpty() && !PROHIBIDOS.containsMatchIn(cmd) && FORMAS.any { it.matches(cmd) } && appopsDeControl(cmd) && apunta(cmd, noTocar) == null

    /** ¿Se puede pedir al sistema que cierre los procesos de fondo de `paquete` (killBackgroundProcesses)? */
    fun puedeMatar(paquete: String, noTocar: Set<String>): Boolean =
        noTocar.isNotEmpty() && PAQUETE.matches(paquete) && apunta(paquete, noTocar) == null

    // ---- Constructores: devuelven null si la orden no pasa `permitida` ----

    fun segundoPlano(paquete: String, permitir: Boolean, noTocar: Set<String>): String? =
        "cmd appops set $paquete RUN_IN_BACKGROUND ${if (permitir) "default" else "ignore"}".takeIf { permitida(it, noTocar) }

    fun leerSegundoPlano(paquete: String, noTocar: Set<String>): String? =
        "cmd appops get $paquete RUN_IN_BACKGROUND".takeIf { permitida(it, noTocar) }

    fun trimCaches(noTocar: Set<String>): String? = "pm trim-caches 2G".takeIf { permitida(it, noTocar) }

    /** Todas las órdenes que puede generar el launcher para un paquete (para las pruebas). */
    fun todasPara(paquete: String, noTocar: Set<String>): List<String?> =
        listOf(segundoPlano(paquete, true, noTocar), segundoPlano(paquete, false, noTocar), leerSegundoPlano(paquete, noTocar))
}

/** Control de Fire OS: segundo plano por app (S2) y límite de procesos cacheados (S3). Todo reversible y anotado en Diagnostico con su orden inversa. */
object Control {
    private const val TIPO = "control"
    private const val AJUSTE = "activity_manager_constants"
    private const val CLAVE = "max_cached_processes"
    private const val PREF_LIMITE = "procesos_limite"
    private const val PREF_ORIGINAL = "procesos_limite_original"
    private const val ORDEN_MS = 6_000L

    /** `sugerido`: el launcher lo propone; `bloqueadoPorOmision`: decisión del usuario (SmartTube), pierde el control remoto por Cast. */
    class AppControl(val paquete: String, val nombre: String, val sugerido: Boolean, val bloqueadoPorOmision: Boolean, val nota: String)

    val APPS = listOf(
        AppControl("org.smarttube.stable", "SmartTube", true, true, "Bloqueado por omisión: pierde el control remoto por Cast"),
        AppControl("com.crunchyroll.crunchyroid", "Crunchyroll", true, false, "Sugerido: hace telemetría en segundo plano"),
        AppControl("com.hbo.hbonow", "HBO Max", false, false, "Opcional"),
        AppControl("com.example.iptv", "IPTV", false, false, "Opcional: la guía puede tardar en actualizarse"),
    )

    /** Opciones de «procesos en segundo plano»: normal (32, la de fábrica), ligero (16), muy ligero (8). */
    val LIMITES = listOf(32 to "normal (32)", 16 to "ligero (16)", 8 to "muy ligero (8)")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun bloqueada(ctx: Context, a: AppControl): Boolean = prefs(ctx).getBoolean("bg_" + a.paquete, a.bloqueadoPorOmision)

    /** Aplica y recuerda el estado. Reversión: la orden contraria, que se anota en el diagnóstico. */
    fun fijarSegundoPlano(ctx: Context, a: AppControl, bloquear: Boolean): PasoMant {
        val nt = NoTocar.cargar(ctx)
        val cmd = Ordenes.segundoPlano(a.paquete, !bloquear, nt) ?: return PasoMant(false, "orden no permitida para ${a.nombre}")
        val p = Mantenimiento.adb(ctx, cmd, ORDEN_MS)
        Diagnostico.registrar(TIPO, "$cmd: " + (if (p.ok) "ok" else p.texto) + " · revertir: " + Ordenes.segundoPlano(a.paquete, bloquear, nt))
        if (p.ok) prefs(ctx).edit().putBoolean("bg_" + a.paquete, bloquear).apply()
        return p
    }

    /** Vuelve a aplicar «ignore» a las apps bloqueadas (idempotente; se llama en el mantenimiento). Devuelve cuántas se aplicaron. */
    internal fun reaplicar(ctx: Context, presupuesto: MantenimientoPlan.Presupuesto): Int {
        val nt = NoTocar.cargar(ctx)
        var n = 0
        for (a in APPS.filter { bloqueada(ctx, it) }) {
            val cmd = Ordenes.segundoPlano(a.paquete, false, nt) ?: continue
            val p = Mantenimiento.adb(ctx, cmd, presupuesto.limite(ORDEN_MS))
            Diagnostico.registrar(TIPO, "$cmd: " + (if (p.ok) "ok" else p.texto) + " · revertir: " + Ordenes.segundoPlano(a.paquete, true, nt))
            if (p.ok) n++
        }
        return n
    }

    /** Cierra los procesos de fondo de un paquete, salvo los protegidos. */
    fun matar(ctx: Context, paquete: String): Boolean {
        if (!Ordenes.puedeMatar(paquete, NoTocar.cargar(ctx))) return false
        return try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(paquete); true } catch (e: Exception) { false }
    }

    // ---------- S3: límite de procesos cacheados ----------

    /** Nuevo valor de `activity_manager_constants`: conserva las demás claves; 32 (fábrica) quita la clave; null = borrar el ajuste. */
    fun combinar(actual: String?, n: Int, original: String? = null): String? {
        val resto = actual.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=').trim() != CLAVE }
        // 32 (fábrica): restaura la entrada que había antes del primer cambio, si había; si no, solo quita la clave.
        val todo = if (n == 32) resto + listOfNotNull(entradaDe(original)) else resto + "$CLAVE=$n"
        return todo.joinToString(",").ifEmpty { null }
    }

    /** La entrada `max_cached_processes=<v>` de un valor de `activity_manager_constants`, normalizada, o null. */
    internal fun entradaDe(valor: String?): String? =
        valor.orEmpty().split(',').map { it.trim() }.firstOrNull { it.substringBefore('=').trim() == CLAVE }
            ?.let { CLAVE + "=" + it.substringAfter('=', "").trim() }?.takeIf { !it.endsWith("=") }

    /** Lo elegido por el usuario (32 si nunca eligió nada: no se cambia nada hasta que elija). */
    fun limite(ctx: Context): Int = prefs(ctx).getInt(PREF_LIMITE, 32).takeIf { n -> LIMITES.any { it.first == n } } ?: 32

    fun fijarLimite(ctx: Context, n: Int): PasoMant {
        if (LIMITES.none { it.first == n }) return PasoMant(false, "valor no permitido")
        return try {
            val cr = ctx.contentResolver
            val antes = Settings.Global.getString(cr, AJUSTE)
            // El valor de fábrica se guarda antes del primer cambio (solo una vez) para poder restaurarlo con 32.
            val p = prefs(ctx)
            if (!p.contains(PREF_ORIGINAL)) p.edit().putString(PREF_ORIGINAL, entradaDe(antes).orEmpty()).apply()
            val nuevo = combinar(antes, n, p.getString(PREF_ORIGINAL, null)?.takeIf { it.isNotEmpty() })
            if (nuevo == null) cr.delete(Settings.Global.getUriFor(AJUSTE), null, null) else Settings.Global.putString(cr, AJUSTE, nuevo)
            prefs(ctx).edit().putInt(PREF_LIMITE, n).apply()
            Diagnostico.registrar(TIPO, "$AJUSTE: ${antes ?: "(vacío)"} -> ${nuevo ?: "(borrado)"} · revertir: $AJUSTE=${antes ?: "(borrar)"}")
            PasoMant(true, "Procesos en segundo plano: ${LIMITES.first { it.first == n }.second}")
        } catch (e: SecurityException) {
            PasoMant(false, "sin WRITE_SECURE_SETTINGS (instalar.ps1 lo concede)")
        } catch (e: Exception) {
            PasoMant(false, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
        }
    }
}


/** Limpieza y criterios del mantenimiento sin dependencias de Android (se prueban en la JVM). */
internal object MantenimientoLimpieza {
    // UsageEvents.Event: ACTIVITY_RESUMED = MOVE_TO_FOREGROUND = 1.
    private const val ACTIVIDAD_AL_FRENTE = 1

    /** ¿Este evento cuenta como uso de la tele? Una actividad al frente de otro paquete (no el propio launcher). */
    fun esUso(tipo: Int, paquete: String?, propio: String): Boolean = tipo == ACTIVIDAD_AL_FRENTE && !paquete.isNullOrEmpty() && paquete != propio

    /** Nombres de captura propios: `cap_*.png` (los que genera MainActivity) y los antiguos ap, inicio, captura, home*. */
    private val CAPTURA_PROPIA = Regex("^(?:cap_[A-Za-z0-9_-]*|ap|inicio|captura|home[A-Za-z0-9_-]*)\\.png$", RegexOption.IGNORE_CASE)

    /** Solo capturas propias .png (no carpetas, otros .png ni enlaces) de `dir` (sin recursión) con más de `maxMs` de antigüedad. Devuelve (cuántos, bytes). */
    fun borrarCapturasViejas(dir: java.io.File?, ahora: Long, maxMs: Long): Pair<Int, Long> {
        val archivos = dir?.listFiles() ?: return 0 to 0L
        var n = 0
        var bytes = 0L
        for (f in archivos) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath()) || !f.isFile || !CAPTURA_PROPIA.matches(f.name) || ahora - f.lastModified() <= maxMs) continue
            val tam = f.length()
            if (f.delete()) { n++; bytes += tam }
        }
        return n to bytes
    }
}
