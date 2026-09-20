package ai.opstate.core.model

/**
 * Pure Kotlin domain models shared by the scheduler, causal store and tests.
 * Android- and Rokid-specific code adapts into these types at the boundary.
 */

enum class ServiceMode { AWARENESS, DECISION, PREPARATION, EXECUTION }
enum class DecisionLabel { SERVICE, NO_SERVICE }
enum class EpisodeLifecycle {
    OPEN_UNMET,
    WAITING_EXTERNAL,
    COMPLETED,
    CANCELLED,
    SUPERSEDED,
    BACKGROUND_ONLY,
    UNKNOWN,
}

enum class ObservationKind {
    IMAGE,
    SYNTHETIC_CONTEXT,
    SENSOR_SUMMARY,
    USER_ACTION,
    ACTION_RECEIPT,
}

enum class CaptureReason {
    PERIODIC_BASELINE,
    MAX_STALENESS,
    MATERIAL_CHANGE,
    TRANSITION_SETTLED,
    ROUTINE_WINDOW,
    DEADLINE_WINDOW,
    MANUAL_DEBUG,
    QUALITY_RETRY,
    POST_SERVICE_AUDIT,
}

enum class AnchorTriggerFamily {
    PERIODIC_COVERAGE,
    MATERIAL_CHANGE,
    TRANSITION_SETTLED,
    DEADLINE_APPROACH,
    STABLE_DWELL,
    ROUTINE_WINDOW,
    VISUAL_NOVELTY,
    POST_SERVICE_AUDIT,
    MANUAL_DEBUG,
}

enum class AnchorBlocker {
    NO_CANDIDATE_EPISODE,
    IMAGE_INVALID,
    IMAGE_TOO_SIMILAR,
    MOTION_HIGH,
    SPEECH_ACTIVE,
    RECENT_INTERACTION,
    DISPLAY_BUSY,
    INFERENCE_IN_FLIGHT,
    CAPTURE_COOLDOWN,
    EVALUATION_COOLDOWN,
    EPISODE_COOLDOWN,
    ROUTINE_COOLDOWN,
    DEVICE_NOT_READY,
    THERMAL_OR_BATTERY,
}

enum class DeliveryStatus {
    NOT_ATTEMPTED,
    SUPPRESSED_BY_RECEIPT,
    INVALID_EVIDENCE,
    STALE_DECISION,
    TRANSPORT_FAILED,
    DISPLAY_VISIBLE,
    DISPLAY_FAILED,
    NO_SERVICE_SILENCE,
}

data class Quaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    fun normalized(): Quaternion {
        val n = kotlin.math.sqrt(x * x + y * y + z * z + w * w)
        return if (n <= 1e-12) Quaternion(0.0, 0.0, 0.0, 1.0)
        else Quaternion(x / n, y / n, z / n, w / n)
    }
}

data class Vector3(val x: Double, val y: Double, val z: Double) {
    fun magnitude(): Double = kotlin.math.sqrt(x * x + y * y + z * z)
}

data class SensorSample(
    val elapsedRealtimeNanos: Long,
    val rotation: Quaternion?,
    val linearAccelerationMps2: Vector3?,
    val angularVelocityDegPerSec: Vector3? = null,
)

data class MotionFeatures(
    val windowStartElapsedMs: Long,
    val windowEndElapsedMs: Long,
    val sampleCount: Int,
    val durationMs: Long,
    val angularVelocityRmsDegPerSec: Double,
    val angularVelocityP95DegPerSec: Double,
    val linearAccelerationRmsMps2: Double,
    val linearAccelerationP95Mps2: Double,
    val stableRatio: Double,
    val isStable: Boolean,
    val isMoving: Boolean,
    val valid: Boolean,
)

data class InteractionSnapshot(
    val speechRatio: Double = 0.0,
    val voiceSessionActive: Boolean = false,
    val millisSinceLastUserInteraction: Long = Long.MAX_VALUE,
    val contextCardVisible: Boolean = false,
    val serviceCardVisible: Boolean = false,
) {
    val displayBusy: Boolean get() = contextCardVisible || serviceCardVisible
}

enum class ImageQualityStatus { VALID, TOO_DARK, TOO_BRIGHT, BLURRY, CORRUPTED }

data class ImageQuality(
    val valid: Boolean,
    val meanLuma: Double,
    val blurScore: Double,
    val noveltyScore: Double,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sha256: String,
    val status: ImageQualityStatus=if(valid) ImageQualityStatus.VALID else ImageQualityStatus.CORRUPTED,
    val darkPixelRatio: Double?=null,
    val brightPixelRatio: Double?=null,
)

data class ObservationRecord(
    val observationId: String,
    val kind: ObservationKind,
    val occurredAtEpochMs: Long,
    val availableAtEpochMs: Long,
    val content: String,
    val assetId: String? = null,
    val episodeHandle: String? = null,
    val materialRevision: Long? = null,
)

data class CandidateEpisode(
    /** Opaque identity supplied to the target model. */
    val episodeHandle: String,
    val objectHandle: String,
    /** Stable intent identity. This value may be hashed before transport. */
    val intentFingerprint: String,
    val serviceMode: ServiceMode,
    /** Scheduler eligibility is separate from semantic lifecycle. */
    val schedulerEligible: Boolean = true,
    val lifecycle: EpisodeLifecycle = EpisodeLifecycle.UNKNOWN,
    val materialRevision: Long = 0L,
    val deadlineEpochMs: Long? = null,
    val routineWindowActive: Boolean = false,
    val triggerKeys: Set<String> = emptySet(),
)

data class CapturePlan(
    val due: Boolean,
    val reasons: Set<CaptureReason>,
    val earliestAtElapsedMs: Long,
    val forceEvenWithoutCandidate: Boolean = false,
)

data class SchedulerSnapshot(
    val nowEpochMs: Long,
    val nowElapsedMs: Long,
    val lastCaptureElapsedMs: Long?,
    val lastEvaluationElapsedMs: Long?,
    val lastMotionSeenElapsedMs: Long?,
    val lastMaterialChangeEpochMs: Long?,
    val materialChangePending: Boolean,
    val motion: MotionFeatures?,
    val interaction: InteractionSnapshot,
    val imageQuality: ImageQuality?,
    val candidateEpisodes: List<CandidateEpisode>,
    val inferenceInFlight: Boolean,
    val deviceReady: Boolean,
    val thermalOrBatteryBlocked: Boolean,
    val visualTriggerKeys: Set<String> = emptySet(),
    val lastBaselineCaptureElapsedMs: Long? = lastCaptureElapsedMs,
    val deadlineCapturePending: Boolean = false,
    val routineCapturePending: Boolean = false,
    val postServiceAuditPending: Boolean = false,
    val materialChangedEpisodeHandles: Set<String>? = null,
)

data class AnchorCandidate(
    val anchorId: String,
    val anchorTimeEpochMs: Long,
    val triggerObservationId: String,
    val currentAssetId: String,
    val candidateObjectHandle: String,
    val candidateEpisodeHandle: String,
    val triggerFamilies: Set<AnchorTriggerFamily>,
    val score: Double,
    /** Scheduler-only diagnostics. Must never enter target-model input. */
    val schedulerDiagnostics: Map<String, String>,
)

data class AnchorSchedulingResult(
    val emitted: List<AnchorCandidate>,
    val blockers: Set<AnchorBlocker>,
    val scoreByEpisode: Map<String, Double>,
    val blockersByEpisode: Map<String, Set<AnchorBlocker>> = emptyMap(),
)

/** Runtime evaluation identity produced by the online detector; it carries no prescribed task target. */
data class PacketEvaluation(
    val packetId: String,
    val snapshotAtEpochMs: Long,
    val triggerObservationId: String,
    val currentAssetId: String,
    val triggerTypes: Set<String>,
)

data class ServiceDecision(
    val requestId: String,
    val decisionId: String,
    val label: DecisionLabel,
    val serviceMode: ServiceMode? = null,
    val title: String? = null,
    val body: String? = null,
    val evidenceObservationIds: Set<String> = emptySet(),
    val producedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

data class ActionReceipt(
    val receiptId: String,
    val decisionId: String,
    val episodeHandle: String,
    val objectHandle: String,
    val intentFingerprint: String,
    val serviceMode: ServiceMode,
    val servedMaterialRevision: Long,
    val visibleAtEpochMs: Long,
    val evidenceObservationIds: Set<String>,
    /**
     * What the card said. A receipt is the only record of what the wearer has already been shown,
     * so a receipt that does not carry the text cannot support any judgement about a repeat - which
     * is the judgement the whole receipt contract exists for.
     */
    val title: String? = null,
    val body: String? = null,
)

data class DeliveryOutcome(
    val decisionId: String,
    val status: DeliveryStatus,
    val reason: String,
    val receipt: ActionReceipt? = null,
)
