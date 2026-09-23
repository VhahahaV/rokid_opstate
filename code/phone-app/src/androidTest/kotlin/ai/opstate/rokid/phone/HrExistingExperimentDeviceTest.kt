package ai.opstate.rokid.phone

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.HrAgentOutput
import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.DirectModelClient
import ai.opstate.rokid.phone.runtime.ProactivityEffort
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Short, bounded real-model A/B on already collected phone photographs and the curated 56-row context. */
@RunWith(AndroidJUnit4::class)
class HrExistingExperimentDeviceTest {
    @Test fun compareExistingExperimentFrames()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("hr_existing_experiment")=="true")
        val context=instrumentation.targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val folder=requireNotNull(context.getExternalFilesDir(null))
        val input=File(folder,"hr_experiment_selection.json")
        assertTrue("HR_EXPERIMENT_INPUT_MISSING",input.isFile)
        val manifest=StudyWire.json.parseToJsonElement(input.readText()).jsonObject
        val clips=manifest.getValue("clips").jsonArray.map {it.jsonObject}
        val contexts=manifest.getValue("public_contexts").jsonArray.map {it.jsonObject}
        val clipLimit=args.getString("hr_clips","2")!!.toInt().coerceIn(1,12)
        val clipFrom=args.getString("hr_clip_from","1")!!.toInt().coerceIn(1,12)
        val armChoice=args.getString("hr_arm","both")!!
        require(armChoice in setOf("old","hr","both")) {"HR_ARM_INVALID"}
        val effort=ProactivityEffort.valueOf(args.getString("hr_effort",ProactivityEffort.RESTRAINED.name)!!)
        val config=app.modelConfig()
        check(app.credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
        val results=mutableListOf<JsonObject>()
        val started=SystemClock.elapsedRealtime()
        var modelCalls=0
        var stoppedByBudget=false
        val source=Regex("^source/files/sessions/([A-Za-z0-9_-]+)/images/([A-Za-z0-9_-]+\\.jpg)$")
        for(clip in clips.drop(clipFrom-1).take(clipLimit)) {
            val frames=clip.getValue("frames").jsonArray.map {it.jsonObject}
            for(arm in if(armChoice=="both") listOf("old","hr") else listOf(armChoice)) {
                val history=CausalContextStore()
                val outputHistory=mutableListOf<HrAgentOutput>()
                val contextIds=linkedSetOf<String>()
                for(row in contexts) {
                    val id=row.getValue("id").jsonPrimitive.content
                    val at=row.getValue("at_epoch_ms").jsonPrimitive.long
                    contextIds += id
                    history.append(ObservationRecord(id,ObservationKind.SYNTHETIC_CONTEXT,at,at,
                        row.getValue("title").jsonPrimitive.content+" · "+row.getValue("body").jsonPrimitive.content))
                }
                var virtualNow=frames.first().getValue("virtual_at_epoch_ms").jsonPrimitive.long
                val client=DirectModelClient.create(context,config,key={app.credentials.modelApiKey(config.credentialScope())},
                    useHr=arm=="hr",clockEpochMs={virtualNow},proactivityEffort=if(arm=="hr") effort else ProactivityEffort.RESTRAINED)
                val contract=client.contract()
                for(frame in frames) {
                    if(modelCalls>=72 || SystemClock.elapsedRealtime()-started>=15*60_000L) {
                        stoppedByBudget=true;break
                    }
                    val order=frame.getValue("order").jsonPrimitive.int
                    virtualNow=frame.getValue("virtual_at_epoch_ms").jsonPrimitive.long
                    val relative=frame.getValue("relative_path").jsonPrimitive.content
                    val match=requireNotNull(source.matchEntire(relative)) {"HR_SOURCE_PATH_INVALID"}
                    val jpeg=app.journal.localImage(match.groupValues[1],"images/${match.groupValues[2]}").readBytes()
                    check(StudyWire.sha256(jpeg)==frame.getValue("sha256").jsonPrimitive.content) {"HR_SOURCE_HASH_MISMATCH"}
                    val observationId="frame-$order"
                    val assetId="asset-$order"
                    history.append(ObservationRecord(observationId,ObservationKind.IMAGE,virtualNow,virtualNow,
                        "Real collected egocentric image from local phone experiment.",assetId=assetId))
                    val visible=history.historyRetrievalDetailed(virtualNow,setOf(observationId),
                        pinnedIds=contextIds).first
                    val selected=if(arm=="old") visible else listOfNotNull(visible.singleOrNull {it.observationId==observationId})
                    val request=InferenceRequest(contract.protocolVersion,"hr-replay-${arm}-${UUID.randomUUID()}",
                        "packet-$order",virtualNow,assetId,StudyWire.sha256(jpeg),
                        ModelCandidate("stream","episode-$order"),selected.map {row->ModelObservation(
                            row.observationId,row.kind,row.occurredAtEpochMs,row.availableAtEpochMs,row.content,
                            row.assetId,row.episodeHandle,row.materialRevision)},emptyList(),
                        contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash,
                        setOf("VISUAL"),listOf(observationId),0,false)
                    val began=SystemClock.elapsedRealtime()
                    var callsForFrame=0
                    var normalizedOutput: String?=null
                    val outcome=runCatching {
                        if(arm=="hr") client.evaluateHrTurn(request,jpeg,emptyMap(),history,outputHistory) {raw->
                            if(raw.format.startsWith("PROVIDER_")) callsForFrame++
                            if(raw.format=="HR_STEP_NORMALIZED" || raw.format=="HR_FINAL_NORMALIZED") normalizedOutput=raw.body
                        } else {
                            val decision=client.evaluateWithHistory(request,jpeg,emptyMap()) {raw->
                                if(raw.format.startsWith("PROVIDER_")) callsForFrame++
                            }
                            ai.opstate.rokid.phone.runtime.HrTurnOutcome(request,decision,
                                ai.opstate.core.hr.HrStep(ai.opstate.core.hr.HrVisualNote("",emptyList()),
                                    ai.opstate.core.hr.HrCandidate(null,null,emptyList(),emptyList(),ai.opstate.core.hr.HrRoute.SILENT),
                                    ai.opstate.core.hr.HrRoute.SILENT,null,null),null,emptyList())
                        }
                    }
                    modelCalls+=callsForFrame
                    val response=outcome.getOrNull()?.response
                    if(arm=="hr") outcome.getOrNull()?.step?.note?.takeIf {it.text.isNotBlank()}?.let {note->
                        history.append(ObservationRecord("note-$order",ObservationKind.MODEL_NOTE,
                            virtualNow,virtualNow,note.text),virtualNow,note.sourceIds.toSet())
                    }
                    if(arm=="hr" && response?.decision==DecisionLabel.SERVICE) outputHistory+=HrAgentOutput(
                        "packet-$order",virtualNow,response.title.orEmpty(),response.body.orEmpty(),
                        response.evidenceObservationIds.toList(),false)
                    results+=StudyWire.payload(
                        "clip" to clip.getValue("clip"),"arm" to arm,"order" to order,
                        "photo_sha256" to frame.getValue("sha256"),"virtual_at_epoch_ms" to virtualNow,
                        "model_calls" to callsForFrame,"latency_ms" to (SystemClock.elapsedRealtime()-began),
                        "decision" to response?.decision?.name,"title" to response?.title,"body" to response?.body,
                        "evidence_ids" to JsonArray(response?.evidenceObservationIds.orEmpty().sorted().map(::JsonPrimitive)),
                        "step_route" to if(arm=="hr") outcome.getOrNull()?.step?.route?.name else null,
                        "candidate_kind" to if(arm=="hr") outcome.getOrNull()?.step?.candidate?.kind?.name else null,
                        "why_now" to if(arm=="hr") outcome.getOrNull()?.step?.candidate?.whyNow else null,
                        "visual_note" to if(arm=="hr") outcome.getOrNull()?.step?.note?.text else null,
                        "final_decision" to if(arm=="hr") outcome.getOrNull()?.final?.decision?.name else null,
                        "retrieved_ids" to JsonArray(outcome.getOrNull()?.retrievedIds.orEmpty().map(::JsonPrimitive)),
                        "timing_suppression" to outcome.getOrNull()?.timingSuppression,
                        "forced_model_pass" to outcome.getOrNull()?.forcedModelPass,
                        "error" to outcome.exceptionOrNull()?.let {it.message ?: it.javaClass.simpleName},
                        "failed_normalized_output" to if(outcome.isFailure) normalizedOutput else null)
                    instrumentation.sendStatus(1,Bundle().apply {
                        putInt("hr_experiment_rows",results.size);putInt("hr_experiment_model_calls",modelCalls)
                    })
                }
                if(stoppedByBudget) break
            }
            if(stoppedByBudget) break
        }
        val result=StudyWire.payload("schema" to "hr_phone_real_model_ab_v1",
            "input_sha256" to StudyWire.sha256(input.readBytes()),"model_id" to config.modelId,
            "clock" to "VIRTUAL_SOURCE_TIME_REAL_PHONE_PROVIDER",
            "delivery" to "NOT_ATTEMPTED_REPLAY",
            "proactivity_effort" to effort.name,
            "first_clip" to clipFrom,"selected_clips" to clipLimit,"arm_choice" to armChoice,
            "completed_rows" to results.size,"model_calls" to modelCalls,
            "stopped_by_budget" to stoppedByBudget,"rows" to JsonArray(results))
        File(folder,"hr_existing_experiment_result.json").writeText(Json {prettyPrint=true}
            .encodeToString(JsonObject.serializer(),result))
        println("HR_EXISTING_EXPERIMENT completed_rows=${results.size} model_calls=$modelCalls budget_stop=$stoppedByBudget")
    }
}
