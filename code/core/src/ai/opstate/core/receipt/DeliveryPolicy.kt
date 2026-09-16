package ai.opstate.core.receipt

import ai.opstate.core.inference.InferenceContract
import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.model.CandidateEpisode
import ai.opstate.core.model.DecisionLabel

/** Decides delivery after the immutable raw model decision has been recorded. */
object DeliveryPolicy {
    enum class Status {
        DELIVER, NO_SERVICE, SUPPRESS_DUPLICATE, SUPPRESS_STALE,
        SUPPRESS_INVALID_EVIDENCE, SUPPRESS_SESSION_CHANGED, SUPPRESS_DISPLAY_BUSY, TECHNICAL_FAILURE,
    }
    data class Verdict(val status: Status,val reason: String)
    fun evaluate(
        request: InferenceRequest,
        response: InferenceResponse,
        requestedEpisode: CandidateEpisode,
        currentEpisode: CandidateEpisode?,
        requestSessionId: String,
        requestEpoch: Long,
        currentSessionId: String?,
        currentEpoch: Long?,
        nowEpochMs: Long,
        receiptPolicy: ReceiptPolicy,
        displayBusy: Boolean,
    ): Verdict {
        val validation=InferenceContract.validate(response,request)
        if(validation.isNotEmpty()) return Verdict(
            if(validation.any { "evidence" in it }) Status.SUPPRESS_INVALID_EVIDENCE else Status.TECHNICAL_FAILURE,
            validation.joinToString(),
        )
        if(nowEpochMs>=response.expiresAtEpochMs) return Verdict(Status.SUPPRESS_STALE,"decision expired")
        if(requestSessionId!=currentSessionId || requestEpoch!=currentEpoch) return Verdict(Status.SUPPRESS_SESSION_CHANGED,"session changed while evaluating")
        if(currentEpisode==null || currentEpisode.materialRevision!=requestedEpisode.materialRevision ||
            currentEpisode.intentFingerprint!=requestedEpisode.intentFingerprint) {
            return Verdict(Status.SUPPRESS_STALE,"candidate material changed while evaluating")
        }
        if(response.decision==DecisionLabel.NO_SERVICE) return Verdict(Status.NO_SERVICE,"model selected silence")
        if(receiptPolicy.shouldSuppress(currentEpisode)) return Verdict(Status.SUPPRESS_DUPLICATE,"same material already visibly served")
        if(displayBusy) return Verdict(Status.SUPPRESS_DISPLAY_BUSY,"display became busy while evaluating")
        return Verdict(Status.DELIVER,"valid service awaiting visible acknowledgement")
    }
}
