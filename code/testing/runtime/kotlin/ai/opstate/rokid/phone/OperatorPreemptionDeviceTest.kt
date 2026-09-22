package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ServiceMode
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.ImageFrame
import ai.opstate.rokid.phone.transport.RokidEvent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The operator's own press does not queue behind the card that is already showing.
 *
 * The engine holds a new service behind a displayed card on purpose - that is what stops an automatic
 * trigger from painting over one the wearer has not finished reading. A press is not that. It was
 * measured at 11.7 s against 5.7 s with the display free, and the whole difference was the packet
 * waiting out the card's ten-second window, so the press now clears the display and takes it.
 *
 * What this covers is that the two behaviours stayed apart: an automatic trigger still waits, and the
 * operator's press does not, including when the glasses have not yet confirmed the clear.
 */
@RunWith(AndroidJUnit4::class)
class OperatorPreemptionDeviceTest {
    private class CountingModel : InferenceClient {
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","preemption-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),
            mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit)=
            error("evaluateWithHistory is required")
        override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
            onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            val ordinal=requests.size+1
            requests+=request
            val now=System.currentTimeMillis()
            val image=request.observations.last {it.assetId==request.currentAssetId}.observationId
            return InferenceResponse(request.requestId,"decision-$ordinal",DecisionLabel.SERVICE,ServiceMode.AWARENESS,
                "第 $ordinal 张", "这是第 $ordinal 次服务的卡片正文，用于验证抢占行为。",setOf(image),now,now+30_000)
        }
    }

    @Test fun aPressTakesTheDisplayWhileAnAutomaticTriggerWaitsForIt()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=CountingModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        suspend fun await(ms: Long=15_000,check: ()->Boolean)=withTimeout(ms) {while(!check()) delay(20)}
        fun rows(stream: String)=database.dao().stream(requireNotNull(id),stream)
        var sequence=0
        suspend fun pressAndDeliverFrame(): String {
            val capture=withTimeout(5_000) {engine.captureNow();transport.captures.receive()}
            val bytes=checkerboardJpeg(sequence++)
            val at=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(capture.captureId,at-1_000,at,bytes,320,240,0,
                StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))))
            return capture.captureId
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("OPERATOR_PREEMPTION","unused://service",expectedModelId="preemption-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=60_000,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}

            // One press, one card, acknowledged: the display is now occupied.
            pressAndDeliverFrame()
            await {model.requests.size==1}
            await {transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            val first=transport.sent.last {it.type=="SHOW_SERVICE_CARD"}.payload.getValue("decision_id").jsonPrimitive.content
            transport.events.emit(RokidEvent.DisplayVisibleAck(requireNotNull(id),transport.epoch,first,System.currentTimeMillis(),2))
            await {rows("receipts").size==1}
            // The receipt is written inside the transaction before the state update lands, so the row is
            // not the same moment as the state: read the state by waiting for it, not by racing it.
            await {engine.state.value.delivery=="DISPLAY_VISIBLE"}
            assertEquals(1,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})

            // An automatic frame arriving now must NOT reach the model: the card owns the display.
            val held=sequence
            val auto=checkerboardJpeg(sequence++)
            val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("auto-$held",now-1_000,now,auto,320,240,0,
                StudyWire.sha256(auto),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(emptyList())))))
            delay(500)
            assertEquals("an automatic frame took a display that was in use",1,model.requests.size)

            // The operator presses. The card goes, the wait does not happen, and the request is sent.
            val sentBefore=transport.sent.size
            pressAndDeliverFrame()
            await {model.requests.size==2}
            val preempted=rows("runtime_events").filter {it.eventType=="SERVICE_CARD_PREEMPTED"}
            assertEquals("the press did not report clearing the display",1,preempted.size)
            assertTrue("the press did not clear the display on the glasses",
                transport.sent.drop(sentBefore).any {it.type=="CLEAR_SERVICE_CARD"})
            await {transport.sent.count {it.type=="SHOW_SERVICE_CARD"}==2}
            assertTrue(journal.verify(requireNotNull(id)))
        } finally {
            runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin()
            id?.let {runCatching {journal.delete(it)}};database.close()
        }
    }

    private fun checkerboardJpeg(seed: Int): ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        for(x in 0 until 320) for(y in 0 until 240)
            bitmap.setPixel(x,y,if((x/16+y/16+seed)%2==0) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        val out=ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out)
        return out.toByteArray()
    }
}
