package com.winlator.cmod.shared.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual curl/OpenSSL JNI checks against a local TLS server. No accounts or external requests. */
@RunWith(AndroidJUnit4::class)
class NativeContentIOTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private lateinit var trustedCa: File
    private lateinit var unrelatedCa: File

    @Before fun setUp() {
        directory = File(instrumentation.targetContext.cacheDir, "native-tls-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        trustedCa = copyAsset("localhost.pem")
        unrelatedCa = copyAsset("unrelated.pem")
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun trustedHttpsDownloadsExactBytesAndReportsContentLength() {
        TlsServer().use { server ->
            val destination = File(directory, "content.bin")
            assertEquals(PAYLOAD.size.toLong(), NativeContentIO.fetchContentLength(server.url(), trustedCa.path))
            assertTrue(NativeContentIO.downloadFile(server.url(), destination, trustedCa.path, null))
            assertArrayEquals(PAYLOAD, destination.readBytes())
            assertFalse(File(destination.path + ".part").exists())
        }
    }

    @Test fun untrustedCertificateCannotReplaceAnExistingFile() {
        TlsServer().use { server ->
            assertRejected(server.url(), unrelatedCa)
        }
    }

    @Test fun hostnameMismatchIsRejectedEvenWithATrustedCertificate() {
        TlsServer().use { server ->
            assertRejected(server.url("127.0.0.1"), trustedCa)
        }
    }

    @Test fun truncatedHttpsBodyKeepsTheExistingFileAndRemovesPartial() {
        TlsServer(truncate = true).use { server ->
            assertRejected(server.url(), trustedCa, checkLength = false)
        }
    }

    @Test fun httpFailureDoesNotReplaceAnExistingFile() {
        TlsServer(status = 404).use { server ->
            assertRejected(server.url(), trustedCa)
        }
    }

    private fun assertRejected(url: String, ca: File, checkLength: Boolean = true) {
        val destination = File(directory, "existing.bin").apply { writeText("keep existing content") }
        if (checkLength) assertEquals(-1L, NativeContentIO.fetchContentLength(url, ca.path))
        assertFalse(NativeContentIO.downloadFile(url, destination, ca.path, null))
        assertEquals("keep existing content", destination.readText())
        assertFalse(File(destination.path + ".part").exists())
    }

    private fun copyAsset(name: String): File = File(directory, name).also { output ->
        instrumentation.context.assets.open("native-network/$name").use { input ->
            output.outputStream().use { input.copyTo(it) }
        }
    }

    private inner class TlsServer(
        private val truncate: Boolean = false,
        private val status: Int = 200,
    ) : Closeable {
        private val socket: SSLServerSocket
        private val worker: Thread
        @Volatile private var failure: Throwable? = null

        init {
            val password = "vapor-test-only".toCharArray()
            val keys = KeyStore.getInstance("PKCS12").apply {
                instrumentation.context.assets.open("native-network/localhost.p12").use { load(it, password) }
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password)
            }
            val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            socket = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            worker = thread(name = "Vapor-loopback-TLS") {
                try {
                    while (!socket.isClosed) {
                        (socket.accept() as SSLSocket).use { client ->
                            client.soTimeout = 5_000
                            try {
                                val reader = client.inputStream.bufferedReader(Charsets.US_ASCII)
                                val request = reader.readLine() ?: return@use
                                while (true) {
                                    val line = reader.readLine()
                                    if (line.isNullOrEmpty()) break
                                }
                                val output = client.outputStream
                                output.write("HTTP/1.1 $status Test\r\nContent-Length: ${PAYLOAD.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                if (!request.startsWith("HEAD ")) {
                                    output.write(PAYLOAD, 0, if (truncate) PAYLOAD.size / 2 else PAYLOAD.size)
                                }
                                output.flush()
                            } catch (_: SSLException) {
                                // Expected when the native client rejects the test certificate/hostname.
                            }
                        }
                    }
                } catch (error: Throwable) {
                    if (!socket.isClosed) failure = error
                }
            }
        }

        fun url(host: String = "localhost") = "https://$host:${socket.localPort}/fixture"

        override fun close() {
            socket.close()
            worker.join(6_000)
            assertFalse("Local TLS server did not stop", worker.isAlive)
            failure?.let { throw AssertionError("Local TLS fixture failed", it) }
        }
    }

    companion object {
        private val PAYLOAD = ByteArray(8_193) { (it % 251).toByte() }
    }
}
