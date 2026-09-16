package ai.opstate.rokid.phone.runtime

import ai.opstate.protocol.StudyWire
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable
data class DirectModelConfig(
    val modelId: String="qwen-3.8-max",
    val endpoint: String="https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    val wireApi: String="chat_completions",
    val maxOutputTokens: Int=512,
    val temperature: Double=0.0,
    val topP: Double=1.0,
    val seed: Long?=20260824,
    val enableThinking: Boolean?=false,
    val reasoningEffort: String?=null,
    val responseLanguage: String="zh-CN",
    val timeoutMs: Long=15000,
    val decisionTtlMs: Long=20000,
) {
    fun resolvedEndpoint(): String {
        var base=endpoint.trim().trimEnd('/')
        for(suffix in listOf("/chat/completions","/responses")) if(base.endsWith(suffix)) base=base.removeSuffix(suffix)
        if(URI(base).path.isNullOrEmpty()) base+="/v1"
        return base+if(wireApi=="responses") "/responses" else "/chat/completions"
    }
    fun credentialScope(): String = resolvedEndpoint().removeSuffix("/chat/completions").removeSuffix("/responses")
    fun validate(allowTestLoopback: Boolean=false) {
        require(wireApi in setOf("chat_completions","responses")) {"MODEL_API_FORMAT_INVALID"}
        val uri=URI(resolvedEndpoint())
        require(!uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null &&
            (uri.scheme=="https" || (allowTestLoopback && uri.scheme=="http" && uri.host in setOf("127.0.0.1","localhost")))) {"MODEL_API_URL_INVALID"}
        require(modelId.isNotBlank() && modelId.length<=160 && modelId.none {it.isISOControl()}) {"MODEL_ID_INVALID"}
        require(maxOutputTokens in 64..16384 && temperature.isFinite() && temperature in 0.0..2.0 && topP.isFinite() && topP in 0.0..1.0 &&
            timeoutMs in 1000..60000 && decisionTtlMs in 1000..60000) {"MODEL_PARAMETERS_INVALID"}
        require(reasoningEffort==null || reasoningEffort in setOf("none","minimal","low","medium","high","xhigh")) {"MODEL_PARAMETERS_INVALID"}
    }
    fun parameters(): JsonObject=if(wireApi=="responses") buildJsonObject {
        put("max_output_tokens",maxOutputTokens);reasoningEffort?.let {put("reasoning_effort",it)}
    } else buildJsonObject {
        put("max_output_tokens",maxOutputTokens);put("temperature",temperature);put("top_p",topP)
        seed?.let {put("seed",it)};enableThinking?.let {put("enable_thinking",it)}
    }
    fun publicSnapshot(): JsonObject=StudyWire.payload("connection_mode" to "DIRECT_API_KEY","model_id" to modelId,
        "endpoint" to resolvedEndpoint(),"wire_api" to wireApi,"parameters" to parameters(),"response_language" to responseLanguage,
        "timeout_ms" to timeoutMs,"decision_ttl_ms" to decisionTtlMs)
    companion object {
        fun qwen()=DirectModelConfig()
        fun gpt()=DirectModelConfig("gpt-5.6-sol","https://api.shuaiapi.com/v1/responses","responses",4096,
            seed=null,enableThinking=null,reasoningEffort="high")
    }
}
