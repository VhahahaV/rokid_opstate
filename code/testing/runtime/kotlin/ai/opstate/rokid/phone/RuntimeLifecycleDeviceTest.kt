package ai.opstate.rokid.phone

import ai.opstate.protocol.*
import ai.opstate.rokid.phone.data.*
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real Android actor/HTTP/parser/Room; transport, sensors, image and visible ACK are explicit test fixtures. */
@RunWith(AndroidJUnit4::class)
class RuntimeLifecycleDeviceTest {
    class FixtureTransport : StudyTransport {
        override val state=MutableStateFlow<RokidConnectionState>(RokidConnectionState.Disconnected)
        override val ready=MutableStateFlow(true)
        override val events=MutableSharedFlow<RokidEvent>(extraBufferCapacity=32)
        var id="fixture";var epoch=0L
        var build: PeerBuildInfo?=null;var buildOnRefresh: PeerBuildInfo?=null;var startTimeout=false;var starts=0
        override fun peerBuildInfo()=build
        override suspend fun refreshPeer(): Result<PeerBuildInfo?> {buildOnRefresh?.let {build=it};return Result.success(build)}
        val sent=CopyOnWriteArrayList<StudyMessage>()
        val captures=Channel<CaptureCommand>(Channel.UNLIMITED)
        override suspend fun scan()=Result.success(emptyList<RokidDevice>())
        override suspend fun connect(device: RokidDevice)=Result.success(Unit)
        override suspend fun disconnect() {ready.value=false}
        override suspend fun sendControl(message: ByteArray)=Result.success(Unit)
        override suspend fun startSession(id: String,epoch: Long): Result<Unit> {
            if(startTimeout) return runCatching {withTimeout(5) {delay(100)}}
            starts++;this.id=id;this.epoch=epoch;return Result.success(Unit)
        }
        override fun controlMessage(type: String,payload: JsonObject,ttlMs: Long?)=StudyMessage(session_id=id,session_epoch=epoch,
            sent_at_epoch_ms=System.currentTimeMillis(),type=type,payload=payload,expires_at_epoch_ms=ttlMs?.let {System.currentTimeMillis()+it})
        override suspend fun command(message: StudyMessage): Result<Unit> {sent+=message;return Result.success(Unit)}
        override suspend fun requestCapture(command: CaptureCommand): Result<Unit> {captures.send(command);return Result.success(Unit)}
        override suspend fun clearAllDisplays(sessionEpoch: Long)=command(controlMessage("EMERGENCY_CLEAR"))
    }
    private class Fixture(val label: String="SERVICE",val slow: Boolean=false,val decisionTtlMs: Long=30_000) {
        private val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=object : ContextWrapper(instrumentation.targetContext) {
            override fun getAssets()=instrumentation.context.assets
        }
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=FixtureTransport()
        val server=MockWebServer()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""})
        val id get()=transport.id
        init {
            server.dispatcher=object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body=if(request.path=="/v1/contract") StudyWire.json.encodeToJsonElement(FrozenHarnessContract(
                        "opstate-online-v2","runtime-fixture","a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),
                        mock=true,mockMode="scripted",notForPaper=true,strictContract=true))
                    else {
                        val raw=request.body.readUtf8().substringAfter("name=\"request\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
                        val input=StudyWire.json.parseToJsonElement(raw).jsonObject
                        val now=System.currentTimeMillis()
                        StudyWire.payload("request_id" to input.getValue("request_id"),"decision_id" to "d-${input.getValue("request_id").jsonPrimitive.content}",
                            "decision" to label,"service" to if(label=="SERVICE") StudyWire.payload("mode" to "PREPARATION","title" to "Test equipment","body" to "Prepare the test equipment.") else JsonNull,
                            "evidence_refs" to JsonArray(listOf(input.getValue("observations").jsonArray.last().jsonObject.getValue("observation_id"))),
                            "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+decisionTtlMs)
                    }
                    return MockResponse().setHeader("Content-Type","application/json").setBody(body.toString()).also {
                        if(slow && request.path=="/v1/evaluate") it.setBodyDelay(1,TimeUnit.SECONDS)
                    }
                }
            }
        }
        suspend fun start(shadow: Boolean=false) {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("TEST_ONLY",server.url("/").toString(),"scenarios/runtime-fault-fixture.json",expectedModelId="runtime-fixture",shadowInference=shadow)).getOrThrow()
            transport.sent.clear() // Exclude explicit operator startup status from model-decision assertions.
        }
        fun rows(stream: String)=database.dao().stream(id,stream)
        fun payload(row: JournalEntity)=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
        suspend fun await(check: ()->Boolean)=withTimeout(5000) {while(!check()) delay(10)}
        suspend fun capture(): CaptureCommand {engine.captureNow();return withTimeout(3000) {transport.captures.receive()}}
        suspend fun captureFrame(receivedAtEpochMs: Long=System.currentTimeMillis()) {
            val capture=capture()
            val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
            val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};bitmap.recycle()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(capture.captureId,receivedAtEpochMs,receivedAtEpochMs,bytes,320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE")))
        }
        suspend fun evaluate() {
            val now=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(now-4000,now,80,0.1,0.2,0.01,0.02,1.0,true,false,0.0,false,10000,false,false)))
            await {rows("sensor_windows").isNotEmpty()}
            captureFrame()
            await {rows("inference_requests").isNotEmpty()}
        }
        suspend fun pendingDisplay(): StudyMessage {
            evaluate();await {transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            return transport.sent.last {it.type=="SHOW_SERVICE_CARD"}
        }
        suspend fun lateAck(display: StudyMessage) {
            transport.events.emit(RokidEvent.DisplayVisibleAck(display.session_id,display.session_epoch,
                display.payload.getValue("decision_id").jsonPrimitive.content,System.currentTimeMillis(),2))
            if(engine.state.value.sessionId!=null) deviceBarrier()
            engine.pauseSession()
        }
        suspend fun deviceBarrier() {
            // This diagnostic event is handled even while paused, after all earlier device events.
            val marker="TEST_BARRIER_${System.nanoTime()}"
            transport.events.emit(RokidEvent.TransportError(marker,"explicit test barrier",true))
            await {engine.state.value.error==marker}
        }
        suspend fun close() {
            engine.stopSession();scope.coroutineContext.job.cancelAndJoin()
            if(database.dao().session(id)!=null) {assertTrue(journal.verify(id));journal.delete(id)}
            database.close();server.shutdown()
        }
    }
    private suspend fun fixture(label: String="SERVICE",slow: Boolean=false,decisionTtlMs: Long=30_000,block: suspend Fixture.()->Unit) {
        val fixture=Fixture(label,slow,decisionTtlMs)
        try {fixture.start();fixture.block()} finally {fixture.close()}
    }
    @Test fun pauseAndStopCloseCapturesAndResumeRejectsAnOldEpoch()=runBlocking {
        fixture {
            val first=capture();engine.pauseSession();engine.pauseSession()
            assertEquals(1,rows("captures").count {it.eventType=="CAPTURE_CANCELLED"})
            engine.resumeSession();assertTrue(transport.epoch>first.sessionEpoch)
            val second=capture()
            transport.events.emit(RokidEvent.CaptureFailed(first.captureId,"LATE_ERROR"))
            engine.stopSession()
            val cancelled=rows("captures").filter {it.eventType=="CAPTURE_CANCELLED"}.map(::payload)
            assertEquals(listOf(first.captureId,second.captureId),cancelled.map {it.getValue("capture_id").jsonPrimitive.content})
            assertEquals(listOf("SESSION_PAUSED","SESSION_STOPPED"),cancelled.map {it.getValue("code").jsonPrimitive.content})
            assertTrue(rows("receipts").isEmpty())
        }
    }
    @Test fun interruptedDisplaysHaveTerminalRecordsAndLateAcksNeverCreateReceipts()=runBlocking {
        for(reason in listOf("SESSION_PAUSED","SESSION_STOPPED","DISPLAY_NOT_READY","DISMISSED_BEFORE_VISIBLE_ACK:TIMEOUT","VISIBLE_ACK_TIMEOUT")) fixture(decisionTtlMs=if(reason=="VISIBLE_ACK_TIMEOUT") 800 else 30_000) {
            val display=pendingDisplay();val decision=display.payload.getValue("decision_id").jsonPrimitive.content
            when(reason) {
                "SESSION_PAUSED" -> engine.pauseSession()
                "SESSION_STOPPED" -> engine.stopSession()
                "DISPLAY_NOT_READY" -> transport.events.emit(RokidEvent.DisplayFailed(id,transport.epoch,decision,reason))
                "DISMISSED_BEFORE_VISIBLE_ACK:TIMEOUT" -> transport.events.emit(RokidEvent.DisplayDismissed(decision,"TIMEOUT"))
                else -> Unit // The fixture's public event or decision TTL provides the terminal condition.
            }
            await {rows("deliveries").any {payload(it)["reason"]==JsonPrimitive(reason)}}
            if(reason=="VISIBLE_ACK_TIMEOUT") {
                await {engine.state.value.phase!="DELIVERING"}
                assertTrue(transport.sent.any {it.type=="CLEAR_SERVICE_CARD"})
            }
            lateAck(display)
            val terminal=rows("deliveries").map(::payload).filter {it["status"]!=JsonPrimitive("NOT_ATTEMPTED")}
            assertEquals(reason,1,terminal.size)
            assertEquals("DISPLAY_FAILED",terminal.single().getValue("status").jsonPrimitive.content)
            assertTrue(reason,rows("receipts").isEmpty())
            assertEquals(1,rows("raw_responses").size);assertEquals(1,rows("decisions").size)
        }
    }
    @Test fun cancellingInFlightHttpDoesNotBecomeSilenceAndNoServiceSendsNoDisplayCommand()=runBlocking {
        fixture(slow=true) {
            evaluate();engine.pauseSession()
            assertEquals(1,rows("runtime_events").count {it.eventType=="INFERENCE_CANCELLED"})
            assertTrue(rows("decisions").isEmpty());assertTrue(rows("receipts").isEmpty())
            assertTrue(transport.sent.none {it.type=="SHOW_SERVICE_CARD"})
        }
        fixture(label="NO_SERVICE") {
            evaluate();await {rows("deliveries").isNotEmpty()}
            assertEquals("NO_SERVICE_SILENCE",payload(rows("deliveries").single()).getValue("status").jsonPrimitive.content)
            assertTrue(transport.sent.isEmpty());assertTrue(rows("receipts").isEmpty())
        }
    }
    @Test fun reconnectionPreservesSessionHistoryButNeverAcceptsAnOldVisibleAck()=runBlocking {
        fixture {
            val display=pendingDisplay();val original=database.dao().session(id)!!
            val frozen=StudyWire.json.parseToJsonElement(database.dao().checkpoint(id)!!.stateJson).jsonObject.getValue("contract")
            transport.ready.value=false;transport.events.emit(RokidEvent.ConnectionLost("BLUETOOTH_DISCONNECTED"))
            await {engine.state.value.phase=="PAUSED"}
            assertEquals("CONNECTION_LOST",payload(rows("deliveries").last()).getValue("reason").jsonPrimitive.content)
            transport.ready.value=true;transport.events.emit(RokidEvent.DeviceReady("fixture","fixture"))
            await {rows("runtime_events").any {it.eventType=="SESSION_RESUMED"}}
            val recovered=database.dao().session(id)!!
            assertEquals(original.startedAtEpochMs,recovered.startedAtEpochMs)
            assertTrue(recovered.sessionEpoch>original.sessionEpoch)
            assertEquals(frozen,StudyWire.json.parseToJsonElement(database.dao().checkpoint(id)!!.stateJson).jsonObject.getValue("contract"))
            assertEquals("LINK_RECONNECTED",payload(rows("runtime_events").last {it.eventType=="SESSION_RESUMED"}).getValue("reason").jsonPrimitive.content)
            val before=rows("sensor_windows").size
            transport.events.emit(RokidEvent.DisplayVisibleAck(display.session_id,display.session_epoch,
                display.payload.getValue("decision_id").jsonPrimitive.content,System.currentTimeMillis(),2))
            val now=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(now-4000,now,80,0.1,0.2,0.01,0.02,1.0,true,false,0.0,false,10000,false,false)))
            await {rows("sensor_windows").size>before}
            assertTrue(rows("receipts").isEmpty())
            assertEquals(recovered.sessionEpoch,capture().sessionEpoch)
        }
    }
    @Test fun operatorPauseDuringAnOutagePreventsAutomaticResume()=runBlocking {
        fixture {
            val capture=capture()
            transport.ready.value=false;transport.events.emit(RokidEvent.ConnectionLost("BLUETOOTH_DISCONNECTED"))
            await {engine.state.value.phase=="PAUSED"}
            engine.pauseSession()
            assertEquals(1,rows("runtime_events").count {it.eventType=="SESSION_RECONNECT_CANCELLED"})
            transport.ready.value=true;transport.events.emit(RokidEvent.DeviceReady("fixture","fixture"))
            deviceBarrier();engine.pauseSession()
            assertEquals("PAUSED",engine.state.value.phase);assertEquals(capture.sessionEpoch,transport.epoch)
            assertTrue(rows("runtime_events").none {it.eventType=="SESSION_RESUMED"})
            engine.resumeSession()
            assertTrue(transport.epoch>capture.sessionEpoch)
        }
    }
    @Test fun unreadableCameraFramesExplainWhyNoModelWasCalledAndOnlyRetryOnce()=runBlocking {
        fixture {
            val black=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
            val jpeg=ByteArrayOutputStream().use {black.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};black.recycle()
            var command=capture()
            repeat(2) {index->
                val now=System.currentTimeMillis()
                transport.events.emit(RokidEvent.ImageReceived(ImageFrame(command.captureId,now,now,jpeg,320,240,0,StudyWire.sha256(jpeg),"TEST_FIXTURE")))
                if(index==0) command=withTimeout(3000) {transport.captures.receive()}
            }
            await {engine.state.value.error=="IMAGE_TOO_DARK"}
            assertEquals(2,engine.state.value.liveCaptures)
            assertTrue(rows("inference_requests").isEmpty())
            assertTrue(rows("receipts").isEmpty())
            assertTrue(transport.captures.tryReceive().isFailure)
        }
    }
    @Test fun shadowInferenceKeepsTheRealModelDecisionWithoutSendingAServiceOrCreatingAReceipt()=runBlocking {
        val fixture=Fixture()
        try {
            fixture.start(shadow=true);fixture.evaluate()
            fixture.await {fixture.rows("deliveries").isNotEmpty()}
            assertEquals("SERVICE",fixture.payload(fixture.rows("decisions").single()).getValue("decision").jsonPrimitive.content)
            assertEquals("SHADOW_INFERENCE",fixture.payload(fixture.rows("deliveries").single()).getValue("reason").jsonPrimitive.content)
            assertTrue(fixture.transport.sent.none {it.type=="SHOW_SERVICE_CARD"})
            assertTrue(fixture.rows("receipts").isEmpty())
            assertEquals(1,fixture.rows("raw_responses").size)
        } finally {fixture.close()}
    }
    @Test fun onlyTheFirstCorrelatedCaptureAcknowledgementContributesToLatency()=runBlocking {
        fixture {
            val command=capture();val start=command.deadlineElapsedMs-25000
            transport.events.emit(RokidEvent.CaptureAcknowledged(command.captureId,start+10))
            transport.events.emit(RokidEvent.CaptureAcknowledged(command.captureId,start+500))
            deviceBarrier()
            assertEquals(10L,engine.state.value.commandAckMs)
            assertEquals(1,rows("runtime_events").count {it.eventType=="CAPTURE_COMMAND_ACK"})
        }
    }
    @Test fun queuedCandidatesWaitUntilTheCurrentServiceIsDismissed()=runBlocking {
        fixture {
            val display=pendingDisplay()
            await {rows("observations").map(::payload).any {it["observation_id"]==JsonPrimitive("fixture-change")}}
            val packets=rows("packets").size
            captureFrame(System.currentTimeMillis()+16_000)
            await {rows("packets").size>packets}
            delay(150)
            assertEquals(1,rows("inference_requests").size)
            transport.events.emit(RokidEvent.DisplayVisibleAck(display.session_id,display.session_epoch,
                display.payload.getValue("decision_id").jsonPrimitive.content,System.currentTimeMillis(),2))
            await {rows("receipts").size==1}
            delay(150)
            assertEquals(1,rows("inference_requests").size)
            transport.events.emit(RokidEvent.DisplayDismissed(display.payload.getValue("decision_id").jsonPrimitive.content,"DISMISS"))
            await {rows("inference_requests").size==2}
            engine.pauseSession()
            assertEquals(1,rows("receipts").size)
        }
    }
    @Test fun sessionStartTimeoutCompletesTheCallerAndLeavesAnErrorInsteadOfPreparingForever()=runBlocking {
        val fixture=Fixture()
        try {
            fixture.transport.startTimeout=true
            val result=withTimeout(3000) {runCatching {fixture.start()}}
            assertTrue(result.isFailure)
            assertEquals("ERROR",fixture.engine.state.value.phase)
            assertEquals("SESSION_PREPARATION_TIMEOUT",fixture.engine.state.value.error)
        } finally {fixture.close()}
    }
    @Test fun aNewPeerBuildDetectedDuringRefreshCannotResumeTheFrozenSession()=runBlocking {
        val fixture=Fixture()
        try {
            fixture.transport.build=PeerBuildInfo("firmware","fixture","a".repeat(64));fixture.start()
            fixture.engine.pauseSession();val epoch=fixture.transport.epoch
            fixture.transport.buildOnRefresh=PeerBuildInfo("firmware","fixture","b".repeat(64))
            assertTrue(runCatching {fixture.engine.resumeSession()}.isFailure)
            assertEquals(epoch,fixture.transport.epoch);assertEquals(1,fixture.transport.starts)
            assertEquals("PAUSED",fixture.engine.state.value.phase)
            assertTrue(fixture.rows("runtime_events").any {it.eventType=="PEER_BUILD_CHANGED"})
        } finally {fixture.close()}
    }
}
