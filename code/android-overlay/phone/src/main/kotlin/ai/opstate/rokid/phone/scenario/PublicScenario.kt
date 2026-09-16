package ai.opstate.rokid.phone.scenario

import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*

private val authoringKeys=setOf("gold","expected_decision","private_gold_file","private_lifecycle","why_now","negative_type","teacher_receipts","scheduler_diagnostics_private")
private fun containsAuthoringFields(element: JsonElement): Boolean=when(element) {
    is JsonObject->element.keys.any {it in authoringKeys} || element.values.any(::containsAuthoringFields)
    is JsonArray->element.any(::containsAuthoringFields)
    else->false
}
fun decodePublicScenario(text: String): StudyScenarioDto {
    val element=StudyWire.json.parseToJsonElement(text)
    require(!containsAuthoringFields(element)) {"SCENARIO_CONTAINS_PRIVATE_FIELDS"}
    return StudyWire.json.decodeFromJsonElement<StudyScenarioDto>(element)
}
