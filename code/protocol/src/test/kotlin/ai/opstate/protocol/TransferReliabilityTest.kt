package ai.opstate.protocol

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TransferReliabilityTest {
    private val bytes=ByteArray(20001) {(it%251).toByte()}
    private fun descriptor()=ImageDescriptor("capture","session",1,100,1280,720,0,bytes.size,StudyWire.sha256(bytes))
    @Test fun finalImageAckCompletesTransferWhenLastChunkAckWasLost()=runBlocking<Unit> {
        val sender=BluetoothImageSender(descriptor(),ackTimeoutMs=10)
        val sent=mutableListOf<Int>()
        sender.send(bytes) {index,_,_->
            sent+=index
            if(index==descriptor().chunkCount-1) assertTrue(sender.acknowledgeImage("capture",descriptor().sha256,bytes.size))
            else sender.acknowledgeChunk("capture",index)
        }
        assertEquals(listOf(0,1,2),sent)
    }
    @Test fun lostChunkAckRetriesSameBytesAndIdempotencyKey()=runBlocking<Unit> {
        val sender=BluetoothImageSender(descriptor(),ackTimeoutMs=5)
        val sent=mutableListOf<Pair<String,ByteArray>>()
        var first=0
        sender.send(bytes) {index,chunk,key->
            if(index==0) {sent+=key to chunk;first++}
            if(index!=0 || first==2) sender.acknowledgeChunk("capture",index)
        }
        assertEquals(2,sent.size);assertEquals(sent[0].first,sent[1].first);assertArrayEquals(sent[0].second,sent[1].second)
    }
    @Test fun wrongImageAckAndCancellationCannotCompleteAnotherTransfer()=runBlocking<Unit> {
        val sender=BluetoothImageSender(descriptor(),ackTimeoutMs=1000)
        assertFalse(sender.acknowledgeImage("other",descriptor().sha256,bytes.size))
        assertFalse(sender.acknowledgeImage("capture","0".repeat(64),bytes.size))
        val started=CompletableDeferred<Unit>()
        val job=launch {sender.send(bytes) {_,_,_->started.complete(Unit)}}
        started.await();job.cancelAndJoin()
        assertFalse(sender.acknowledgeChunk("capture",0))
    }
    @Test fun replayKeepsEffectIdentityButCorrelatesTheRetryAndRejectsChangedPayload() {
        val request=StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=100,type="SESSION_START",idempotency_key="start")
        val response=request.copy(type="SESSION_READY",message_id="response",payload=StudyWire.payload("camera_ready" to true,"reply_to" to request.message_id))
        val cache=CommandReplyCache();cache.remember(request,response)
        val retry=request.copy(message_id="retry")
        assertEquals(JsonPrimitive("retry"),cache.replay(retry)!!.payload["reply_to"])
        assertEquals("SESSION_READY",cache.replay(retry)!!.type)
        assertNull(cache.replay(retry.copy(session_epoch=2)))
        assertEquals("ERROR",cache.replay(retry.copy(payload=StudyWire.payload("changed" to true)))!!.type)
    }
}
