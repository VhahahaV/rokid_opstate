package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalLifecycleDeviceTest {
    @Test fun interruptedProcessIsClosedOnceWithoutInventingReceipts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,db)
        val id="test-recovery-${System.currentTimeMillis()}"
        try {
            journal.start(id,100,"TEST","interruption","CONTRACT_REPLAY","0".repeat(64),100)
            val config=StudyWire.payload("contract" to StudyWire.payload("model_id" to "retained-mock"))
            journal.checkpoint(id,100,"EVALUATING",config,120)
            assertEquals(1,journal.recoverInterrupted(150))
            assertEquals(0,journal.recoverInterrupted(160))
            assertEquals(150L,db.dao().session(id)!!.endedAtEpochMs)
            val checkpoint=db.dao().checkpoint(id)!!
            assertEquals("STOPPED",checkpoint.state)
            val data=StudyWire.json.parseToJsonElement(checkpoint.stateJson).jsonObject
            assertEquals(config["contract"],data["contract"])
            assertEquals("PROCESS_RESTART",data.getValue("recovery_reason").jsonPrimitive.content)
            assertTrue(journal.verify(id))
            assertEquals(1,db.dao().stream(id,"runtime_events").count { it.eventType=="SESSION_INTERRUPTED" })
            assertTrue(db.dao().stream(id,"receipts").isEmpty())
            journal.delete(id)
            assertNull(db.dao().session(id));assertNull(db.dao().checkpoint(id))
        } finally { db.close() }
    }
    @Test fun resumeAdvancesEpochWithoutLosingHistoryOrFrozenConfiguration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,db);val id="test-epoch-${System.currentTimeMillis()}"
        try {
            journal.start(id,10,"TEST","resume","BENCHMARK_ALIGNED","0".repeat(64),100)
            val frozen=StudyWire.payload("contract" to StudyWire.payload("model_id" to "retained"))
            journal.checkpoint(id,10,"PAUSED",frozen,110)
            val first=db.dao().lastJournal(id)!!
            journal.advanceEpoch(id,11,120)
            assertEquals(11L,db.dao().session(id)!!.sessionEpoch)
            assertEquals(11L,db.dao().checkpoint(id)!!.epoch)
            assertEquals(first,db.dao().journal(id).first())
            assertEquals(frozen,StudyWire.json.parseToJsonElement(db.dao().checkpoint(id)!!.stateJson))
            assertTrue(runCatching {journal.advanceEpoch(id,10,121)}.isFailure)
            assertTrue(journal.verify(id))
            journal.stop(id,130);journal.delete(id)
        } finally {db.close()}
    }
    @Test fun recoveryFindsLegacySessionsWithoutAnyCheckpoint() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,db);val id="test-no-checkpoint-${System.currentTimeMillis()}"
        try {
            journal.start(id,10,"TEST","startup-window","BENCHMARK_ALIGNED","0".repeat(64),100)
            assertNull(db.dao().checkpoint(id))
            assertEquals(1,journal.recoverInterrupted(120));assertEquals(0,journal.recoverInterrupted(121))
            assertEquals(120L,db.dao().session(id)!!.endedAtEpochMs)
            assertEquals("STOPPED",db.dao().checkpoint(id)!!.state)
            assertTrue(journal.verify(id));journal.delete(id)
        } finally {db.close()}
    }
    @Test fun crashRecoveryClosesOnlyUnfinishedOperationsAndKeepsTheFrozenContract() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,db);val id="test-pending-recovery-${System.currentTimeMillis()}"
        try {
            val config=StudyWire.payload("contract" to StudyWire.payload("model_id" to "fixture"))
            journal.start(id,10,"TEST","pending","BENCHMARK_ALIGNED","0".repeat(64),100,config)
            assertEquals("PREPARING",db.dao().checkpoint(id)!!.state)
            for(capture in listOf("complete","pending")) journal.append(id,"captures","CAPTURE_REQUEST",StudyWire.payload("capture_id" to capture,"status" to "REQUESTED"),101)
            journal.append(id,"captures","CAPTURE_COMPLETED",StudyWire.payload("capture_id" to "complete","status" to "COMPLETED"),102)
            for(anchor in listOf("queued","sent","finished")) journal.append(id,"anchors","ANCHOR_EMITTED",StudyWire.payload("anchor_id" to anchor),103)
            for(request in listOf("sent","finished")) journal.append(id,"inference_requests","INFERENCE_SENT",StudyWire.payload("request_id" to request,"anchor_id" to request),104)
            journal.append(id,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to "finished","decision_id" to "d"),105)
            journal.append(id,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to "before-delivery","decision_id" to "d-before-delivery"),105)
            journal.append(id,"deliveries","DELIVERY_DECISION",StudyWire.payload("decision_id" to "d","status" to "NOT_ATTEMPTED","reason" to "PENDING_VISIBLE_ACK"),106)
            assertEquals(1,journal.recoverInterrupted(120));assertEquals(0,journal.recoverInterrupted(121))
            fun cancelled(type: String)=db.dao().journal(id).filter {it.eventType==type}.map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            assertEquals(JsonPrimitive("pending"),cancelled("CAPTURE_CANCELLED").single()["capture_id"])
            assertEquals(JsonPrimitive("queued"),cancelled("ANCHOR_CANCELLED").single()["anchor_id"])
            assertEquals(JsonPrimitive("sent"),cancelled("INFERENCE_CANCELLED").single()["request_id"])
            assertEquals(JsonPrimitive("DISPLAY_FAILED"),cancelled("DELIVERY_DECISION").last()["status"])
            assertEquals(JsonPrimitive("NOT_ATTEMPTED"),cancelled("DELIVERY_DECISION").single {it["decision_id"]==JsonPrimitive("d-before-delivery")}["status"])
            assertEquals(config["contract"],StudyWire.json.parseToJsonElement(db.dao().checkpoint(id)!!.stateJson).jsonObject["contract"])
            assertTrue(db.dao().stream(id,"receipts").isEmpty());assertTrue(journal.verify(id));journal.delete(id)
        } finally {db.close()}
    }

}
