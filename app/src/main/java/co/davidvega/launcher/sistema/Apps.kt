package co.davidvega.launcher.sistema

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import org.json.JSONObject
import java.io.File

data class AppItem(val paquete: String, val etiqueta: String, val imagen: Bitmap?, val esBanner: Boolean, val segundosUso: Long)

/**
 * Lista de apps del Fire TV ordenada por uso. Fuentes de uso, en orden:
 *  1. UsageStatsManager si se concedió GET_USAGE_STATS (adb shell appops set ...).
 *  2. uso_apps.json (lo genera uso_apps.py leyendo `dumpsys usagestats`; se empuja a getExternalFilesDir o va en assets).
 *  3. Sin datos: orden alfabético.
 */
object Apps {
    // Apps de Amazon que sí tiene sentido mostrar (el resto del sistema se oculta).
    private val AMAZON_UTILES = setOf(
        "com.amazon.firebat", "com.amazon.tv.settings.v2", "com.amazon.venezia",
        "com.netflix.ninja", "com.amazon.firetv.youtube", "com.amazon.cloud9",
    )

    // El launcher de Amazon, com.amazon.avod (Prime Video viejo, no abre desde terceros; se usa com.amazon.firebat), Amazon Kids y las compras: no aportan.
    private val OCULTAS = setOf("com.amazon.tv.launcher", "com.amazon.avod", "com.amazon.tahoe", "com.amazon.shoptv.firetv.client")
    const val AJUSTES = "com.amazon.tv.settings.v2"
    const val BUSCAR = "__buscar__" // tarjeta propia del launcher, no una app

    // Nombres más claros que la etiqueta del sistema.
    private val ETIQUETAS = mapOf(
        "com.amazon.avod" to "Prime Video", "com.amazon.firebat" to "Prime Video",
        "com.example.iptv" to "IPTV", "com.hbo.hbonow" to "HBO Max", "com.limelight" to "Moonlight",
        "com.lonelycatgames.Xplore" to "X-plore", "com.esaba.downloader" to "Downloader",
        "com.amazon.venezia" to "Appstore", "com.amazon.tv.settings.v2" to "Ajustes",
    )

    fun cargar(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val intents = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        val vistos = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (cat in intents) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(q, 0)) vistos.putIfAbsent(r.activityInfo.packageName, r)
        }
        val uso = usoPorPaquete(ctx)
        val lista = vistos.values
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName !in OCULTAS }
            .filter {
                val ai = it.activityInfo.applicationInfo
                val sistema = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                !sistema || it.activityInfo.packageName in AMAZON_UTILES
            }
            .map {
                val img = imagenDe(ctx, it)
                AppItem(it.activityInfo.packageName, ETIQUETAS[it.activityInfo.packageName] ?: it.loadLabel(pm).toString(), img.bitmap, img.esBanner, uso[it.activityInfo.packageName] ?: 0L)
            }
            .sortedWith(compareByDescending<AppItem> { it.segundosUso }.thenBy { it.etiqueta.lowercase() })
            .toList()
        // Ajustes no tiene entrada de Leanback: se añade a mano y se abre con la acción estándar de Ajustes.
        return lista + AppItem(AJUSTES, "Ajustes", null, false, uso[AJUSTES] ?: 0L).takeIf { l -> lista.none { it.paquete == AJUSTES } }.let { listOfNotNull(it) }
    }

    private class Imagen(val bitmap: Bitmap?, val esBanner: Boolean)

    /**
     * Caché de imágenes por paquete + fecha de actualización de la app + fecha del banner externo: antes cada onResume reconstruía
     * todos los bitmaps (hasta 480x270 ARGB por app) y la lista vieja y la nueva convivían un instante en 1,7 GB de RAM.
     * Con la caché, las mismas instancias vuelven en la lista y `recargarApps` ve que nada cambió.
     */
    private val cacheImagenes = java.util.concurrent.ConcurrentHashMap<String, Imagen>()

    private fun imagenDe(ctx: Context, r: android.content.pm.ResolveInfo): Imagen {
        val pm = ctx.packageManager
        val paquete = r.activityInfo.packageName
        val externo = ctx.getExternalFilesDir(null)?.let { File(it, "banners/$paquete.png") }?.takeIf { it.exists() }
        val actualizada = try { pm.getPackageInfo(paquete, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val clave = "$paquete|$actualizada|${externo?.lastModified() ?: 0L}"
        cacheImagenes[clave]?.let { return it }
        val propio = bannerPropio(ctx, paquete, externo)
        val banner = if (propio != null) null else r.activityInfo.loadBanner(pm) ?: r.activityInfo.applicationInfo.loadBanner(pm)
        val img = Imagen(propio ?: (banner ?: r.loadIcon(pm)).aBitmap(), propio != null || banner != null)
        cacheImagenes.keys.removeAll { it.startsWith("$paquete|") } // la versión anterior de esta app ya no sirve
        cacheImagenes[clave] = img
        return img
    }

    // Banner propio para una app (p. ej. Moonlight): banners/<paquete>.png en getExternalFilesDir (se cambia con adb push) o en assets.
    // Los externos se decodifican reducidos (como mucho ~960 px de ancho): un PNG grande a tamaño completo pesaba decenas de MB.
    private fun bannerPropio(ctx: Context, paquete: String, externo: File?): Bitmap? = try {
        if (externo != null) {
            val medida = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(externo.path, medida)
            var salto = 1
            while (medida.outWidth / (salto * 2) >= 960) salto *= 2
            android.graphics.BitmapFactory.decodeFile(externo.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = salto })
        }
        else ctx.assets.open("banners/$paquete.png").use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    private fun usoPorPaquete(ctx: Context): Map<String, Long> {
        val m = HashMap<String, Long>()
        if (tienePermisoUso(ctx)) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val fin = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, fin - 30L * 86400_000, fin)
                ?.forEach { m.merge(it.packageName, it.totalTimeInForeground / 1000, Long::plus) }
        }
        if (m.isEmpty()) {
            val externo = ctx.getExternalFilesDir(null)?.let { File(it, "uso_apps.json") }?.takeIf { it.exists() }?.readText()
            val texto = externo ?: try { ctx.assets.open("uso_apps.json").bufferedReader().use { it.readText() } } catch (e: Exception) { null }
            if (texto != null) try {
                val o = JSONObject(texto).optJSONObject("segundos") ?: JSONObject()
                o.keys().forEach { k -> m[k] = o.getLong(k) }
            } catch (_: Exception) {}
        }
        // El uso de Prime Video se registra a nombre de com.amazon.avod; la tarjeta abre com.amazon.firebat.
        m["com.amazon.avod"]?.let { m.merge("com.amazon.firebat", it, Long::plus) }
        return m
    }

    private fun tienePermisoUso(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun Drawable.aBitmap(): Bitmap? = try {
        if (this is BitmapDrawable && bitmap != null) bitmap
        else {
            val w = if (intrinsicWidth > 0) intrinsicWidth else 256
            val h = if (intrinsicHeight > 0) intrinsicHeight else 256
            val b = Bitmap.createBitmap(minOf(w, 480), minOf(h, 270), Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            setBounds(0, 0, c.width, c.height); draw(c); b
        }
    } catch (e: Exception) { null }
}
