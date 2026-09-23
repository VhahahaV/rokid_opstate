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

/** One guarded manual capture through real glasses, phone HR provider and real visible acknowledgement. */
@RunWith(AndroidJUnit4::class)
class HrGuaranteedLiveModelAckDeviceTest {
    @Test fun oneRealModelPacketReachesVisibleGlassesReceipt()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hr_live_model_ack")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val config=app.modelConfig()
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
            engine.start(SessionSetup("HR_REAL_MODEL_ACK",config.resolvedEndpoint(),scenarioAsset="",
                expectedModelId=config.modelId,directModel=config,mode=SessionMode.SERVICE,
                glassesCapture=true,frontEndEnabled=false,captureIntervalMs=60_000,
                proactivityEffort=ProactivityEffort.GUARANTEED)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            engine.captureNow()
            withTimeout(70_000) {while(database.dao().stream(requireNotNull(id),"receipts").isEmpty()) {
                engine.state.value.error?.let {throw IllegalStateException("HR_LIVE_MODEL_ERROR:$it")}
                delay(100)
            }}
            val receipt=database.dao().stream(requireNotNull(id),"receipts").single()
            val payload=StudyWire.json.parseToJsonElement(receipt.canonicalJson).jsonObject
            assertEquals("RECEIPT_COMMITTED",receipt.eventType)
            assertTrue(payload.getValue("visible_ack_frame_count").jsonPrimitive.int>=2)
            val decisions=database.dao().stream(requireNotNull(id),"decisions")
                .filter {it.eventType=="MODEL_DECISION"}
            assertEquals(1,decisions.size)
            val decision=StudyWire.json.parseToJsonElement(decisions.single().canonicalJson).jsonObject
            val forcedModelPass=database.dao().stream(requireNotNull(id),"runtime_events")
                .any {it.eventType=="HR_GUARANTEED_MODEL_SERVICE"}
            assertFalse(database.dao().stream(requireNotNull(id),"runtime_events")
                .any {it.eventType=="HR_EFFORT_FALLBACK"})
            val report=StudyWire.payload("kind" to "REAL_PHONE_REAL_MODEL_REAL_GLASSES_HR",
                "session_id" to id,"model_id" to config.modelId,"effort" to "GUARANTEED",
                "decision" to decision.getValue("decision"),"forced_model_pass" to forcedModelPass,
                "receipt_id" to payload.getValue("receipt_id"),
                "visible_ack_frame_count" to payload.getValue("visible_ack_frame_count"),
                "front_end" to "DISABLED_MANUAL_CAPTURE",
                "journal_verified" to journal.verify(requireNotNull(id)))
            File(requireNotNull(context.getExternalFilesDir(null)),"hr_live_model_glasses_ack.json").writeText(report.toString())
            println("HR_LIVE_MODEL_GLASSES_ACK $report")
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
