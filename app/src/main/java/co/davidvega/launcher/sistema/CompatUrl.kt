package co.davidvega.launcher.sistema

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/** Versiones con `Charset` de URLDecoder/URLEncoder (Java 10, ausentes en Android 9). Las llamadas de NewPipeExtractor se reescriben aquí al compilar (ver buildSrc). */
object CompatUrl {
    @JvmStatic fun decode(s: String, cs: Charset): String = URLDecoder.decode(s, cs.name())
    @JvmStatic fun encode(s: String, cs: Charset): String = URLEncoder.encode(s, cs.name())
}
