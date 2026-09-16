package ai.opstate.rokid.phone.runtime

import ai.opstate.rokid.phone.scenario.ScenarioPlayback
import org.junit.Assert.*
import org.junit.Test

class ScenarioPlaybackTest {
    @Test fun pauseFreezesProgressAndResumeNeverCountsThePauseTwice() {
        val clock=ScenarioPlayback(10_000,1000,120_000)
        assertEquals(12_000,clock.position(3000))
        clock.pause(3000);clock.pause(4000)
        assertEquals(12_000,clock.position(9000))
        clock.resume(9000);clock.resume(10000)
        assertEquals(13_000,clock.position(10000))
        assertEquals(120_000,clock.position(500000))
    }
    @Test fun positionsOutsideTheScenarioCannotBeStarted() {
        for(position in listOf(-1L,120001L)) assertThrows(IllegalArgumentException::class.java) {ScenarioPlayback(position,0,120000)}
    }
}
