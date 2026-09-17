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
    @Volatile private var started=false

    private val callback=object : CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String?, caps: Caps?, binary: ByteArray?) {
            if(name!=STUDY_CHANNEL || caps==null || caps.size()!=1) return
            val value=caps.at(0)
            if(value.type()!=Caps.Value.TYPE_STRING) return
            val message=value.string ?: return
            if(message.toByteArray().size>MAX_CONTROL_BYTES) { _error.value="CONTROL_MESSAGE_TOO_LARGE"; return }
            if(incoming.trySend(message).isFailure) _error.value="CONTROL_QUEUE_OVERFLOW"
        }
    }

    private fun subscribe() = bridge.subscribe(STUDY_CHANNEL,callback)

    /**
     * Puts the glasses into the pairable state that advertises the CXR BLE service UUID
     * (00009100-…). The phone discovers glasses only through a scan filtered on that UUID, so
     * without this call the glasses is invisible to it no matter how the two are bonded.
     */
    fun startBTPairing() {
        // The no-arg native helper announces DEVICE_TYPE_UNKNOWN (0), which the CXR service rejects
        // with "CXRControl args invalid"; declare the Android peer type the listener already defines.
        val outcome=runCatching {
            val status=bridge.sendMessage("startBTPairing",Caps().apply { writeUInt32(DEVICE_TYPE_ANDROID) })
            check(status==0) { "CXR_SEND_$status" }
        }
        android.util.Log.i("OPSTATE","BT_PAIRING type=$DEVICE_TYPE_ANDROID result=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "OK"}")
    }

    private companion object { const val DEVICE_TYPE_ANDROID=1 }

    @Synchronized fun start() {
        if(started) return
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, id: String?, type: Int) {
                _connected.value=true; _error.value=null
                // The registration is per process but does not survive a Bluetooth drop, so renew it on
                // every reconnect. -2 means the SDK already holds it, which is the state we want.
                if(started) {
                    val status=subscribe()
                    if(status!=0 && status!=-2) _error.value="CXR_SUBSCRIBE_$status"
                }
            }
            override fun onConnecting(name: String?, id: String?, type: Int) { _connected.value=false }
            override fun onDisconnected() { _connected.value=false }
            override fun onARTCStatus(health: Float, reset: Boolean) = Unit
            override fun onRokidAccountChanged(account: String?) = Unit
        })
        val status=subscribe()
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
