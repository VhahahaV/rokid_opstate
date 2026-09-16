package ai.opstate.rokid.glasses

import ai.opstate.protocol.*
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Android crypto/storage compatibility tests. Loopback TCP is not evidence of Wi-Fi P2P or CXR authentication. */
@RunWith(AndroidJUnit4::class)
class ProtocolDeviceTest {
    @Test fun sessionTlsTransfersBytesOnAndroidWithCertificateValidation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bytes=ByteArray(32_000) {(it%251).toByte()}
        SessionImageServer({android.os.SystemClock.elapsedRealtime()},bindAddress="127.0.0.1").use {server ->
            val image=ImageDescriptor("tls-fixture","fixture",1,System.currentTimeMillis(),1280,720,0,bytes.size,StudyWire.sha256(bytes))
            server.offer(image,bytes)
            val client=SessionPeerTls.client(OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build(),server.certificatePem,server.certificateSha256,InetAddress.getByName("127.0.0.1"))
            val request=Request.Builder().url("https://${SessionPeerTls.HOST}:${server.port}/image/tls-fixture").header("Authorization","Bearer ${server.token}").build()
            client.newCall(request).execute().use {response ->
                assertEquals(200,response.code)
                val received=response.body!!.bytes();assertArrayEquals(bytes,received)
                verifyImage(image,received)
                File(context.filesDir,"diagnostics").apply {mkdirs()}.resolve("tls-probe.json").writeText(StudyWire.payload(
                    "kind" to "ANDROID_TLS_LOOPBACK_FIXTURE","bytes" to received.size,"sha256" to StudyWire.sha256(received),
                    "tls_version" to response.handshake!!.tlsVersion.javaName,"cipher" to response.handshake!!.cipherSuite.javaName,
                    "radio_validated" to false,"camera_validated" to false).toString())
            }
            client.newCall(request.newBuilder().header("Authorization","Bearer invalid").build()).execute().use {assertEquals(403,it.code)}
        }
    }
    @Test fun acceptedEpochSurvivesFreshGateWithoutReplayingOldDisplay() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="epoch-test-${UUID.randomUUID()}"
        val preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        try {
            fun gate()=SessionCommandGate(initialEpoch=preferences.getLong("epoch",Long.MIN_VALUE),persistEpoch={check(preferences.edit().putLong("epoch",it).commit())})
            val start=StudyMessage(session_id="fixture",session_epoch=100,sent_at_epoch_ms=100,type="SESSION_START")
            assertEquals(SessionCommandGate.Admission.ACCEPT,gate().admit(start,100))
            val restarted=gate()
            assertEquals(SessionCommandGate.Admission.WRONG_SESSION,restarted.admit(start,101))
            assertEquals(SessionCommandGate.Admission.WRONG_SESSION,restarted.admit(start.copy(type="SHOW_SERVICE_CARD"),101))
            assertEquals(SessionCommandGate.Admission.ACCEPT,restarted.admit(start.copy(session_epoch=101),102))
        } finally {context.deleteSharedPreferences(name)}
    }
}
