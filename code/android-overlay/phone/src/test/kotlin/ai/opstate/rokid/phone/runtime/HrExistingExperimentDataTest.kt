package ai.opstate.rokid.phone.runtime

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.HrContextAssembler
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Uses the selected photographs and context from the actual 2026-09-23 phone experiment. */
class HrExistingExperimentDataTest {
    private val root=File("../../../").canonicalFile
    private val selection=File(root,"reports/latest/hr_experiment_selection_20260923.json")

    @Test fun twentyFourRealFramesAndFiftySixContextsStayCausal() {
        assumeTrue("local experiment selection is not tracked in Git",selection.isFile)
        val source=StudyWire.json.parseToJsonElement(selection.readText()).jsonObject
        assertEquals(35,source.getValue("historical_results_matched_to_real_photos").jsonPrimitive.int)
        val contexts=source.getValue("public_contexts").jsonArray.map {it.jsonObject}
        assertEquals(56,contexts.size)
        val clips=source.getValue("clips").jsonArray.map {it.jsonObject}
        assertEquals(12,clips.size)
        var frames=0
        var matchedOldResults=0
        for(clip in clips) {
            val store=CausalContextStore()
            for(row in contexts) {
                val at=row.getValue("at_epoch_ms").jsonPrimitive.long
                store.append(ObservationRecord(row.getValue("id").jsonPrimitive.content,
                    ObservationKind.SYNTHETIC_CONTEXT,at,at,
                    row.getValue("title").jsonPrimitive.content+" · "+row.getValue("body").jsonPrimitive.content))
            }
            matchedOldResults += clip.getValue("historical_results_for_review_only").jsonArray.size
            val pair=clip.getValue("frames").jsonArray.map {it.jsonObject}
            assertEquals(2,pair.size)
            for(frame in pair) {
                val at=frame.getValue("virtual_at_epoch_ms").jsonPrimitive.long
                val order=frame.getValue("order").jsonPrimitive.int
                val file=File(root,"reports/latest/two-day-history/"+frame.getValue("relative_path").jsonPrimitive.content)
                assertTrue("real photo missing $order",file.isFile)
                val bytes=file.readBytes()
                assertEquals("photo changed $order",frame.getValue("sha256").jsonPrimitive.content,
                    StudyWire.sha256(bytes))
                assertTrue("not a JPEG $order",bytes.size>4 && bytes[0]==0xff.toByte() &&
                    bytes[1]==0xd8.toByte() && bytes[bytes.lastIndex-1]==0xff.toByte() &&
                    bytes[bytes.lastIndex]==0xd9.toByte())
                val id="frame-$order"
                store.append(ObservationRecord(id,ObservationKind.IMAGE,at,at,"local real photograph",
                    assetId="asset-$order"))
                val context=HrContextAssembler(store).step(at,at,setOf(id),setOf(id))
                assertTrue(id in context.visibleIds)
                val visible=store.causalArchiveSnapshot(at,at).associateBy {it.observationId}
                assertTrue(context.visibleIds.all {visible[it]?.availableAtEpochMs?.let {whenAvailable->whenAvailable<=at}==true})
                assertFalse(context.visibleIds.any {it in setOf("D2-25","D2-26") && at<
                    contexts.first {row->row.getValue("id").jsonPrimitive.content==it}.getValue("at_epoch_ms").jsonPrimitive.long})
                frames++
            }
        }
        assertEquals(24,frames)
        assertTrue("historical service decisions not represented",matchedOldResults>=6)
        println("HR_EXISTING_EXPERIMENT frames=$frames contexts=${contexts.size} historical_decisions_in_clips=$matchedOldResults")
    }

    @Test fun actualAssignmentDeadlineIsInvisibleBeforeItsArrivalAndRetrievableAfter() {
        assumeTrue("local experiment selection is not tracked in Git",selection.isFile)
        val source=StudyWire.json.parseToJsonElement(selection.readText()).jsonObject
        val contexts=source.getValue("public_contexts").jsonArray.map {it.jsonObject}
        val deadline=contexts.single {it.getValue("id").jsonPrimitive.content=="D2-25"}
        val at=deadline.getValue("at_epoch_ms").jsonPrimitive.long
        val store=CausalContextStore()
        for(row in contexts) {
            val available=row.getValue("at_epoch_ms").jsonPrimitive.long
            store.append(ObservationRecord(row.getValue("id").jsonPrimitive.content,
                ObservationKind.SYNTHETIC_CONTEXT,available,available,
                row.getValue("title").jsonPrimitive.content+" · "+row.getValue("body").jsonPrimitive.content))
        }
        val query="网络作业 Assignment 1 PDF 9月28日 23:59"
        assertFalse(store.searchArchive(query,at-1,at-1).any {it.observationId=="D2-25"})
        assertTrue(store.searchArchive(query,at,at).any {it.observationId=="D2-25"})
    }
}
