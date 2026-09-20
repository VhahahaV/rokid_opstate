package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import ai.opstate.rokid.phone.review.SessionReviewRepository
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/**
 * Withdrawing a synthetic context note.
 *
 * The record is append-only and hash-chained, so a note cannot be taken out of it; what these pin down
 * is that the note nevertheless leaves every place it was still readable - the record page, the history
 * a later session seeds from, and the count the operator reads - while the journal keeps saying that it
 * was there and that someone withdrew it.
 *
 * A merge copies a note into every record that had imported the history it came from, so one ledger
 * line stands for several rows in a merged record; withdrawing the line has to take all of them.
 */
@RunWith(AndroidJUnit4::class)
class ContextRetractionDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val ledgerType="MESSAGE"
    private val ledgerTitle="李明"
    private val ledgerBody="下午的组会挪到 B302 了，时间还是四点。"

    /** A record with something in it, a configuration an export will accept, and a STOPPED checkpoint. */
    private fun fixture(journal: StudyJournal,id: String): Long {
        val now=System.currentTimeMillis()
        val configuration=StudyWire.payload("session_mode" to "COLLECTION","contract" to StudyWire.payload(
            "model_id" to "retained-mock","prompt_hash" to "a".repeat(64),
            "preprocess_hash" to "b".repeat(64),"parser_hash" to "c".repeat(64)))
        journal.start(id,100,"TEST","local-collection-v1","BENCHMARK_ALIGNED",
            StudyWire.sha256(StudyJournal.canonical(configuration).toString().toByteArray()),now,configuration)
        return now
    }

    @Test fun withdrawingOneLedgerLineTakesEveryCopyOfItAndIsNotRepeated() {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val id="test-context-retract-${System.currentTimeMillis()}"
        try {
            val now=fixture(journal,id)
            val at=now-60_000
            // The ledger line, the copy a merge brought in from a record that imported the same history
            // (same time, same words), and an unrelated note that must survive.
            journal.addSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at)
            journal.addSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at)
            journal.addSyntheticContext(id,"NOTE","睡前检查","数据备份到移动硬盘。",at+1_000)
            journal.stop(id,now)

            val page=SessionReviewRepository(journal)
            val history=LocalCollectionRepository(journal)
            // One fact, whichever way it is read: the page and the history both collapse the two copies.
            assertEquals(2,page.contexts(id).size)
            assertEquals(2,history.load(id,0).contexts.size)

            val first=requireNotNull(journal.retractSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at,null,System.currentTimeMillis()))
            assertEquals("both copies of the note are withdrawn",2,first.retracted.size)
            assertEquals(0,first.alreadyRetracted.size)

            // A second run appends nothing: the file is meant to be safe to repeat.
            val again=requireNotNull(journal.retractSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at,null,System.currentTimeMillis()))
            assertEquals(0,again.retracted.size)
            assertEquals(2,again.alreadyRetracted.size)
            assertEquals(1,database.dao().stream(id,"runtime_events").count {it.eventType=="CONTEXT_RETRACTED"})
            assertEquals(2,journal.retractedContextObservations(id).size)

            // Every reader the note reached, and the count the page shows.
            assertEquals(listOf("睡前检查"),page.contexts(id).map {it.title})
            // The history reader hands over observation records, which carry the note's text as written.
            assertEquals(listOf("[NOTE] 睡前检查\n数据备份到移动硬盘。"),
                history.load(id,0).contexts.map {it.content})
            assertEquals(2,page.load(id).retractedContexts)
            assertTrue("the record must still verify",journal.verify(id))

            // The rows are still in the archive, and the tombstone says which ones went.
            val archive=ZipFile(journal.export(id))
            try {
                val observations=archive.getInputStream(archive.getEntry("observations.jsonl")).readBytes().decodeToString()
                assertEquals("the archive keeps every row",3,observations.lines().count {it.isNotBlank()})
                val events=archive.getInputStream(archive.getEntry("runtime_events.jsonl")).readBytes().decodeToString()
                val tombstone=events.lines().filter {it.isNotBlank()}.map {StudyWire.json.parseToJsonElement(it).jsonObject}
                    .single {it["event"]?.jsonPrimitive?.contentOrNull=="CONTEXT_RETRACTED"}
                assertEquals(2,tombstone.getValue("observation_ids").jsonArray.size)
                assertEquals("MESSAGE",tombstone["synthetic_type"]?.jsonPrimitive?.contentOrNull)
                assertEquals(at,tombstone["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull)
                assertEquals("OPERATOR_RETRACT",tombstone["source"]?.jsonPrimitive?.contentOrNull)
            } finally {archive.close()}
        } finally {runCatching {journal.delete(id)};database.close()}
    }

    /** Withdrawing is a statement about rows, not about a time and some words. */
    @Test fun aRowWrittenAfterTheWithdrawalIsNotWithdrawnByIt() {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val id="test-context-retract-again-${System.currentTimeMillis()}"
        try {
            val now=fixture(journal,id)
            val at=now-60_000
            journal.addSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at)
            journal.stop(id,now)
            requireNotNull(journal.retractSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at,null,System.currentTimeMillis()))
            assertFalse(journal.hasSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at))

            // The same ledger line written again is a new row, and the old tombstone does not name it.
            journal.addSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at)
            assertTrue(journal.hasSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,at))
            assertEquals(1,SessionReviewRepository(journal).contexts(id).size)
        } finally {runCatching {journal.delete(id)};database.close()}
    }

    /** A ledger line for a note this record never had says nothing and writes nothing. */
    @Test fun withdrawingALineTheRecordDoesNotHoldWritesNothing() {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val id="test-context-retract-missing-${System.currentTimeMillis()}"
        try {
            val now=fixture(journal,id)
            journal.addSyntheticContext(id,"NOTE","睡前检查","数据备份到移动硬盘。",now)
            journal.stop(id,now)
            assertNull(journal.retractSyntheticContext(id,ledgerType,ledgerTitle,ledgerBody,now-60_000,null,System.currentTimeMillis()))
            assertEquals(0,database.dao().stream(id,"runtime_events").count {it.eventType=="CONTEXT_RETRACTED"})
            assertTrue(journal.verify(id))
            assertEquals(1,SessionReviewRepository(journal).contexts(id).size)
        } finally {runCatching {journal.delete(id)};database.close()}
    }
}
