package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Real actor, Room and image persistence; only the glasses transport and model are controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class CollectionModeDeviceTest {
    @Test fun bufferedCollectionPersistsBeforeCommitDeduplicatesAndDrainsBeforeStopping()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={error("COLLECTION_MUST_NOT_INITIALIZE_MODEL")})
        var id: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(5_000) {while(!check()) delay(20)}
        suspend fun status(pending: Int,automatic: Boolean)=transport.events.emit(RokidEvent.CaptureBufferStatus(StudyWire.payload(
            "captured" to 3,"pending" to pending,"free_bytes" to 1_000_000_000L,"syncing" to true,"automatic" to automatic,"capturing" to false,"next_capture_in_ms" to 5_000)))
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("BUFFER_TEST","unused://collection",mode=SessionMode.COLLECTION,captureIntervalMs=5_000,glassesCapture=true,syncBatchSize=100)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}
            assertTrue(transport.captures.tryReceive().isFailure)
            val bytes=checkerboardJpeg();val now=System.currentTimeMillis()
            val frame=ImageFrame("buffer-test",now-300_000,now,bytes,320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true)
            transport.events.emit(RokidEvent.ImageReceived(frame))
            await {transport.sent.any {it.type=="IMAGE_COMMITTED"}}
            val row=StudyWire.json.parseToJsonElement(database.dao().stream(id,"observations").single().canonicalJson).jsonObject
            assertTrue(row.toString().contains("local_relative_path"))
            assertTrue(journal.verify(id))
            transport.events.emit(RokidEvent.ImageReceived(frame));delay(100)
            assertEquals(1,database.dao().stream(id,"observations").size)
            assertTrue(database.dao().stream(id,"packets").isEmpty())
            assertTrue(database.dao().stream(id,"inference_requests").isEmpty())
            status(2,true);engine.stopSession()
            assertEquals("SYNCING",engine.state.value.phase)
            status(2,false);delay(100);assertNotNull(engine.state.value.sessionId)
            status(0,false);await {engine.state.value.sessionId==null}
            assertNotNull(database.dao().session(id)?.endedAtEpochMs)
        } finally {scope.coroutineContext.job.cancelAndJoin();id?.let(journal::delete);database.close()}
    }
    private class HistoryModel : InferenceClient {
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        val imageAssets=CopyOnWriteArrayList<Set<String>>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","collection-history-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit)=
            error("evaluateWithHistory is required")
        override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
            onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            requests+=request;imageAssets+=historicalJpegs.keys
            val now=System.currentTimeMillis()
            val body=StudyWire.payload("request_id" to request.requestId,"decision_id" to "decision-${request.requestId}",
                "decision" to "NO_SERVICE","service" to JsonNull,"evidence_refs" to listOf(request.observations.last().observationId),
                "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+30_000).toString()
            onRaw(RawHarnessResponse(request.requestId,200,body,StudyWire.sha256(body.toByteArray()),now,0))
            return InferenceResponse(request.requestId,"decision-${request.requestId}",DecisionLabel.NO_SERVICE,null,null,null,
                setOf(request.observations.last().observationId),now,now+30_000)
        }
    }

    @Test fun completedCollectionCanBeSelectedAsVisualHistoryForAServiceSession()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=HistoryModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        val sessionIds=mutableListOf<String>()
        val jpeg=checkerboardJpeg()
        suspend fun await(check: ()->Boolean)=withTimeout(10_000) {while(!check()) delay(20)}
        suspend fun deliver(command: CaptureCommand) {
            val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(command.captureId,now,now,jpeg,320,240,0,
                StudyWire.sha256(jpeg),"TEST_FIXTURE")))
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("COLLECT_TEST","unused://collection",mode=SessionMode.COLLECTION,captureIntervalMs=5_000)).getOrThrow()
            val collectionId=requireNotNull(engine.state.value.sessionId);sessionIds+=collectionId
            assertEquals(SessionMode.COLLECTION,engine.state.value.mode)
            deliver(withTimeout(3_000) {transport.captures.receive()})
            await {engine.state.value.liveCaptures==1}
            assertTrue(database.dao().stream(collectionId,"inference_requests").isEmpty())
            journal.append(collectionId,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                "observation_id" to "collected-context","kind" to "SYNTHETIC_CONTEXT","occurred_at_epoch_ms" to System.currentTimeMillis(),
                "available_at_epoch_ms" to System.currentTimeMillis(),"content" to "下午三点在 B302 开会"),System.currentTimeMillis())
            engine.stopSession()

            val available=LocalCollectionRepository(journal).list().single {it.sessionId==collectionId}
            assertEquals(1,available.validImages)
            assertEquals(1,available.contextCount)
            val sessionCount=database.dao().sessions().size
            val loaded=LocalCollectionRepository(journal).load(collectionId,1)
            assertEquals("下午三点在 B302 开会",loaded.contexts.single().content)
            assertEquals(sessionCount,database.dao().sessions().size)
            assertNull(engine.state.value.sessionId)
            assertTrue(model.requests.isEmpty())
            engine.start(SessionSetup("SERVICE_TEST","unused://service",expectedModelId="collection-history-fixture",
                mode=SessionMode.SERVICE,collectionHistorySessionId=collectionId,collectionHistoryImageLimit=1)).getOrThrow()
            val serviceId=requireNotNull(engine.state.value.sessionId);sessionIds+=serviceId
            assertEquals("HISTORY_READY",engine.state.value.phase)
            assertEquals(SessionMode.SERVICE,engine.state.value.mode)
            val imported=database.dao().stream(serviceId,"observations")
            assertEquals(2,imported.size)
            assertTrue(imported.all {it.canonicalJson.contains("LOCAL_COLLECTION_HISTORY")})
            delay(300)
            assertTrue(model.requests.isEmpty())
            assertTrue(database.dao().stream(serviceId,"inference_requests").isEmpty())

            engine.setAutomaticCapture(false);delay(100)
            engine.resumeSession()
            val elapsed=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(elapsed-4_000,elapsed,80,0.1,0.2,0.01,0.02,
                1.0,true,false,0.0,false,10_000,false,false)))
            await {database.dao().stream(serviceId,"sensor_windows").isNotEmpty()}
            engine.captureNow();deliver(withTimeout(3_000) {transport.captures.receive()})
            await {model.requests.isNotEmpty()}
            assertEquals(1,model.imageAssets.single().size)
            assertTrue(model.requests.single().observations.any {it.observationId.startsWith("collection-")})
            assertTrue(database.dao().stream(serviceId,"inference_requests").isNotEmpty())
        } finally {
            runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin()
            sessionIds.forEach {assertTrue(journal.verify(it));journal.delete(it)}
            database.close()
        }
    }

    private fun checkerboardJpeg(): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);bitmap.recycle();it.toByteArray()}
    }
}
