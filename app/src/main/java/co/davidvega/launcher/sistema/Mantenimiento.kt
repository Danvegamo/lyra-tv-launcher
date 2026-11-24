package co.davidvega.launcher.sistema

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.ApplicationInfo
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import co.davidvega.launcher.data.Diagnostico
import co.davidvega.launcher.data.Preferencias
import dadb.AdbKeyPair
import java.io.File

/**
 * Mantenimiento nocturno: tras semanas encendido el Fire TV se queda sin RAM y sin swap (Crunchyroll baja a 360p).
 * Una app no puede reiniciar el aparato, así que el launcher habla con el adbd de la propia tele (127.0.0.1:5555, adb por red
 * activado) como un cliente adb más, con la librería dadb (ver AdbLocal). Con ese usuario `shell` sí se pueden ejecutar `pm trim-caches` y `reboot`.
 *
 * Cada día a las 4:30 una alarma (AlarmManager) lanza el mantenimiento, solo si TODO esto se cumple:
 *  - el ajuste «Mantenimiento nocturno» está activo (con el ajuste apagado no hay alarma);
 *  - la tele no está en uso: pantalla apagada o salvapantallas (si se ve la pantalla encendida y no se puede saber si es el
 *    salvapantallas, se asume «en uso»);
 *  - ninguna app está reproduciendo (MediaSession en PLAYING);
 *  - lleva encendida más de 20 h (evita bucles de reinicio).
 * Si algo no se cumple se anota el motivo y se reintenta una vez 30 min después (no después de las 6:30). Las condiciones se vuelven a
 * comprobar justo antes del `reboot`, y no se reinicia si `pm trim-caches` falló.
 * Acción: `pm trim-caches 2G` y `reboot`. Sin adb local (no autorizado o apagado) se cierran las apps de terceros en segundo plano UNA sola vez
 * y Ajustes avisa de que falta autorizar. Todo el trabajo cabe en 50 s (el receptor usa goAsync).
 * La autorización se hace una vez desde Ajustes («Autorizar mantenimiento»): Fire OS muestra «¿Permitir la depuración?» y se marca «Siempre».
 * «Probar ahora» nunca reinicia, así que se salta las condiciones de uso: llega siempre a adb y a trim-caches.
 */
object Mantenimiento {
    private const val ETIQUETA = "LauncherMant"
    private const val TIPO = "mantenimiento"
    const val ACCION = "co.davidvega.launcher.MANTENIMIENTO"
    const val EXTRA_REINTENTO = "reintento"
    private const val UPTIME_MIN_MS = 20 * 3_600_000L
    private const val PUERTO = 5555
    private const val NO_SE_EJECUTA = "No se ejecutaría: "
    private const val TOPE_TOTAL_MS = 50_000L
    private const val ECO_MS = 10_000L
    private const val TRIM_MS = 25_000L
    private const val REBOOT_MS = 8_000L
    private const val PREF_SIN_AUTORIZAR = "mant_sin_autorizar"
    private const val PREF_PLAN_B = "mant_plan_b_hecho"
    private const val PREF_ANTES = "mant_estado_antes"
    private const val USO_RECIENTE_MS = 3_600_000L
    private const val CAPTURAS_MAX_MS = 30L * 86_400_000L
    private const val COIL_MAX_BYTES = 64L * 1024 * 1024

    // ---------- Alarma ----------

    private fun pendiente(ctx: Context, reintento: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, if (reintento) 2 else 1,
            Intent(ctx, MantenimientoReceiver::class.java).setAction(ACCION).putExtra(EXTRA_REINTENTO, reintento),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun armar(ctx: Context, cuando: Long, reintento: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // API 28: setExactAndAllowWhileIdle no pide permiso especial y despierta también en Doze.
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pendiente(ctx, reintento))
    }

    private fun cancelar(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendiente(ctx, false)); am.cancel(pendiente(ctx, true))
    }

    /**
     * Deja armada la alarma diaria (la próxima 4:30 local). Se llama desde Arranque (también al cambiar la hora o la zona), MainActivity, Ajustes
     * y al terminar cada ejecución. Con el ajuste apagado se cancelan las alarmas: ya no despierta el aparato sin motivo.
     */
    fun programar(ctx: Context) {
        try {
            if (!Preferencias.mantenimiento(ctx)) { cancelar(ctx); return }
            armar(ctx, MantenimientoPlan.proxima(System.currentTimeMillis()), false)
        } catch (e: Exception) { Diagnostico.registrar(TIPO, "no se pudo programar la alarma (${e.javaClass.simpleName})") }
    }

    /** Reintento en 30 min; false si pasaría de las 6:30. */
    private fun programarReintento(ctx: Context): Boolean {
        val t = MantenimientoPlan.reintento(System.currentTimeMillis()) ?: return false
        armar(ctx, t, true)
        return true
    }

    // ---------- Condiciones ----------

    private class Motivo(val texto: String, val reintentable: Boolean)

    private const val AJUSTE_APAGADO = "ajuste «Mantenimiento nocturno» apagado"

    /** null si se puede ejecutar. La prueba (`reiniciar=false`) nunca reinicia, así que puede correr con la tele en uso: sin condiciones. */
    private fun motivo(ctx: Context, reiniciar: Boolean): Motivo? {
        if (!reiniciar) return null
        if (!Preferencias.mantenimiento(ctx)) return Motivo(AJUSTE_APAGADO, false) // no se reintenta
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            when (salvapantallasActivo(ctx)) {
                true -> {}
                false -> return Motivo("la tele está en uso (pantalla encendida, sin salvapantallas)", true)
                null -> return Motivo("pantalla encendida y no se puede saber si es el salvapantallas (se asume en uso)", true)
            }
        }
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val suena = msm.getActiveSessions(ComponentName(ctx, EscuchaMedios::class.java)).any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (suena) return Motivo("hay una reproducción en curso", true)
        } catch (e: SecurityException) {
            return Motivo("no se pueden leer las sesiones de medios (escucha de medios apagada)", true)
        }
        val ms = SystemClock.elapsedRealtime()
        if (ms < UPTIME_MIN_MS) return Motivo("lleva encendida solo ${ms / 3_600_000L} h (mínimo 20 h)", true)
        if (usoReciente(ctx)) return Motivo("hubo uso de la tele en la última hora", true)
        return null
    }

    /** ¿Alguna app (que no sea este launcher) pasó a primer plano en la última hora? Sin permiso de uso no se puede saber: false. */
    private fun usoReciente(ctx: Context): Boolean = try {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ahora = System.currentTimeMillis()
        val ev = um.queryEvents(ahora - USO_RECIENTE_MS, ahora)
        val e = UsageEvents.Event()
        var uso = false
        while (ev.hasNextEvent() && !uso) { ev.getNextEvent(e); uso = MantenimientoLimpieza.esUso(e.eventType, e.packageName, ctx.packageName) }
        uso
    } catch (_: Exception) { false }

    /** Texto del motivo para quien lo pida desde fuera (null si se puede ejecutar). */
    fun motivoParaNoEjecutar(ctx: Context, reiniciar: Boolean): String? = motivo(ctx, reiniciar)?.texto

    // Salvapantallas: el último aviso de ACTION_DREAMING_STARTED/STOPPED (si el launcher estaba vivo para oírlo).
    @Volatile private var sonandoAviso: Boolean? = null
    @Volatile private var escuchandoSalvapantallas = false

    /** Registra (una vez por proceso) el oyente de DREAMING_STARTED/STOPPED. Se llama desde App.onCreate. */
    fun iniciar(ctx: Context) {
        if (escuchandoSalvapantallas) return
        escuchandoSalvapantallas = true
        compararTrasReinicio(ctx.applicationContext)
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { sonandoAviso = i.action == Intent.ACTION_DREAMING_STARTED }
            }, IntentFilter().apply { addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED) })
        } catch (e: Exception) { escuchandoSalvapantallas = false }
    }

    /**
     * ¿Está el salvapantallas en marcha? `getSystemService("dreams")` devuelve null en Android 9 (DreamManager no se registra para las apps), así
     * que se pregunta a IDreamManager por ServiceManager con reflexión; si falla, se usa el último aviso DREAMING_*; si tampoco hay, null = no se sabe.
     */
    private fun salvapantallasActivo(ctx: Context): Boolean? {
        try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "dreams") as IBinder?
            if (binder != null) {
                val dm = Class.forName("android.service.dreams.IDreamManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                return dm.javaClass.getMethod("isDreaming").invoke(dm) as Boolean
            }
        } catch (_: Throwable) { /* API oculta bloqueada o distinta: se prueba lo siguiente */ }
        return sonandoAviso
    }

    // ---------- adb local ----------

    private fun llaves(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** adb en 127.0.0.1:5555 con tiempo máximo `ms` (ver AdbLocal). Con `esperaCorte` solo un corte de conexión ya conectados cuenta como éxito. */
    fun adb(ctx: Context, cmd: String, ms: Long, esperaCorte: Boolean = false): PasoMant {
        // Único punto por el que sale una orden adb: ninguna que apunte a un paquete de no_tocar.txt (ver Ordenes).
        if (!Ordenes.permitida(cmd, NoTocar.cargar(ctx))) {
            Diagnostico.registrar(TIPO, "orden rechazada (no permitida): $cmd")
            return PasoMant(false, "orden no permitida")
        }
        return AdbLocal.ejecutar("127.0.0.1", PUERTO, { llaves(ctx) }, cmd, ms, esperaCorte)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** ¿Falta autorizar adb? (el último intento sin autorización no llegó a adb). Lo lee Ajustes para avisar. */
    fun faltaAutorizar(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_SIN_AUTORIZAR, false)

    private fun marcarAutorizacion(ctx: Context, falta: Boolean) {
        val e = prefs(ctx).edit().putBoolean(PREF_SIN_AUTORIZAR, falta)
        if (!falta) e.putBoolean(PREF_PLAN_B, false) // autorizado: si algún día se pierde, el plan B vuelve a tener su única vez
        e.apply()
    }

    /** Botón «Autorizar mantenimiento»: abre la conexión y hace `echo ok`; así Fire OS pregunta «¿Permitir la depuración?». */
    fun autorizar(ctx: Context): PasoMant {
        val p = adb(ctx, "echo ok", 60_000L)
        marcarAutorizacion(ctx, !p.ok)
        Diagnostico.registrar(TIPO, if (p.ok) "adb local autorizado" else "autorizar adb local falló: ${p.texto}")
        return p
    }

    // ---------- Ejecución ----------

    /**
     * Ejecuta el mantenimiento. `reiniciar=false` es la prueba: hace `echo ok` y `pm trim-caches` (sin mirar si la tele está en uso) y NUNCA reinicia.
     * Devuelve el resumen para Ajustes; todo se anota en Diagnostico. Cabe en 50 s: cada paso recibe lo que queda del presupuesto.
     */
    fun ejecutar(ctx: Context, reiniciar: Boolean): PasoMant {
        val presupuesto = MantenimientoPlan.Presupuesto(TOPE_TOTAL_MS)
        val m = motivo(ctx, reiniciar)
        if (m != null) {
            Diagnostico.registrar(TIPO, "no se ejecuta: ${m.texto}")
            return PasoMant(false, NO_SE_EJECUTA + m.texto, m.reintentable)
        }
        val eco = adb(ctx, "echo ok", presupuesto.limite(ECO_MS))
        if (!eco.ok) {
            marcarAutorizacion(ctx, true)
            Diagnostico.registrar(TIPO, "sin adb local: autorizar en Ajustes (${eco.texto})")
            if (reiniciar) cerrarSegundoPlano(ctx)
            return PasoMant(false, "Sin adb local: autorizar en Ajustes (${eco.texto})")
        }
        marcarAutorizacion(ctx, false)
        val antes = Salud.instantanea()
        val trim = adb(ctx, "pm trim-caches 2G", presupuesto.limite(TRIM_MS))
        Diagnostico.registrar(TIPO, "pm trim-caches 2G: " + if (trim.ok) "ok" else trim.texto)
        limpiarPropios(ctx)
        val bloqueadas = Control.reaplicar(ctx, presupuesto)
        Diagnostico.registrar(TIPO, "cmd appops RUN_IN_BACKGROUND ignore: $bloqueadas apps")
        if (!reiniciar) return PasoMant(trim.ok, "adb ok; trim-caches " + (if (trim.ok) "ok" else "falló (${trim.texto})") + "; no se reinició (prueba)")
        if (!trim.ok) {
            Diagnostico.registrar(TIPO, "no se reinicia: trim-caches falló (${trim.texto})")
            return PasoMant(false, "trim-caches falló, no se reinicia: ${trim.texto}", reintentable = true)
        }
        // Pasaron hasta ~35 s desde el chequeo inicial: la tele pudo encenderse o empezar una reproducción. Se vuelve a mirar antes del reboot.
        val ahora = motivo(ctx, true)
        if (ahora != null) {
            Diagnostico.registrar(TIPO, "no se reinicia: ${ahora.texto}")
            return PasoMant(false, NO_SE_EJECUTA + ahora.texto, ahora.reintentable)
        }
        if (presupuesto.restante() < 3_000L) {
            Diagnostico.registrar(TIPO, "no se reinicia: se acabó el tiempo disponible")
            return PasoMant(false, "no se reinicia: se acabó el tiempo disponible", reintentable = true)
        }
        if (!guardarAntes(ctx, antes)) Diagnostico.registrar(TIPO, "no se pudo guardar el estado previo")
        Diagnostico.registrar(TIPO, "estado antes del reinicio: ${antes.resumen()}")
        Diagnostico.registrar(TIPO, "enviando reboot")
        val r = adb(ctx, "reboot", presupuesto.limite(REBOOT_MS), esperaCorte = true)
        Diagnostico.registrar(TIPO, if (r.ok) "reiniciando" else "reboot falló: ${r.texto}")
        return PasoMant(r.ok, if (r.ok) "reiniciando" else "reboot falló: ${r.texto}")
    }

    /**
     * Plan B sin adb: cierra en segundo plano las apps de terceros con icono, menos el launcher. Solo UNA vez (hasta que se autorice adb):
     * matar descargas y sincronizaciones cada noche sin que el usuario haya aceptado nada era demasiado.
     */
    private fun cerrarSegundoPlano(ctx: Context) {
        if (prefs(ctx).getBoolean(PREF_PLAN_B, false)) { Diagnostico.registrar(TIPO, "plan B ya usado: falta autorizar adb en Ajustes"); return }
        prefs(ctx).edit().putBoolean(PREF_PLAN_B, true).apply()
        try {
            val pm = ctx.packageManager
            val paquetes = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).map { it.activityInfo.applicationInfo }
            }.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }.map { it.packageName }.distinct()
            paquetes.forEach { Control.matar(ctx, it) } // Control.matar rechaza los paquetes de no_tocar.txt
            Diagnostico.registrar(TIPO, "plan B (única vez): se pidió cerrar ${paquetes.size} apps en segundo plano; falta autorizar adb en Ajustes")
        } catch (e: Exception) {
            Diagnostico.registrar(TIPO, "plan B falló (${e.javaClass.simpleName})")
        }
    }

    // ---------- Antes y después del reinicio, limpieza propia ----------

    private fun guardarAntes(ctx: Context, i: Instantanea): Boolean =
        prefs(ctx).edit().putString(PREF_ANTES, "${System.currentTimeMillis()}|${i.aTexto()}").commit()

    /**
     * Tras un reinicio hecho por el mantenimiento, compara el estado de entonces con el de ahora (2 min después de abrir el proceso, para que
     * el arranque se asiente) y lo anota en el diagnóstico. Se llama desde `iniciar`. Si el estado guardado es de hace más de 3 h, se descarta.
     */
    private fun compararTrasReinicio(ctx: Context) {
        val g = prefs(ctx).getString(PREF_ANTES, null) ?: return
        val t = g.substringBefore('|').toLongOrNull()
        val antes = Instantanea.deTexto(g.substringAfter('|', ""))
        prefs(ctx).edit().remove(PREF_ANTES).apply()
        if (t == null || antes == null || System.currentTimeMillis() - t > 3 * 3_600_000L) return
        Thread {
            try {
                Thread.sleep(120_000L)
                val ahora = Salud.instantanea()
                Diagnostico.registrar(TIPO, "tras el reinicio: " + SaludCalculo.mejora(antes, ahora))
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    /** Capturas .png de más de 30 días en Android/data/co.davidvega.launcher/files y la caché de imágenes de Coil si pasa de 64 MB. */
    private fun limpiarPropios(ctx: Context) {
        try {
            val borrados = MantenimientoLimpieza.borrarCapturasViejas(ctx.getExternalFilesDir(null), System.currentTimeMillis(), CAPTURAS_MAX_MS)
            Diagnostico.registrar(TIPO, "capturas propias viejas borradas: ${borrados.first} (${borrados.second / 1024} KB)")
        } catch (e: Exception) { Diagnostico.registrar(TIPO, "limpieza de capturas falló (${e.javaClass.simpleName})") }
        try {
            val disco = coil.Coil.imageLoader(ctx).diskCache
            if (disco != null && disco.size > COIL_MAX_BYTES) {
                val antes = disco.size
                disco.clear()
                Diagnostico.registrar(TIPO, "caché de Coil: ${antes / 1_048_576} MB -> 0 (tope 64 MB)")
            }
        } catch (e: Exception) { Diagnostico.registrar(TIPO, "caché de Coil: ${e.javaClass.simpleName}") }
    }

    /** Última entrada de mantenimiento del diagnóstico, para Ajustes. */
    fun ultimaEntrada(): String =
        Diagnostico.recientes(100).firstOrNull { it.tipo == TIPO }?.let { Diagnostico.hora(it.t) + " · " + it.detalle } ?: "Aún no hay mantenimientos registrados"

    internal fun alDisparar(ctx: Context, reintento: Boolean) {
        programar(ctx) // la de mañana (si el ajuste está apagado, cancela y no queda alarma), antes de cualquier otra cosa
        val r = try { ejecutar(ctx, reiniciar = true) } catch (e: Throwable) { PasoMant(false, "error: ${e.javaClass.simpleName}") }
        android.util.Log.i(ETIQUETA, "mantenimiento: ${r.texto}")
        // Solo se reintenta si una condición pasajera no se cumplió (no si falló adb ni si el ajuste está apagado) y solo una vez.
        if (!r.ok && !reintento && r.reintentable) {
            Diagnostico.registrar(TIPO, if (programarReintento(ctx)) "reintento en 30 min" else "sin reintento: pasarían las 6:30")
        }
    }
}

/** Alarma de las 4:30 (y su reintento). El trabajo va en un hilo aparte con goAsync para no bloquear el hilo principal. */
class MantenimientoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Mantenimiento.ACCION) return
        val reintento = intent.getBooleanExtra(Mantenimiento.EXTRA_REINTENTO, false)
        val pendiente = goAsync()
        val app = ctx.applicationContext
        Thread {
            try { Mantenimiento.alDisparar(app, reintento) } finally { pendiente.finish() }
        }.start()
    }
}
