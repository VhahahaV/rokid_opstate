package ai.opstate.rokid.phone

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.*
import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.RokidDevice
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real phone/glasses transport and renderer, with a clearly labelled scripted HR decision. */
@RunWith(AndroidJUnit4::class)
class HrRealGlassesAckDeviceTest {
    private class VisibleCardHr : HrInferenceClient {
        override val hrEnabled=true
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract(
            "opstate-online-hr-mobile-v1","hr-visible-fixture","a".repeat(64),"b".repeat(64),
            "c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="hr_real_glasses_display",
            notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse=
            error("HR turn required")
        override suspend fun evaluateHrTurn(request: InferenceRequest,currentJpeg: ByteArray,
            historicalJpegs: Map<String,ByteArray>,history: CausalContextStore,
            agentHistory: List<HrAgentOutput>,onRaw: (RawHarnessResponse)->Unit): HrTurnOutcome {
            val provider=object: HrStageProvider {
                override suspend fun step(envelope: JsonObject): HrStep {
                    val rows=(envelope.getValue("new_records").jsonArray+envelope.getValue("recent_context").jsonArray)
                        .map {it.jsonObject}
                    val image=rows.first {it.getValue("kind").jsonPrimitive.content==ObservationKind.IMAGE.name}
                        .getValue("record_id").jsonPrimitive.content
                    val record=rows.first {it.getValue("kind").jsonPrimitive.content==ObservationKind.SYNTHETIC_CONTEXT.name}
                        .getValue("record_id").jsonPrimitive.content
                    val evidence=listOf(record,image)
                    return HrStep(HrVisualNote("",emptyList()),HrCandidate(HrCandidateKind.OPEN_ITEM,
                        "操作员短测要求眼镜确认显示",evidence,emptyList(),HrRoute.SERVICE),HrRoute.SERVICE,null,
                        HrService("AWARENESS","HR显示短测","这张测试卡只验证真实眼镜可见回执。",evidence))
                }
                override suspend fun final(envelope: JsonObject): HrFinal=error("unexpected retrieval")
            }
            return HrTurnRunner(history,provider).run(request,agentHistory,20_000)
        }
    }

    @Test fun liveGlassesReturnTwoFrameServiceReceipt()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hr_live_ack")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        runCatching {withTimeout(20_000) {app.transport.ready.first {it}}}.onFailure {
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val engine=StudyRuntimeEngine(context,scope,app.transport,journal,{""},inferenceOverride={VisibleCardHr()})
        var id: String?=null
        try {
            engine.start(SessionSetup("HR_REAL_GLASSES_ACK","unused://hr",expectedModelId="hr-visible-fixture",
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=true,captureIntervalMs=60_000)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            engine.setAutomaticCapture(false)
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"显示短测",
                "操作员现在验证测试卡的真实眼镜两帧回执。")).getOrThrow()
            engine.captureNow()
            fun manualPacketIds()=database.dao().stream(requireNotNull(id),"inference_requests")
                .filter {it.eventType=="INFERENCE_SENT"}
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .filter {row->row["trigger_types"]?.jsonArray?.any {it==JsonPrimitive("MANUAL")}==true}
                .mapNotNull {it["packet_id"]?.jsonPrimitive?.contentOrNull}.toSet()
            withTimeout(50_000) {while(database.dao().stream(requireNotNull(id),"receipts")
                .none {row->StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["packet_id"]
                    ?.jsonPrimitive?.contentOrNull in manualPacketIds()}) delay(100)}
            val receipt=database.dao().stream(requireNotNull(id),"receipts").first {row->
                StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["packet_id"]
                    ?.jsonPrimitive?.contentOrNull in manualPacketIds()}
            val payload=StudyWire.json.parseToJsonElement(receipt.canonicalJson).jsonObject
            val asset=database.dao().stream(requireNotNull(id),"inference_requests")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .first {it["packet_id"]==payload["packet_id"]}.getValue("current_asset_id")
            val photo=database.dao().stream(requireNotNull(id),"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .first {it["kind"]==JsonPrimitive("IMAGE") && it["asset_id"]==asset}
            assertEquals("BYPASSED_MANUAL",photo["front_end"]?.jsonPrimitive?.content)
            assertEquals(listOf("MANUAL"),photo.getValue("trigger_types").jsonArray.map {it.jsonPrimitive.content})
            assertFalse(photo.containsKey("front_end_score"))
            assertEquals("RECEIPT_COMMITTED",receipt.eventType)
            assertTrue(payload.getValue("visible_ack_frame_count").jsonPrimitive.int>=2)
            assertEquals("HR显示短测",payload.getValue("title").jsonPrimitive.content)
            val output=StudyWire.payload("kind" to "REAL_PHONE_REAL_GLASSES_SCRIPTED_HR",
                "session_id" to id,"receipt_id" to payload.getValue("receipt_id"),
                "visible_ack_frame_count" to payload.getValue("visible_ack_frame_count"),
                "front_end" to "BYPASSED_MANUAL","real_model" to false,
                "journal_verified" to journal.verify(requireNotNull(id)))
            File(requireNotNull(context.getExternalFilesDir(null)),"hr_real_glasses_ack.json").writeText(output.toString())
            println("HR_REAL_GLASSES_ACK $output")
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            id?.let {if(database.dao().session(it)!=null) {
                assertTrue(journal.verify(it));journal.delete(it)
            }}
            database.close()
        }
    }
}
