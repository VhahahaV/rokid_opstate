package ai.opstate.rokid.phone.runtime

import ai.opstate.core.hr.HrService
import ai.opstate.core.hr.HrTimingGuard
import ai.opstate.core.hr.HrVisualNote
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Rechecks production gating against the model's captured responses on the real local photo set. */
class HrCapturedOutputGateTest {
    @Test fun terminalLogCardIsBlockedButRelevantDocumentCardRemainsEligible() {
        val root=File("../../../").canonicalFile
        assumeTrue("captured local model output is not tracked in Git",File(root,
            "reports/latest/hr_existing_experiment_hr_guard_full_20260923.json").isFile && File(root,
            "reports/latest/hr_experiment_selection_20260923.json").isFile)
        val capture=StudyWire.json.parseToJsonElement(File(root,
            "reports/latest/hr_existing_experiment_hr_guard_full_20260923.json").readText()).jsonObject
        val selection=StudyWire.json.parseToJsonElement(File(root,
            "reports/latest/hr_experiment_selection_20260923.json").readText()).jsonObject
        val contexts=selection.getValue("public_contexts").jsonArray.map {it.jsonObject}
            .associateBy {it.getValue("id").jsonPrimitive.content}
        fun verdict(clip: Int,order: Int): String? {
            val row=capture.getValue("rows").jsonArray.map {it.jsonObject}.single {
                it.getValue("clip").jsonPrimitive.int==clip && it.getValue("order").jsonPrimitive.int==order
            }
            assertEquals("SERVICE",row.getValue("decision").jsonPrimitive.content)
            val refs=row.getValue("evidence_ids").jsonArray.map {it.jsonPrimitive.content}
            val records=refs.mapNotNull {id->contexts[id]?.let {source->ObservationRecord(id,
                ObservationKind.SYNTHETIC_CONTEXT,source.getValue("at_epoch_ms").jsonPrimitive.long,
                source.getValue("at_epoch_ms").jsonPrimitive.long,
                source.getValue("title").jsonPrimitive.content+" · "+source.getValue("body").jsonPrimitive.content)} }
            return HrTimingGuard.visualTaskMismatch(
                HrVisualNote(row.getValue("visual_note").jsonPrimitive.content,listOf("frame-$order")),
                records,setOf("frame-$order"),emptySet(),HrService("PREPARATION",
                    row.getValue("title").jsonPrimitive.content,row.getValue("body").jsonPrimitive.content,refs),
                row.getValue("virtual_at_epoch_ms").jsonPrimitive.long)
        }
        assertEquals("HR_VISUAL_TASK_MISMATCH",verdict(3,205))
        assertNull(verdict(5,1182))
    }
}
