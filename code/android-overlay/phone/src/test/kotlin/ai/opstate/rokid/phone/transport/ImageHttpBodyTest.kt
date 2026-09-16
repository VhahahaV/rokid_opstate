package ai.opstate.rokid.phone.transport

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ImageHttpBodyTest {
    @Test fun cancellationClosesAnUnresponsivePeerCallImmediately()=runBlocking<Unit> {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call=OkHttpClient().newCall(Request.Builder().url(server.url("/image/capture")).build())
            val transfer=launch {call.readImageBody(8)}
            withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            withTimeout(1000) {transfer.cancelAndJoin()}
            assertTrue(call.isCanceled())
        }
    }
    @Test fun sizeAndHttpFailuresCannotBePublishedAsAnImage()=runBlocking<Unit> {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("12345678"))
            val client=OkHttpClient()
            fun call()=client.newCall(Request.Builder().url(server.url("/image/capture")).build())
            assertArrayEquals("12345678".toByteArray(),call().readImageBody(8))
            server.enqueue(MockResponse().setBody("wrong"))
            assertEquals("P2P_IMAGE_LENGTH_MISMATCH",runCatching {call().readImageBody(8)}.exceptionOrNull()?.message)
            server.enqueue(MockResponse().setResponseCode(401).setBody("12345678"))
            assertEquals("P2P_HTTP_FAILED",runCatching {call().readImageBody(8)}.exceptionOrNull()?.message)
        }
    }
}
