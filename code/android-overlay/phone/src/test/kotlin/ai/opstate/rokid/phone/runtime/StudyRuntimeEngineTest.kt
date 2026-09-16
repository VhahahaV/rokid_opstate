package ai.opstate.rokid.phone.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class StudyRuntimeEngineTest {
    @Test fun fixedCollectionScheduleStaysAnchoredAndSkipsMissedSlotsWithoutDrift() {
        val origin=1_000L
        assertEquals(11_000L,nextFixedCaptureElapsed(origin,10_000,origin))
        assertEquals(11_000L,nextFixedCaptureElapsed(origin,10_000,10_999))
        assertEquals(21_000L,nextFixedCaptureElapsed(origin,10_000,11_000))
        assertEquals(41_000L,nextFixedCaptureElapsed(origin,10_000,35_500))
    }
}
