package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse
import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.HrAgentOutput

/** The runtime owns observations/delivery; the client owns one frozen model connection. */
interface InferenceClient {
    suspend fun contract(expectedModelId: String?=null): FrozenHarnessContract
    suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit={}): InferenceResponse
    suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        onRaw: (RawHarnessResponse)->Unit={}): InferenceResponse = evaluate(request,currentJpeg,onRaw)
}

/** Separate capability so the actor and device fixtures can exercise the full HR turn. */
interface HrInferenceClient : InferenceClient {
    val hrEnabled: Boolean
    suspend fun evaluateHrTurn(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        history: CausalContextStore,agentHistory: List<HrAgentOutput>,
        onRaw: (RawHarnessResponse)->Unit={}): HrTurnOutcome
}
