package co.davidvega.launcher.sistema

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Un acceso directo a una pantalla de Ajustes. `intentos` se prueban en orden hasta que uno abre. */
data class Atajo(val id: String, val titulo: String, val detalle: String, val intentos: List<String>)

/**
 * Accesos directos a Ajustes del Fire TV. Probados por adb en Fire OS 7.6.9.7 (ver README, "Control del Fire TV").
 * Fire OS protege varias pantallas de com.amazon.tv.settings.v2 (Pantalla y sonido, Accesibilidad, Preferencias...):
 * un tercero recibe SecurityException. Esas se alcanzan desde "Todos los ajustes".
 */
object Ajustes {
    const val AMAZON_SETTINGS = "com.amazon.tv.settings.v2"

    val ATAJOS = listOf(
        Atajo("wifi", "Wi-Fi y red", "Conexión y redes", listOf(Settings.ACTION_WIFI_SETTINGS)),
        Atajo("mandos", "Mandos y Bluetooth", "Control remoto, teclados, audífonos", listOf("android.settings.CONTROLLERS_SETTINGS", "com.amazon.device.settings.action.CONTROLLERS")),
        Atajo("apps", "Aplicaciones", "Instaladas y permisos", listOf(Settings.ACTION_APPLICATION_SETTINGS)),
        Atajo("almacenamiento", "Almacenamiento", "Espacio libre y USB", listOf("com.amazon.device.settings.action.EXTERNAL_STORAGE")),
        Atajo("mifire", "Mi Fire TV", "Versión, almacenamiento y actualizaciones", listOf(Settings.ACTION_DEVICE_INFO_SETTINGS)),
        Atajo("todos", "Todos los ajustes", "Pantalla, sonido, accesibilidad", listOf(Settings.ACTION_SETTINGS)),
        // Diálogo de energía de Android vía el servicio de accesibilidad (probado el 22 oct 2026: Apagar, Reiniciar, Bloqueo).
        Atajo("energia", "Apagar o reiniciar", "Apagar, reiniciar o bloquear", listOf(ENERGIA)),
    )

    private const val ENERGIA = "accesibilidad:energia"

    /** Devuelve el índice del intento que abrió, o -1 si ninguno. */
    fun abrir(ctx: Context, a: Atajo): Int {
        a.intentos.forEachIndexed { n, accion ->
            if (accion == ENERGIA) return if (HomeAccessibilityService.accionGlobal("energia")) n else -1
            try {
                if (accion == Settings.ACTION_SETTINGS) HomeAccessibilityService.pausar(8000)
                ctx.startActivity(Intent(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i("Launcher", "ajuste ${a.id}: intento $n ($accion) ok")
                return n
            } catch (e: ActivityNotFoundException) {
                Log.i("Launcher", "ajuste ${a.id}: intento $n ($accion) no existe")
            } catch (e: SecurityException) {
                Log.i("Launcher", "ajuste ${a.id}: intento $n ($accion) denegado")
            }
        }
        return -1
    }

    fun copiar(ctx: Context, texto: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("titulo", texto))
    }
}
