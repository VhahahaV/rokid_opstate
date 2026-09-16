package ai.opstate.rokid.phone.replay

import android.content.Context
import ai.opstate.core.inference.InferenceContract
import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ReplayState(val phase: String="IDLE",val sessionId: String?=null,val completed: Int=0,val total: Int=0,
    val services: Int=0,val silent: Int=0,val failures: Int=0,val currentAnchor: String?=null,val error: String?=null) {
    val running get()=phase in setOf("PREPARING","RUNNING","STOPPING")
}

/** Uses the production HTTP client/parser/journal; never commands the camera or fabricates display receipts. */
class DatasetReplayRunner(private val context: Context,private val journal: StudyJournal,private val token: ()->String) {
    private val busy=AtomicBoolean(false)
    private val _state=MutableStateFlow(ReplayState())
    val state=_state.asStateFlow()
    suspend fun run(endpoint: String,limit: Int=266,expectedModelId: String?=null): String = withContext(Dispatchers.IO) {
        check(busy.compareAndSet(false,true)) { "REPLAY_ALREADY_RUNNING" }
        var id: String?=null
        var started=false
        _state.value=ReplayState(phase="PREPARING",total=limit)
        try {
            val dataset=PublicReplayDataset { path->context.assets.open(PublicReplayDataset.ASSET_ROOT+path).use { it.readBytes() } }
            val selected=dataset.select(limit)
            val client=RemoteHarnessClient(endpoint,token,StudyWire.json,routeClient=InternetRouting(context)::client)
            val contract=client.contract(expectedModelId)
            require(contract.protocolVersion=="opstate-online-v2" && contract.mock && contract.mockMode=="dataset" && contract.strictContract) { "STRICT_DATASET_MOCK_REQUIRED" }
            val sessionId="replay-${UUID.randomUUID()}";id=sessionId
            val now=System.currentTimeMillis()
            val config=StudyWire.payload("profile" to "CONTRACT_REPLAY","catalog" to dataset.catalog,"contract" to StudyWire.json.encodeToJsonElement(contract),
                "anchor_count" to selected.size,"projection_version" to "public-r6-v1","live_camera" to false,"teacher_receipts" to false)
            journal.start(sessionId,now,"MOCK","January V10 · R6","CONTRACT_REPLAY",StudyWire.sha256(StudyJournal.canonical(config).toString().toByteArray()),now,config)
            started=true
            journal.checkpoint(sessionId,now,"RUNNING",StudyWire.payload("contract" to StudyWire.json.encodeToJsonElement(contract),"config" to config,
                "glasses_app_version" to "not_applicable","mock" to true,"not_for_paper" to true),now)
            _state.value=ReplayState(phase="RUNNING",sessionId=sessionId,total=selected.size)
            val emitted=hashSetOf<String>()
            val imagePaths=mutableMapOf<String,String>()
            for(anchor in selected) {
                currentCoroutineContext().ensureActive()
                val request=dataset.request(anchor,contract,sessionId)
                val jpeg=dataset.image(request.currentAssetId)
                val path=imagePaths.getOrPut(request.currentAssetId) { journal.image(sessionId,"asset-${request.currentImageSha256.take(24)}",jpeg,request.currentImageSha256) }
                _state.update { it.copy(currentAnchor=request.anchorId) }
                for(obs in request.observations) if(emitted.add(obs.observationId)) {
                    val wire=WireObservation(obs.observationId,obs.kind.name,obs.occurredAtEpochMs,obs.availableAtEpochMs,obs.content,obs.assetId,obs.episodeHandle,obs.materialRevision)
                    journal.append(sessionId,"observations","PUBLIC_OBSERVATION",StudyWire.json.encodeToJsonElement(wire).jsonObject,obs.availableAtEpochMs)
                }
                // Separate image attachment records avoid changing immutable historical observations.
                journal.append(sessionId,"observations","REPLAY_IMAGE_ATTACHMENT",StudyWire.payload("observation_id" to "attachment-${request.anchorId}",
                    "kind" to "IMAGE_ATTACHMENT","asset_id" to request.currentAssetId,"available_at_epoch_ms" to request.anchorTimeEpochMs,
                    "occurred_at_epoch_ms" to request.anchorTimeEpochMs,"local_relative_path" to path,"sha256" to request.currentImageSha256),request.anchorTimeEpochMs)
                journal.append(sessionId,"anchors","REPLAY_ANCHOR",StudyWire.payload("anchor_id" to request.anchorId,"anchor_time_epoch_ms" to request.anchorTimeEpochMs,
                    "current_asset_id" to request.currentAssetId,"candidate_episode_handle" to request.candidate.episodeHandle,
                    "source_index" to anchor.getValue("anchor_index"),"profile" to "CONTRACT_REPLAY"),request.anchorTimeEpochMs)
                val wire=StudyWire.json.encodeToJsonElement(request.toWire()).jsonObject
                journal.append(sessionId,"inference_requests","INFERENCE_SENT",wire,System.currentTimeMillis())
                try {
                    val response=client.evaluate(request,jpeg) {raw->
                        journal.append(sessionId,"raw_responses","RAW_DECISION",StudyWire.payload("request_id" to raw.requestId,"anchor_id" to request.anchorId,
                            "http_status" to raw.httpStatus,"raw_response_json" to raw.body,"raw_sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs),raw.receivedAtEpochMs)
                    }
                    check(InferenceContract.validate(response,request).isEmpty()) { "REPLAY_RESPONSE_CONTRACT_INVALID" }
                    journal.append(sessionId,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to response.requestId,"anchor_id" to request.anchorId,
                        "decision_id" to response.decisionId,"decision" to response.decision.name,"title" to response.title,"body" to response.body,
                        "evidence_observation_ids" to JsonArray(response.evidenceObservationIds.map(::JsonPrimitive)),
                        "produced_at_epoch_ms" to response.producedAtEpochMs,"expires_at_epoch_ms" to response.expiresAtEpochMs),System.currentTimeMillis())
                    journal.append(sessionId,"deliveries","REPLAY_NOT_DELIVERED",StudyWire.payload("delivery_id" to "replay-${response.decisionId}",
                        "decision_id" to response.decisionId,"status" to "REPLAY_NOT_DELIVERED","reason" to "CONTRACT_REPLAY_HAS_NO_DISPLAY"),System.currentTimeMillis())
                    _state.update { it.copy(services=it.services+if(response.decision==DecisionLabel.SERVICE) 1 else 0,silent=it.silent+if(response.decision==DecisionLabel.NO_SERVICE) 1 else 0) }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    journal.append(sessionId,"runtime_events","TECHNICAL_FAILURE",StudyWire.payload("request_id" to request.requestId,"anchor_id" to request.anchorId,"code" to error.javaClass.simpleName),System.currentTimeMillis())
                    _state.update { it.copy(failures=it.failures+1,error=error.message) }
                }
                _state.update { it.copy(completed=it.completed+1) }
            }
            journal.append(sessionId,"runtime_events","REPLAY_FINISHED",StudyWire.payload("completed" to _state.value.completed,
                "failures" to _state.value.failures,"service" to _state.value.services,"no_service" to _state.value.silent),System.currentTimeMillis())
            journal.stop(sessionId,System.currentTimeMillis())
            _state.update { it.copy(phase=if(it.failures==0) "COMPLETE" else "FAILED",currentAnchor=null) }
            sessionId
        } catch(cancelled: CancellationException) {
            if(started) journal.append(id!!,"runtime_events","REPLAY_CANCELLED",StudyWire.payload("completed" to _state.value.completed),System.currentTimeMillis())
            _state.update { it.copy(phase="CANCELLED",currentAnchor=null) };throw cancelled
        } catch(error: Exception) {
            _state.update { it.copy(phase="FAILED",error=error.message,currentAnchor=null) };throw error
        } finally {
            try { if(started) withContext(NonCancellable+Dispatchers.IO) { journal.stop(id!!,System.currentTimeMillis()) } }
            finally { busy.set(false) }
        }
    }
}
