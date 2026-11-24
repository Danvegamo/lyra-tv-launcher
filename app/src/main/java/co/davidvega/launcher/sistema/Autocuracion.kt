package co.davidvega.launcher.sistema

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import co.davidvega.launcher.MainActivity
import co.davidvega.launcher.data.Diagnostico

/**
 * Autocuración: Fire OS a veces saca el servicio de accesibilidad de la lista de activos (tras un «Forzar detención»,
 * una reinstalación o un reinicio), y sin él no funcionan Home, la voz ni la búsqueda guiada. Si la app tiene
 * WRITE_SECURE_SETTINGS (lo concede instalar.ps1 con pm grant), se vuelve a añadir sola, igual que la escucha de medios.
 * Sin el permiso solo se anota en el diagnóstico.
 */
object Autocuracion {
    private const val ACCESIBILIDAD = "co.davidvega.launcher/co.davidvega.launcher.sistema.HomeAccessibilityService"
    private const val MEDIOS = "co.davidvega.launcher/co.davidvega.launcher.sistema.EscuchaMedios"

    fun asegurar(ctx: Context, motivo: String) {
        val cr = ctx.contentResolver
        asegurarEnLista(ctx, "enabled_accessibility_services", ACCESIBILIDAD, motivo) {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        asegurarEnLista(ctx, "enabled_notification_listeners", MEDIOS, motivo) {}
    }

    private fun asegurarEnLista(ctx: Context, clave: String, componente: String, motivo: String, despues: () -> Unit) {
        val cr = ctx.contentResolver
        val actual = Settings.Secure.getString(cr, clave).orEmpty()
        if (actual.split(':').any { it.equals(componente, ignoreCase = true) }) return
        try {
            Settings.Secure.putString(cr, clave, if (actual.isBlank()) componente else "$actual:$componente")
            despues()
            android.util.Log.i("LauncherHome", "autocuración ($motivo): reactivado $clave")
            Diagnostico.registrar("autocuracion", "$motivo: reactivado $clave")
        } catch (e: SecurityException) {
            Diagnostico.registrar("autocuracion", "$motivo: $clave apagado y sin WRITE_SECURE_SETTINGS (correr instalar.ps1)")
        }
    }
}

/** Al encender la tele (o tras actualizar la app): reactiva servicios y pone el launcher delante. */
class Arranque : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Cambio de hora o de zona: la alarma de las 4:30 se recalcula y no se toca nada más.
        if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) { Mantenimiento.programar(ctx); return }
        val motivo = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "actualización" else "arranque"
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // El mando listo al encender: el launcher va primero y lo pesado espera 8 s (E3). Si la accesibilidad tarda en volver,
            // Home abre unos segundos el inicio de Amazon, como ya ocurría.
            try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { android.util.Log.w("LauncherHome", "arranque: $e") }
            val app = ctx.applicationContext
            diferido = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Autocuracion.asegurar(app, motivo)
                Mantenimiento.programar(app)
                runCatching { (app as? co.davidvega.launcher.App)?.eventos?.reintentar() }
                diferido = false
            }, ESPERA_ARRANQUE_MS)
            return
        }
        Autocuracion.asegurar(ctx, motivo)
        Mantenimiento.programar(ctx)
    }

    companion object {
        /** Tras BOOT_COMPLETED, lo pesado (autocuración, mantenimiento, cola de eventos) espera esto para no competir con la entrada. */
        const val ESPERA_ARRANQUE_MS = 8_000L

        /** Verdadero entre BOOT_COMPLETED y el fin de la espera; MainActivity.onResume no repite entonces lo que se difiere. */
        @Volatile var diferido = false
    }
}
