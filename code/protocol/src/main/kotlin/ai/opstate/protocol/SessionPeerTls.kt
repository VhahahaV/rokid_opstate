package ai.opstate.protocol

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocketFactory

/** Per-session identity advertised by the authenticated SDK channel, never a global trust override. */
class SessionPeerTls(nowEpochMs: Long=System.currentTimeMillis()) {
    private val held=HeldCertificate.Builder().commonName(HOST).addSubjectAlternativeName(HOST)
        // Accommodate the observed device clock offset. The pin and session token remain session-scoped.
        .validityInterval(nowEpochMs-7*86_400_000L,nowEpochMs+7*86_400_000L).build()
    private val server=HandshakeCertificates.Builder().heldCertificate(held).build()
    val certificatePem: String=held.certificatePem()
    val certificateSha256: String=StudyWire.sha256(held.certificate.encoded)
    val serverSocketFactory: SSLServerSocketFactory get()=server.sslContext().serverSocketFactory
    companion object {
        const val HOST="opstate-peer.invalid"
        const val PROTOCOL="https-pinned-v1"
        fun client(base: OkHttpClient,certificatePem: String,expectedHash: String,address: InetAddress): OkHttpClient {
            require(address.isSiteLocalAddress || address.isLoopbackAddress) { "P2P_ADDRESS_NOT_PRIVATE" }
            require(certificatePem.length in 1..8192 && !certificatePem.contains("PRIVATE KEY")) { "INVALID_PEER_CERTIFICATE" }
            val certificate=CertificateFactory.getInstance("X.509").generateCertificate(certificatePem.byteInputStream()) as X509Certificate
            require(StudyWire.sha256(certificate.encoded)==expectedHash) { "PEER_CERTIFICATE_HASH_MISMATCH" }
            val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
            return base.newBuilder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if(hostname!=HOST) throw UnknownHostException("P2P_HOST_MISMATCH")
                        return listOf(address)
                    }
                }).build()
        }
    }
}
