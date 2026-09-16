package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.protocol.StudyWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class FrozenHarnessContract(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("prompt_hash") val promptHash: String,
    @SerialName("preprocess_hash") val preprocessHash: String,
    @SerialName("parser_hash") val parserHash: String,
    @SerialName("request_schema_sha256") val requestSchemaSha256: String,
    @SerialName("response_schema_sha256") val responseSchemaSha256: String,
    val mock: Boolean=false,
    @SerialName("mock_mode") val mockMode: String?=null,
    @SerialName("not_for_paper") val notForPaper: Boolean=false,
    @SerialName("strict_contract") val strictContract: Boolean=false,
    @SerialName("model_config") val modelConfig: JsonObject?=null,
)
data class RawHarnessResponse(val requestId: String,val httpStatus: Int,val body: String,val bodySha256: String,
    val receivedAtEpochMs: Long,val latencyMs: Long,val format: String="OPSTATE_V2",val redacted: Boolean=false)

/** One configured endpoint, cancellable HTTP, and raw persistence before parsing/delivery. */
class RemoteHarnessClient(
    private val baseUrl: String,
    private val bearerTokenProvider: () -> String,
    json: Json,
    client: OkHttpClient? = null,
    private val routeClient: ((String,OkHttpClient)->OkHttpClient)?=null,
) : InferenceClient {
    private val json=Json(json) { ignoreUnknownKeys=false; isLenient=false }
    private val http=client ?: OkHttpClient.Builder()
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS)
        .callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override suspend fun contract(expectedModelId: String?): FrozenHarnessContract {
        val raw=exchange("contract",requestBuilder("/v1/contract").get().build())
        if(raw.httpStatus !in 200..299) throw IOException("harness contract HTTP ${raw.httpStatus}")
        return json.decodeFromString(FrozenHarnessContract.serializer(),raw.body).also { contract ->
            require(expectedModelId.isNullOrBlank() || expectedModelId==contract.modelId) { "CONFIGURED_MODEL_MISMATCH" }
        }
    }

    override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("request",json.encodeToString(request.toWire()))
            .addFormDataPart("image","current.jpg",jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        val httpRequest=requestBuilder("/v1/evaluate").header("Idempotency-Key",request.requestId).post(multipart).build()
        val raw=exchange(request.requestId,httpRequest)
        return withContext(Dispatchers.IO) {
            onRaw(raw)
            if(raw.httpStatus !in 200..299) throw IOException("harness HTTP ${raw.httpStatus}")
            ResponseParser.parse(json,raw.body,request.protocolVersion)
        }
    }
    private fun requestBuilder(path: String): Request.Builder = Request.Builder().url(baseUrl.trimEnd('/')+path).also { builder ->
        bearerTokenProvider().takeIf {it.isNotBlank()}?.let {builder.header("Authorization","Bearer $it")}
    }
    private suspend fun exchange(id: String,request: Request): RawHarnessResponse = suspendCancellableCoroutine { continuation ->
        val started=System.nanoTime()
        val client=routeClient?.invoke(baseUrl,http) ?: http
        val call=client.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object : Callback {
            override fun onFailure(call: Call,error: IOException) {if(continuation.isActive) continuation.resumeWithException(error)}
            override fun onResponse(call: Call,response: Response) {
                try {
                    val result=response.use {
                        val body=response.body ?: throw IOException("empty harness response")
                        val bytes=body.byteStream().use {input->
                            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(true) {val read=input.read(buffer);if(read<0) break
                                if(output.size()+read>1024*1024) throw IOException("harness response exceeds 1 MiB")
                                output.write(buffer,0,read)}
                            output.toByteArray()
                        }
                        RawHarnessResponse(id,response.code,bytes.toString(Charsets.UTF_8),StudyWire.sha256(bytes),System.currentTimeMillis(),(System.nanoTime()-started)/1_000_000)
                    }
                    if(continuation.isActive) continuation.resume(result)
                } catch(error: Exception) {if(continuation.isActive) continuation.resumeWithException(error)}
            }
        })
    }
}
