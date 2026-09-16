package ai.opstate.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class SessionImageServerTest {
    private fun base()=OkHttpClient.Builder().callTimeout(3,TimeUnit.SECONDS).build()
    @Test fun realTlsSocketRequiresSessionTokenAndExpiresAfterAckOrTtl() {
        var clock=100L
        SessionImageServer({clock},1000,"127.0.0.1").use { server ->
            val bytes=ByteArray(32000) {(it%251).toByte()}
            val descriptor=ImageDescriptor("capture-tls","s",1,100,1280,720,0,bytes.size,StudyWire.sha256(bytes))
            server.offer(descriptor,bytes)
            val client=SessionPeerTls.client(base(),server.certificatePem,server.certificateSha256,InetAddress.getByName("127.0.0.1"))
            fun get(token: String=server.token): Pair<Int,ByteArray> = client.newCall(Request.Builder()
                .url("https://${SessionPeerTls.HOST}:${server.port}/image/capture-tls").header("Authorization","Bearer $token").build()).execute().use {
                it.code to requireNotNull(it.body).bytes()
            }
            assertEquals(403,get("wrong").first)
            val response=get();assertEquals(200,response.first);assertArrayEquals(bytes,response.second)
            verifyImage(descriptor,response.second)
            server.acknowledge(descriptor.capture_id);assertEquals(404,get().first)
            server.offer(descriptor,bytes);clock=1100;assertEquals(404,get().first)
        }
    }
    @Test fun changedCertificateAndAnotherSessionIdentityAreRejected() {
        SessionImageServer({100},1000,"127.0.0.1").use { server ->
            assertThrows(IllegalArgumentException::class.java) {
                SessionPeerTls.client(base(),server.certificatePem,"0".repeat(64),InetAddress.getLoopbackAddress())
            }
            val other=SessionPeerTls()
            val wrong=SessionPeerTls.client(base(),other.certificatePem,other.certificateSha256,InetAddress.getByName("127.0.0.1"))
            assertThrows(SSLException::class.java) {wrong.newCall(Request.Builder().url("https://${SessionPeerTls.HOST}:${server.port}/health").build()).execute().close()}
        }
    }
}
