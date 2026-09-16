package ai.opstate.rokid.phone.transport

import ai.opstate.protocol.*
import ai.opstate.rokid.phone.rokid.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Exercises the real adapter over an explicit SDK test port; does not claim physical radio validation. */
class LegacyCxrTransportTest {
    private class FakeLink : CxrLinkPort {
        override val state=MutableStateFlow(CxrLinkState())
        override val messages=MutableSharedFlow<String>(extraBufferCapacity=256)
        val sent=mutableListOf<StudyMessage>()
        val cache=CommandReplyCache()
        var hellos=0;var coldHellos=0;var suppressHello=false;var dropSessionReply=false;var starts=0
        var displayReady=true
        var appVersion="fixture";var apkHash="a".repeat(64);var clockSynced=false;var p2pStarts=0
        override suspend fun scan(durationMs: Long)=listOf(CxrDevice("AC:86:D1:56:FC:AF","test glasses"))
        override fun savedDevice()=CxrDevice("AC:86:D1:56:FC:AF","test glasses")
        override suspend fun connect(device: CxrDevice) {state.value=CxrLinkState(phase="CONNECTED",bluetoothConnected=true)}
        override fun startP2p(): Result<Unit> {p2pStarts++;return Result.success(Unit)}
        override fun disconnect() {state.value=CxrLinkState()}
        suspend fun emit(message: StudyMessage) {messages.emit(StudyWire.encode(message))}
        suspend fun reply(request: StudyMessage,type: String,payload: JsonObject) {
            emit(request.copy(type=type,payload=JsonObject(payload+("reply_to" to JsonPrimitive(request.message_id)))))
        }
        override suspend fun send(message: String): Result<Unit> {
            val request=StudyWire.decode(message);sent+=request
            when(request.type) {
                "HELLO" -> {
                    hellos++
                    if(!suppressHello) reply(request,"READY",StudyWire.payload("glasses_time_ms" to System.currentTimeMillis(),
                        "camera_ready" to (hellos>coldHellos),"display_ready" to (displayReady && hellos>coldHellos),"firmware" to "fixture","app_version" to appVersion,"apk_sha256" to apkHash))
                }
                "CLOCK_SYNC" -> {clockSynced=true;reply(request,"CLOCK_READY",StudyWire.payload("clock_ready" to true))}
                "SESSION_START" -> {
                    val response=cache.replay(request) ?: request.copy(type="SESSION_READY",payload=StudyWire.payload("camera_ready" to true,"reply_to" to request.message_id)).also {
                        starts++;cache.remember(request,it)
                    }
                    if(dropSessionReply) dropSessionReply=false else emit(response)
                }
            }
            return Result.success(Unit)
        }
    }
    private fun elapsed()=System.nanoTime()/1_000_000
    private fun device()=RokidDevice("AC:86:D1:56:FC:AF","test glasses")
    @Test fun screenOffDoesNotPreventConnectionOrCaptureSession()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink().apply {displayReady=false}
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        try {transport.connect(device()).getOrThrow();transport.startSession("screen-off",1).getOrThrow();assertTrue(transport.ready.value)}
        finally {scope.cancel()}
    }
    @Test fun warmupDoesNotRequireDisconnectingAnAlreadyConnectedSdk()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink().apply {coldHellos=2}
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        try {
            withTimeout(1000) {transport.connect(device()).getOrThrow()}
            assertTrue(transport.ready.value);assertEquals(5,link.hellos)
        } finally {scope.cancel()}
    }
    @Test fun refreshingARestartedPeerUpdatesItsBuildAndClockWithoutRestartingP2p()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        try {
            transport.connect(device()).getOrThrow();assertEquals(1,link.p2pStarts)
            link.clockSynced=false;link.appVersion="fixture-new";link.apkHash="b".repeat(64)
            val peer=transport.refreshPeer().getOrThrow()!!
            assertTrue(link.clockSynced);assertEquals("fixture-new",peer.appVersion);assertEquals("b".repeat(64),peer.apkSha256)
            assertEquals(1,link.p2pStarts)
        } finally {scope.cancel()}
    }
    @Test fun lostSessionReplyRetriesSameCommandWithoutDuplicatingSessionStart()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=50,readinessPollMs=1)
        try {
            transport.connect(device()).getOrThrow();link.dropSessionReply=true
            transport.startSession("s",1).getOrThrow()
            val starts=link.sent.filter {it.type=="SESSION_START"}
            assertEquals(2,starts.size);assertEquals(starts[0].message_id,starts[1].message_id)
            assertEquals(starts[0].idempotency_key,starts[1].idempotency_key);assertEquals(1,link.starts)
        } finally {scope.cancel()}
    }
    @Test fun repeatedFinalChunkReplaysAcknowledgementsWithoutPublishingImageTwice()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        val events=mutableListOf<RokidEvent>();scope.launch {transport.events.collect {events+=it}}
        try {
            transport.connect(device()).getOrThrow();transport.startSession("s",1).getOrThrow()
            val bytes=ByteArray(20_001) {(it%251).toByte()}
            val descriptor=ImageDescriptor("capture","s",1,System.currentTimeMillis(),1280,720,0,bytes.size,StudyWire.sha256(bytes))
            transport.requestCapture(CaptureCommand("s",1,"capture",emptySet(),deadlineElapsedMs=elapsed()+25_000,captureTimeoutMs=5000)).getOrThrow()
            val command=link.sent.last {it.type=="CAPTURE_REQUEST"}
            assertEquals(5000,command.payload.getValue("capture_timeout_ms").jsonPrimitive.int)
            assertTrue(command.expires_at_epoch_ms!!-command.sent_at_epoch_ms>20_000)
            link.emit(StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=System.currentTimeMillis(),type="IMAGE_OFFER",
                payload=StudyWire.payload("image" to StudyWire.json.encodeToJsonElement(descriptor),"camera_duration_ms" to 123)))
            suspend fun chunk(index: Int) {link.emit(StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=System.currentTimeMillis(),type="IMAGE_CHUNK",
                payload=StudyWire.payload("capture_id" to "capture","index" to index,"data" to Base64.getEncoder().encodeToString(bytes.copyOfRange(index*8192,minOf((index+1)*8192,bytes.size))))))}
            for(index in 0 until descriptor.chunkCount) chunk(index)
            withTimeout(1000) {while(events.filterIsInstance<RokidEvent.ImageReceived>().isEmpty()) yield()}
            chunk(descriptor.chunkCount-1)
            withTimeout(1000) {while(link.sent.count {it.type=="IMAGE_ACK"}<2) yield()}
            assertEquals(1,events.filterIsInstance<RokidEvent.ImageReceived>().size)
            assertEquals(123L,events.filterIsInstance<RokidEvent.ImageReceived>().single().frame.cameraDurationMs)
            assertTrue(events.filterIsInstance<RokidEvent.ImageReceived>().single().frame.transferDurationMs!!>=0)
            assertEquals(JsonPrimitive(descriptor.sha256),link.sent.last {it.type=="IMAGE_ACK"}.payload["sha256"])
        } finally {scope.cancel()}
    }
    @Test fun oldReadyReplyCannotResurrectADisconnectedLink()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink().apply {suppressHello=true}
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        val connection=launch {transport.connect(device())}
        try {
            withTimeout(1000) {while(link.sent.none {it.type=="HELLO"}) yield()}
            val old=link.sent.first {it.type=="HELLO"}
            link.disconnect()
            link.reply(old,"READY",StudyWire.payload("glasses_time_ms" to System.currentTimeMillis(),"camera_ready" to true,"display_ready" to true))
            yield();assertFalse(transport.ready.value)
        } finally {connection.cancelAndJoin();scope.cancel()}
    }
    @Test fun commandErrorsKeepTheirOperationIdentityAndDoNotFailNewCaptures()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        val events=mutableListOf<RokidEvent>();scope.launch {transport.events.collect {events+=it}}
        try {
            transport.connect(device()).getOrThrow();transport.startSession("s",1).getOrThrow()
            transport.requestCapture(CaptureCommand("s",1,"rejected",emptySet(),deadlineElapsedMs=elapsed()+1000)).getOrThrow()
            suspend fun rejected() {link.emit(StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=System.currentTimeMillis(),
                type="ERROR",payload=StudyWire.payload("capture_id" to "rejected","code" to "CAPTURE_BUSY_OR_DISCONNECTED")))}
            rejected()
            transport.requestCapture(CaptureCommand("s",1,"next",emptySet(),deadlineElapsedMs=elapsed()+1000)).getOrThrow()
            rejected()
            link.emit(StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=System.currentTimeMillis(),type="ERROR",
                payload=StudyWire.payload("decision_id" to "d","code" to "DISPLAY_NOT_READY")))
            assertEquals(listOf(RokidEvent.CaptureFailed("rejected","CAPTURE_BUSY_OR_DISCONNECTED")),events.filterIsInstance<RokidEvent.CaptureFailed>())
            assertEquals(listOf(RokidEvent.DisplayFailed("s",1,"d","DISPLAY_NOT_READY")),events.filterIsInstance<RokidEvent.DisplayFailed>())
        } finally {scope.cancel()}
    }
    @Test fun transferDeadlinePublishesCaptureFailureAndRejectsLateImageOffers()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        val events=mutableListOf<RokidEvent>();scope.launch {transport.events.collect {events+=it}}
        try {
            transport.connect(device()).getOrThrow();transport.startSession("s",1).getOrThrow()
            transport.requestCapture(CaptureCommand("s",1,"timed-out",emptySet(),deadlineElapsedMs=elapsed()+40)).getOrThrow()
            withTimeout(1000) {while(events.filterIsInstance<RokidEvent.CaptureFailed>().isEmpty()) delay(5)}
            val descriptor=ImageDescriptor("timed-out","s",1,System.currentTimeMillis(),32,24,0,1,StudyWire.sha256(byteArrayOf(1)))
            link.emit(StudyMessage(session_id="s",session_epoch=1,sent_at_epoch_ms=System.currentTimeMillis(),type="IMAGE_OFFER",
                payload=StudyWire.payload("image" to StudyWire.json.encodeToJsonElement(descriptor))))
            assertEquals(listOf(RokidEvent.CaptureFailed("timed-out","IMAGE_TRANSFER_TIMEOUT")),events.filterIsInstance<RokidEvent.CaptureFailed>())
            assertTrue(events.filterIsInstance<RokidEvent.ImageReceived>().isEmpty())
            assertTrue(link.sent.none {it.type=="IMAGE_GET_BLUETOOTH"})
        } finally {scope.cancel()}
    }
    @Test fun reconnectRequiresNewEpochBeforeAnyCaptureOrDisplayCanBeSent()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val link=FakeLink()
        val transport=LegacyCxrTransport(link,scope,::elapsed,rpcTimeoutMs=100,readinessPollMs=1)
        val events=mutableListOf<RokidEvent>();scope.launch {transport.events.collect {events+=it}}
        try {
            transport.connect(device()).getOrThrow();transport.startSession("s",1).getOrThrow()
            val oldDisplay=transport.controlMessage("SHOW_SERVICE_CARD",StudyWire.payload("decision_id" to "old"),30_000)
            transport.disconnect();transport.connect(device()).getOrThrow()
            assertEquals(1,events.filterIsInstance<RokidEvent.ConnectionLost>().size)
            assertTrue(transport.command(oldDisplay).isFailure)
            assertTrue(transport.sendControl(StudyWire.encode(oldDisplay).toByteArray()).isFailure)
            assertTrue(transport.requestCapture(CaptureCommand("s",1,"old",emptySet(),deadlineElapsedMs=elapsed()+1000)).isFailure)
            assertEquals("RECONNECT_REQUIRES_NEW_EPOCH",transport.startSession("s",1).exceptionOrNull()?.message)
            transport.startSession("s",2).getOrThrow()
            assertTrue(transport.command(oldDisplay).isFailure)
            transport.command(transport.controlMessage("SHOW_SERVICE_CARD",StudyWire.payload("decision_id" to "fresh"),30_000)).getOrThrow()
            val shown=link.sent.filter {it.type=="SHOW_SERVICE_CARD"}
            assertEquals(1,shown.size);assertEquals(2L,shown.single().session_epoch)
            assertEquals(JsonPrimitive("fresh"),shown.single().payload["decision_id"])
        } finally {scope.cancel()}
    }
}
