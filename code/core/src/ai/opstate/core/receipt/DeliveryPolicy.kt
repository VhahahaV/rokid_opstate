package ai.opstate.core.receipt

import ai.opstate.core.inference.InferenceContract
import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.model.CandidateEpisode
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ObservationKind

/** Decides delivery after the immutable raw model decision has been recorded. */
object DeliveryPolicy {
    enum class Status {
        DELIVER, NO_SERVICE, SUPPRESS_DUPLICATE, SUPPRESS_STALE,
        SUPPRESS_INVALID_EVIDENCE, SUPPRESS_SESSION_CHANGED, SUPPRESS_DISPLAY_BUSY,
        SUPPRESS_UNGROUNDED, TECHNICAL_FAILURE,
    }
    data class Verdict(val status: Status,val reason: String)

    /**
     * How much of the production decision the harness applies after the model has answered.
     *
     * [GUARANTEED] is the user's high-effort setting for automatic and manual evaluations.
     * [FORCED_DEMO] remains for old explicit test fixtures. Both skip rules that only make cards rare;
     * staleness, session identity, visible evidence and display availability still apply.
     */
    enum class Mode(val appliesProductionGates: Boolean) {
        PRODUCTION(true), GUARANTEED(false), FORCED_DEMO(false)
    }

    /**
     * The record floor a card has to clear before it is shown.
     *
     * A camera frame can illustrate a contribution but cannot be the thing it rests on: a card whose only
     * citation is the picture in front of the wearer says what the wearer can already see, which is the
     * shape of card a triggered-but-empty moment produces. [minimum] is how many distinct non-camera
     * observations the card must cite, and it is 0 when the request carried too few to ask for - a
     * session with no record loaded has nothing but the camera, and refusing to speak there would refuse
     * every service the session can produce rather than the empty ones.
     */
    data class Grounding(val minimum: Int) {
        val enforced: Boolean get() = minimum > 0
        companion object {
            val ANY = Grounding(0)
            /** Two, as in the offline service evidence contract: one record cannot be reconciled with itself. */
            fun of(available: Int, minimum: Int = 2): Grounding = if(available >= minimum) Grounding(minimum) else ANY
        }
    }

    /**
     * The non-camera records a card stands on, counted the way the offline contract counts them: a record
     * cited directly, or cited through the receipt of an earlier card that rested on it. The camera frame
     * is not among them - it may illustrate a contribution but cannot be the record the contribution is
     * reconciled against.
     */
    private fun citedRecordIds(request: InferenceRequest,response: InferenceResponse): Set<String> = buildSet {
        fun isRecord(id: String)=request.observations.any {
            it.observationId==id && it.kind!=ObservationKind.IMAGE && it.kind!=ObservationKind.ACTION_RECEIPT}
        request.observations.filter {it.observationId in response.evidenceObservationIds && isRecord(it.observationId)}
            .forEach {add(it.observationId)}
        request.receipts.filter {it.receiptId in response.evidenceObservationIds}
            .forEach {receipt->receipt.evidenceObservationIds.filter(::isRecord).forEach {add(it)}}
    }

    /** What this request could ground a card on at all, so a session with no record is not muted. */
    fun groundingFor(request: InferenceRequest,minimum: Int = 2): Grounding {
        fun isRecord(id: String)=request.observations.any {
            it.observationId==id && it.kind!=ObservationKind.IMAGE && it.kind!=ObservationKind.ACTION_RECEIPT}
        val available=buildSet {
            request.observations.filter {it.availableAtEpochMs<=request.anchorTimeEpochMs && isRecord(it.observationId)}
                .forEach {add(it.observationId)}
            request.receipts.flatMap {it.evidenceObservationIds}.filter(::isRecord).forEach {add(it)}
        }.size
        return Grounding.of(available,minimum)
    }

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
        grounding: Grounding = Grounding.ANY,
        mode: Mode = Mode.PRODUCTION,
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
        if(mode.appliesProductionGates) {
            if(grounding.enforced) {
                val cited=citedRecordIds(request,response).size
                if(cited<grounding.minimum) return Verdict(Status.SUPPRESS_UNGROUNDED,
                    "card stands on $cited non-camera record(s); ${grounding.minimum} required")
            }
            if(receiptPolicy.shouldSuppressContribution(response.title,response.body)) {
                return Verdict(Status.SUPPRESS_DUPLICATE,"same card already visibly served")
            }
            if(receiptPolicy.shouldSuppress(currentEpisode)) return Verdict(Status.SUPPRESS_DUPLICATE,"same material already visibly served")
        }
        if(displayBusy) return Verdict(Status.SUPPRESS_DISPLAY_BUSY,"display became busy while evaluating")
        return Verdict(Status.DELIVER,"valid service awaiting visible acknowledgement")
    }
}
