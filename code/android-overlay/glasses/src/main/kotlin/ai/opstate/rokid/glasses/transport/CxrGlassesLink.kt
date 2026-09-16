package ai.opstate.rokid.glasses.transport

import ai.opstate.protocol.MAX_CONTROL_BYTES
import ai.opstate.protocol.STUDY_CHANNEL
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Application-owned CXR-S bridge: one SDK instance and one subscription per process. */
class CxrGlassesLink {
    private val bridge = CXRServiceBridge()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val incoming = Channel<String>(256)
    val messages: Flow<String> = incoming.receiveAsFlow()
    private var started=false

    @Synchronized fun start() {
        if(started) return
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, id: String?, type: Int) { _connected.value=true; _error.value=null }
            override fun onConnecting(name: String?, id: String?, type: Int) { _connected.value=false }
            override fun onDisconnected() { _connected.value=false }
            override fun onARTCStatus(health: Float, reset: Boolean) = Unit
            override fun onRokidAccountChanged(account: String?) = Unit
        })
        val status=bridge.subscribe(STUDY_CHANNEL,object : CXRServiceBridge.MsgCallback {
            override fun onReceive(name: String?, caps: Caps?, binary: ByteArray?) {
                if(name!=STUDY_CHANNEL || caps==null || caps.size()!=1) return
                val value=caps.at(0)
                if(value.type()!=Caps.Value.TYPE_STRING) return
                val message=value.string ?: return
                if(message.toByteArray().size>MAX_CONTROL_BYTES) { _error.value="CONTROL_MESSAGE_TOO_LARGE"; return }
                if(incoming.trySend(message).isFailure) _error.value="CONTROL_QUEUE_OVERFLOW"
            }
        })
        check(status==0) { "CXR_SUBSCRIBE_$status" }
        started=true
    }

    @Synchronized fun send(message: String): Result<Unit> = runCatching {
        check(started) { "CXR_BRIDGE_NOT_STARTED" }
        require(message.toByteArray().size<=MAX_CONTROL_BYTES)
        val status=bridge.sendMessage(STUDY_CHANNEL,Caps().apply { write(message) })
        check(status==0) { "CXR_SEND_$status" }
    }
}
