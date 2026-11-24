package co.davidvega.launcher.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Escritura de archivos del launcher sin pérdidas: cada ruta tiene un único cerrojo (todos los escritores de un archivo lo
 * comparten) y las escrituras completas van a un `.tmp` que se renombra encima, de modo que un corte de luz o dos escritores
 * a la vez nunca dejan un JSON a medias. Los anexos (`anexar`) toman el mismo cerrojo que las reescrituras.
 */
object Archivos {
    private val cerrojos = ConcurrentHashMap<String, Any>()

    fun cerrojo(f: File): Any = cerrojos.getOrPut(f.absolutePath) { Any() }

    /** Reemplaza el contenido de `f` de forma atómica (temporal + renombrado). Lanza si no se pudo. */
    fun escribir(f: File, texto: String) = synchronized(cerrojo(f)) { escribirSinCerrojo(f, texto) }

    private fun escribirSinCerrojo(f: File, texto: String) {
        f.absoluteFile.parentFile?.mkdirs()
        val tmp = File(f.absoluteFile.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(texto.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Igual que `escribir`, pero sin lanzar: devuelve false si falló. */
    fun escribirOk(f: File, texto: String): Boolean = try { escribir(f, texto); true } catch (_: Exception) { false }

    /** Añade al final (con el cerrojo del archivo, para que no se cruce con una reescritura). */
    fun anexar(f: File, texto: String): Boolean = synchronized(cerrojo(f)) {
        try { f.absoluteFile.parentFile?.mkdirs(); f.appendText(texto); true } catch (_: Exception) { false }
    }

    /** Líneas no vacías ([] si no existe o no se puede leer). */
    fun lineas(f: File): List<String> = synchronized(cerrojo(f)) {
        try { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() } catch (_: Exception) { emptyList() }
    }

    /** Lee las líneas, las transforma y reescribe de forma atómica, todo dentro del cerrojo (no se pierde ningún anexo). */
    fun transformarLineas(f: File, cambio: (List<String>) -> List<String>): Boolean = synchronized(cerrojo(f)) {
        try {
            val nuevas = cambio(if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList())
            escribirSinCerrojo(f, if (nuevas.isEmpty()) "" else nuevas.joinToString("\n") + "\n"); true
        } catch (_: Exception) { false }
    }
}
