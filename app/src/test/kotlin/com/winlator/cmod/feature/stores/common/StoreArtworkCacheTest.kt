package com.winlator.cmod.feature.stores.common

import android.app.Application
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StoreArtworkCacheTest {
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(15)
    private val context get() = RuntimeEnvironment.getApplication()
    private val root get() = File(context.filesDir, "library_artwork_cache")
    private val servers = mutableListOf<Pair<ServerSocket, ExecutorService>>()
    private val bytes = "complete artwork fixture".toByteArray()

    @Before fun resetDisk() { root.deleteRecursively() }

    @After fun stopServers() {
        servers.forEach { (server, executor) ->
            server.close()
            executor.shutdownNow()
        }
        root.deleteRecursively()
    }

    private class Exchange(val path: String, private val socket: Socket) {
        val responseBody get() = socket.getOutputStream()

        fun sendResponseHeaders(status: Int, length: Long) {
            responseBody.write(("HTTP/1.1 $status Fixture\r\n" +
                "Content-Length: ${length.coerceAtLeast(0)}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            responseBody.flush()
        }
    }

    private fun server(handler: (Exchange) -> Unit): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        servers += server to executor
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                executor.submit {
                    socket.use {
                        it.soTimeout = 5_000
                        val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        while (!input.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                        handler(Exchange(path, it))
                    }
                }
            }
        }
        return server
    }

    private fun ref(server: ServerSocket, id: Int = 1) = StoreArtworkCache.ArtworkRef(
        "steam", id.toString(), "library_capsule", "http://127.0.0.1:${server.localPort}/$id.png",
    )

    private fun send(exchange: Exchange) {
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    @Test fun completedDiskArtworkIsReusedWithoutAnotherNetworkRequest() = runBlocking {
        val requests = AtomicInteger()
        val server = server { requests.incrementAndGet(); send(it) }
        val ref = ref(server)
        assertTrue(StoreArtworkCache.cacheAll(context, listOf(ref, ref)))
        val file = StoreArtworkCache.imageModel(context, ref) as File
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(StoreArtworkCache.cacheAll(context, listOf(ref)))
        assertEquals(1, requests.get())
        assertTrue(root.walkTopDown().none { it.extension == "tmp" })
    }

    @Test fun largeLibrariesUseAtMostFourConcurrentTransfers() = runBlocking {
        val started = CountDownLatch(4)
        val fifthStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = server {
            if (requests.incrementAndGet() > 4) fifthStarted.countDown()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            send(it)
        }
        val download = async(Dispatchers.Default) {
            StoreArtworkCache.cacheAll(context, (1..40).map { ref(server, it) })
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(fifthStarted.await(200, TimeUnit.MILLISECONDS))
            assertEquals(4, requests.get())
        } finally { release.countDown() }
        assertTrue(withTimeout(5_000) { download.await() })
        assertEquals(40, requests.get())
    }

    @Test fun hidingDuringResponseHeadersCancelsCallsAndDoesNotStartQueuedArtwork() = runBlocking {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = server {
            requests.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            send(it)
        }
        val download = async(Dispatchers.Default) {
            StoreArtworkCache.cacheAll(context, (1..40).map { ref(server, it) })
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { download.cancelAndJoin() }
            assertEquals(4, requests.get())
            assertTrue(download.isCancelled)
            assertTrue(root.walkTopDown().none { it.isFile })
        } finally { release.countDown() }
    }

    @Test fun hidingMidBodyRemovesPartialFileAndResumeCanDownloadAgain() = runBlocking {
        val partialSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = AtomicBoolean(true)
        val server = server {
            if (slow.get()) {
                it.sendResponseHeaders(200, 1_000_000)
                it.responseBody.write(bytes)
                it.responseBody.flush()
                partialSent.countDown()
                release.await(5, TimeUnit.SECONDS)
            } else send(it)
        }
        val ref = ref(server)
        val download = async(Dispatchers.Default) { StoreArtworkCache.cacheAll(context, listOf(ref)) }
        try {
            assertTrue(partialSent.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { download.cancelAndJoin() }
            assertEquals(ref.url, StoreArtworkCache.imageModel(context, ref))
            assertTrue(root.walkTopDown().none { it.isFile })
            slow.set(false)
            assertTrue(withTimeout(5_000) { StoreArtworkCache.cacheAll(context, listOf(ref)) })
            assertArrayEquals(bytes, (StoreArtworkCache.imageModel(context, ref) as File).readBytes())
        } finally { release.countDown() }
    }

    @Test fun truncatedResponseCannotBecomeACompletedDiskImage() = runBlocking {
        val server = server {
            it.sendResponseHeaders(200, bytes.size.toLong() + 100)
            it.responseBody.write(bytes)
        }
        val ref = ref(server)
        assertFalse(StoreArtworkCache.cacheAll(context, listOf(ref)))
        assertEquals(ref.url, StoreArtworkCache.imageModel(context, ref))
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    @Test fun oneBadUrlOrHttpFailureDoesNotCancelOtherArtwork() = runBlocking {
        val server = server {
            if (it.path == "/2.png") it.sendResponseHeaders(404, -1) else send(it)
        }
        val good = ref(server)
        val missing = ref(server, 2)
        val invalid = good.copy(gameId = "invalid", url = "http://")
        assertTrue(StoreArtworkCache.cacheAll(context, listOf(invalid, missing, good)))
        assertEquals(missing.url, StoreArtworkCache.imageModel(context, missing))
        assertTrue(StoreArtworkCache.imageModel(context, good) is File)
    }
}
