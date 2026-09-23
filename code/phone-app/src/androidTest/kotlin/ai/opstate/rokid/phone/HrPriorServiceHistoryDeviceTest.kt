package ai.opstate.rokid.phone

import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that an explicitly selected old service record supplies output history, not public facts. */
@RunWith(AndroidJUnit4::class)
class HrPriorServiceHistoryDeviceTest {
    @Test fun selectedServiceRecordRestoresItsOwnOutputsAndVisibleStatus()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneApplication
        app.storageReady.await()
        val source="session-fa0ff284-9ef4-4bb6-ab7f-73eb3986acb6"
        val decisions=app.journal.dao.stream(source,"decisions").count {it.eventType=="MODEL_DECISION"}
        assertTrue("current local service experiment missing",decisions>=10)
        val seed=LocalCollectionRepository(app.journal).load(source,1)
        assertEquals(source,seed.summary.sessionId)
        assertTrue(seed.priorServices.isNotEmpty())
        assertTrue(seed.priorServices.all {it.title.isNotBlank() && it.body.isNotBlank()})
        assertTrue(seed.priorServices.none {output->seed.contexts.any {it.observationId==output.packetId}})
        assertTrue(seed.priorServices.any {it.visiblyDelivered})
        println("HR_PRIOR_SERVICE_HISTORY outputs=${seed.priorServices.size} confirmed=${seed.priorServices.count {it.visiblyDelivered}}")
    }
}
