package ai.opstate.protocol

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Session-scoped HTTPS JPEG endpoint; its certificate and token travel over the authenticated SDK channel. */
class SessionImageServer(
    private val nowElapsedMs: ()->Long,
    private val retentionMs: Long=30_000,
    private val bindAddress: String="0.0.0.0",
) : Closeable {
    private data class Stored(val descriptor: ImageDescriptor,val bytes: ByteArray,val deadline: Long)
    private val frames=linkedMapOf<String,Stored>()
    private val sockets=java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val capacity=Semaphore(2)
    private val executor=Executors.newFixedThreadPool(2) { work -> Thread(work,"OPSTATE-ImageHTTP").apply { isDaemon=true } }
    private val tls=SessionPeerTls()
    val certificatePem: String get()=tls.certificatePem
    val certificateSha256: String get()=tls.certificateSha256
    private val server=tls.serverSocketFactory.createServerSocket(0,4,InetAddress.getByName(bindAddress))
    val port: Int get()=server.localPort
    val token: String=ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    @Volatile private var closed=false
    private val acceptThread=thread(name="OPSTATE-ImageAccept",isDaemon=true) {
        while(!closed) {
            val client=try { server.accept() } catch(_: Exception) { break }
            if(!capacity.tryAcquire()) { client.close(); continue }
            sockets+=client
            try { executor.execute { try { serve(client) } finally { sockets-=client; capacity.release() } } }
            catch(_: java.util.concurrent.RejectedExecutionException) { sockets-=client; client.close(); capacity.release() }
        }
    }
    @Synchronized fun offer(descriptor: ImageDescriptor,jpeg: ByteArray) {
        check(!closed)
        verifyImage(descriptor,jpeg)
        require(descriptor.capture_id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        prune()
        require(frames.size<2 || descriptor.capture_id in frames) { "IMAGE_OFFER_CAPACITY" }
        frames[descriptor.capture_id]=Stored(descriptor,jpeg.copyOf(),nowElapsedMs()+retentionMs)
    }
    @Synchronized fun acknowledge(captureId: String) { frames.remove(captureId) }
    @Synchronized fun image(captureId: String): Pair<ImageDescriptor,ByteArray>? {
        prune()
        return frames[captureId]?.let { it.descriptor to it.bytes.copyOf() }
    }
    @Synchronized private fun prune() { frames.entries.removeAll { nowElapsedMs()>=it.value.deadline } }
    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout=3_000
                val input=socket.getInputStream()
                val request=readLine(input,1024).split(' ')
                val headers=mutableMapOf<String,String>()
                var count=0
                while(true) {
                    val line=readLine(input,2048)
                    if(line.isEmpty()) break
                    require(++count<=32)
                    val colon=line.indexOf(':')
                    require(colon>0)
                    headers[line.substring(0,colon).lowercase()]=line.substring(colon+1).trim()
                }
                if(request.size!=3 || request[0]!="GET") { respond(socket,400,"bad request".toByteArray()); return }
                if(!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(),"Bearer $token".toByteArray())) {
                    respond(socket,403,"forbidden".toByteArray()); return
                }
                if(request[1]=="/health") { respond(socket,200,"ready".toByteArray()); return }
                val captureId=request[1].removePrefix("/image/")
                val found=if(request[1].startsWith("/image/")) image(captureId) else null
                if(found==null) respond(socket,404,"not found".toByteArray())
                else respond(socket,200,found.second,found.first.sha256)
            } catch(_: Exception) {
                runCatching { respond(socket,400,"bad request".toByteArray()) }
            }
        }
    }
    private fun readLine(input: InputStream,limit: Int): String {
        val data=java.io.ByteArrayOutputStream()
        while(data.size()<=limit) {
            val next=input.read()
            if(next==-1) error("UNEXPECTED_EOF")
            if(next==10) return data.toString("US-ASCII").trimEnd('\r')
            data.write(next)
        }
        error("HTTP_HEADER_TOO_LARGE")
    }
    private fun respond(socket: Socket,status: Int,body: ByteArray,hash: String?=null) {
        val reason=when(status) { 200->"OK"; 403->"Forbidden"; 404->"Not Found"; else->"Bad Request" }
        val output=socket.getOutputStream()
        val headers="HTTP/1.1 $status $reason\r\nContent-Length: ${body.size}\r\nContent-Type: ${if(hash!=null) "image/jpeg" else "text/plain"}\r\nCache-Control: no-store\r\nConnection: close\r\n"+
            (hash?.let { "ETag: \"$it\"\r\n" } ?: "")+"\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII)); output.write(body); output.flush()
    }
    override fun close() {
        closed=true
        server.close()
        synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } } }
        executor.shutdownNow()
        synchronized(this) { frames.clear() }
    }
}
