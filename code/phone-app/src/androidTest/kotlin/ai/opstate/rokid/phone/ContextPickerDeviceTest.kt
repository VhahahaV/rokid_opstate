package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.review.SessionReviewRepository
import ai.opstate.rokid.phone.runtime.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The context a staged run can be made on, and what the picker that offers it gets.
 *
 * Two things went wrong together in the field. Every note in a session seeded from an earlier record
 * came out blank, because the reader only knew one of the three shapes a note has been written in -
 * so the picker drew empty rows. And choosing one of those empty rows failed validation inside the
 * actor, which never answered the reply it had been given, so the console waited for an answer that
 * was not coming and stayed busy until the app was force-stopped.
 */
@RunWith(AndroidJUnit4::class)
class ContextPickerDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    /** The three writers, their three shapes, and the one reader that has to understand all of them. */
    @Test fun everyShapeANoteHasBeenWrittenInReadsBackWithItsTitleAndText() {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val id="test-context-${System.currentTimeMillis()}"
        try {
            val now=System.currentTimeMillis()
            journal.start(id,100,"TEST","live-service-v1","BENCHMARK_ALIGNED","0".repeat(64),now)
            fun note(observationId: String,extra: Map<String,JsonElement>) {
                journal.append(id,"observations","OBSERVATION_PERSISTED",
                    JsonObject(StudyWire.payload("observation_id" to observationId,"kind" to "SYNTHETIC_CONTEXT",
                        "occurred_at_epoch_ms" to now,"available_at_epoch_ms" to now)+extra),now)
            }
            // The dialog: title and body stored apart, with the header kept in the text as well.
            note("c-dialog",StudyWire.payload("synthetic_type" to "MESSAGE","title" to "李明",
                "body" to "下午的组会挪到 B302 了。","content" to "[MESSAGE] 李明\n下午的组会挪到 B302 了。"))
            // Seeded from an earlier record: `content` only, header and all.
            note("c-seeded",StudyWire.payload("content" to "[EMAIL] 关于第三章的补充实验\n数据已经放到共享目录。",
                "source" to "LOCAL_COLLECTION_HISTORY"))
            // The imported public history: `public_text` only.
            note("c-public",StudyWire.payload("public_text" to "[CALENDAR] 周会\n周三下午三点，会议室 A。"))
            journal.stop(id,now)

            val entries=SessionReviewRepository(journal).contexts(id)
            assertEquals(3,entries.size)
            entries.forEach {entry->
                assertTrue("${entry.id} has no title",entry.title.isNotBlank())
                assertTrue("${entry.id} has no text",entry.text.isNotBlank())
                // The header is where the title came from; it must not still be sitting in the text.
                assertFalse("${entry.id} still carries its header",entry.text.startsWith("["))
            }
            assertEquals(listOf("李明","关于第三章的补充实验","周会"),entries.map {it.title})
            assertEquals(listOf("MESSAGE","EMAIL","CALENDAR"),entries.map {it.type})
            assertEquals(listOf("下午的组会挪到 B302 了。","数据已经放到共享目录。","周三下午三点，会议室 A。"),entries.map {it.text})
        } finally {runCatching {journal.delete(id)};database.close()}
    }

    /**
     * A staged run the actor cannot start has to come back as a failure.
     *
     * It waits for a reply, and the reply is only sent by the actor: an event whose handler throws
     * without answering leaves the caller suspended for ever, which is what froze the console. The
     * timeout here is the assertion - before the fix this never returned at all.
     */
    @Test fun aChosenContextThatCannotBeUsedFailsInsteadOfHanging()=runBlocking {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={RecordingModel()})
        var id: String?=null
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("CONTEXT_TEST","unused://service",expectedModelId="context-fixture",
                mode=SessionMode.SERVICE)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            // What the picker produced for every seeded note before the reader was fixed: no title.
            val outcome=withTimeout(10_000) {engine.evaluateChosenContext(SyntheticContextInput(SyntheticContextType.NOTE,"",""))}
            assertTrue("an unusable note must come back as a failure",outcome.isFailure)
            assertEquals("CONTEXT_INPUT_INVALID",outcome.exceptionOrNull()?.message)
            // And the session is still usable afterwards, rather than stuck behind the bad one.
            val usable=withTimeout(10_000) {engine.evaluateChosenContext(SyntheticContextInput(
                SyntheticContextType.NOTE,"李明","下午的组会挪到 B302 了。"))}
            assertTrue(usable.isSuccess)
            assertNull(engine.state.value.error)
        } finally {
            runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin()
            id?.let {runCatching {journal.delete(it)}}
            database.close()
        }
    }

    /** Answers every evaluation so the staged run completes; nothing here is about the model. */
    private class RecordingModel : InferenceClient {
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","context-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit)=
            error("evaluateWithHistory is required")
        override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
            onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            val now=System.currentTimeMillis()
            val body=StudyWire.payload("request_id" to request.requestId,"decision_id" to "decision-${request.requestId}",
                "decision" to "NO_SERVICE","service" to JsonNull,"evidence_refs" to emptyList<String>(),
                "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+30_000).toString()
            onRaw(RawHarnessResponse(request.requestId,200,body,StudyWire.sha256(body.toByteArray()),now,0))
            return InferenceResponse(request.requestId,"decision-${request.requestId}",DecisionLabel.NO_SERVICE,null,null,null,
                emptySet(),now,now+30_000)
        }
    }
}
