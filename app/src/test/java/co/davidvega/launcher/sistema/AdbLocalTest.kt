package co.davidvega.launcher.sistema

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbKeyPair
import dadb.AdbTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

class AdbLocalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun llaves(): AdbKeyPair {
        val priv = tmp.newFile("k"); val pub = tmp.newFile("k.pub")
        AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun hilosAdb() = Thread.getAllStackTraces().keys.count { it.name == "adb-mant" && it.isAlive }

    /**
     * Antes (tests\adv\DadbCierreFuga.java): un servidor que acepta y calla (el diálogo «Permitir depuración» sin contestar) dejaba el hilo
     * bloqueado para siempre porque dadb.close() no cierra nada durante el apretón de manos. Ahora el socket tiene tiempo máximo y el hilo termina.
     */
    @Test fun servidorQueAceptaYCallaNoDejaHilosColgados() {
        val srv = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val aceptados = java.util.Collections.synchronizedList(ArrayList<Socket>())
        val aceptador = Thread { try { while (true) aceptados += srv.accept() } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        val k = llaves()
        val t0 = System.currentTimeMillis()
        val r = AdbLocal.ejecutar("127.0.0.1", srv.localPort, { k }, "echo ok", 1_500L)
        val gastado = System.currentTimeMillis() - t0
        assertFalse(r.ok)
        assertTrue("debe volver a tiempo (tardó $gastado ms)", gastado < 4_000L)
        // El socket vence 1 s después del límite del paso: el hilo termina solo.
        val limite = System.currentTimeMillis() + 6_000L
        while (hilosAdb() > 0 && System.currentTimeMillis() < limite) Thread.sleep(100)
        assertEquals("hilos adb-mant vivos tras el tiempo agotado", 0, hilosAdb())
        srv.close(); aceptador.interrupt(); synchronized(aceptados) { aceptados.forEach { runCatching { it.close() } } }
    }

    @Test fun puertoCerradoFallaYNoCuentaComoReinicio() {
        val libre = ServerSocket(0).use { it.localPort } // puerto que acaba de quedar cerrado
        val k = llaves()
        val r = AdbLocal.ejecutar("127.0.0.1", libre, { k }, "reboot", 3_000L, esperaCorte = true)
        assertFalse("no conectar no es un reinicio", r.ok)
    }

    @Test fun sinTiempoRestanteNoIntentaNada() {
        assertFalse(AdbLocal.ejecutar("127.0.0.1", 1, { error("no debe pedir llaves") }, "echo ok", 0L).ok)
    }

    @Test fun corteEsperadoSoloSiYaEstabaConectado() {
        assertTrue(AdbLocal.corteEsperado(AdbConnectionClosedException("cerrada")))
        assertTrue(AdbLocal.corteEsperado(EOFException()))
        assertTrue(AdbLocal.corteEsperado(RuntimeException("envoltorio", SocketException("Connection reset"))))
        assertFalse(AdbLocal.corteEsperado(ConnectException("Connection refused")))
        assertFalse(AdbLocal.corteEsperado(SocketTimeoutException("Read timed out")))
        assertFalse(AdbLocal.corteEsperado(AdbConnectException("no conecta")))
        assertFalse(AdbLocal.corteEsperado(AdbAuthException("sin autorizar")))
        assertFalse(AdbLocal.corteEsperado(AdbTimeoutException("tiempo")))
        assertFalse(AdbLocal.corteEsperado(IllegalStateException("otra cosa")))
    }
}
