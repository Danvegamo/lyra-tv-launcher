package co.davidvega.launcher.sistema

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.os.storage.StorageManager
import java.io.File

/** Lectura de RAM, swap y almacenamiento en un momento dado (kB y bytes). */
data class Instantanea(val ramDispKb: Long, val ramTotalKb: Long, val swapLibreKb: Long, val swapTotalKb: Long, val libreBytes: Long, val totalBytes: Long, val uptimeMs: Long) {
    fun resumen(): String = "RAM libre ${ramDispKb / 1024} MB de ${ramTotalKb / 1024}; swap libre ${swapLibreKb / 1024} de ${swapTotalKb / 1024} MB; disco libre ${libreBytes / 1_048_576} MB de ${totalBytes / 1_048_576}"

    fun aTexto(): String = listOf(ramDispKb, ramTotalKb, swapLibreKb, swapTotalKb, libreBytes, totalBytes, uptimeMs).joinToString(",")

    companion object {
        fun deTexto(t: String?): Instantanea? {
            val n = t?.split(',')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
            return if (n.size == 7) Instantanea(n[0], n[1], n[2], n[3], n[4], n[5], n[6]) else null
        }
    }
}

/** Cálculos de salud sin dependencias de Android (se prueban en la JVM). */
internal object SaludCalculo {
    enum class Nivel { OK, AMARILLO, ROJO }

    /** Amarillo con 20 % libre o menos, rojo con 10 % o menos. */
    fun nivelAlmacen(libre: Long, total: Long): Nivel {
        if (total <= 0) return Nivel.OK
        val f = libre.toDouble() / total
        return if (f <= 0.10) Nivel.ROJO else if (f <= 0.20) Nivel.AMARILLO else Nivel.OK
    }

    /** `/proc/meminfo` → clave → kB. */
    fun parsearMeminfo(texto: String): Map<String, Long> =
        texto.lineSequence().mapNotNull { l ->
            val k = l.substringBefore(':', "").trim()
            val v = l.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            if (k.isNotEmpty() && v != null) k to v else null
        }.toMap()

    /** «Total PSS by process» de `dumpsys meminfo` → (paquete/proceso, kB), de mayor a menor, `n` primeros. */
    fun parsearPss(texto: String, n: Int = 5): List<Pair<String, Long>> {
        val re = Regex("""^\s*([\d.,]+)K:\s+(\S+)\s+\(pid""")
        val l = texto.lineSequence().dropWhile { !it.contains("Total PSS by process") }.drop(1).takeWhile { !it.contains("Total PSS by") }
        return l.mapNotNull { re.find(it)?.let { m -> m.groupValues[2] to (m.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: return@let null) } }
            .sortedByDescending { it.second }.take(n).toList()
    }

    /** `adb_keys`: una llave por línea, «clave comentario»; devuelve el comentario (usuario@equipo) o «(sin nombre)». */
    fun parsearLlaves(texto: String): List<String> =
        texto.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
            val partes = l.split(' ', limit = 2)
            partes.getOrNull(1)?.trim()?.ifEmpty { null } ?: "(sin nombre)"
        }.toList()

    fun mb(kb: Long) = "${kb / 1024} MB"

    /** Cuánto mejoró (positivo = más libre) entre `antes` y `despues`. */
    fun mejora(antes: Instantanea, despues: Instantanea): String =
        "RAM libre ${mb(antes.ramDispKb)} -> ${mb(despues.ramDispKb)}; swap libre ${mb(antes.swapLibreKb)} -> ${mb(despues.swapLibreKb)}; " +
            "disco libre ${antes.libreBytes / 1_048_576} -> ${despues.libreBytes / 1_048_576} MB"
}

/** Panel de salud (S1) y aviso de adb por red (S5). Lecturas baratas; se refrescan al abrir el panel. */
object Salud {
    const val PUERTO_ADB = 5555

    fun instantanea(): Instantanea {
        val m = try { SaludCalculo.parsearMeminfo(File("/proc/meminfo").readText()) } catch (e: Exception) { emptyMap() }
        val st = try { StatFs(android.os.Environment.getDataDirectory().path) } catch (e: Exception) { null }
        return Instantanea(
            m["MemAvailable"] ?: 0, m["MemTotal"] ?: 0, m["SwapFree"] ?: 0, m["SwapTotal"] ?: 0,
            st?.availableBytes ?: 0, st?.totalBytes ?: 0, SystemClock.elapsedRealtime(),
        )
    }

    fun dias(ms: Long): String = if (ms >= 86_400_000L) "${ms / 86_400_000L} d ${ms % 86_400_000L / 3_600_000L} h" else "${ms / 3_600_000L} h"

    class Fila(val nombre: String, val detalle: String)

    /** Cinco procesos con más PSS, leídos de `dumpsys meminfo` por el adb local (una app normal solo ve su propio proceso). */
    fun masPesadas(ctx: Context): List<Fila> {
        val p = Mantenimiento.adb(ctx, "dumpsys meminfo", 10_000L)
        if (!p.ok) return listOf(Fila("Sin adb local", p.texto))
        return SaludCalculo.parsearPss(p.texto).map { Fila(it.first, SaludCalculo.mb(it.second)) }
    }

    /** Las tres apps con más datos (StorageStatsManager, necesita acceso a uso). IPTV lleva la nota de cierre de sesión. */
    fun masDatos(ctx: Context): List<Fila> {
        return try {
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val pm = ctx.packageManager
            val usuario = Process.myUserHandle()
            pm.getInstalledApplications(0).filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }.mapNotNull { ai ->
                try { ai.packageName to ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, usuario).dataBytes } catch (e: Exception) { null }
            }.sortedByDescending { it.second }.take(3).map { (pkg, b) ->
                val nombre = Control.APPS.firstOrNull { it.paquete == pkg }?.nombre ?: pkg
                Fila(nombre, "${b / 1_048_576} MB" + if (pkg == "com.example.iptv") " · cierre de sesión si se borra" else "")
            }
        } catch (e: Exception) {
            listOf(Fila("Sin acceso a uso", "Concede el acceso a uso al launcher"))
        }
    }

    // ---------- S5: adb por red ----------

    /** ¿Está `adb_wifi_enabled` en 1? (el puerto 5555 queda abierto en la red de la casa). */
    fun adbPorRed(ctx: Context): Boolean = try { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 } catch (e: Exception) { false }

    const val AVISO_ADB = "adb abierto por la red (puerto 5555): solo entran equipos con una llave RSA ya autorizada."

    /** Llaves autorizadas (comentarios de `adb_keys`) por el adb local, o un mensaje si no se pudo leer. */
    fun llavesAutorizadas(ctx: Context): List<String> {
        val p = Mantenimiento.adb(ctx, "cat /data/misc/adb/adb_keys", 8_000L)
        if (!p.ok) return listOf("No se pudieron leer: ${p.texto}")
        return SaludCalculo.parsearLlaves(p.texto).ifEmpty { listOf("(ninguna)") }
    }
}
