package ai.opstate.rokid.phone.runtime

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.*
import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.inference.ModelObservation
import ai.opstate.core.model.DecisionLabel
import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord
import ai.opstate.core.model.ServiceMode
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface HrStageProvider {
    suspend fun step(envelope: JsonObject): HrStep
    suspend fun final(envelope: JsonObject): HrFinal
}

data class HrTurnOutcome(
    val request: InferenceRequest,
    val response: InferenceResponse,
    val step: HrStep,
    val final: HrFinal?,
    val retrievedIds: List<String>,
    val timingSuppression: String?=null,
    /** A second model stage wrote the card after the first decision was silent or rejected. */
    val forcedModelPass: Boolean=false,
)

/** One immutable world snapshot, one STEP and at most one local RETRIEVE/FINAL. */
class HrTurnRunner(
    private val history: CausalContextStore,
    private val provider: HrStageProvider,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val retriever: HrArchiveRetriever=PhoneLexicalArchiveRetriever(history),
    private val effort: ProactivityEffort=ProactivityEffort.RESTRAINED,
) {
    private fun currentRecord(request: InferenceRequest): ModelObservation? {
        if(request.currentAssetId=="NO_IMAGE") return null
        val matches=request.observations.filter {it.assetId==request.currentAssetId && it.kind==ObservationKind.IMAGE}
        return matches.firstOrNull {it.observationId in request.packetNewEventIds}
            ?: matches.maxWithOrNull(compareBy<ModelObservation> {it.availableAtEpochMs}.thenBy {it.observationId})
            ?: error("HR_CURRENT_IMAGE_INVALID")
    }
    private fun localTime(epochMs: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Shanghai")))
    suspend fun run(request: InferenceRequest, agentHistory: List<HrAgentOutput>, ttlMs: Long): HrTurnOutcome {
        require(nowEpochMs()-request.anchorTimeEpochMs < 30_000L) { "HR_PACKET_STALE" }
        val currentId = currentRecord(request)?.observationId
        val assembler = HrContextAssembler(history,retriever)
        val stepContext = assembler.step(request.anchorTimeEpochMs, nowEpochMs(),
            request.packetNewEventIds.toSet(),listOfNotNull(currentId).toSet())
        // Live glasses use MANUAL or CVTT_* trigger names. Freshness belongs to the observation in
        // this packet, not to the trigger's spelling; a reused historical image is not a new record.
        val fresh = listOfNotNull(currentId?.takeIf { it in request.packetNewEventIds }).toSet()
        val outputs = agentHistory.filter { it.producedAtEpochMs <= request.anchorTimeEpochMs }
        val step = provider.step(envelope(request, stepContext, outputs, "AGENT_STEP", null, null))
        HrContract.checkStep(step, stepContext.visibleIds, fresh)
        var final: HrFinal? = null
        var chosenContext = stepContext
        if (step.route == HrRoute.RETRIEVE) {
            require(nowEpochMs()-request.anchorTimeEpochMs < 30_000L) { "HR_PACKET_STALE" }
            chosenContext = assembler.finalContext(stepContext, step.candidate.availableEvidence.toSet(),
                requireNotNull(step.query), request.anchorTimeEpochMs, nowEpochMs())
            final = provider.final(envelope(request, chosenContext, outputs, "AGENT_FINAL", step.candidate, step.query))
            HrContract.checkFinal(final, chosenContext.visibleIds)
        }
        val selected = (chosenContext.newRecords + chosenContext.recentContext + chosenContext.prefetchedHistory)
            .distinctBy { it.observationId }.toMutableList()
        if (currentId!=null && selected.none { it.observationId == currentId }) {
            val source = history.causalArchiveSnapshot(request.anchorTimeEpochMs, nowEpochMs())
                .singleOrNull { it.observationId == currentId } ?: error("HR_CURRENT_IMAGE_NOT_CAUSAL")
            selected += source
        }
        val effective = request.copy(observations = selected.map(::modelRecord), receipts = emptyList())
        val proposed = if (final != null) final.service else step.service
        fun suppressionFor(card: HrService): String? {
            val cited=selected.filter {it.observationId in card.evidenceIds}
            val newDigital=selected.filter {it.observationId in request.packetNewEventIds &&
                it.kind!=ObservationKind.IMAGE}.mapTo(hashSetOf()) {it.observationId}
            return (if(effort==ProactivityEffort.GUARANTEED && card.evidenceIds.none {it in request.packetNewEventIds})
                "HR_GUARANTEED_NO_CURRENT_EVIDENCE" else null) ?:
                (if(effort==ProactivityEffort.GUARANTEED && (card.title.trim() in setOf("现场观察","新情境") ||
                    "已纳入本次观察" in card.body || "可在手机记录中查看" in card.body)) "HR_TEMPLATE_CONTENT" else null) ?:
                HrTimingGuard.internalIdLeak(card) ?:
                HrTimingGuard.contradiction(card,cited,request.anchorTimeEpochMs) ?:
                if(effort==ProactivityEffort.GUARANTEED) null else
                    HrTimingGuard.noCurrentOpportunity(card,cited,listOfNotNull(currentId).toSet(),newDigital,
                        request.packetTriggerTypes,request.anchorTimeEpochMs,
                        currentId!=null && currentId in step.candidate.availableEvidence &&
                            HrTimingGuard.specificVisualLink(step.note,cited)) ?:
                    HrTimingGuard.visualTaskMismatch(step.note,cited,listOfNotNull(currentId).toSet(),newDigital,
                        card,request.anchorTimeEpochMs)
        }
        val suppression=proposed?.let(::suppressionFor)
        var service=proposed.takeIf {suppression==null}
        var forcedModelPass=false
        if(effort==ProactivityEffort.GUARANTEED && service==null &&
            selected.any {it.observationId in request.packetNewEventIds}) {
            require(nowEpochMs()-request.anchorTimeEpochMs < 30_000L) {"HR_PACKET_STALE"}
            val regeneration=envelope(request,chosenContext,outputs,"GUARANTEED_SERVICE",null,null).let {base->
                JsonObject(base+StudyWire.payload("regeneration_reason" to (suppression ?: "MODEL_SILENT")))
            }
            val generated=provider.final(regeneration)
            HrContract.checkFinal(generated,chosenContext.visibleIds)
            require(generated.decision==HrRoute.SERVICE) {"HR_GUARANTEED_MODEL_SILENT"}
            val card=requireNotNull(generated.service) {"HR_GUARANTEED_MODEL_SERVICE_MISSING"}
            require(card.evidenceIds.any {it in request.packetNewEventIds}) {"HR_GUARANTEED_NO_CURRENT_EVIDENCE"}
            require(suppressionFor(card)==null) {"HR_GUARANTEED_UNSAFE_SERVICE"}
            final=generated;service=card;forcedModelPass=true
        }
        val decision = if (service == null) DecisionLabel.NO_SERVICE else DecisionLabel.SERVICE
        val produced = nowEpochMs()
        val expires = minOf(produced + ttlMs, request.anchorTimeEpochMs + 30_000L)
        require(produced < expires) { "HR_PACKET_STALE" }
        val response = InferenceResponse(effective.requestId, "decision-${UUID.randomUUID()}", decision,
            service?.let { ServiceMode.valueOf(it.mode) }, service?.title, service?.body,
            service?.evidenceIds?.toSet().orEmpty(), produced, expires)
        // Existing delivery contract validates against the same evidence set the final stage saw.
        val allowed = effective.observations.mapTo(hashSetOf()) { it.observationId }
        require(allowed.containsAll(response.evidenceObservationIds)) { "HR_FINAL_EVIDENCE_NOT_ATTACHED" }
        return HrTurnOutcome(effective, response, step, final,
            if (step.route == HrRoute.RETRIEVE) chosenContext.prefetchedHistory.map { it.observationId } else emptyList(),
            suppression,forcedModelPass)
    }

    private fun modelRecord(row: ObservationRecord) = ModelObservation(row.observationId, row.kind,
        row.occurredAtEpochMs, row.availableAtEpochMs, row.content, row.assetId,
        row.episodeHandle, row.materialRevision)

    private fun envelope(request: InferenceRequest, context: HrContext, outputs: List<HrAgentOutput>,
                         stage: String, candidate: HrCandidate?, query: String?): JsonObject {
        fun record(row: ObservationRecord) = StudyWire.payload(
            "record_id" to row.observationId, "kind" to row.kind.name,
            "available_at_epoch_ms" to row.availableAtEpochMs,
            "occurred_at_epoch_ms" to row.occurredAtEpochMs,
            "available_at_local" to localTime(row.availableAtEpochMs),
            "occurred_at_local" to localTime(row.occurredAtEpochMs),
            "text" to row.content, "asset_id" to row.assetId,
            "source_ids" to JsonArray(history.sourceIds(row.observationId).sorted().map(::JsonPrimitive)))
        val topic = (context.newRecords+context.recentContext+context.prefetchedHistory)
            .filter { it.kind != ObservationKind.IMAGE }.joinToString(" ") { it.content.lowercase() }
        val recentOutputs = outputs.takeLast(16)
        val recentPackets = recentOutputs.mapTo(hashSetOf()) { it.packetId }
        val older = outputs.filter { it.packetId !in recentPackets }.map { output ->
            output to Regex("[\\p{L}\\p{N}]{2,}").findAll(output.title+" "+output.body)
                .count { topic.contains(it.value.lowercase()) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8).map { it.first }
        val candidates = (older+recentOutputs).distinctBy { it.packetId }
        val visibleOutputs = mutableListOf<HrAgentOutput>()
        var historyBudget = 2048
        for(output in candidates.asReversed()) {
            val cost = output.title.codePointCount(0,output.title.length) +
                output.body.codePointCount(0,output.body.length) + 48
            if(cost > historyBudget) continue
            visibleOutputs += output
            historyBudget -= cost
        }
        visibleOutputs.reverse()
        val base = StudyWire.payload(
            "stage" to stage, "packet_id" to request.anchorId,
            "snapshot_at_epoch_ms" to request.anchorTimeEpochMs,
            "snapshot_at_local" to localTime(request.anchorTimeEpochMs),
            "snapshot_timezone" to "Asia/Shanghai",
            "trigger_types" to JsonArray(request.packetTriggerTypes.sorted().map(::JsonPrimitive)),
            "freshness" to StudyWire.payload("latest_image_age_ms" to request.packetLatestImageAgeMs,
                "observation_gap" to request.packetObservationGap),
            "new_records" to JsonArray(context.newRecords.map(::record)),
            "recent_context" to JsonArray(context.recentContext.map(::record)),
            "prefetched_history" to JsonArray(context.prefetchedHistory.map(::record)),
            "image_manifest" to JsonArray(currentRecord(request)?.let {listOf(StudyWire.payload(
                "image_id" to it.observationId,
                "reused" to (it.observationId !in request.packetNewEventIds)))} ?: emptyList()),
            "agent_history" to JsonArray(visibleOutputs.map { output -> StudyWire.payload(
                "packet_id" to output.packetId, "title" to output.title, "body" to output.body,
                "produced_at_epoch_ms" to output.producedAtEpochMs,
                "delivery_confirmed" to output.visiblyDelivered) }),
            "agent_history_view" to StudyWire.payload("enabled" to true,
                "is_complete" to (outputs.size <= visibleOutputs.size),
                "omitted_at_least" to (outputs.size - visibleOutputs.size)),
            "visible_reference_ids" to JsonArray(context.visibleIds.sorted().map(::JsonPrimitive)),
            "retrieval_backend" to retriever.backendId,
            "proactivity_effort" to effort.name,
            "retrieval_omitted_count" to context.omittedCount)
        if (candidate == null) return base
        return JsonObject(base + StudyWire.payload("retrieval_context" to StudyWire.payload(
            "query" to query, "candidate" to StudyWire.payload(
                "candidate_kind" to candidate.kind?.name,
                "why_now" to candidate.whyNow,
                "available_evidence" to JsonArray(candidate.availableEvidence.map(::JsonPrimitive)),
                "missing_facts" to JsonArray(candidate.missingFacts.map(::JsonPrimitive))))))
    }
}
