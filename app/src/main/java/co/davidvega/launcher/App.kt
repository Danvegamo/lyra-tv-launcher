package co.davidvega.launcher

import android.app.Application
import co.davidvega.launcher.data.Aprendizaje
import co.davidvega.launcher.data.Catalogo
import co.davidvega.launcher.data.Diagnostico
import co.davidvega.launcher.data.Eventos
import co.davidvega.launcher.data.SeguirViendo
import co.davidvega.launcher.data.Vistos
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Coil con la memoria acotada (el Fire TV Stick 4K Max tiene 1,7 GB compartidos con todo el sistema) y los objetos
 * compartidos entre la pantalla y el servicio de escucha de reproducción (mismo proceso).
 */
class App : Application(), ImageLoaderFactory {
    val catalogo by lazy { Catalogo(this) }
    val aprendizaje by lazy { Aprendizaje(this, Aprendizaje.semillas(catalogo.gustos())) }
    val eventos by lazy { Eventos(this) }
    val vistos by lazy { Vistos(this) }
    val seguir by lazy { SeguirViendo(this, this) }

    override fun onCreate() {
        super.onCreate()
        Diagnostico.iniciar(this)
        co.davidvega.launcher.sistema.Mantenimiento.iniciar(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(8 * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(64L * 1024 * 1024).build() }
        .allowRgb565(true)
        .crossfade(false)
        .build()
}
