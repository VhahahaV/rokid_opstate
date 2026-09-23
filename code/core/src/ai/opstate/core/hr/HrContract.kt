package ai.opstate.core.hr

/** The phone's History Retrieval turn. These types are internal and never go over StudyWire. */
enum class HrRoute { SILENT, SERVICE, RETRIEVE }
enum class HrCandidateKind { COMPARISON, OPEN_ITEM, DEADLINE, HANDOFF, CONFLICT }

data class HrCandidate(
    val kind: HrCandidateKind?,
    val whyNow: String?,
    val availableEvidence: List<String>,
    val missingFacts: List<String>,
    val nextAction: HrRoute,
)

data class HrVisualNote(val text: String, val sourceIds: List<String>)
data class HrService(val mode: String, val title: String, val body: String, val evidenceIds: List<String>)
data class HrStep(
    val note: HrVisualNote,
    val candidate: HrCandidate,
    val route: HrRoute,
    val query: String?,
    val service: HrService?,
)
data class HrFinal(val decision: HrRoute, val service: HrService?)

data class HrAgentOutput(
    val packetId: String,
    val producedAtEpochMs: Long,
    val title: String,
    val body: String,
    val evidenceIds: List<String>,
    /** Only a real visible acknowledgement can set this; attempted delivery does not. */
    val visiblyDelivered: Boolean,
)

object HrContract {
    private fun length(text: String) = text.codePointCount(0, text.length)

    fun checkStep(step: HrStep, visibleIds: Set<String>, freshImageIds: Set<String>) {
        require(step.candidate.nextAction == step.route) { "HR_ROUTE_MISMATCH" }
        require(step.candidate.availableEvidence.distinct().size == step.candidate.availableEvidence.size &&
            visibleIds.containsAll(step.candidate.availableEvidence)) { "HR_CANDIDATE_EVIDENCE_INVALID" }
        require(step.candidate.missingFacts.size <= 8 && step.candidate.missingFacts.all { it.isNotBlank() }) {
            "HR_MISSING_FACTS_INVALID"
        }
        require(step.note.sourceIds.distinct().size == step.note.sourceIds.size &&
            visibleIds.containsAll(step.note.sourceIds) && length(step.note.text) <= 240) { "HR_NOTE_INVALID" }
        if (step.note.text.isNotBlank()) {
            require(step.note.sourceIds.any { it in freshImageIds }) { "HR_NOTE_REQUIRES_FRESH_IMAGE" }
        } else require(step.note.sourceIds.isEmpty()) { "HR_EMPTY_NOTE_HAS_SOURCES" }
        when (step.route) {
            HrRoute.SILENT -> require(step.query == null && step.service == null) { "HR_SILENT_FIELDS" }
            HrRoute.RETRIEVE -> require(step.service == null && !step.query.isNullOrBlank() &&
                length(step.query) <= 160 && step.candidate.kind != null) { "HR_RETRIEVE_FIELDS" }
            HrRoute.SERVICE -> {
                require(step.query == null && step.candidate.kind != null && step.candidate.missingFacts.isEmpty()) {
                    "HR_SERVICE_CANDIDATE_INVALID"
                }
                checkService(requireNotNull(step.service) { "HR_SERVICE_MISSING" }, visibleIds)
                require(step.candidate.availableEvidence.containsAll(step.service.evidenceIds)) {
                    "HR_SERVICE_OUTSIDE_CANDIDATE"
                }
            }
        }
    }

    fun checkFinal(final: HrFinal, visibleIds: Set<String>) {
        require(final.decision != HrRoute.RETRIEVE) { "HR_SECOND_RETRIEVAL_FORBIDDEN" }
        if (final.decision == HrRoute.SILENT) require(final.service == null) { "HR_FINAL_SILENT_FIELDS" }
        else checkService(requireNotNull(final.service) { "HR_FINAL_SERVICE_MISSING" }, visibleIds)
    }

    fun checkService(service: HrService, visibleIds: Set<String>) {
        require(service.mode in setOf("AWARENESS", "DECISION", "PREPARATION", "EXECUTION") &&
            service.title.isNotBlank() && length(service.title) <= 40 &&
            service.body.isNotBlank() && length(service.body) <= 140) { "HR_SERVICE_TEXT_INVALID" }
        require(service.evidenceIds.size in 1..8 && service.evidenceIds.distinct().size == service.evidenceIds.size &&
            visibleIds.containsAll(service.evidenceIds)) { "HR_SERVICE_EVIDENCE_INVALID" }
    }
}
