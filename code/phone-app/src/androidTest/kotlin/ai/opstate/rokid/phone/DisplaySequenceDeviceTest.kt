package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.core.receipt.*
import ai.opstate.protocol.*
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Production policy + journal + actual radio/renderer/ACK. Inputs and model decisions are declared fixtures. */
@RunWith(AndroidJUnit4::class)
class DisplaySequenceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun twentySilenceServiceDuplicateAndRevisionRounds()=runBlocking<Unit> {
        assumeTrue("Explicit physical display sequence only",InstrumentationRegistry.getArguments().getString("display_sequence")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        check(app.studyRuntime.state.value.sessionId==null) {"END_STUDY_SESSION_FIRST"}
        val dir=File(app.filesDir,"validation").apply {mkdirs()}
        val signal=File(dir,"display-pixel.signal").apply {delete()}
        val device=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        // The app reopens the saved glasses as it starts, and the instrumentation restarts it, so the
        // handshake is given its time before this test opens a link of its own: a second connect while
        // the first is still coming up is what the SDK refuses, and it reports that as a disconnect.
        runCatching {withTimeout(45_000) {app.transport.ready.first {it}}}.onFailure {
            app.transport.connect(RokidDevice(device.address,device.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        app.transport.refreshPeer().getOrThrow()
        val id="display-sequence-${UUID.randomUUID()}";val epoch=System.currentTimeMillis()
        val modelService=RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.SERVICE)
        val modelSilent=RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE)
        val contract=modelService.contract(null);val policy=ReceiptPolicy()
        val visible=Channel<RokidEvent.DisplayVisibleAck>(Channel.UNLIMITED)
        val collector=launch(Dispatchers.IO) {app.transport.events.collect {if(it is RokidEvent.DisplayVisibleAck) visible.send(it)}}
        val config=StudyWire.payload("contract" to StudyWire.json.encodeToJsonElement(contract),"scope" to "PHYSICAL_DISPLAY_RECEIPT_SEQUENCE",
            "image_source" to "GENERATED_TEST_FIXTURE","live_camera" to false,"phone_build" to installedBuildIdentity(app),
            "glasses_build" to app.transport.peerBuildInfo()?.let {StudyWire.json.encodeToJsonElement(it)},"glasses_app_version" to app.transport.peerBuildInfo()?.appVersion)
        app.transport.startSession(id,epoch).getOrThrow()
        app.journal.start(id,epoch,"TEST_DISPLAY_SEQUENCE","controlled-display-sequence","DISPLAY_RECEIPT_GATE",
            StudyWire.sha256(ai.opstate.rokid.phone.data.StudyJournal.canonical(config).toString().toByteArray()),System.currentTimeMillis(),config)
        var sequence=0;var sent=0;var rounds=0;var failure: Throwable?=null
        val durations=mutableListOf<Long>()
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GRAY)}
        val jpeg=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};bitmap.recycle()
        val sha=StudyWire.sha256(jpeg);val asset="fixture-$sha";val path=app.journal.image(id,"generated-image",jpeg,sha)
        suspend fun command(type: String,payload: JsonObject=JsonObject(emptyMap()),ttl: Long?=null,key: String?=null) {
            var message=app.transport.controlMessage(type,payload,ttl)
            if(key!=null) message=message.copy(idempotency_key=key)
            app.journal.append(id,"runtime_events","TEST_OUTBOUND_COMMAND",StudyWire.payload("type" to type,"message_id" to message.message_id),System.currentTimeMillis())
            app.transport.command(message).getOrThrow();sent++
        }
        suspend fun pixels(token: String) {
            File(dir,"display-sequence-stage.json").writeText(StudyWire.payload("stage" to "PIXEL_CHECK","token" to token,"round" to rounds).toString())
            withTimeout(15000) {while(!signal.isFile || signal.readText().trim()!=token) delay(50)}
        }
        suspend fun decision(episode: CandidateEpisode,stage: String,label: DecisionLabel): Pair<InferenceRequest,InferenceResponse> {
            val n=sequence++;val now=System.currentTimeMillis();val anchor="anchor-$n";val rid="request-$id-$n"
            val imageId="image-$n";val contextId="context-$n"
            val observations=listOf(ModelObservation(contextId,ObservationKind.SYNTHETIC_CONTEXT,now,now,
                "Controlled display fixture: $stage; task revision ${episode.materialRevision}.",null,episode.episodeHandle,episode.materialRevision),
                ModelObservation(imageId,ObservationKind.IMAGE,now,now,"Generated test image; physical camera is not part of this display gate.",asset,null,null))
            observations.forEach {obs->app.journal.append(id,"observations","OBSERVATION_PERSISTED",JsonObject(
                StudyWire.json.encodeToJsonElement(WireObservation(obs.observationId,obs.kind.name,now,now,obs.content,obs.assetId,obs.episodeHandle,obs.materialRevision)).jsonObject+
                    if(obs.assetId!=null) StudyWire.payload("local_relative_path" to path,"sha256" to sha,"asset_source" to "TEST_FIXTURE") else emptyMap()),now)}
            val receipts=policy.allReceipts().map {ModelActionReceipt(it.receiptId,it.decisionId,it.episodeHandle,it.objectHandle,it.intentFingerprint,it.serviceMode,
                it.visibleAtEpochMs,it.servedMaterialRevision,it.evidenceObservationIds)}
            val request=InferenceRequest(contract.protocolVersion,rid,anchor,now,asset,sha,ModelCandidate(episode.objectHandle,episode.episodeHandle),observations,
                receipts,contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash)
            app.journal.append(id,"anchors","ANCHOR_EMITTED",StudyWire.payload("anchor_id" to anchor,"anchor_time_epoch_ms" to now,
                "current_asset_id" to asset,"candidate_episode_handle" to episode.episodeHandle),now)
            app.journal.append(id,"inference_requests","INFERENCE_SENT",StudyWire.json.encodeToJsonElement(request.toWire()).jsonObject,now)
            val response=(if(label==DecisionLabel.SERVICE) modelService else modelSilent).evaluate(request,jpeg) {raw->
                app.journal.append(id,"raw_responses","RAW_DECISION",StudyWire.payload("request_id" to rid,"anchor_id" to anchor,
                    "http_status" to raw.httpStatus,"raw_response_json" to raw.body,"raw_sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs),raw.receivedAtEpochMs)
            }
            app.journal.append(id,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to rid,"anchor_id" to anchor,"decision_id" to response.decisionId,
                "decision" to response.decision.name,"title" to response.title,"body" to response.body,
                "evidence_observation_ids" to JsonArray(response.evidenceObservationIds.map(::JsonPrimitive)),
                "produced_at_epoch_ms" to response.producedAtEpochMs,"expires_at_epoch_ms" to response.expiresAtEpochMs),System.currentTimeMillis())
            return request to response
        }
        suspend fun apply(episode: CandidateEpisode,pair: Pair<InferenceRequest,InferenceResponse>,expected: DeliveryPolicy.Status) {
            val (request,response)=pair;val now=System.currentTimeMillis()
            val verdict=DeliveryPolicy.evaluate(request,response,episode,episode,id,epoch,id,epoch,now,policy,false)
            assertEquals(expected,verdict.status)
            fun delivery(status: String,reason: String,receipt: String?=null) {app.journal.append(id,"deliveries","DELIVERY_DECISION",StudyWire.payload(
                "delivery_id" to "delivery-${UUID.randomUUID()}","decision_id" to response.decisionId,"status" to status,"reason" to reason,
                "event_at_epoch_ms" to System.currentTimeMillis(),"receipt_id" to receipt),System.currentTimeMillis())}
            when(verdict.status) {
                DeliveryPolicy.Status.NO_SERVICE->delivery("NO_SERVICE_SILENCE",verdict.reason)
                DeliveryPolicy.Status.SUPPRESS_DUPLICATE->delivery("SUPPRESSED_BY_RECEIPT",verdict.reason)
                DeliveryPolicy.Status.DELIVER->{
                    delivery("NOT_ATTEMPTED","PENDING_VISIBLE_ACK")
                    command("SHOW_SERVICE_CARD",StudyWire.payload("decision_id" to response.decisionId,"service_mode" to response.serviceMode!!.name,
                        "title" to response.title,"body" to response.body,"visible_for_ms" to 1000),20000,"display-${response.decisionId}")
                    val ack=withTimeout(5000) {var match: RokidEvent.DisplayVisibleAck
                        do {match=visible.receive()} while(match.sessionId!=id || match.sessionEpoch!=epoch || match.decisionId!=response.decisionId)
                        match}
                    assertTrue(ack.renderFrameCount>=2)
                    val outcome=policy.finalizeDelivery(InferenceContract.toDecision(response),episode,episode.objectHandle,ack.visibleAtEpochMs,
                        InferenceContract.allowedEvidenceIds(request),System.currentTimeMillis()) {receipt->
                        app.journal.database.runInTransaction {
                            delivery("DISPLAY_VISIBLE","REAL_TWO_FRAME_ACK",receipt.receiptId)
                            app.journal.append(id,"receipts","RECEIPT_COMMITTED",StudyWire.payload("receipt_id" to receipt.receiptId,"decision_id" to receipt.decisionId,
                                "episode_handle" to receipt.episodeHandle,"object_handle" to receipt.objectHandle,"intent_fingerprint" to receipt.intentFingerprint,
                                "service_mode" to receipt.serviceMode.name,"served_material_revision" to receipt.servedMaterialRevision,"visible_at_epoch_ms" to receipt.visibleAtEpochMs,
                                "evidence_observation_ids" to JsonArray(receipt.evidenceObservationIds.map(::JsonPrimitive)),"visible_ack_frame_count" to ack.renderFrameCount,
                                "anchor_id" to request.anchorId),System.currentTimeMillis())
                        }
                    }
                    assertEquals(DeliveryStatus.DISPLAY_VISIBLE,outcome.status)
                    durations+=ack.visibleAtEpochMs-response.producedAtEpochMs
                    val count=policy.allReceipts().size
                    policy.finalizeDelivery(InferenceContract.toDecision(response),episode,episode.objectHandle,ack.visibleAtEpochMs,
                        InferenceContract.allowedEvidenceIds(request),System.currentTimeMillis())
                    assertEquals(count,policy.allReceipts().size)
                    command("CLEAR_SERVICE_CARD");delay(200)
                }
                else->error("UNEXPECTED_DELIVERY_VERDICT")
            }
        }
        try {
            command("CLEAR_ALL");delay(300)
            repeat(20) {cycle->
                rounds=cycle
                val episode=CandidateEpisode("ep-$cycle","obj-$cycle","intent-$cycle",ServiceMode.PREPARATION,true)
                pixels("before-$cycle")
                val before=sent
                apply(episode,decision(episode,"early quiet window",DecisionLabel.NO_SERVICE),DeliveryPolicy.Status.NO_SERVICE)
                assertEquals(before,sent)
                pixels("after-$cycle")
                apply(episode,decision(episode,"prepare to depart",DecisionLabel.SERVICE),DeliveryPolicy.Status.DELIVER)
                val afterService=sent
                apply(episode,decision(episode,"same requirement after visible receipt",DecisionLabel.SERVICE),DeliveryPolicy.Status.SUPPRESS_DUPLICATE)
                assertEquals(afterService,sent)
                val revised=episode.copy(materialRevision=1)
                apply(revised,decision(revised,"updated task requirement",DecisionLabel.SERVICE),DeliveryPolicy.Status.DELIVER)
                rounds=cycle+1
            }
            assertEquals(40,policy.allReceipts().size)
        } catch(error: Throwable) {failure=error}
        finally {
            command("SESSION_STOP");app.journal.stop(id,System.currentTimeMillis());collector.cancelAndJoin()
            app.journal.export(id).copyTo(File(dir,"display-sequence.zip"),overwrite=true)
            File(dir,"display-sequence-result.json").writeText(StudyWire.payload("status" to if(failure==null) "PASS" else "FAIL","session_id" to id,
                "scope" to "PHYSICAL_DISPLAY_POLICY_RECEIPT_SEQUENCE","rounds" to rounds,"visible_receipts" to policy.allReceipts().size,
                "visible_latencies_ms" to JsonArray(durations.map(::JsonPrimitive)),"camera_source" to "GENERATED_FIXTURE","model_source" to "LOCAL_SCRIPTED_MOCK",
                "pixel_comparison" to "HOST_REPORT_REQUIRED","failure_type" to failure?.javaClass?.simpleName,"failure_code" to failure?.message).toString())
            File(dir,"display-sequence-stage.json").writeText(StudyWire.payload("stage" to if(failure==null) "COMPLETE" else "FAILED").toString());signal.delete()
        }
        failure?.let {throw it};Unit
    }
}
