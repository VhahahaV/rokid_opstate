package ai.opstate.rokid.phone

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.*
import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.ImageFrame
import ai.opstate.rokid.phone.transport.RokidEvent
import android.graphics.Bitmap
import android.graphics.Color
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

/** Android actor and journal with an injected HR decision; display acknowledgements are fixture events. */
@RunWith(AndroidJUnit4::class)
class HrLifecycleDeviceTest {
    private class ScriptedHr : HrInferenceClient {
        override val hrEnabled=true
        var calls=0
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract(
            "opstate-online-hr-mobile-v1","hr-fixture","a".repeat(64),"b".repeat(64),
            "c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="hr_scripted",
            notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse =
            error("HR path must be used")
        override suspend fun evaluateHrTurn(request: InferenceRequest,currentJpeg: ByteArray,
            historicalJpegs: Map<String,ByteArray>,history: CausalContextStore,
            agentHistory: List<HrAgentOutput>,onRaw: (RawHarnessResponse)->Unit): HrTurnOutcome {
            calls++
            val provider=object: HrStageProvider {
                override suspend fun step(envelope: JsonObject): HrStep {
                    val rows=(envelope.getValue("new_records").jsonArray+envelope.getValue("recent_context").jsonArray)
                        .map {it.jsonObject}
                    val record=rows.first {it.getValue("kind").jsonPrimitive.content=="SYNTHETIC_CONTEXT"}
                        .getValue("record_id").jsonPrimitive.content
                    val image=rows.firstOrNull {it.getValue("kind").jsonPrimitive.content=="IMAGE"}
                        ?.getValue("record_id")?.jsonPrimitive?.content
                    val evidence=listOfNotNull(record,image)
                    return HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.OPEN_ITEM,
                        "操作员要求现场核对",evidence,emptyList(),HrRoute.SERVICE),HrRoute.SERVICE,null,
                        HrService("PREPARATION","登机准备","登机口 B42，09:35 开始登机。",evidence))
                }
                override suspend fun final(envelope: JsonObject): HrFinal = error("No retrieval requested")
            }
            return HrTurnRunner(history,provider).run(request,agentHistory,20_000)
        }
    }

    private fun jpeg(): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320)
            bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);bitmap.recycle();it.toByteArray()}
    }

    @Test fun hrServiceCommitsReceiptOnlyAfterTwoFramesAndDoesNotRepeat()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val model=ScriptedHr()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        fun statuses(sessionId: String)=database.dao().stream(sessionId,"deliveries").mapNotNull {row->
            StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["status"]?.jsonPrimitive?.contentOrNull}
        suspend fun await(label: String,condition: ()->Boolean) {
            val done=withTimeoutOrNull(6_000) {while(!condition()) delay(20);true}
            check(done==true) {"HR_DEVICE_WAIT_$label: phase=${engine.state.value.phase}, error=${engine.state.value.error}, " +
                "model_calls=${model.calls}, sent=${transport.sent.map {it.type}}, " +
                "deliveries=${id?.let(::statuses)}"}
        }
        var sequence=0
        suspend fun frame() {
            val bytes=jpeg();val now=System.currentTimeMillis();sequence++
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("hr-fixture-$sequence",now-100,now,bytes,
                320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))))
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("HR_COMPONENT","unused://hr",expectedModelId="hr-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=30_000,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.CALENDAR,"登机安排","09:35 从 B42 登机。")).getOrThrow()
            frame()
            await("first_card") {transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            val first=transport.sent.last {it.type=="SHOW_SERVICE_CARD"}
            val decisionId=first.payload.getValue("decision_id").jsonPrimitive.content
            transport.events.emit(RokidEvent.DisplayVisibleAck(requireNotNull(id),transport.epoch,decisionId,
                System.currentTimeMillis(),1))
            delay(100)
            assertTrue(database.dao().stream(requireNotNull(id),"receipts").isEmpty())
            transport.events.emit(RokidEvent.DisplayVisibleAck(requireNotNull(id),transport.epoch,decisionId,
                System.currentTimeMillis(),2))
            await("visible_receipt") {database.dao().stream(requireNotNull(id),"receipts").size==1}
            transport.events.emit(RokidEvent.DisplayDismissed(decisionId,"DISMISS"))
            frame()
            await("duplicate_suppressed") {model.calls==2 && statuses(requireNotNull(id)).contains("SUPPRESSED_BY_RECEIPT")}
            assertEquals(1,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})
            assertTrue(database.dao().stream(requireNotNull(id),"inference_requests")
                .any {it.eventType=="HR_EFFECTIVE_REQUEST"})
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            id?.let {if(database.dao().session(it)!=null) assertTrue(journal.verify(it))}
            database.close()
        }
    }

    @Test fun digitalRecordReachesHrWithoutWaitingForAnotherPhotograph()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val model=ScriptedHr()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("HR_TEXT_COMPONENT","unused://hr",expectedModelId="hr-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=60_000,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.CALENDAR,"登机安排","09:35 从 B42 登机。")).getOrThrow()
            withTimeout(22_000) {while(transport.sent.none {it.type=="SHOW_SERVICE_CARD"}) delay(50)}
            assertEquals(1,model.calls)
            val request=database.dao().stream(requireNotNull(id),"inference_requests")
                .first {it.eventType=="HR_EFFECTIVE_REQUEST"}
            val payload=StudyWire.json.parseToJsonElement(request.canonicalJson).jsonObject
            assertEquals(JsonPrimitive("NO_IMAGE"),payload["current_asset_id"])
            assertTrue(payload.getValue("observations").jsonArray.none {
                it.jsonObject["kind"]==JsonPrimitive("IMAGE")})
            assertTrue(database.dao().stream(requireNotNull(id),"receipts").isEmpty())
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            id?.let {if(database.dao().session(it)!=null) assertTrue(journal.verify(it))}
            database.close()
        }
    }
}
