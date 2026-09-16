package ai.opstate.rokid.phone.scenario

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StudyScenarioDto(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("scenario_id") val scenarioId: String,
    val title: String,
    @SerialName("participant_visible_events") val events: List<ScenarioEventDto>,
    @SerialName("candidate_episodes") val episodes: List<ScenarioEpisodeDto>,
    @SerialName("operator_setup") val operatorSetup: OperatorSetupDto,
)

@Serializable
data class ScenarioEventDto(
    @SerialName("event_id") val eventId: String,
    val kind: String,
    @SerialName("occurred_at_offset_ms") val occurredAtOffsetMs: Long,
    @SerialName("available_at_offset_ms") val availableAtOffsetMs: Long,
    @SerialName("episode_handle") val episodeHandle: String? = null,
    @SerialName("material_revision") val materialRevision: Long? = null,
    @SerialName("public_text") val publicText: String,
    @SerialName("runtime_delivery") val runtimeDelivery: String = "SHOW_CONTEXT_CARD",
    @SerialName("material_change_for_scheduler") val materialChangeForScheduler: Boolean = true,
)

@Serializable
data class ScenarioEpisodeDto(
    @SerialName("object_handle") val objectHandle: String,
    @SerialName("episode_handle") val episodeHandle: String,
    @SerialName("intent_fingerprint") val intentFingerprint: String,
    @SerialName("service_mode") val serviceMode: String,
    @SerialName("scheduler_eligible") val schedulerEligible: Boolean,
    @SerialName("material_revision") val materialRevision: Long,
    @SerialName("deadline_offset_ms") val deadlineOffsetMs: Long? = null,
    @SerialName("routine_window_active") val routineWindowActive: Boolean = false,
    @SerialName("scheduler_trigger_keys") val schedulerTriggerKeys: Set<String> = emptySet(),
)

@Serializable
data class OperatorSetupDto(
    @SerialName("required_props") val requiredProps: List<String>,
    @SerialName("participant_task") val participantTask: String,
    val reset: String,
)
