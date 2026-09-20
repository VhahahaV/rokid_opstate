package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.readImportLedger
import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import ai.opstate.rokid.phone.review.SessionReviewRepository
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Applies a rewritten story ledger to the record it was authored for.
 *
 * Destructive and one-off, so it refuses to run unless asked for by name:
 *
 *   adb push configs/synthetic_history/import_rewrite_2026-09-19.json \
 *     /sdcard/Android/data/ai.opstate.rokid.phone/files/synthetic-import.json
 *   adb shell am instrument -w -e rewrite true \
 *     -e class ai.opstate.rokid.phone.SyntheticStoryRewriteDeviceTest \
 *     ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It is kept in the tree rather than run once by hand because it rewrote the study record: what was
 * done to the data should be reproducible rather than remembered. It goes through the console's own
 * 导入 button, so the path an operator would take is the path that runs, and it leaves the numbers it
 * saw in reports/latest/story_history_validation.json.
 */
@RunWith(AndroidJUnit4::class)
class SyntheticStoryRewriteDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.application as PhoneApplication
    private val context get()=compose.activity.applicationContext

    /** Any record row at all, whatever it is: the ones on screen are enough to know the list is up. */
    private val anyRecordRow=SemanticsMatcher("a record row") {node->
        node.config.contains(SemanticsProperties.TestTag) &&
            node.config[SemanticsProperties.TestTag].startsWith("session-")
    }

    /** The record the ledger was authored for: the one that holds a note twice. */
    private fun mergedRecord()=runBlocking {
        withContext(Dispatchers.IO) {
            val dao=graph.database.dao()
            dao.sessions().firstOrNull {session->
                val ids=dao.stream(session.sessionId,"observations").mapNotNull {row->
                    runCatching {
                        val value=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
                        value["observation_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf {value["kind"]?.jsonPrimitive?.contentOrNull=="SYNTHETIC_CONTEXT"}
                    }.getOrNull()
                }
                ids.size!=ids.toSet().size
            }
        }
    }

    @Test fun theRewriteLeavesTheRecordShowingOneCarrierWrittenFactPerThingThatHappened()=runBlocking<Unit> {
        assumeTrue("destructive one-off",InstrumentationRegistry.getArguments().getString("rewrite")=="true")
        graph.storageReady.await()
        val file=File(context.filesDir,"synthetic-import.json")
        assertTrue("push import_rewrite_2026-09-19.json to files/synthetic-import.json first",file.isFile)
        val ledger=withContext(Dispatchers.IO) {
            readImportLedger(StudyWire.json.parseToJsonElement(file.readText()).jsonArray.map {it.jsonObject})
        }
        assertTrue("the file must carry both kinds of line",
            ledger.additions.isNotEmpty() && ledger.retractions.isNotEmpty())
        val session=requireNotNull(mergedRecord()) {"no record on this device holds a note twice"}

        // The console's own import button, in the record the operator would open.
        compose.onNodeWithText("记录",useUnmergedTree=true).performClick()
        val row="session-${session.sessionId}"
        // The records are read from the journal behind the tab, so the list is empty for a moment.
        compose.waitUntil(10_000) {compose.onAllNodes(anyRecordRow).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("operator-content-1").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithText("照片与记录").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("导入").performClick()
        compose.waitUntil(60_000) {compose.onAllNodesWithText("撤回",substring=true).fetchSemanticsNodes().isNotEmpty()}

        val after=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).contexts(session.sessionId)}
        val history=withContext(Dispatchers.IO) {LocalCollectionRepository(graph.journal).load(session.sessionId,0)}
        val withdrawn=withContext(Dispatchers.IO) {graph.journal.retractedContextObservations(session.sessionId)}
        val verified=withContext(Dispatchers.IO) {graph.journal.verify(session.sessionId)}
        val serviceProse=Regex("服务|提醒时|不应|不得|待核对|为准|复盘")
        val left=after.filter {serviceProse.containsMatchIn(it.title+it.text)}
        val report=buildJsonObject {
            put("record",JsonPrimitive(session.sessionId))
            put("ledger_lines",JsonPrimitive(ledger.additions.size))
            put("retraction_lines",JsonPrimitive(ledger.retractions.size))
            put("contexts_shown",JsonPrimitive(after.size))
            put("rows_withdrawn",JsonPrimitive(withdrawn.size))
            put("history_contexts",JsonPrimitive(history.contexts.size))
            put("history_images",JsonPrimitive(history.images.size))
            put("chain_verified",JsonPrimitive(verified))
            put("prose_left",JsonArray(left.map {JsonPrimitive(it.title)}))
        }
        File(requireNotNull(context.getExternalFilesDir(null)),"story-history-validation.json")
            .writeText(Json {prettyPrint=true}.encodeToString(JsonObject.serializer(),report))

        ledger.additions.forEach {line->
            assertTrue("the record is missing ${line.title}",
                after.any {it.occurredAt==line.atEpochMs && it.title==line.title})
        }
        assertEquals("the same fact is drawn once",after.size,after.map {it.occurredAt to it.text}.toSet().size)
        assertTrue("no service prose is left: ${left.map {it.title}}",left.isEmpty())
        assertTrue("nothing was withdrawn",withdrawn.isNotEmpty())
        assertTrue("the record must still verify",verified)
        assertEquals("history reads the facts the page draws",after.size,history.contexts.size)
    }
}
