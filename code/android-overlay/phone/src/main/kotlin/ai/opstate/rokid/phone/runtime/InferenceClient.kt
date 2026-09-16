package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.InferenceResponse

/** The runtime owns observations/delivery; the client owns one frozen model connection. */
interface InferenceClient {
    suspend fun contract(expectedModelId: String?=null): FrozenHarnessContract
    suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit={}): InferenceResponse
    suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
        onRaw: (RawHarnessResponse)->Unit={}): InferenceResponse = evaluate(request,currentJpeg,onRaw)
}
