package ai.opstate.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** One bounded stop-and-wait transfer; final verified image ACK supersedes a lost per-chunk ACK. */
class BluetoothImageSender(val descriptor: ImageDescriptor,private val ackTimeoutMs: Long=1500,private val maxAttempts: Int=3) {
    private val pending=ConcurrentHashMap<Int,CompletableDeferred<Unit>>()
    @Volatile private var imageAcknowledged=false
    init {descriptor.validate();require(ackTimeoutMs>0 && maxAttempts>0)}
    fun acknowledgeChunk(captureId: String,index: Int): Boolean =
        captureId==descriptor.capture_id && pending[index]?.complete(Unit)==true
    fun acknowledgeImage(captureId: String,sha256: String,bytes: Int): Boolean {
        if(captureId!=descriptor.capture_id || sha256!=descriptor.sha256 || bytes!=descriptor.byte_length) return false
        imageAcknowledged=true
        pending.values.forEach {it.complete(Unit)}
        return true
    }
    suspend fun send(bytes: ByteArray,sendChunk: suspend (Int,ByteArray,String)->Unit) {
        verifyImage(descriptor,bytes)
        try {
            for(index in 0 until descriptor.chunkCount) {
                currentCoroutineContext().ensureActive()
                if(imageAcknowledged) return
                val ack=CompletableDeferred<Unit>();pending[index]=ack
                val chunk=bytes.copyOfRange(index*descriptor.chunk_size,minOf((index+1)*descriptor.chunk_size,bytes.size))
                try {
                    var acknowledged=false
                    for(attempt in 0 until maxAttempts) {
                        if(imageAcknowledged) return
                        sendChunk(index,chunk,"chunk-${descriptor.capture_id}:$index")
                        if(withTimeoutOrNull(ackTimeoutMs) {ack.await();true}==true) {acknowledged=true;break}
                    }
                    check(acknowledged || imageAcknowledged) { "CHUNK_ACK_TIMEOUT" }
                } finally {pending.remove(index)?.cancel()}
            }
        } finally {pending.values.forEach {it.cancel()};pending.clear()}
    }
}
