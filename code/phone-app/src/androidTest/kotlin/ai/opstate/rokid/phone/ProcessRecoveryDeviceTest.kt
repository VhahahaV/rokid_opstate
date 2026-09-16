package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.FrozenHarnessContract
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Two explicit invocations, separated by a real target-app process stop/relaunch. No SDK/radio is simulated as real. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryDeviceTest {
    @Test fun preparedSessionSurvivesARealProcessRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("recovery_phase")
        assumeTrue("Run explicitly with recovery_phase=prepare or verify",phase in setOf("prepare","verify"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneApplication
        app.storageReady.await()
        val journal=app.journal;val dao=journal.dao
        val directory=File(app.filesDir,"validation").apply {mkdirs()}
        val seed=File(directory,"process-recovery-seed.json")
        if(phase=="prepare") {
            val original=JsonObject(dao.sessions().associate {it.sessionId to JsonPrimitive(dao.lastJournal(it.sessionId)!!.chainHash)})
            val id="test-process-recovery-${UUID.randomUUID()}";val now=System.currentTimeMillis()
            val contract=StudyWire.json.encodeToJsonElement(FrozenHarnessContract("opstate-online-v2","recovery-fixture",
                "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="scripted",notForPaper=true))
            val config=StudyWire.payload("contract" to contract,"test_fixture" to true)
            journal.start(id,now,"TEST_ONLY","process_recovery_fixture","BENCHMARK_ALIGNED",StudyWire.sha256(config.toString().toByteArray()),now,config)
            journal.append(id,"captures","CAPTURE_REQUEST",StudyWire.payload("capture_id" to "pending-process-capture","status" to "REQUESTED"),now)
            assertEquals("PREPARING",dao.checkpoint(id)!!.state)
            seed.writeText(StudyWire.payload("session_id" to id,"original_process_id" to Process.myPid(),"original_heads" to original,"contract" to contract).toString())
        } else {
            val input=StudyWire.json.parseToJsonElement(seed.readText()).jsonObject
            val id=input.getValue("session_id").jsonPrimitive.content
            check(id.startsWith("test-process-recovery-"))
            assertNotEquals(input.getValue("original_process_id").jsonPrimitive.int,Process.myPid())
            assertNotNull(dao.session(id)!!.endedAtEpochMs);assertEquals("STOPPED",dao.checkpoint(id)!!.state)
            assertEquals(input["contract"],StudyWire.json.parseToJsonElement(dao.checkpoint(id)!!.stateJson).jsonObject["contract"])
            assertEquals(1,dao.stream(id,"runtime_events").count {it.eventType=="SESSION_INTERRUPTED"})
            assertEquals(1,dao.stream(id,"captures").count {it.eventType=="CAPTURE_CANCELLED"})
            assertTrue(dao.stream(id,"receipts").isEmpty());assertTrue(journal.verify(id))
            input.getValue("original_heads").jsonObject.forEach {(session,hash)->assertEquals(hash.jsonPrimitive.content,dao.lastJournal(session)!!.chainHash)}
            File(directory,"process-recovery-result.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "original_process_id" to input["original_process_id"],"recovery_process_id" to Process.myPid(),
                "recovery_reason" to "PROCESS_RESTART","captures_cancelled" to 1,"receipts_created" to 0,"frozen_contract_preserved" to true,
                "preexisting_sessions_unchanged" to true,"physical_radio_test" to false).toString())
            journal.delete(id)
        }
    }
}
