package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
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
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.io.File
import java.util.zip.ZipFile

/**
 * Removing a single photograph.
 *
 * The record is append-only and hash-chained, so a deletion cannot take a row out of it; what the two
 * tests here pin down is that the picture nevertheless leaves every place it was still readable - the
 * album, the history a later session can seed from, the archive, and a session that is running while
 * the operator deletes it.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDeletionDeviceTest {
    /** The journal keeps the event, the bytes go, and every reader that matters stops finding it. */
    @Test fun deleteKeepsTheChainHidesThePhotoAndStillExports() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val id="test-photo-delete-${System.currentTimeMillis()}"
        try {
            val now=System.currentTimeMillis()
            val jpeg=checkerboardJpeg()
            val sha=StudyWire.sha256(jpeg)
            // A real runtime configuration hash, so the archive this produces is one the repository's
            // own trace validator accepts and the export contract is exercised, not just the journal.
            val configuration=StudyWire.payload("session_mode" to "COLLECTION","contract" to StudyWire.payload(
                "model_id" to "retained-mock","prompt_hash" to "a".repeat(64),
                "preprocess_hash" to "b".repeat(64),"parser_hash" to "c".repeat(64)))
            val configHash=StudyWire.sha256(StudyJournal.canonical(configuration).toString().toByteArray())
            journal.start(id,100,"TEST","photos","CONTRACT_REPLAY",configHash,now,configuration)
            val doomedJpeg=checkerboardJpeg(1)
            val doomedSha=StudyWire.sha256(doomedJpeg)
            val kept=journal.image(id,"capture-kept",jpeg,sha)
            val doomed=journal.image(id,"capture-doomed",doomedJpeg,doomedSha)
            fun observe(observationId: String,path: String,bytes: ByteArray,frameSha: String,at: Long)=
                journal.append(id,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                    "observation_id" to observationId,"kind" to "IMAGE","occurred_at_epoch_ms" to at,
                    "available_at_epoch_ms" to at,"content" to "Current egocentric image observation.",
                    "asset_id" to "asset-${observationId}","local_relative_path" to path,"sha256" to frameSha,
                    "width" to 320,"height" to 240,"rotation_degrees" to 0,"quality_valid" to true,
                    "quality_status" to "VALID","byte_length" to bytes.size),at)
            observe("obs-kept",kept,jpeg,sha,now)
            observe("obs-doomed",doomed,doomedJpeg,doomedSha,now+1_000)
            journal.stop(id,now)
            assertEquals(2,LocalCollectionRepository(journal).list().single {it.sessionId==id}.validImages)

            val deletion=journal.deletePhoto(id,"obs-doomed",now)
            assertNotNull(deletion)
            assertEquals("obs-doomed",deletion!!.getValue("observation_id").jsonPrimitive.content)
            assertFalse(journal.localImage(id,doomed).exists())
            assertTrue(journal.localImage(id,kept).isFile)
            assertEquals(setOf("obs-doomed"),journal.deletedPhotoObservations(id))
            // Still a valid record, and no longer offering the picture as history for a new session.
            assertTrue(journal.verify(id))
            assertEquals(1,LocalCollectionRepository(journal).list().single {it.sessionId==id}.validImages)
            assertEquals(listOf("obs-kept"),LocalCollectionRepository(journal).load(id,0).images.map {it.sourceObservationId})
            // A second tap appends nothing.
            assertNull(journal.deletePhoto(id,"obs-doomed",now))
            assertEquals(1,database.dao().stream(id,"runtime_events").count {it.eventType=="PHOTO_DELETED"})

            val archive=journal.export(id)
            // Kept where it can be pulled off the device and handed to tools/validate_runtime_trace.py,
            // which is the only thing that actually checks the archive contract the export promises.
            archive.copyTo(File(requireNotNull(context.getExternalFilesDir(null)),"photo-deletion-export.zip"),overwrite=true)
            ZipFile(archive).use {zip ->
                assertNotNull(zip.getEntry(kept))
                assertNull(zip.getEntry(doomed))
                // The record in the archive still says the frame existed; the deletion is what says it
                // is not here any more, and the trace validator reads exactly this.
                assertEquals(2,zip.getInputStream(zip.getEntry("observations.jsonl")).bufferedReader().readLines().size)
                val events=zip.getInputStream(zip.getEntry("runtime_events.jsonl")).bufferedReader().readLines()
                    .map {StudyWire.json.parseToJsonElement(it).jsonObject}
                assertEquals(setOf("obs-doomed"),events.filter {it["event"]?.jsonPrimitive?.contentOrNull=="PHOTO_DELETED"}
                    .map {it.getValue("observation_id").jsonPrimitive.content}.toSet())
            }
            // A record that has had a photograph taken out is still a record an operator can work on.
            journal.addSyntheticContext(id,"NOTE","会议改到 B302","原定三点，改到 B302。",now)
            assertTrue(journal.verify(id))
        } finally {
            runCatching {journal.delete(id)};database.close()
        }
    }

    /**
     * The one a running session can get wrong: the session holds its history in memory, so removing the
     * file is not enough - a request built afterwards would still carry the bytes.
     *
     * Two service sessions are seeded from the same collection record, one of them with the photograph
     * removed first: the same seed, the same capture, the same real chain, and the only difference is
     * whether the model is handed the picture.
     */
    @Test fun aDeletedHistoryPhotographStopsReachingTheModel()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=RecordingModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        val ids=mutableListOf<String>()
        val jpeg=checkerboardJpeg()
        suspend fun await(step: String,check: ()->Boolean) {
            try {withTimeout(10_000) {while(!check()) delay(20)}}
            catch(error: TimeoutCancellationException) {throw AssertionError("timed out waiting for: $step",error)}
        }
        suspend fun deliver(command: CaptureCommand) {
            val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(command.captureId,now,now,jpeg,320,240,0,
                StudyWire.sha256(jpeg),"TEST_FIXTURE")))
        }
        fun rows(sessionId: String): List<JsonObject> = database.dao().stream(sessionId,"observations")
            .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
        /** A service session seeded from [collectionId], resumed, and asked for one evaluation. */
        suspend fun serviceSessionOver(collectionId: String): String {
            engine.start(SessionSetup("SERVICE_TEST","unused://service",expectedModelId="photo-deletion-fixture",
                mode=SessionMode.SERVICE,collectionHistorySessionId=collectionId,collectionHistoryImageLimit=0)).getOrThrow()
            val id=requireNotNull(engine.state.value.sessionId);ids+=id
            assertEquals("HISTORY_READY",engine.state.value.phase)
            engine.setAutomaticCapture(false);delay(100)
            engine.resumeSession()
            val elapsed=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(elapsed-4_000,elapsed,80,0.1,0.2,0.01,0.02,
                1.0,true,false,0.0,false,10_000,false,false)))
            await("the sensor window in $id") {database.dao().stream(id,"sensor_windows").isNotEmpty()}
            return id
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("SEED_TEST","unused://collection",mode=SessionMode.COLLECTION,captureIntervalMs=5_000)).getOrThrow()
            val collectionId=requireNotNull(engine.state.value.sessionId);ids+=collectionId
            // The first collection capture is due immediately, so this waits on the cadence rather than
            // asking for one: asking would race the automatic shot and leave two captures in flight.
            deliver(withTimeout(5_000) {transport.captures.receive()})
            await("the collection capture") {engine.state.value.liveCaptures==1}
            engine.stopSession()

            // Baseline: seeded from the record as it stands, the frame is what the model is given.
            val baseline=serviceSessionOver(collectionId)
            engine.captureNow();deliver(withTimeout(3_000) {transport.captures.receive()})
            await("the baseline request") {model.requests.isNotEmpty()}
            val seededAsset=model.imageAssets.last().single {it.startsWith("history-")}
            assertTrue("the seeded frame should reach the model",model.imageAssets.last().isNotEmpty())
            engine.stopSession();delay(100)
            assertNull(engine.state.value.sessionId)

            // Same record, but the photograph is taken out of this session before anything is asked.
            val treated=serviceSessionOver(collectionId)
            val seeded=rows(treated).single {it["asset_id"]?.jsonPrimitive?.contentOrNull==seededAsset}
            val observationId=seeded.getValue("observation_id").jsonPrimitive.content
            val file=journal.localImage(treated,seeded.getValue("local_relative_path").jsonPrimitive.content)
            assertTrue(file.isFile)
            assertTrue(engine.deletePhoto(observationId).getOrThrow())
            assertFalse(file.isFile)
            // It was removed before this session captured anything, and the capture sequence is what
            // numbers the next shot, so that has not moved while the removal count has.
            assertEquals(0,engine.state.value.liveCaptures)
            assertEquals(1,engine.state.value.deletedCaptures)
            assertEquals("IMAGE_DELETED",engine.state.value.observationStatus)
            // A second tap appends nothing.
            assertFalse(engine.deletePhoto(observationId).getOrThrow())

            engine.captureNow();deliver(withTimeout(3_000) {transport.captures.receive()})
            await("the request after the deletion") {model.requests.size>1}
            assertTrue("the removed frame must not be attached",model.imageAssets.last().none {it==seededAsset})
            assertTrue("nor cited as evidence",model.requests.last().observations.none {it.observationId==observationId})
            // The record is still whole: a removal is an event in it, not a hole in it.
            assertTrue(journal.verify(treated))
        } finally {
            runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin()
            ids.forEach {assertTrue("chain broken for $it",journal.verify(it));journal.delete(it)}
            database.close()
        }
    }

    /** Records which history frames each request was handed, which is the thing being asserted. */
    private class RecordingModel : InferenceClient {
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        val imageAssets=CopyOnWriteArrayList<Set<String>>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","photo-deletion-fixture",
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

    /** Two frames of one record are two different photographs: same bytes at the same instant is one. */
    private fun checkerboardJpeg(shift: Int=0): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10+shift)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);bitmap.recycle();it.toByteArray()}
    }
}
