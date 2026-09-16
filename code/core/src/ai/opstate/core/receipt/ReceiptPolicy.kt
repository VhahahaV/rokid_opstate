package ai.opstate.core.receipt

import ai.opstate.core.model.*
import java.util.UUID

/**
 * Receipt policy is deterministic and separate from the target model.
 * A receipt is committed only after the glasses report real visibility.
 */
class ReceiptPolicy(
    private val receiptIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val receipts = mutableListOf<ActionReceipt>()

    @Synchronized
    fun shouldSuppress(episode: CandidateEpisode): Boolean {
        val latest = receipts.asReversed().firstOrNull {
            it.episodeHandle == episode.episodeHandle &&
                it.intentFingerprint == episode.intentFingerprint
        } ?: return false
        return episode.materialRevision <= latest.servedMaterialRevision
    }

    @Synchronized
    fun finalizeDelivery(
        decision: ServiceDecision,
        episode: CandidateEpisode,
        objectHandle: String,
        visibleAckEpochMs: Long?,
        visibleEvidenceIds: Set<String>,
        nowEpochMs: Long,
        persistReceipt: (ActionReceipt) -> Unit = {},
    ): DeliveryOutcome {
        if (decision.label == DecisionLabel.NO_SERVICE) {
            return DeliveryOutcome(
                decisionId = decision.decisionId,
                status = DeliveryStatus.NO_SERVICE_SILENCE,
                reason = "target model selected NO_SERVICE",
            )
        }
        if (nowEpochMs >= decision.expiresAtEpochMs || visibleAckEpochMs?.let { it >= decision.expiresAtEpochMs } == true) {
            return DeliveryOutcome(decision.decisionId, DeliveryStatus.STALE_DECISION, "decision expired")
        }
        if (!visibleEvidenceIds.containsAll(decision.evidenceObservationIds)) {
            return DeliveryOutcome(decision.decisionId, DeliveryStatus.INVALID_EVIDENCE, "non-causal evidence reference")
        }
        if (shouldSuppress(episode)) {
            return DeliveryOutcome(
                decision.decisionId,
                DeliveryStatus.SUPPRESSED_BY_RECEIPT,
                "same episode/intent/material revision already visible",
            )
        }
        if (visibleAckEpochMs == null) {
            return DeliveryOutcome(
                decision.decisionId,
                DeliveryStatus.DISPLAY_FAILED,
                "no DISPLAY_VISIBLE_ACK; receipt not committed",
            )
        }
        if (visibleAckEpochMs < decision.producedAtEpochMs || visibleAckEpochMs > nowEpochMs) {
            return DeliveryOutcome(decision.decisionId, DeliveryStatus.DISPLAY_FAILED, "visible ACK has invalid timestamp")
        }
        val mode = requireNotNull(decision.serviceMode) { "SERVICE requires serviceMode" }
        val receipt = ActionReceipt(
            receiptId = "receipt-${receiptIdFactory()}",
            decisionId = decision.decisionId,
            episodeHandle = episode.episodeHandle,
            objectHandle = objectHandle,
            intentFingerprint = episode.intentFingerprint,
            serviceMode = mode,
            servedMaterialRevision = episode.materialRevision,
            visibleAtEpochMs = visibleAckEpochMs,
            evidenceObservationIds = decision.evidenceObservationIds,
        )
        // Persistence must succeed before this receipt can suppress any future service.
        persistReceipt(receipt)
        receipts += receipt
        return DeliveryOutcome(
            decision.decisionId,
            DeliveryStatus.DISPLAY_VISIBLE,
            "service visibly rendered; receipt committed",
            receipt,
        )
    }

    @Synchronized
    fun allReceipts(): List<ActionReceipt> = receipts.toList()
}
