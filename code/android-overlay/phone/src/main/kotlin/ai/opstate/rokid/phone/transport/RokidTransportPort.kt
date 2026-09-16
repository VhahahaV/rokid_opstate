package ai.opstate.rokid.phone.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ai.opstate.protocol.StudyMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Adapter boundary for either the current Rokid Security SDK or the legacy CXR
 * stack. Only one implementation may be active in a release build.
 */
interface RokidTransportPort {
    val state: StateFlow<RokidConnectionState>
    val events: Flow<RokidEvent>

    suspend fun scan(): Result<List<RokidDevice>>
    suspend fun connect(device: RokidDevice): Result<Unit>
    suspend fun disconnect()
    suspend fun sendControl(message: ByteArray): Result<Unit>
    suspend fun requestCapture(command: CaptureCommand): Result<Unit>
    suspend fun clearAllDisplays(sessionEpoch: Long): Result<Unit>
}

interface StudyTransport : RokidTransportPort {
    val ready: StateFlow<Boolean>
    suspend fun startSession(id: String,epoch: Long): Result<Unit>
    fun controlMessage(type: String,payload: JsonObject=JsonObject(emptyMap()),ttlMs: Long?=null): StudyMessage
    suspend fun command(message: StudyMessage): Result<Unit>
    fun peerBuildInfo(): PeerBuildInfo?=null
    suspend fun refreshPeer(): Result<PeerBuildInfo?> = Result.success(peerBuildInfo())
}

@Serializable
data class PeerBuildInfo(val firmware: String,@SerialName("app_version") val appVersion: String,@SerialName("apk_sha256") val apkSha256: String?)

data class RokidDevice(val id: String,val displayName: String,val systemPaired: Boolean=false,val advertising: Boolean=false)

data class CaptureCommand(
    val sessionId: String,
    val sessionEpoch: Long,
    val captureId: String,
    val reasonCodes: Set<String>,
    val width: Int = 1280,
    val height: Int = 720,
    val jpegQuality: Int = 80,
    val deadlineElapsedMs: Long,
    val captureTimeoutMs: Long = 5_000,
)

sealed interface RokidConnectionState {
    data object Disconnected : RokidConnectionState
    data object Scanning : RokidConnectionState
    data class Connecting(val device: RokidDevice) : RokidConnectionState
    data class Connected(
        val device: RokidDevice,
        val controlChannel: String,
        val mediaChannel: String,
    ) : RokidConnectionState
    data class Failed(val code: String, val recoverable: Boolean) : RokidConnectionState
}

sealed interface RokidEvent {
    data class DeviceReady(val firmware: String, val appVersion: String) : RokidEvent
    data class ConnectionLost(val code: String) : RokidEvent
    data class SensorWindowReceived(val window: SensorWindowPacket) : RokidEvent
    data class ImageReceived(val frame: ImageFrame) : RokidEvent
    data class CaptureBufferStatus(val payload: JsonObject) : RokidEvent
    data class CaptureAcknowledged(val captureId: String,val receivedElapsedMs: Long) : RokidEvent
    data class CaptureFailed(val captureId: String,val code: String) : RokidEvent
    data class SessionStatusVisible(val sessionId: String,val sessionEpoch: Long,val statusId: String,val renderFrameCount: Int) : RokidEvent
    data class DisplayVisibleAck(
        val sessionId: String,
        val sessionEpoch: Long,
        val decisionId: String,
        val visibleAtEpochMs: Long,
        val renderFrameCount: Int,
    ) : RokidEvent
    data class DisplayDismissed(val decisionId: String, val reason: String) : RokidEvent
    data class DisplayFailed(val sessionId: String,val sessionEpoch: Long,val decisionId: String,val code: String) : RokidEvent
    data class UserAction(val decisionId: String?, val action: String, val atEpochMs: Long) : RokidEvent
    data class TransportError(val code: String, val detail: String, val recoverable: Boolean) : RokidEvent
}

data class SensorWindowPacket(
    val windowStartElapsedMs: Long,
    val windowEndElapsedMs: Long,
    val sampleCount: Int,
    val angularVelocityRmsDegPerSec: Double,
    val angularVelocityP95DegPerSec: Double,
    val linearAccelerationRmsMps2: Double,
    val linearAccelerationP95Mps2: Double,
    val stableRatio: Double,
    val isStable: Boolean,
    val isMoving: Boolean,
    val speechRatio: Double?,
    val voiceSessionActive: Boolean,
    val millisSinceLastInteraction: Long,
    val contextCardVisible: Boolean,
    val serviceCardVisible: Boolean,
)

data class ImageFrame(
    val captureId: String,
    val capturedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sha256: String,
    val transferChannel: String,
    val cameraDurationMs: Long?=null,
    val transferDurationMs: Long?=null,
    val buffered: Boolean=false,
    val sourceMetadata: JsonObject=JsonObject(emptyMap()),
)
