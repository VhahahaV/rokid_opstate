package ai.opstate.core.sensor

import ai.opstate.core.model.AnchorBlocker
import ai.opstate.core.model.InteractionSnapshot

/**
 * Privacy-preserving interaction gate. It consumes only aggregate VAD and UI
 * state; raw PCM is neither required nor retained.
 */
class QuietGate(
    private val maxSpeechRatio: Double = 0.35,
    private val minMillisSinceInteraction: Long = 2_500L,
    private val vadEnabled: Boolean = true,
) {
    fun blockers(snapshot: InteractionSnapshot): Set<AnchorBlocker> = buildSet {
        if (vadEnabled && (snapshot.voiceSessionActive || snapshot.speechRatio > maxSpeechRatio)) {
            add(AnchorBlocker.SPEECH_ACTIVE)
        }
        if (snapshot.millisSinceLastUserInteraction < minMillisSinceInteraction) {
            add(AnchorBlocker.RECENT_INTERACTION)
        }
        if (snapshot.displayBusy) add(AnchorBlocker.DISPLAY_BUSY)
    }
}
