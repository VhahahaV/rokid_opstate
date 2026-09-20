package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ServiceMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WireInferenceRequest(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("anchor_id") val anchorId: String,
    @SerialName("anchor_time_epoch_ms") val anchorTimeEpochMs: Long,
    @SerialName("current_asset_id") val currentAssetId: String,
    @SerialName("current_image_sha256") val currentImageSha256: String,
    val candidate: WireCandidate,
    val observations: List<WireObservation>,
    val receipts: List<WireReceipt>,
    @SerialName("prompt_hash") val promptHash: String,
    @SerialName("preprocess_hash") val preprocessHash: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("model_id") val modelId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("parser_hash") val parserHash: String? = null,
)

@Serializable
data class WireCandidate(
    @SerialName("object_handle") val objectHandle: String,
    @SerialName("episode_handle") val episodeHandle: String,
)

@Serializable
data class WireObservation(
    @SerialName("observation_id") val observationId: String,
    @SerialName("kind") val kind: String,
    @SerialName("occurred_at_epoch_ms") val occurredAtEpochMs: Long,
    @SerialName("available_at_epoch_ms") val availableAtEpochMs: Long,
    val content: String,
    @SerialName("asset_id") val assetId: String?,
    @SerialName("episode_handle") val episodeHandle: String?,
    @SerialName("material_revision") val materialRevision: Long?,
)

@Serializable
data class WireReceipt(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("decision_id") val decisionId: String,
    @SerialName("episode_handle") val episodeHandle: String,
    @SerialName("object_handle") val objectHandle: String,
    @SerialName("intent_fingerprint") val intentFingerprint: String,
    @SerialName("service_mode") val serviceMode: String,
    @SerialName("served_material_revision") val servedMaterialRevision: Long,
    @SerialName("visible_at_epoch_ms") val visibleAtEpochMs: Long,
    @SerialName("evidence_observation_ids") val evidenceObservationIds: Set<String>,
    @SerialName("visible_ack_frame_count") val visibleAckFrameCount: Int = 2,
    /** The card this receipt stands for. Without it a receipt cannot answer "have I said this already?". */
    val title: String? = null,
    val body: String? = null,
)

@Serializable
data class WireInferenceResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("decision_id") val decisionId: String,
    val decision: String,
    @SerialName("service_mode") val serviceMode: String? = null,
    val title: String? = null,
    val body: String? = null,
    @SerialName("evidence_observation_ids") val evidenceObservationIds: Set<String> = emptySet(),
    @SerialName("produced_at_epoch_ms") val producedAtEpochMs: Long,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long,
)

fun InferenceRequest.toWire() = WireInferenceRequest(
    protocolVersion, requestId, anchorId, anchorTimeEpochMs, currentAssetId, currentImageSha256,
    WireCandidate(candidate.objectHandle, candidate.episodeHandle),
    observations.map {
        WireObservation(
            observationId = it.observationId,
            kind = it.kind.name,
            occurredAtEpochMs = it.occurredAtEpochMs,
            availableAtEpochMs = it.availableAtEpochMs,
            content = it.content,
            assetId = it.assetId,
            episodeHandle = it.episodeHandle,
            materialRevision = it.materialRevision,
        )
    },
    receipts.map {
        WireReceipt(
            receiptId = it.receiptId,
            decisionId = it.decisionId,
            episodeHandle = it.episodeHandle,
            objectHandle = it.objectHandle,
            intentFingerprint = it.intentFingerprint,
            serviceMode = it.serviceMode.name,
            servedMaterialRevision = it.servedMaterialRevision,
            visibleAtEpochMs = it.visibleAtEpochMs,
            evidenceObservationIds = it.evidenceObservationIds,
            title = it.title,
            body = it.body,
        )
    },
    promptHash,
    preprocessHash,
    modelId,
    parserHash,
)

fun WireInferenceResponse.toDomain() = InferenceResponse(
    requestId = requestId,
    decisionId = decisionId,
    decision = DecisionLabel.valueOf(decision),
    serviceMode = serviceMode?.let(ServiceMode::valueOf),
    title = title,
    body = body,
    evidenceObservationIds = evidenceObservationIds,
    producedAtEpochMs = producedAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
)

@Serializable
data class WireServiceV2(val mode: String,val title: String,val body: String)
@Serializable
data class WireInferenceResponseV2(
    @SerialName("request_id") val requestId: String,
    @SerialName("decision_id") val decisionId: String,
    val decision: String,
    val service: WireServiceV2?,
    val confidence: Double?=null,
    @SerialName("evidence_refs") val evidenceRefs: List<String>,
    @SerialName("produced_at_epoch_ms") val producedAtEpochMs: Long,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long,
) {
    fun toDomain()=InferenceResponse(requestId,decisionId,DecisionLabel.valueOf(decision),service?.mode?.let(ServiceMode::valueOf),
        service?.title,service?.body,evidenceRefs.toSet(),producedAtEpochMs,expiresAtEpochMs)
}
