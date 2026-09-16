package ai.opstate.core.inference

import ai.opstate.core.model.*

/** Public model input. Scheduler score/reason, private lifecycle and Gold are absent. */
data class ModelCandidate(
    val objectHandle: String,
    val episodeHandle: String,
)

data class ModelObservation(
    val observationId: String,
    val kind: ObservationKind,
    val occurredAtEpochMs: Long,
    val availableAtEpochMs: Long,
    val content: String,
    val assetId: String?,
    val episodeHandle: String?,
    val materialRevision: Long?,
)

data class ModelActionReceipt(
    val receiptId: String,
    val decisionId: String,
    val episodeHandle: String,
    val objectHandle: String,
    val intentFingerprint: String,
    val serviceMode: ServiceMode,
    val visibleAtEpochMs: Long,
    val servedMaterialRevision: Long,
    val evidenceObservationIds: Set<String>,
    val visibleAckFrameCount: Int = 2,
)

data class InferenceRequest(
    val protocolVersion: String = "opstate-online-v1",
    val requestId: String,
    val anchorId: String,
    val anchorTimeEpochMs: Long,
    val currentAssetId: String,
    val currentImageSha256: String,
    val candidate: ModelCandidate,
    val observations: List<ModelObservation>,
    val receipts: List<ModelActionReceipt>,
    val promptHash: String,
    val preprocessHash: String,
    val modelId: String? = null,
    val parserHash: String? = null,
    val packetTriggerTypes: Set<String> = emptySet(),
    val packetNewEventIds: List<String> = emptyList(),
    val packetLatestImageAgeMs: Long? = null,
    val packetObservationGap: Boolean = false,
)

data class InferenceResponse(
    val requestId: String,
    val decisionId: String,
    val decision: DecisionLabel,
    val serviceMode: ServiceMode? = null,
    val title: String? = null,
    val body: String? = null,
    val evidenceObservationIds: Set<String> = emptySet(),
    val producedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

object InferenceContract {
    fun buildPacketRequest(
        requestId: String,packet: PacketEvaluation,image: ImageQuality,causalEvents: List<ObservationRecord>,receipts: List<ActionReceipt>,
        promptHash: String,preprocessHash: String,modelId: String?,parserHash: String?,protocolVersion: String,
        newEventIds: List<String>,latestImageAgeMs: Long?,observationGap: Boolean,
    ): InferenceRequest {
        val observations=causalEvents.filter {it.kind!=ObservationKind.ACTION_RECEIPT && it.availableAtEpochMs<=packet.snapshotAtEpochMs}.map {
            ModelObservation(it.observationId,it.kind,it.occurredAtEpochMs,it.availableAtEpochMs,it.content,it.assetId,it.episodeHandle,it.materialRevision)
        }
        val modelReceipts=receipts.filter {it.visibleAtEpochMs<=packet.snapshotAtEpochMs}.map {
            ModelActionReceipt(it.receiptId,it.decisionId,it.episodeHandle,it.objectHandle,it.intentFingerprint,it.serviceMode,it.visibleAtEpochMs,
                it.servedMaterialRevision,it.evidenceObservationIds)
        }
        return InferenceRequest(protocolVersion,requestId,packet.packetId,packet.snapshotAtEpochMs,packet.currentAssetId,image.sha256,
            ModelCandidate("stream","stream-${packet.packetId}"),observations,modelReceipts,promptHash,preprocessHash,modelId,parserHash,
            packet.triggerTypes,newEventIds,latestImageAgeMs,observationGap)
    }

    fun buildRequest(
        requestId: String,
        anchor: AnchorCandidate,
        image: ImageQuality,
        causalEvents: List<ObservationRecord>,
        receipts: List<ActionReceipt>,
        promptHash: String,
        preprocessHash: String,
        modelId: String? = null,
        parserHash: String? = null,
        protocolVersion: String = "opstate-online-v1",
        packetTriggerTypes: Set<String> = emptySet(),
        packetNewEventIds: List<String> = emptyList(),
        packetLatestImageAgeMs: Long? = null,
        packetObservationGap: Boolean = false,
    ): InferenceRequest {
        val observations = causalEvents
            .filter { it.kind != ObservationKind.ACTION_RECEIPT && it.availableAtEpochMs <= anchor.anchorTimeEpochMs }
            .map {
                ModelObservation(
                    observationId = it.observationId,
                    kind = it.kind,
                    occurredAtEpochMs = it.occurredAtEpochMs,
                    availableAtEpochMs = it.availableAtEpochMs,
                    content = it.content,
                    assetId = it.assetId,
                    episodeHandle = it.episodeHandle,
                    materialRevision = it.materialRevision,
                )
            }
        return InferenceRequest(
            protocolVersion = protocolVersion,
            requestId = requestId,
            anchorId = anchor.anchorId,
            anchorTimeEpochMs = anchor.anchorTimeEpochMs,
            currentAssetId = anchor.currentAssetId,
            currentImageSha256 = image.sha256,
            candidate = ModelCandidate(
                objectHandle = anchor.candidateObjectHandle,
                episodeHandle = anchor.candidateEpisodeHandle,
            ),
            observations = observations,
            receipts = receipts.filter { it.visibleAtEpochMs <= anchor.anchorTimeEpochMs }.map {
                ModelActionReceipt(
                    receiptId = it.receiptId,
                    decisionId = it.decisionId,
                    episodeHandle = it.episodeHandle,
                    objectHandle = it.objectHandle,
                    intentFingerprint = it.intentFingerprint,
                    serviceMode = it.serviceMode,
                    visibleAtEpochMs = it.visibleAtEpochMs,
                    servedMaterialRevision = it.servedMaterialRevision,
                    evidenceObservationIds = it.evidenceObservationIds,
                )
            },
            promptHash = promptHash,
            preprocessHash = preprocessHash,
            modelId = modelId,
            parserHash = parserHash,
            packetTriggerTypes = packetTriggerTypes,
            packetNewEventIds = packetNewEventIds,
            packetLatestImageAgeMs = packetLatestImageAgeMs,
            packetObservationGap = packetObservationGap,
        )
    }

    fun validate(response: InferenceResponse, request: InferenceRequest): List<String> = buildList {
        if (response.requestId != request.requestId) add("requestId mismatch")
        val visibleIds = allowedEvidenceIds(request)
        if (!visibleIds.containsAll(response.evidenceObservationIds)) add("invalid/future evidence reference")
        if (response.expiresAtEpochMs <= response.producedAtEpochMs) add("invalid expiry")
        if (response.decision == DecisionLabel.NO_SERVICE) {
            if (response.serviceMode != null || response.title != null || response.body != null) {
                add("NO_SERVICE must not contain HOW")
            }
        } else {
            if (response.serviceMode == null) add("SERVICE requires serviceMode")
            if (response.title.isNullOrBlank()) add("SERVICE requires title")
            if (response.body.isNullOrBlank()) add("SERVICE requires body")
            if ((response.title?.let { it.codePointCount(0,it.length) } ?: 0) > 40) add("title too long")
            if ((response.body?.let { it.codePointCount(0,it.length) } ?: 0) > 140) add("body too long")
        }
    }

    fun allowedEvidenceIds(request: InferenceRequest): Set<String> = buildSet {
        request.observations.filter { it.availableAtEpochMs<=request.anchorTimeEpochMs }.forEach {add(it.observationId)}
        if(request.protocolVersion=="opstate-online-v2") request.receipts
            .filter {it.visibleAtEpochMs<=request.anchorTimeEpochMs && it.visibleAckFrameCount>=2}.forEach {add(it.receiptId)}
    }

    fun toDecision(response: InferenceResponse): ServiceDecision = ServiceDecision(
        requestId = response.requestId,
        decisionId = response.decisionId,
        label = response.decision,
        serviceMode = response.serviceMode,
        title = response.title,
        body = response.body,
        evidenceObservationIds = response.evidenceObservationIds,
        producedAtEpochMs = response.producedAtEpochMs,
        expiresAtEpochMs = response.expiresAtEpochMs,
    )
}
