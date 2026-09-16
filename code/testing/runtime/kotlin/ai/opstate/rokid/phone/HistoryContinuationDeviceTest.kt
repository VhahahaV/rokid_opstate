package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import ai.opstate.rokid.phone.replay.*
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual R6 public assets + actor + Room. Only the camera/transport/model are controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class HistoryContinuationDeviceTest {
    private class Model : InferenceClient {
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","history-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            requests+=request
            val now=System.currentTimeMillis()
            val body=StudyWire.payload("request_id" to request.requestId,"decision_id" to "decision-${request.requestId}","decision" to "NO_SERVICE",
                "service" to JsonNull,"evidence_refs" to JsonArray(listOf(JsonPrimitive(request.observations.last().observationId))),
                "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+30000).toString()
            onRaw(RawHarnessResponse(request.requestId,200,body,StudyWire.sha256(body.toByteArray()),now,0))
            return InferenceResponse(request.requestId,"decision-${request.requestId}",DecisionLabel.NO_SERVICE,null,null,null,
                setOf(request.observations.last().observationId),now,now+30000)
        }
    }
    @Test fun publicHistoryAndTypedContextContinueIntoLiveRequestsAndSeekCannotLeakFutureRecords()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,db);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=Model()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        val ids=mutableListOf<String>()
        suspend fun await(check: ()->Boolean)=withTimeout(10000) {while(!check()) delay(20)}
        try {
            val source=PublicReplayDataset {path->context.assets.open(PublicReplayDataset.ASSET_ROOT+path).use {it.readBytes()}}
            val middle=source.anchors.size/2
            val seed=source.continuation(middle,System.currentTimeMillis())
            assertTrue(seed.observations.all {it.availableAtEpochMs<=seed.sourceCutoffMs+seed.alignmentMs})
            assertEquals(source.observations.count {it.availableAtEpochMs<=seed.sourceCutoffMs},seed.observations.size)
            engine.start(SessionSetup("TEST_HISTORY","local://fixture",expectedModelId="history-fixture",historyAnchorIndex=middle)).getOrThrow()
            val id=engine.state.value.sessionId!!;ids+=id
            assertEquals("HISTORY_READY",engine.state.value.phase)
            delay(4500)
            assertTrue(transport.captures.tryReceive().isFailure);assertTrue(model.requests.isEmpty())
            assertTrue(db.dao().stream(id,"receipts").isEmpty())
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.CALENDAR,"测试日程","今天 15:30 在 B302 召开组会，请带好电脑。",true,false)).getOrThrow()
            val manual=db.dao().stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}.single {it["source"]==JsonPrimitive("OPERATOR_SYNTHETIC")}
            assertEquals("CALENDAR",manual.getValue("synthetic_type").jsonPrimitive.content)
            engine.setAutomaticCapture(false);engine.resumeSession()
            val elapsed=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(elapsed-4000,elapsed,80,0.1,0.2,0.01,0.02,1.0,true,false,0.0,false,10000,false,false)))
            await {db.dao().stream(id,"sensor_windows").isNotEmpty()}
            engine.captureNow();val capture=withTimeout(3000) {transport.captures.receive()}
            val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
            val jpeg=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};bitmap.recycle()
            val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(capture.captureId,now,now,jpeg,320,240,0,StudyWire.sha256(jpeg),"TEST_FIXTURE")))
            await {db.dao().stream(id,"decisions").isNotEmpty()}
            val request=model.requests.single()
            assertTrue(request.observations.any {it.content.contains("[CALENDAR] 测试日程")})
            assertTrue(request.observations.all {it.availableAtEpochMs<=request.anchorTimeEpochMs})
            val requestIds=request.observations.mapTo(linkedSetOf()) {it.observationId}
            val seedIds=seed.observations.mapTo(linkedSetOf()) {it.observationId}
            assertTrue(requestIds.any {it in seedIds})
            assertTrue(requestIds.intersect(seedIds).size<seedIds.size)
            assertTrue(request.receipts.isEmpty())
            assertTrue(db.dao().stream(id,"receipts").isEmpty())
            val previousEpoch=transport.epoch
            engine.seekHistoryTo(0).getOrThrow()
            val restarted=engine.state.value.sessionId!!;ids+=restarted
            assertNotEquals(id,restarted);assertTrue(transport.epoch>previousEpoch)
            assertEquals("HISTORY_READY",engine.state.value.phase)
            val restored=db.dao().stream(restarted,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject.getValue("observation_id").jsonPrimitive.content}
            assertEquals(source.continuation(0,now).observations.map {it.observationId},restored)
            assertFalse(restored.contains(manual.getValue("observation_id").jsonPrimitive.content))
            transport.events.emit(RokidEvent.DisplayVisibleAck(id,previousEpoch,"late-old-result",now,2))
            engine.pauseSession();assertTrue(db.dao().stream(restarted,"receipts").isEmpty())
            val dir=File(context.filesDir,"validation").apply {mkdirs()}
            val archive=journal.export(id)
            java.util.zip.ZipFile(archive).use {zip->
                val manifest=StudyWire.json.parseToJsonElement(zip.getInputStream(zip.getEntry("session_manifest.json")).bufferedReader().use {it.readText()}).jsonObject
                val build=manifest.getValue("frozen").jsonObject.getValue("phone_build").jsonObject
                assertEquals(context.packageName,build.getValue("package_name").jsonPrimitive.content)
                val apk=build.getValue("artifacts").jsonArray.first().jsonObject
                assertTrue(apk.getValue("sha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                assertTrue(apk.getValue("bytes").jsonPrimitive.long>0)
                val start=databaseConfiguration(journal,id)
                assertEquals(start.getValue("phone_build"),build)
            }
            archive.copyTo(File(dir,"history-continuation-fixture.zip"),overwrite=true)
            File(dir,"history-continuation-fixture.json").writeText(StudyWire.payload("status" to "PASS","history_count" to seed.observations.size,
                "manual_type" to "CALENDAR","model_requests" to model.requests.size,"receipts" to 0,"backward_seek_isolated" to true,
                "transport" to "TEST_FIXTURE","model" to "LOCAL_MOCK").toString())
        } finally {
            engine.stopSession();scope.coroutineContext.job.cancelAndJoin()
            ids.forEach {assertTrue(journal.verify(it));journal.delete(it)};db.close()
        }
    }
    private fun databaseConfiguration(journal: StudyJournal,id: String)=StudyWire.json.parseToJsonElement(
        journal.dao.stream(id,"runtime_events").first {it.eventType=="SESSION_CONFIGURATION"}.canonicalJson).jsonObject
}
