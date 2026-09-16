package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.review.SessionReviewRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after DatasetReplayDeviceTest; also exercises migration of an existing large Room database. */
@RunWith(AndroidJUnit4::class)
class JournalReviewDeviceTest {
    @Test fun firstAndLastTrialsLoadByIndexWithTheirOriginalImages()=runBlocking<Unit> {
        val graph=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneApplication
        graph.storageReady.await()
        val source=File(graph.filesDir,"validation/phone-dataset-replay.json")
        check(source.isFile) { "Run DatasetReplayDeviceTest first" }
        val id=StudyWire.json.parseToJsonElement(source.readText()).jsonObject.getValue("session_id").jsonPrimitive.content
        val repo=SessionReviewRepository(graph.journal)
        val review=repo.load(id)
        assertTrue(review.verified);assertEquals(266,review.trials.size)
        val timings=review.trials.let { listOf(it.first(),it.last()) }.map { row ->
            val began=System.nanoTime();val trial=repo.trial(id,row);val elapsed=(System.nanoTime()-began)/1_000_000
            assertEquals(row.anchorId,trial.request!!.getValue("anchor_id").jsonPrimitive.content)
            assertTrue(trial.image!!.isFile)
            assertEquals(trial.request.getValue("current_image_sha256").jsonPrimitive.content,ai.opstate.rokid.phone.data.StudyJournal.hashFile(trial.image))
            assertTrue("trial query took $elapsed ms",elapsed<2500)
            StudyWire.payload("anchor_id" to row.anchorId,"load_ms" to elapsed)
        }
        File(graph.filesDir,"validation/phone-trial-index.json").writeText(StudyWire.payload("session_id" to id,"audit_chain_valid" to true,"trials" to JsonArray(timings)).toString())
    }
}
