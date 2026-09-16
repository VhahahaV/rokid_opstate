package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import ai.opstate.protocol.artifactSha256
import ai.opstate.rokid.phone.data.*
import ai.opstate.rokid.phone.security.PrivateCredentialStore
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real provider and production codec; generated images/text only, isolated from the running study host. */
@RunWith(AndroidJUnit4::class)
class ProviderSequenceDeviceTest {
    @Test fun twentyConsecutiveProviderResponsesMatchTheFrozenContract()=runBlocking<Unit> {
        assumeTrue("Explicit real provider sequence only",InstrumentationRegistry.getArguments().getString("provider_sequence")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.filesDir,"validation").apply {mkdirs()}
        val input=File(dir,"provider-key-input")
        val config=context.getSharedPreferences("study-settings",0).getString("model_api_config",null)?.let {
            StudyWire.json.decodeFromString(DirectModelConfig.serializer(),it)
        } ?: DirectModelConfig.qwen()
        val credentials=PrivateCredentialStore(context)
        val importKey=input.isFile
        check(!importKey || context.packageName.endsWith(".librarytest")) {"KEY_IMPORT_ONLY_IN_ISOLATED_TEST_PACKAGE"}
        val db=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build();val journal=StudyJournal(context,db)
        val id="provider-sequence-${UUID.randomUUID()}";var started=false;var completed=0;var failure: Throwable?=null
        try {
            if(importKey) try {credentials.putModelApiKey(config.credentialScope(),input.readText().trim())} finally {input.delete()}
            check(credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"TEST_PROVIDER_KEY_FILE_REQUIRED"}
            val client=DirectModelClient.create(context,config) {credentials.modelApiKey(config.credentialScope())}
            val frozen=client.contract(config.modelId)
            val now=System.currentTimeMillis()
            val setup=StudyWire.payload("contract" to StudyWire.json.encodeToJsonElement(frozen),"scope" to "REAL_PROVIDER_PROTOCOL_SEQUENCE",
                "image_source" to "GENERATED_CHECKERBOARD","transport_source" to "NO_GLASSES_TRANSPORT",
                "phone_build" to installedBuildIdentity(context),"glasses_app_version" to "not_applicable")
            journal.start(id,now,"TEST_PROVIDER_SEQUENCE","synthetic-api-sequence","PROVIDER_PROTOCOL_SEQUENCE",
                StudyWire.sha256(StudyJournal.canonical(setup).toString().toByteArray()),now,setup)
            started=true
            val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
            val jpeg=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};bitmap.recycle()
            val sha=StudyWire.sha256(jpeg);val asset="generated-$sha"
            val path=journal.image(id,"generated-checkerboard",jpeg,sha)
            val contexts=listOf("合成日历：明天下午三点有项目会议，地点和时间没有变化。",
                "合成消息：五分钟后开会，会议室刚从 A201 改为 B302。",
                "合成任务状态：材料已提交，正在等待同事确认，无新的待办。",
                "合成消息：会议已取消，今天不需要前往会议室。")
            repeat(20) {index->
                val at=System.currentTimeMillis();val anchor="anchor-$index";val rid="request-$id-$index"
                val observations=listOf(ModelObservation("context-$index",ObservationKind.SYNTHETIC_CONTEXT,at,at,contexts[index%contexts.size],null,"episode-fixture",index.toLong()),
                    ModelObservation("image-$index",ObservationKind.IMAGE,at,at,"Generated checkerboard for API protocol validation; not an actual scene.",asset,null,null))
                observations.forEach {obs->journal.append(id,"observations","OBSERVATION_PERSISTED",JsonObject(
                    StudyWire.json.encodeToJsonElement(WireObservation(obs.observationId,obs.kind.name,at,at,obs.content,obs.assetId,obs.episodeHandle,obs.materialRevision)).jsonObject+
                    if(obs.assetId!=null) StudyWire.payload("local_relative_path" to path,"sha256" to sha,"asset_source" to "TEST_FIXTURE") else emptyMap()),at)}
                val request=InferenceRequest(frozen.protocolVersion,rid,anchor,at,asset,sha,ModelCandidate("object-fixture","episode-fixture"),observations,
                    emptyList(),frozen.promptHash,frozen.preprocessHash,frozen.modelId,frozen.parserHash)
                journal.append(id,"anchors","ANCHOR_EMITTED",StudyWire.payload("anchor_id" to anchor,"anchor_time_epoch_ms" to at,
                    "current_asset_id" to asset,"candidate_episode_handle" to "episode-fixture"),at)
                journal.append(id,"inference_requests","INFERENCE_SENT",StudyWire.json.encodeToJsonElement(request.toWire()).jsonObject,at)
                val response=try {client.evaluate(request,jpeg) {raw->journal.append(id,"raw_responses",if(raw.format.startsWith("PROVIDER_")) "PROVIDER_RESPONSE" else "RAW_DECISION",
                    StudyWire.payload("request_id" to rid,"anchor_id" to anchor,"http_status" to raw.httpStatus,"raw_response_json" to raw.body,
                        "raw_sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs,"raw_format" to raw.format,"redacted" to raw.redacted),raw.receivedAtEpochMs)}}
                catch(error: Exception) {journal.append(id,"runtime_events","TECHNICAL_FAILURE",StudyWire.payload("request_id" to rid,"code" to error.javaClass.simpleName),System.currentTimeMillis());throw error}
                assertTrue(InferenceContract.validate(response,request).isEmpty())
                journal.append(id,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to rid,"anchor_id" to anchor,"decision_id" to response.decisionId,
                    "decision" to response.decision.name,"title" to response.title,"body" to response.body,
                    "evidence_observation_ids" to JsonArray(response.evidenceObservationIds.map(::JsonPrimitive)),
                    "produced_at_epoch_ms" to response.producedAtEpochMs,"expires_at_epoch_ms" to response.expiresAtEpochMs),System.currentTimeMillis())
                journal.append(id,"deliveries","DELIVERY_DECISION",StudyWire.payload("delivery_id" to "delivery-$index","decision_id" to response.decisionId,
                    "status" to "NOT_ATTEMPTED","reason" to "PROVIDER_SEQUENCE_NO_GLASS_DISPLAY","event_at_epoch_ms" to System.currentTimeMillis()),System.currentTimeMillis())
                completed++
                File(dir,"provider-sequence-progress.json").writeText(StudyWire.payload("completed" to completed,"total" to 20,"model" to config.modelId).toString())
            }
        } catch(error: Throwable) {failure=error}
        finally {
            if(importKey) credentials.remove("model-api-key")
            input.delete()
            if(started) {
                journal.stop(id,System.currentTimeMillis())
                journal.export(id).copyTo(File(dir,"provider-sequence.zip"),overwrite=true)
            }
            File(dir,"provider-sequence-result.json").writeText(StudyWire.payload("status" to if(failure==null && completed==20) "PASS" else "FAIL",
                "completed" to completed,"model" to config.modelId,"scope" to "REAL_PROVIDER_PROTOCOL_SEQUENCE","physical_camera" to false,
                "glasses_display" to false,"test_key_removed" to (if(importKey) credentials.read("model-api-key")==null else null),
                "existing_host_key_preserved" to !importKey,
                "apk_sha256" to artifactSha256(File(context.applicationInfo.sourceDir)),"failure_type" to failure?.javaClass?.simpleName,"failure_code" to failure?.message).toString())
            if(started) journal.delete(id);db.close()
        }
        failure?.let {throw it};Unit
    }
}
