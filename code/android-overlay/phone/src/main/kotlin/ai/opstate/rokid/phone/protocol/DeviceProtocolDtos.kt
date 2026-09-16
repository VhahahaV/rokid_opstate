package ai.opstate.rokid.phone.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceEnvelope(
    @EncodeDefault
    @SerialName("protocol_version") val protocolVersion: String = "rokid-study-v4",
    @SerialName("message_id") val messageId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_epoch") val sessionEpoch: Long,
    @SerialName("trial_id") val trialId: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val type: String,
    @SerialName("sent_at_epoch_ms") val sentAtEpochMs: Long,
    @SerialName("expires_at_epoch_ms") val expiresAtEpochMs: Long? = null,
    val payload: Map<String, String> = emptyMap(),
)

@Serializable
data class ContextCardPayload(
    @SerialName("event_id") val eventId: String,
    val source: String,
    val title: String,
    val body: String,
    @SerialName("visible_for_ms") val visibleForMs: Long = 8_000L,
)

@Serializable
data class ServiceCardPayload(
    @SerialName("decision_id") val decisionId: String,
    @SerialName("service_mode") val serviceMode: String,
    val title: String,
    val body: String,
    @SerialName("evidence_badge") val evidenceBadge: String? = null,
    @SerialName("visible_for_ms") val visibleForMs: Long = 10_000L,
)
