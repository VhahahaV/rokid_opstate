package ai.opstate.rokid.phone.runtime

import ai.opstate.core.hr.*
import kotlinx.serialization.json.*

/** Reject shape errors before the model's answer can become a delivery decision. */
object HrWireParser {
    private fun ids(value: JsonElement) = value.jsonArray.map { item ->
        require(item is JsonPrimitive && item.isString) { "HR_ID_NOT_STRING" }
        item.content
    }
    private fun service(value: JsonElement?): HrService? {
        if (value == null || value == JsonNull) return null
        val obj = value.jsonObject
        require(obj.keys == setOf("mode", "title", "body", "evidence_ids")) { "HR_SERVICE_FIELDS" }
        return HrService(obj.getValue("mode").jsonPrimitive.content,
            obj.getValue("title").jsonPrimitive.content, obj.getValue("body").jsonPrimitive.content,
            ids(obj.getValue("evidence_ids")))
    }

    fun step(root: JsonObject): HrStep {
        require(root.keys == setOf("observation_note", "candidate", "memory_ops", "route", "query", "service")) {
            "HR_STEP_FIELDS"
        }
        require(root.getValue("memory_ops").jsonArray.isEmpty()) { "HR_MEMORY_OPS_FORBIDDEN" }
        val candidate = root.getValue("candidate").jsonObject
        require(candidate.keys == setOf("candidate_kind", "why_now", "available_evidence", "missing_facts", "next_action")) {
            "HR_CANDIDATE_FIELDS"
        }
        val kind = candidate.getValue("candidate_kind").takeUnless { it == JsonNull }?.jsonPrimitive?.content
            ?.let(HrCandidateKind::valueOf)
        val why = candidate.getValue("why_now").takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val route = HrRoute.valueOf(root.getValue("route").jsonPrimitive.content)
        val query = root.getValue("query").takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val note=when(val value=root.getValue("observation_note")) {
            is JsonObject -> {
                require(value.keys==setOf("source_ids","text")) { "HR_NOTE_FIELDS" }
                val text=value.getValue("text").jsonPrimitive.content
                val sources=ids(value.getValue("source_ids"))
                if(text.isBlank() && sources.isNotEmpty()) {
                    // A silent reply sometimes keeps an image id after clearing the note. It makes no
                    // claim and cannot be cited later, so discard the orphaned attribution only here.
                    require(route==HrRoute.SILENT && root["query"]==JsonNull && root["service"]==JsonNull) {
                        "HR_EMPTY_NOTE_HAS_SOURCES"
                    }
                    HrVisualNote("",emptyList())
                } else HrVisualNote(text,sources)
            }
            // Flash occasionally emits a descriptive string here even with the JSON schema. A silent
            // answer can safely discard that un-attributed note; an action cannot borrow it as evidence.
            is JsonPrimitive -> {
                require(value.isString && route==HrRoute.SILENT && root["query"]==JsonNull && root["service"]==JsonNull) {
                    "HR_NOTE_FIELDS"
                }
                HrVisualNote("",emptyList())
            }
            else -> error("HR_NOTE_FIELDS")
        }
        return HrStep(note,
            HrCandidate(kind, why, ids(candidate.getValue("available_evidence")),
                ids(candidate.getValue("missing_facts")),
                HrRoute.valueOf(candidate.getValue("next_action").jsonPrimitive.content)),
            route, query, service(root["service"]))
    }

    fun final(root: JsonObject): HrFinal {
        require(root.keys == setOf("decision", "service")) { "HR_FINAL_FIELDS" }
        return HrFinal(HrRoute.valueOf(root.getValue("decision").jsonPrimitive.content), service(root["service"]))
    }
}
