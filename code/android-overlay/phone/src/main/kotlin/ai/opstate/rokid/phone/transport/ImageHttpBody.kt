package ai.opstate.rokid.phone.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Session cancellation closes the socket even while headers or image bytes are still arriving. */
internal suspend fun Call.readImageBody(expectedBytes: Int): ByteArray {
    require(expectedBytes in 1..8*1024*1024)
    return suspendCancellableCoroutine {continuation->
        continuation.invokeOnCancellation {cancel()}
        enqueue(object : Callback {
            override fun onFailure(call: Call,e: IOException) {if(continuation.isActive) continuation.resumeWithException(e)}
            override fun onResponse(call: Call,response: Response) {
                try {
                    val bytes=response.use {
                        check(it.isSuccessful) {"P2P_HTTP_FAILED"}
                        val body=requireNotNull(it.body)
                        check(body.contentLength()==expectedBytes.toLong()) {"P2P_IMAGE_LENGTH_MISMATCH"}
                        body.bytes().also {data->check(data.size==expectedBytes) {"P2P_IMAGE_LENGTH_MISMATCH"}}
                    }
                    if(continuation.isActive) continuation.resume(bytes)
                } catch(error: Exception) {if(continuation.isActive) continuation.resumeWithException(error)}
            }
        })
    }
}
