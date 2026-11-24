package co.davidvega.launcher.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** «Mi lista» (ver después): un JSON en el almacenamiento interno de la app. Sin librerías extra. */
class MiLista(ctx: Context) {
    private val archivo = File(ctx.filesDir, "milista.json")

    fun cargar(): List<Titulo> = try {
        val a = JSONArray(archivo.readText())
        (0 until a.length()).map { Titulo.deJson(a.getJSONObject(it), it) }
    } catch (e: Exception) { emptyList() }

    fun guardar(lista: List<Titulo>) {
        Archivos.escribirOk(archivo, JSONArray(lista.map { it.aJson() }).toString())
    }
}
