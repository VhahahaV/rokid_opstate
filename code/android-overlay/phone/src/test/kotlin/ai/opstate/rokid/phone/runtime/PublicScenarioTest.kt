package ai.opstate.rokid.phone.runtime

import ai.opstate.rokid.phone.scenario.decodePublicScenario
import org.junit.Assert.*
import org.junit.Test

class PublicScenarioTest {
    private val valid="""{"schema_version":"study_scenario_v2","scenario_id":"fixture","title":"Public scenario",
        "participant_visible_events":[],"candidate_episodes":[],"operator_setup":{"required_props":[],"participant_task":"Read a public message mentioning gold.","reset":"Reset"}}"""
    @Test fun ordinaryQuotedWordsDoNotBecomePrivateFields() {assertEquals("fixture",decodePublicScenario(valid).scenarioId)}
    @Test fun nestedAuthoringFieldsCannotBeHiddenInAnOtherwisePublicScenario() {
        for(key in listOf("gold","expected_decision","private_lifecycle","why_now","negative_type","teacher_receipts")) {
            val injected=valid.replace("\"required_props\":[]","\"required_props\":[],\"$key\":\"private\"")
            assertEquals("SCENARIO_CONTAINS_PRIVATE_FIELDS",runCatching {decodePublicScenario(injected)}.exceptionOrNull()?.message)
        }
    }
}
