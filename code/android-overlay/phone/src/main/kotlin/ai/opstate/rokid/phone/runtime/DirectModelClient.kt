package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.*
import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.HrAgentOutput
import ai.opstate.protocol.StudyWire
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Calls the configured provider directly. Keys are supplied separately and never enter the model snapshot. */
class DirectModelClient(
    val config: DirectModelConfig,
    private val keyProvider: ()->String,
    private val promptText: String,
    private val modelSchema: JsonObject,
    private val requestSchemaHash: String,
    private val responseSchemaHash: String,
    private val codeHash: String,
    client: OkHttpClient?=null,
    private val routeClient: ((String,OkHttpClient)->OkHttpClient)?=null,
    allowTestLoopback: Boolean=false,
    private val imagePreprocessor: (ByteArray)->ByteArray={it},
    private val hrStepPrompt: String?=null,
    private val hrFinalPrompt: String?=null,
    private val hrStepSchema: JsonObject?=null,
    private val hrFinalSchema: JsonObject?=null,
    private val hrGuaranteedPrompt: String?=null,
    private val hrGuaranteedSchema: JsonObject?=null,
    private val clockEpochMs: ()->Long=System::currentTimeMillis,
    private val proactivityEffort: ProactivityEffort=ProactivityEffort.RESTRAINED,
) : HrInferenceClient {
    init {config.validate(allowTestLoopback)}
    private val json=StudyWire.json
    private val prompt=promptText+"\nWrite service titles and bodies in ${config.responseLanguage}.\n"
    override val hrEnabled: Boolean get()=hrStepPrompt!=null && hrFinalPrompt!=null && hrStepSchema!=null && hrFinalSchema!=null &&
        (proactivityEffort!=ProactivityEffort.GUARANTEED || (hrGuaranteedPrompt!=null && hrGuaranteedSchema!=null))
    private val effortPreamble=when(proactivityEffort) {
        ProactivityEffort.RESTRAINED->""
        ProactivityEffort.INCLINED->"""主动性档位：倾向服务。有已记录的真实目标、承诺或未决项，并能在当前画面、数字事件、明确的时间关系或工作重入中找到有用时机时，优先给出一张小而具体的帮助。允许有依据的有限条件核查；不要因帮助轻量就沉默。仍需引用足够证据，不得编造完成状态、截止时间、用户意图或重复同一贡献。"""+"\n\n"
        ProactivityEffort.GUARANTEED->"""主动性档位：必定服务。只要本包有因果有效的新图像或新数字记录，就由你生成一张具体、有证据的中文卡。没有任务也可以给出画面独有的可靠现场观察，不要杜撰目标、状态或时间；“现场观察”“新情境”“已纳入本次观察”“可在手机记录中查看”是禁用的占位话术。优先在 STEP 直接 SERVICE；若沉默，后续仍由模型再次生成，不存在程序模板兜底。SERVICE 时 candidate_kind 必须非 null、missing_facts 必须为空、evidence_ids 至少一个且全部可见。"""+"\n\n"
    }
    private val hrStepInstructions: String? get()=hrStepPrompt?.let {
        if(proactivityEffort==ProactivityEffort.GUARANTEED) it+"\n\n"+effortPreamble else effortPreamble+it
    }
    private val hrFinalInstructions: String? get()=hrFinalPrompt?.let {
        if(proactivityEffort==ProactivityEffort.GUARANTEED) it+"\n\n"+effortPreamble else effortPreamble+it
    }
    private val http=client ?: OkHttpClient.Builder().connectTimeout(5000,TimeUnit.MILLISECONDS).readTimeout(config.timeoutMs,TimeUnit.MILLISECONDS)
        .writeTimeout(10000,TimeUnit.MILLISECONDS).callTimeout(config.timeoutMs,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val mutex=Mutex()
    private val completed=linkedMapOf<String,Pair<String,InferenceResponse>>()
    private val frozen: FrozenHarnessContract by lazy {
        val snapshot=JsonObject(config.publicSnapshot()+("proactivity_effort" to JsonPrimitive(proactivityEffort.name)))
        val configHash=StudyWire.sha256(canonical(snapshot).toString().toByteArray())
        val promptIdentity=if(hrEnabled) {
            requireNotNull(hrStepInstructions)+"\n"+requireNotNull(hrFinalInstructions)+
                (if(proactivityEffort==ProactivityEffort.GUARANTEED) "\n"+requireNotNull(hrGuaranteedPrompt) else "")
        } else prompt
        val schemaIdentity=if(hrEnabled) {
            canonical(requireNotNull(hrStepSchema)).toString()+canonical(requireNotNull(hrFinalSchema)).toString()+
                (if(proactivityEffort==ProactivityEffort.GUARANTEED) canonical(requireNotNull(hrGuaranteedSchema)).toString() else "")
        } else canonical(modelSchema).toString()
        FrozenHarnessContract(if(hrEnabled) "opstate-online-hr-mobile-v1" else "opstate-online-v2",config.modelId,
            StudyWire.sha256(promptIdentity.toByteArray()),
            StudyWire.sha256(("hr-packet-phash-histogram-v2-history-retrieval-image-768-align32-v1|"+configHash+schemaIdentity).toByteArray()),
            codeHash,requestSchemaHash,responseSchemaHash,mock=false,notForPaper=true,strictContract=true,
            modelConfig=JsonObject(snapshot+("config_sha256" to JsonPrimitive(configHash))))
    }
    override suspend fun contract(expectedModelId: String?): FrozenHarnessContract {
        require(expectedModelId.isNullOrBlank() || expectedModelId==config.modelId) {"CONFIGURED_MODEL_MISMATCH"}
        return frozen
    }
    private fun key(): String=keyProvider().trim().also {
        require(it.isNotEmpty()) {"MODEL_API_KEY_REQUIRED"}
        require(it.length<=8192 && it.none(Char::isISOControl)) {"MODEL_API_KEY_INVALID"}
    }
    override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse =
        evaluateWithHistory(request,jpeg,emptyMap(),onRaw)

    /** The phone-native HR route; the old single-call interface remains available for explicit baselines. */
    override suspend fun evaluateHrTurn(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        history: CausalContextStore,agentHistory: List<HrAgentOutput>,onRaw: (RawHarnessResponse)->Unit,
    ): HrTurnOutcome {
        require(hrEnabled && request.protocolVersion==frozen.protocolVersion && request.promptHash==frozen.promptHash &&
            request.preprocessHash==frozen.preprocessHash && request.parserHash==frozen.parserHash &&
            request.modelId==frozen.modelId) { "HR_MODEL_CONTRACT_MISMATCH" }
        require(StudyWire.sha256(currentJpeg)==request.currentImageSha256 && currentJpeg.size<=8*1024*1024 &&
            ((request.currentAssetId=="NO_IMAGE" && currentJpeg.isEmpty()) ||
                (request.currentAssetId!="NO_IMAGE" && currentJpeg.isNotEmpty()))) {
            "HR_CURRENT_IMAGE_INVALID"
        }
        require(clockEpochMs()-request.anchorTimeEpochMs < 30_000L) { "HR_PACKET_STALE" }
        val prepared=if(currentJpeg.isEmpty()) currentJpeg else imagePreprocessor(currentJpeg)
        val preparedHistory=historicalJpegs.mapValues { imagePreprocessor(it.value) }
        val key=key()
        suspend fun stage(name: String,envelope: JsonObject,prompt: String,schema: JsonObject,maxTokens: Int): JsonObject {
            require(clockEpochMs()-request.anchorTimeEpochMs < 30_000L) { "HR_PACKET_STALE" }
            val input=canonical(envelope).toString()
            require(input.toByteArray().size<=2*1024*1024) { "HR_INPUT_BUDGET_EXCEEDED" }
            val body=requestBody(input,prepared,preparedHistory,prompt,schema,maxTokens,"opstate_hr_${name.lowercase()}")
            val raw=exchange("${request.requestId}-$name",body,key)
            withContext(NonCancellable+Dispatchers.IO) {onRaw(raw)}
            if(raw.httpStatus !in 200..299) throw IOException("MODEL_API_HTTP_${raw.httpStatus}")
            val parsed=json.parseToJsonElement(extractText(json.parseToJsonElement(raw.body).jsonObject)).jsonObject
            withContext(Dispatchers.IO) {onRaw(raw.copy(body=canonical(parsed).toString(),
                bodySha256=StudyWire.sha256(canonical(parsed).toString().toByteArray()),format="HR_${name}_NORMALIZED"))}
            return parsed
        }
        val provider=object: HrStageProvider {
            override suspend fun step(envelope: JsonObject)=HrWireParser.step(stage("STEP",envelope,
                requireNotNull(hrStepInstructions),requireNotNull(hrStepSchema),2048))
            override suspend fun final(envelope: JsonObject): ai.opstate.core.hr.HrFinal = if(envelope["stage"]==JsonPrimitive("GUARANTEED_SERVICE"))
                HrWireParser.final(stage("GUARANTEED",envelope,requireNotNull(hrGuaranteedPrompt),
                    requireNotNull(hrGuaranteedSchema),1024))
            else HrWireParser.final(stage("FINAL",envelope,
                requireNotNull(hrFinalInstructions),requireNotNull(hrFinalSchema),1024))
        }
        return HrTurnRunner(history,provider,clockEpochMs,effort=proactivityEffort).run(request,agentHistory,config.decisionTtlMs)
    }
    override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        onRaw: (RawHarnessResponse)->Unit): InferenceResponse = mutex.withLock {
        val key=key()
        require(request.protocolVersion==frozen.protocolVersion && request.modelId==frozen.modelId && request.promptHash==frozen.promptHash &&
            request.preprocessHash==frozen.preprocessHash && request.parserHash==frozen.parserHash) {"MODEL_CONTRACT_MISMATCH"}
        require(currentJpeg.size<=8*1024*1024 && StudyWire.sha256(currentJpeg)==request.currentImageSha256) {"MODEL_IMAGE_HASH_MISMATCH"}
        val requestedHistorical=request.observations.mapNotNull {it.assetId}.filter {it!=request.currentAssetId}.distinct()
        require(historicalJpegs.size<=4 && historicalJpegs.keys.all {it in requestedHistorical} &&
            historicalJpegs.values.all {it.isNotEmpty() && it.size<=8*1024*1024}) {"MODEL_HISTORY_IMAGE_INVALID"}
        require(request.observations.all {it.occurredAtEpochMs<=it.availableAtEpochMs && it.availableAtEpochMs<=request.anchorTimeEpochMs} &&
            request.receipts.all {it.visibleAtEpochMs<=request.anchorTimeEpochMs && it.visibleAckFrameCount>=2}) {"MODEL_FUTURE_EVIDENCE"}
        val wire=json.encodeToJsonElement(request.toWire()).jsonObject
        val historyFingerprint=historicalJpegs.toSortedMap().entries.joinToString("|") {(asset,bytes)->"$asset:${StudyWire.sha256(bytes)}"}
        val fingerprint=StudyWire.sha256((canonical(wire).toString()+request.currentImageSha256+historyFingerprint).toByteArray())
        completed[request.requestId]?.let {(previous,response)->
            require(previous==fingerprint) {"MODEL_REQUEST_ID_CONFLICT"};return@withLock response
        }
        val visible=InferenceContract.allowedEvidenceIds(request)
        require(request.observations.any {it.assetId==request.currentAssetId} && visible.size==request.observations.size+request.receipts.size) {"MODEL_EVIDENCE_INVALID"}
        val text=canonical(StudyWire.payload("packet_id" to request.anchorId,"snapshot_at_epoch_ms" to request.anchorTimeEpochMs,
            "trigger_types" to JsonArray(request.packetTriggerTypes.sorted().map(::JsonPrimitive)),
            "new_event_ids" to JsonArray(request.packetNewEventIds.map(::JsonPrimitive)),
            "freshness" to StudyWire.payload("latest_image_age_ms" to request.packetLatestImageAgeMs,"observation_gap" to request.packetObservationGap),
            "current_asset_id" to request.currentAssetId,"observations" to wire.getValue("observations"),
            "receipts" to wire.getValue("receipts"),"visible_evidence_ids" to JsonArray(visible.sorted().map(::JsonPrimitive)))).toString()
        require(text.toByteArray().size<=2*1024*1024) {"MODEL_INPUT_BUDGET_EXCEEDED"}
        val preparedHistory=historicalJpegs.mapValues {imagePreprocessor(it.value)}
        val body=requestBody(text,imagePreprocessor(currentJpeg),preparedHistory)
        val raw=exchange(request.requestId,body,key)
        withContext(NonCancellable+Dispatchers.IO) {onRaw(raw)}
        if(raw.httpStatus !in 200..299) throw IOException("MODEL_API_HTTP_${raw.httpStatus}")
        val parsed=json.parseToJsonElement(extractText(json.parseToJsonElement(raw.body).jsonObject)).jsonObject
        require(parsed.keys==setOf("decision","service","evidence_refs")) {"MODEL_OUTPUT_FIELDS_INVALID"}
        val now=raw.receivedAtEpochMs
        val normalized=JsonObject(parsed+StudyWire.payload("request_id" to request.requestId,"decision_id" to "decision-${UUID.randomUUID()}",
            "produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+config.decisionTtlMs))
        val response=ResponseParser.parse(json,normalized.toString(),"opstate-online-v2")
        val violations=InferenceContract.validate(response,request)
        require(violations.isEmpty()) {when {
            "title too long" in violations || "body too long" in violations->"MODEL_OUTPUT_TOO_LONG"
            "invalid/future evidence reference" in violations->"MODEL_OUTPUT_EVIDENCE_INVALID"
            else->"MODEL_OUTPUT_INVALID"
        }}
        val normalizedBody=canonical(normalized).toString()
        withContext(Dispatchers.IO) {onRaw(raw.copy(body=normalizedBody,bodySha256=StudyWire.sha256(normalizedBody.toByteArray()),format="OPSTATE_V2_NORMALIZED"))}
        completed[request.requestId]=fingerprint to response
        while(completed.size>32) completed.remove(completed.keys.first())
        response
    }
    private fun requestBody(text: String,jpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        instructions: String=prompt,schema: JsonObject=modelSchema,maxTokens: Int=config.maxOutputTokens,
        schemaName: String="opstate_when_how"): JsonObject {
        fun image(bytes: ByteArray)="data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes)
        val historyContent=historicalJpegs.entries.flatMap {(asset,bytes)->listOf(
            StudyWire.payload("type" to "input_text","text" to "Historical collected image: $asset"),
            StudyWire.payload("type" to "input_image","image_url" to image(bytes),"detail" to "high"))}
        val currentContent=if(jpeg.isEmpty()) emptyList() else listOf(
            StudyWire.payload("type" to "input_text","text" to "Current live image"),
            StudyWire.payload("type" to "input_image","image_url" to image(jpeg),"detail" to "high"))
        val responsesContent=JsonArray(listOf(StudyWire.payload("type" to "input_text","text" to text))+historyContent+currentContent)
        val format=StudyWire.payload("name" to schemaName,"strict" to true,"schema" to schema)
        if(config.wireApi=="responses") return buildJsonObject {
            put("model",config.modelId);put("instructions",instructions);put("store",false)
            put("max_output_tokens",maxTokens)
            put("input",JsonArray(listOf(StudyWire.payload("role" to "user","content" to responsesContent))))
            put("text",StudyWire.payload("format" to JsonObject(format+("type" to JsonPrimitive("json_schema")))))
            config.reasoningEffort?.let {put("reasoning",StudyWire.payload("effort" to it))}
        }
        return buildJsonObject {
            put("model",config.modelId);put("max_tokens",maxTokens)
            config.parameters().filterKeys {it!="max_output_tokens"}.forEach {(name,value)->put(name,value)}
            val chatContent=responsesContent.map {part->part.jsonObject.let {item->when(item.getValue("type").jsonPrimitive.content) {
                "input_text"->StudyWire.payload("type" to "text","text" to item.getValue("text"))
                else->StudyWire.payload("type" to "image_url","image_url" to StudyWire.payload("url" to item.getValue("image_url"),"detail" to "high"))
            }}}
            put("messages",JsonArray(listOf(StudyWire.payload("role" to "system","content" to instructions),
                StudyWire.payload("role" to "user","content" to JsonArray(chatContent)))))
            put("response_format",StudyWire.payload("type" to "json_schema","json_schema" to format))
        }
    }
    private fun extractText(raw: JsonObject): String {
        require(raw["model"]?.jsonPrimitive?.content==config.modelId) {"MODEL_REPORTED_ID_MISMATCH"}
        if(config.wireApi=="responses") {
            require(raw["status"]?.jsonPrimitive?.content=="completed") {"MODEL_OUTPUT_INCOMPLETE"}
            val parts=raw.getValue("output").jsonArray.map {it.jsonObject}.filter {it["type"]==JsonPrimitive("message")}.flatMap {it.getValue("content").jsonArray}.map {it.jsonObject}
            require(parts.none {it["type"]==JsonPrimitive("refusal")}) {"MODEL_REFUSAL"}
            return parts.filter {it["type"]==JsonPrimitive("output_text")}.single().getValue("text").jsonPrimitive.content
        }
        val choice=raw.getValue("choices").jsonArray.single().jsonObject
        require(choice["finish_reason"]==JsonPrimitive("stop")) {"MODEL_OUTPUT_INCOMPLETE"}
        val message=choice.getValue("message").jsonObject
        require(message["refusal"]==null || message["refusal"]==JsonNull) {"MODEL_REFUSAL"}
        return message.getValue("content").jsonPrimitive.content
    }
    private suspend fun exchange(id: String,body: JsonObject,key: String): RawHarnessResponse = suspendCancellableCoroutine { continuation ->
        val started=System.nanoTime();val endpoint=config.resolvedEndpoint()
        val call=(routeClient?.invoke(endpoint,http) ?: http).newCall(Request.Builder().url(endpoint).header("Authorization","Bearer $key")
            .header("X-Client-Request-Id",id).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {if(continuation.isActive) continuation.resumeWithException(e)}
            override fun onResponse(call: Call,response: Response) {
                try {
                    val result=response.use {
                        val data=requireNotNull(response.body).byteStream().use {input->
                            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(true) {val n=input.read(buffer);if(n<0) break;require(output.size()+n<=1024*1024) {"MODEL_RESPONSE_TOO_LARGE"};output.write(buffer,0,n)}
                            output.toByteArray().toString(Charsets.UTF_8)
                        }
                        val safe=data.replace(key,"[REDACTED]")
                        RawHarnessResponse(id,response.code,safe,StudyWire.sha256(safe.toByteArray()),System.currentTimeMillis(),(System.nanoTime()-started)/1_000_000,
                            if(config.wireApi=="responses") "PROVIDER_RESPONSES" else "PROVIDER_CHAT_COMPLETIONS",safe!=data)
                    }
                    if(continuation.isActive) continuation.resume(result)
                } catch(error: Exception) {if(continuation.isActive) continuation.resumeWithException(error)}
            }
        })
    }
    companion object {
        /** The prompt a session runs under; the forced-demo variant always asks for a service. */
        const val PROMPT_STANDARD="prompt.md"
        const val PROMPT_FORCE_SERVICE="prompt_force.md"
        fun create(context: Context,config: DirectModelConfig,promptAsset: String=PROMPT_STANDARD,
                   useHr: Boolean=true,clockEpochMs: ()->Long=System::currentTimeMillis,
                   proactivityEffort: ProactivityEffort=ProactivityEffort.RESTRAINED,key: ()->String): DirectModelClient {
            fun asset(name: String)=context.assets.open("direct_model/$name").use {it.readBytes()}
            val hr=useHr && promptAsset==PROMPT_STANDARD
            return DirectModelClient(config,key,asset(promptAsset).toString(Charsets.UTF_8),StudyWire.json.parseToJsonElement(asset("model_output.schema.json").toString(Charsets.UTF_8)).jsonObject,
                StudyWire.sha256(asset("inference_request_v2.schema.json")),StudyWire.sha256(asset("inference_response_v2.schema.json")),
                ai.opstate.rokid.phone.BuildConfig.DIRECT_MODEL_CODE_SHA256,routeClient=InternetRouting(context)::client,
                imagePreprocessor=ModelImagePreprocessor::prepare,
                hrStepPrompt=if(hr) asset("hr_step.md").toString(Charsets.UTF_8) else null,
                hrFinalPrompt=if(hr) asset("hr_final.md").toString(Charsets.UTF_8) else null,
                hrStepSchema=if(hr) StudyWire.json.parseToJsonElement(asset("hr_step.schema.json").toString(Charsets.UTF_8)).jsonObject else null,
                hrFinalSchema=if(hr) StudyWire.json.parseToJsonElement(asset("hr_final.schema.json").toString(Charsets.UTF_8)).jsonObject else null,
                hrGuaranteedPrompt=if(hr) asset("hr_guaranteed.md").toString(Charsets.UTF_8) else null,
                hrGuaranteedSchema=if(hr) StudyWire.json.parseToJsonElement(asset("hr_guaranteed.schema.json").toString(Charsets.UTF_8)).jsonObject else null,
                clockEpochMs=clockEpochMs,proactivityEffort=proactivityEffort)
        }
        fun canonical(value: JsonElement): JsonElement=when(value) {
            is JsonObject->JsonObject(value.toSortedMap().mapValues {canonical(it.value)})
            is JsonArray->JsonArray(value.map(::canonical))
            else->value
        }
    }
}
