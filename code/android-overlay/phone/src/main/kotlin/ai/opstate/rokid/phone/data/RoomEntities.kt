package ai.opstate.rokid.phone.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Keep images in app-private files; Room stores metadata and hashes only. */
@Entity(tableName = "study_session")
data class StudySessionEntity(
    @PrimaryKey val sessionId: String,
    val sessionEpoch: Long,
    val participantAlias: String,
    val scenarioId: String,
    val conditionCode: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val appVersion: String,
    val configHash: String,
)

@Entity(tableName = "context_event", primaryKeys = ["sessionId", "eventId"], indices = [Index("sessionId"), Index("availableAtEpochMs")])
data class ContextEventEntity(
    val eventId: String,
    val sessionId: String,
    val occurredAtEpochMs: Long,
    val availableAtEpochMs: Long,
    val kind: String,
    val episodeHandle: String?,
    val materialRevision: Long?,
    val publicPayloadJson: String,
)

@Entity(tableName = "observation", primaryKeys = ["sessionId", "observationId"], indices = [Index("sessionId"), Index("availableAtEpochMs")])
data class ObservationEntity(
    val observationId: String,
    val sessionId: String,
    val captureId: String?,
    val occurredAtEpochMs: Long,
    val availableAtEpochMs: Long,
    val kind: String,
    val assetId: String?,
    val localRelativePath: String?,
    val sha256: String?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int?,
    val qualityJson: String?,
)

@Entity(tableName = "sensor_window", primaryKeys = ["sessionId", "windowId"], indices = [Index("sessionId"), Index("windowEndElapsedMs")])
data class SensorWindowEntity(
    val windowId: String,
    val sessionId: String,
    val windowStartElapsedMs: Long,
    val windowEndElapsedMs: Long,
    val sampleCount: Int,
    val motionJson: String,
    val speechRatio: Double?,
    val rawAudioStored: Boolean = false,
)

@Entity(tableName = "episode", primaryKeys = ["sessionId", "episodeHandle"], indices = [Index("sessionId")])
data class EpisodeEntity(
    val episodeHandle: String,
    val sessionId: String,
    val objectHandle: String,
    val intentFingerprint: String,
    val serviceMode: String,
    val schedulerEligible: Boolean,
    val materialRevision: Long,
    val schedulerPrivateJson: String,
)

@Entity(tableName = "anchor_candidate", primaryKeys = ["sessionId", "anchorId"], indices = [Index("sessionId"), Index("anchorTimeEpochMs")])
data class AnchorCandidateEntity(
    val anchorId: String,
    val sessionId: String,
    val anchorTimeEpochMs: Long,
    val triggerObservationId: String,
    val currentAssetId: String,
    val objectHandle: String,
    val episodeHandle: String,
    val score: Double,
    val triggerFamiliesJson: String,
    val schedulerDiagnosticsPrivateJson: String,
)

@Entity(tableName = "inference_request", primaryKeys = ["sessionId", "requestId"], indices = [Index("sessionId"), Index("anchorId")])
data class InferenceRequestEntity(
    val requestId: String,
    val sessionId: String,
    val anchorId: String,
    val requestedAtEpochMs: Long,
    val requestJson: String,
    val promptHash: String,
    val preprocessHash: String,
)

@Entity(tableName = "decision", primaryKeys = ["sessionId", "decisionId"], indices = [Index("sessionId"), Index("anchorId")])
data class DecisionEntity(
    val decisionId: String,
    val sessionId: String,
    val anchorId: String,
    val requestId: String,
    val rawResponseJson: String,
    val rawLabel: String,
    val producedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Entity(tableName = "delivery", primaryKeys = ["sessionId", "deliveryId"], indices = [Index("sessionId"), Index("decisionId")])
data class DeliveryEntity(
    val deliveryId: String,
    val sessionId: String,
    val decisionId: String,
    val finalStatus: String,
    val reason: String,
    val sentAtEpochMs: Long?,
    val visibleAtEpochMs: Long?,
    val dismissedAtEpochMs: Long?,
)

@Entity(tableName = "action_receipt", primaryKeys = ["sessionId", "receiptId"], indices = [Index("sessionId"), Index("episodeHandle")])
data class ActionReceiptEntity(
    val receiptId: String,
    val sessionId: String,
    val decisionId: String,
    val episodeHandle: String,
    val objectHandle: String,
    val intentFingerprint: String,
    val serviceMode: String,
    val servedMaterialRevision: Long,
    val visibleAtEpochMs: Long,
    val evidenceIdsJson: String,
)

@Entity(tableName = "audit_event", primaryKeys = ["sessionId", "auditId"], indices = [Index("sessionId"), Index("sequenceNumber")])
data class AuditEventEntity(
    val auditId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val eventAtEpochMs: Long,
    val payloadHash: String,
    val previousHash: String,
    val chainHash: String,
)
