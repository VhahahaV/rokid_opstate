package ai.opstate.rokid.glasses.capture

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes capture requests and keeps camera mechanics behind one interface.
 * The concrete camera adapter may use Rokid media APIs or Camera2.
 */
class CaptureCoordinator(
    private val camera: WarmCameraSession,
    private val uploader: ImageUploader,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val completed = LinkedHashMap<String, CaptureResult>()

    suspend fun warmUp() {
        camera.openLowResolutionPreview()
    }

    suspend fun capture(request: CaptureRequest): CaptureResult = mutex.withLock {
        completed[request.captureId]?.let { return it }
        if (SystemClock.elapsedRealtime() > request.deadlineElapsedMs) {
            return CaptureResult.Failed(request.captureId, "CAPTURE_REQUEST_EXPIRED")
        }
        val frame = try {
            camera.captureJpeg(request.width, request.height, request.jpegQuality)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            return CaptureResult.Failed(request.captureId, "CAMERA_FAILURE:${t.javaClass.simpleName}")
        }
        val uploaded = uploader.upload(
            captureId = request.captureId,
            capturedAtEpochMs = frame.capturedAtEpochMs ?: nowEpochMs(),
            jpeg = frame.jpeg,
            width = frame.width,
            height = frame.height,
            rotationDegrees = frame.rotationDegrees,
        )
        val result = if (uploaded) CaptureResult.Succeeded(request.captureId)
        else CaptureResult.Failed(request.captureId, "TRANSFER_FAILURE")
        completed[request.captureId] = result
        while (completed.size > 128) completed.remove(completed.keys.first())
        result
    }
}

data class CaptureRequest(
    val captureId: String,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
    val deadlineElapsedMs: Long,
)

data class CameraJpeg(
    val jpeg: ByteArray, val width: Int, val height: Int, val rotationDegrees: Int,
    val capturedAtEpochMs: Long? = null,
)

sealed interface CaptureResult {
    val captureId: String
    data class Succeeded(override val captureId: String) : CaptureResult
    data class Failed(override val captureId: String, val code: String) : CaptureResult
}

interface WarmCameraSession {
    suspend fun openLowResolutionPreview()
    suspend fun captureJpeg(width: Int, height: Int, jpegQuality: Int): CameraJpeg
    suspend fun close()
}

interface ImageUploader {
    suspend fun upload(
        captureId: String,
        capturedAtEpochMs: Long,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Boolean
}
