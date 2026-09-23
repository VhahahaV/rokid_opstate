package ai.opstate.rokid.phone

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

/** Separate guarded real-device sessions for manual and automatic Flash/CVTT acceptance. */
@RunWith(AndroidJUnit4::class)
class HrLiveTriggerAcceptanceDeviceTest {
    @Test fun realTriggerModelAndGlassesProduceAVisibleReceipt()=runBlocking<Unit> {
        val source=InstrumentationRegistry.getArguments().getString("hr_trigger_acceptance")
        assumeTrue(source in setOf("manual","automatic"))
        val automatic=source=="automatic"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val config=app.modelConfig()
        assertEquals("qwen3.8-flash",config.modelId)
        check(app.credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        runCatching {withTimeout(20_000) {app.transport.ready.first {it}}}.onFailure {
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val engine=StudyRuntimeEngine(context,scope,app.transport,journal,{""},
            {chosen->app.credentials.modelApiKey(chosen.credentialScope())})
        var id: String?=null
        try {
            engine.start(SessionSetup(if(automatic) "HR_AUTO_REAL_DEVICE" else "HR_MANUAL_REAL_DEVICE",
                config.resolvedEndpoint(),scenarioAsset="",expectedModelId=config.modelId,directModel=config,
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=true,
                captureIntervalMs=if(automatic) 5_000 else 60_000,
                proactivityEffort=ProactivityEffort.GUARANTEED)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            if(!automatic) {engine.setAutomaticCapture(false);engine.captureNow()}
            fun requests()=database.dao().stream(requireNotNull(id),"inference_requests")
                .filter {it.eventType=="INFERENCE_SENT"}
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .filter {row->row["trigger_types"]?.jsonArray?.any {trigger->
                    val name=trigger.jsonPrimitive.content
                    if(automatic) name.startsWith("CVTT_") else name=="MANUAL"
                }==true}
            val (request,receipt)=withTimeout<Pair<JsonObject,JsonObject>>(if(automatic) 100_000 else 70_000) {
                while(true) {
                    val target=requests().firstOrNull()
                    if(target!=null) {
                        val requestId=target.getValue("request_id")
                        val failure=database.dao().stream(requireNotNull(id),"runtime_events")
                            .firstOrNull {row->row.eventType=="TECHNICAL_FAILURE" &&
                                StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["request_id"]==requestId}
                        failure?.let {error("HR_LIVE_MODEL_ERROR:"+it.canonicalJson)}
                        val delivered=database.dao().stream(requireNotNull(id),"receipts")
                            .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                            .firstOrNull {it["packet_id"]==target["packet_id"]}
                        if(delivered!=null) return@withTimeout target to delivered
                    }
                    engine.state.value.error?.let {throw IllegalStateException("HR_LIVE_MODEL_ERROR:$it")}
                    delay(100)
                }
                error("UNREACHABLE")
            }
            assertTrue(receipt.getValue("visible_ack_frame_count").jsonPrimitive.int>=2)
            val packet=request.getValue("packet_id")
            val decision=database.dao().stream(requireNotNull(id),"decisions")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .first {it["packet_id"]==packet}
            assertEquals("SERVICE",decision.getValue("decision").jsonPrimitive.content)
            val normalizedModelCard=database.dao().stream(requireNotNull(id),"raw_responses")
                .filter {it.eventType=="RAW_DECISION"}
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .filter {it["packet_id"]==packet}
                .mapNotNull {row->row["raw_response_json"]?.jsonPrimitive?.contentOrNull?.let {raw->
                    StudyWire.json.parseToJsonElement(raw).jsonObject["service"]?.let {it as? JsonObject}}}
                .last()
            assertEquals(normalizedModelCard.getValue("title"),decision.getValue("title"))
            assertEquals(normalizedModelCard.getValue("body"),decision.getValue("body"))
            val title=decision.getValue("title").jsonPrimitive.content
            val body=decision.getValue("body").jsonPrimitive.content
            assertFalse(title in setOf("现场观察","新情境"))
            assertFalse("已纳入本次观察" in body || "可在手机记录中查看" in body)
            val asset=request.getValue("current_asset_id")
            val photo=database.dao().stream(requireNotNull(id),"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .first {it["kind"]==JsonPrimitive("IMAGE") && it["asset_id"]==asset}
            val photoTriggers=photo.getValue("trigger_types").jsonArray.map {it.jsonPrimitive.content}
            if(automatic) {
                assertTrue(photo.getValue("front_end").jsonPrimitive.content.startsWith("cvtt_"))
                assertTrue(photoTriggers.any {it.startsWith("CVTT_")})
            } else {
                assertEquals("BYPASSED_MANUAL",photo.getValue("front_end").jsonPrimitive.content)
                assertTrue("MANUAL" in photoTriggers)
            }
            val forcedModelPass=database.dao().stream(requireNotNull(id),"runtime_events")
                .any {row->row.eventType=="HR_GUARANTEED_MODEL_SERVICE" &&
                    StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["packet_id"]==packet}
            assertFalse(database.dao().stream(requireNotNull(id),"runtime_events")
                .any {it.eventType=="HR_EFFORT_FALLBACK"})
            val report=StudyWire.payload("kind" to "REAL_PHONE_FLASH_REAL_GLASSES_HR",
                "session_id" to id,"model_id" to config.modelId,"effort" to "GUARANTEED",
                "trigger" to if(automatic) "AUTOMATIC_CVTT" else "MANUAL",
                "trigger_types" to request.getValue("trigger_types"),
                "photo_front_end" to photo.getValue("front_end"),
                "decision" to decision.getValue("decision"),"forced_model_pass" to forcedModelPass,
                "title" to title,"body" to body,"model_response_match" to true,
                "receipt_id" to receipt.getValue("receipt_id"),
                "visible_ack_frame_count" to receipt.getValue("visible_ack_frame_count"),
                "journal_verified" to journal.verify(requireNotNull(id)))
            File(requireNotNull(context.getExternalFilesDir(null)),
                if(automatic) "hr_auto_model_glasses_ack.json" else "hr_manual_model_glasses_ack.json").writeText(report.toString())
            println("HR_LIVE_TRIGGER_ACK $report")
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
