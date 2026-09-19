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

    /**
     * A card is drawn over the photograph the model was reading, and the mark and the words on that
     * photograph come from the decision - which is journalled seconds after the picture is stored. So
     * a decision landing has to send the strip back to the journal, even though every count it
     * otherwise watches is unchanged. It does not, and the photograph that a card was drawn over shows
     * as an ordinary one while the card is on the glasses.
     */
    @Test fun aDecisionSendsThePhotographStripBackToTheJournal() {
        val running=RuntimeViewState(sessionId="s",liveCaptures=3,decisions=2)
        assertEquals(true,running.copy(decisions=3).requiresPhotoReload(running,photosEmpty=false))
        // The rest of the rule, so that the one line above cannot be satisfied by making it always true.
        assertEquals(true,running.copy(liveCaptures=4).requiresPhotoReload(running,photosEmpty=false))
        assertEquals(true,running.copy(deletedCaptures=1).requiresPhotoReload(running,photosEmpty=false))
        assertEquals(true,running.copy(sessionId="t").requiresPhotoReload(running,photosEmpty=false))
        assertEquals(true,running.requiresPhotoReload(running,photosEmpty=true))
        assertEquals(false,running.requiresPhotoReload(running,photosEmpty=false))
        assertEquals(false,running.copy(phase="EVALUATING",elapsedMs=9_000).requiresPhotoReload(running,photosEmpty=false))
    }
}
