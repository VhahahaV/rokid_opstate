package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.review.ContextEntry
import ai.opstate.rokid.phone.review.timelineItems
import ai.opstate.rokid.phone.ui.StudyTheme
import ai.opstate.rokid.phone.ui.timeline
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A record's timeline when the record holds the same observation id twice.
 *
 * An id is unique inside the record that wrote it, and a merged record can hold two rows with one id:
 * merging two records that were each seeded from the same history folds two copies of
 * `history-context-<source>-<n>` - and of `collection-<source>-<nnn>` - into one record. Both rows are
 * really in the record and both are drawn. What a lazy list cannot be given is the same key twice: it
 * throws while it measures, which on the phone is the app closing as the operator scrolls through the
 * record they just merged. That is what these render.
 */
@RunWith(AndroidJUnit4::class)
class MergedRecordTimelineUiTest {
    @get:Rule val compose=createComposeRule()

    /** A frame and a note as the reader hands them over; the bytes are never read by this test. */
    private fun photo(id: String,at: Long)=CapturedPhoto(id,File("/nonexistent/$id.jpg"),at,at,JsonObject(emptyMap()))
    private fun note(id: String,at: Long)=ContextEntry(id,"MESSAGE","室友","宿舍 11:30 关门，阿姨说再晚回来就得登记了。",at)

    @Test fun twoRowsThatShareAnObservationIdBothRender() {
        val shared="history-context-c95e6bd5b96c-23"
        val frame="collection-c95e6bd5b96c-000"
        // The shape the merge leaves: each record that imported the same history contributes its copy.
        // The frames sit apart in time with a note between them, so each is a grid row of its own rather
        // than two tiles in one row - two rows are what collided.
        val photos=listOf(photo(frame,1_000),photo(frame,3_000))
        val contexts=listOf(note(shared,2_000),note(shared,4_000))
        compose.setContent {StudyTheme {LazyColumn(Modifier.fillMaxSize().testTag("timeline")) {
            timeline(timelineItems(photos,contexts,true)) {}
        }}}
        compose.onNodeWithTag("timeline").assertExists()
        assertEquals("both copies of the note belong on screen",
            2,compose.onAllNodesWithTag("context-$shared").fetchSemanticsNodes().size)
        assertEquals("both copies of the frame belong on screen",
            2,compose.onAllNodesWithTag("photo-$frame").fetchSemanticsNodes().size)
    }
}

/**
 * The same thing on the operator's own merged record, which is where it was found: opening the record
 * and scrolling to a note that came in twice killed the app.
 *
 * Skips when the device holds no such record, so it is a check of the record that is there rather than
 * a fixture. The scroll walks the whole timeline up to the note, and every row it passes is measured -
 * which is where a repeated key used to throw. The page draws the fact once now, so what is left to
 * prove here is that the record opens, scrolls to that note and stays open.
 */
@RunWith(AndroidJUnit4::class)
class MergedRecordScrollDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.application as PhoneApplication

    /** Any record row at all, whatever it is: the ones on screen are enough to know the list is up. */
    private val anyRecordRow=SemanticsMatcher("a record row") {node->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("session-")
    }

    /**
     * A fact the record holds more than once, and the id of the row the page draws it from.
     *
     * A merge leaves one row of the same note per record that had imported it, under different ids: the
     * page keeps the first of them in chain order, which is the row a test has to look for. Withdrawn
     * notes keep their rows too, so only the live ones are considered.
     */
    private fun repeatedFact(): Pair<String,String>? {
        val dao=graph.database.dao()
        for(session in dao.sessions()) {
            val withdrawn=graph.journal.retractedContextObservations(session.sessionId)
            val live=dao.stream(session.sessionId,"observations").mapNotNull {row->
                runCatching {
                    val value=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
                    val id=value["observation_id"]?.jsonPrimitive?.contentOrNull
                    if(value["kind"]?.jsonPrimitive?.contentOrNull!="SYNTHETIC_CONTEXT" || id==null || id in withdrawn) null
                    else id to ((value["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L) to
                        (value["content"]?.jsonPrimitive?.contentOrNull
                            ?: value["public_text"]?.jsonPrimitive?.contentOrNull ?: ""))
                }.getOrNull()
            }
            val repeated=live.groupBy {it.second}.entries.firstOrNull {it.value.size>1} ?: continue
            return session.sessionId to repeated.value.first().first
        }
        return null
    }

    @Test fun aMergedRecordScrollsToANoteItHoldsTwice()=runBlocking<Unit> {
        graph.storageReady.await()
        val found=withContext(Dispatchers.IO) {repeatedFact()}
        assumeTrue("no record on this device holds a note twice",found!=null)
        val (sessionId,noteId)=requireNotNull(found)
        compose.onNodeWithText("记录",useUnmergedTree=true).performClick()
        val row="session-$sessionId"
        // The records are read from the journal behind the tab, so the list is still empty for a moment
        // after the click: waiting for a row to appear is what tells "being read" from "nothing there".
        compose.waitUntil(10_000) {compose.onAllNodes(anyRecordRow).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("operator-content-1").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("照片与记录").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("review-content").performScrollToNode(hasTestTag("context-$noteId"))
        // The record holds the note many times over - every merged segment that had imported it carried
        // a copy - and the page draws the fact once. Reaching here is the other half of the assertion: a
        // list handed two rows that share a key used to throw out of measure and close the app.
        assertEquals("the fact is drawn once",
            1,compose.onAllNodesWithTag("context-$noteId").fetchSemanticsNodes().size)
        compose.onNodeWithText("照片与记录").assertExists()
        capture("merged-record-note.png")
    }

    /** Kept where it can be pulled off the device, so the screen can be looked at and not only asserted. */
    private fun capture(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),name)
            .outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
