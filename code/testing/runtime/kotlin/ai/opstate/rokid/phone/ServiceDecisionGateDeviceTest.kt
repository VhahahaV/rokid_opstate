package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ServiceMode
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.JournalEntity
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
 * A model that answers SERVICE on every call does not get a card on every call.
 *
 * The gateway used to be the model's answer alone: whichever way the operator set the forced-service
 * switch, a trigger produced a packet, the packet produced a decision, and a decision of SERVICE was
 * displayed. What this exercises is the delivery gate underneath that answer, on the real engine, with
 * a model deliberately written to serve unconditionally:
 *
 * - a card resting on the camera frame alone is refused while the session holds a record it could have
 *   stood on (`UNGROUNDED`), because the picture in front of the wearer is not something the wearer
 *   learns from;
 * - the same card delivered twice is refused the second time (`SUPPRESSED_BY_RECEIPT`), which the
 *   receipts can only support now that they carry the title and body that were actually shown;
 * - the forced-service switch still produces a card, because that switch is the operator asking to see
 *   the display path work, and it is journalled as such rather than being an invisible difference in
 *   wording.
 *
 * Both refusals are decisions about delivery, not about the model: the call is still made, the raw
 * answer is still recorded, and the run reads back to which of the two happened.
 */
@RunWith(AndroidJUnit4::class)
class ServiceDecisionGateDeviceTest {
    /** Answers from a script. One entry per call; the last entry repeats if the model is called again. */
    private class ScriptedCardModel : InferenceClient {
        data class Step(val title: String,val body: String,val grounded: Boolean)
        val script=mutableListOf<Step>()
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        /** The record this session holds, as the request actually offered it to the model. */
        val recordObservationsOffered=CopyOnWriteArrayList<Int>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","service-decision-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),
            mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit)=
            error("evaluateWithHistory is required")
        override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
            onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            val step=script.getOrElse(requests.size) {script.last()}
            val image=request.observations.last {it.assetId==request.currentAssetId}.observationId
            val records=request.observations.filter {it.kind==ObservationKind.SYNTHETIC_CONTEXT}
            recordObservationsOffered+=records.size
            // "Grounded" here means citing two distinct non-camera records, which is what the offline
            // service evidence contract asks of a delivered card.
            val evidence=if(step.grounded) records.take(2).mapTo(linkedSetOf()) {it.observationId} else setOf(image)
            val now=System.currentTimeMillis()
            val ordinal=requests.size+1
            requests+=request
            val response=InferenceResponse(request.requestId,"decision-$ordinal",DecisionLabel.SERVICE,ServiceMode.PREPARATION,
                step.title,step.body,evidence,now,now+30_000)
            val raw=StudyWire.payload("request_id" to response.requestId,"decision_id" to response.decisionId,"decision" to "SERVICE",
                "service" to StudyWire.payload("mode" to "PREPARATION","title" to step.title,"body" to step.body),
                "evidence_refs" to JsonArray(evidence.map(::JsonPrimitive)),
                "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+30_000).toString()
            onRaw(RawHarnessResponse(request.requestId,200,raw,StudyWire.sha256(raw.toByteArray()),now,0))
            return response
        }
    }

    @Test fun aModelThatAlwaysServesDoesNotGetACardOnEveryCall()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=ScriptedCardModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        fun rows(stream: String)=database.dao().stream(requireNotNull(id),stream)
        fun payload(row: JournalEntity)=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
        suspend fun await(check: ()->Boolean)=withTimeout(30_000) {while(!check()) delay(20)}
        fun deliveryStatuses()=rows("deliveries").mapNotNull {payload(it)["status"]?.jsonPrimitive?.contentOrNull}
        fun cardsSent()=transport.sent.count {it.type=="SHOW_SERVICE_CARD"}
        var frames=0
        fun frame(): ImageFrame {
            val bytes=checkerboardJpeg(frames++)
            return ImageFrame("gate-frame-$frames",System.currentTimeMillis()-1_000,System.currentTimeMillis(),bytes,320,240,0,
                StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))
        }
        suspend fun takeFrame(): ImageFrame {val it=frame();transport.events.emit(RokidEvent.ImageReceived(it));return it}
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("SERVICE_DECISION_GATE","unused://service",expectedModelId="service-decision-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=30_000,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}
            // The record this session holds. Two notes, so a card can stand on a pair the way the offline
            // contract expects, and so the gate has something to ask for in the first place.
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.CALENDAR,"登机时间","09:35 开始登机，登机口 B42。")).getOrThrow()
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"出发清单","充电器和住宿仍未确认。")).getOrThrow()

            // 1. The model serves, and cites only the picture in front of the wearer.
            model.script+=ScriptedCardModel.Step("现场提醒","这里有一张桌子，可以先坐下整理。",grounded=false)
            takeFrame()
            await {model.requests.size==1}
            await {deliveryStatuses().isNotEmpty()}
            assertEquals("a card resting on the camera alone reached the display",
                "UNGROUNDED",deliveryStatuses().last())
            assertEquals("no card may be sent for an ungrounded decision",0,cardsSent())
            assertTrue("the record was not even offered to the model",model.recordObservationsOffered.first()>=2)

            // 2. The same model, now joining the picture to the record.
            model.script+=ScriptedCardModel.Step("登机口变更","登机口 B42，09:35 开始登机，出发清单里充电器和住宿仍未确认。",grounded=true)
            takeFrame()
            await {model.requests.size==2}
            await {transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            val card=transport.sent.last {it.type=="SHOW_SERVICE_CARD"}
            val decisionId=card.payload.getValue("decision_id").jsonPrimitive.content
            transport.events.emit(RokidEvent.DisplayVisibleAck(requireNotNull(id),transport.epoch,
                decisionId,System.currentTimeMillis(),2))
            await {rows("receipts").size==1}
            // The receipt has to be able to say what was shown, or nothing downstream can tell a repeat.
            val receipt=payload(rows("receipts").single())
            assertEquals("登机口变更",receipt["title"]?.jsonPrimitive?.contentOrNull)
            assertTrue(receipt["body"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("B42"))

            // 3. The display is free again and the model repeats itself word for word.
            transport.events.emit(RokidEvent.DisplayDismissed(decisionId,"DISMISS"))
            await {rows("deliveries").any {payload(it)["status"]?.jsonPrimitive?.contentOrNull=="DISPLAY_VISIBLE"}}
            model.script+=ScriptedModelRepeat()
            takeFrame()
            await {model.requests.size==3}
            await {deliveryStatuses().last()=="SUPPRESSED_BY_RECEIPT"}
            assertEquals("the same card was shown twice",1,cardsSent())

            // 4. The operator asks to see the display path, and the switch means something again.
            engine.setForceService(true).getOrThrow()
            assertTrue(engine.state.value.forceService)
            model.script+=ScriptedCardModel.Step("现场提醒","这里有一张桌子，可以先坐下整理。",grounded=false)
            takeFrame()
            await {model.requests.size==4}
            await {cardsSent()==2}
            assertTrue(journal.verify(requireNotNull(id)))
        } finally {runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin();id?.let {runCatching {journal.delete(it)}};database.close()}
    }

    private fun ScriptedModelRepeat()=ScriptedCardModel.Step("登机口变更","登机口 B42，09:35 开始登机，出发清单里充电器和住宿仍未确认。",grounded=true)

    private fun checkerboardJpeg(seed: Int): ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        for(x in 0 until 320) for(y in 0 until 240)
            bitmap.setPixel(x,y,if((x/16+y/16+seed)%2==0) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        val out=ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out)
        return out.toByteArray()
    }
}
