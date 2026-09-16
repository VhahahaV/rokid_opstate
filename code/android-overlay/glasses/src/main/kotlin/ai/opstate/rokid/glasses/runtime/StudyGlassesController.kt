package ai.opstate.rokid.glasses.runtime

import ai.opstate.rokid.glasses.capture.CaptureCoordinator
import ai.opstate.rokid.glasses.capture.CaptureRequest
import ai.opstate.rokid.glasses.sensor.AndroidImuSampler
import ai.opstate.rokid.glasses.sensor.VadOnlyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Thin device client: sensors, capture, cards and acknowledgements only. */
class StudyGlassesController(
    private val scope: CoroutineScope,
    private val transport: GlassesTransport,
    private val imuSampler: AndroidImuSampler,
    private val vadGate: VadOnlyGate?,
    private val captureCoordinator: CaptureCoordinator,
    private val renderer: StudyRenderer,
) {
    private var sessionEpoch: Long = -1L
    private val processedIdempotencyKeys = ConcurrentHashMap.newKeySet<String>()
    private var collectJob: Job? = null

    fun start() {
        imuSampler.start()
        vadGate?.start()
        collectJob = scope.launch {
            transport.commands.collect { command -> handle(command) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        imuSampler.stop()
        vadGate?.stop()
        scope.launch { renderer.clearAll() }
    }

    private suspend fun handle(command: DeviceCommand) {
        if (command.type == "SESSION_START") {
            sessionEpoch = command.sessionEpoch
            processedIdempotencyKeys.clear()
            renderer.clearAll()
            transport.sendReady(sessionEpoch)
            return
        }
        if (command.sessionEpoch != sessionEpoch) return
        if (command.expiresAtEpochMs != null && System.currentTimeMillis() > command.expiresAtEpochMs) return
        if (!processedIdempotencyKeys.add(command.idempotencyKey)) return

        when (command.type) {
            "CAPTURE_REQUEST" -> {
                val request = command.captureRequest ?: return
                transport.sendCaptureStarted(request.captureId)
                val result = captureCoordinator.capture(request)
                transport.sendCaptureResult(result)
            }
            "SHOW_CONTEXT_CARD" -> renderer.showContext(command.payloadJson)
            "CLEAR_CONTEXT_CARD" -> renderer.clearContext()
            "SHOW_SERVICE_CARD" -> renderer.showService(command.payloadJson)
            "CLEAR_SERVICE_CARD" -> renderer.clearService()
            "CLEAR_ALL", "SESSION_RESET" -> renderer.clearAll()
        }
    }
}

interface GlassesTransport {
    val commands: kotlinx.coroutines.flow.Flow<DeviceCommand>
    suspend fun sendReady(epoch: Long)
    suspend fun sendCaptureStarted(captureId: String)
    suspend fun sendCaptureResult(result: ai.opstate.rokid.glasses.capture.CaptureResult)
}

interface StudyRenderer {
    suspend fun showContext(payloadJson: String)
    suspend fun showService(payloadJson: String)
    suspend fun clearContext()
    suspend fun clearService()
    suspend fun clearAll()
}

data class DeviceCommand(
    val type: String,
    val sessionEpoch: Long,
    val idempotencyKey: String,
    val expiresAtEpochMs: Long?,
    val payloadJson: String,
    val captureRequest: CaptureRequest? = null,
)
