package ai.opstate.rokid.phone.rokid

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** SDK boundary. Production uses CxrPhoneLink; transport tests can exercise wire failures without a licensed device. */
interface CxrLinkPort {
    val state: StateFlow<CxrLinkState>
    val messages: Flow<String>
    suspend fun scan(durationMs: Long=8_000): List<CxrDevice>
    fun savedDevice(): CxrDevice?
    suspend fun connect(device: CxrDevice)
    fun startP2p(): Result<Unit>
    suspend fun send(message: String): Result<Unit>
    fun disconnect()
}
