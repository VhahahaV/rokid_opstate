package ai.opstate.rokid.phone.rokid

import ai.opstate.protocol.MAX_CONTROL_BYTES
import ai.opstate.protocol.STUDY_CHANNEL
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

interface CxrCredentialsProvider {
    fun clientSecret(): String?
    fun licenseBytes(): ByteArray?
}

data class CxrDevice(val address: String,val name: String,val systemPaired: Boolean=false,val advertising: Boolean=false)
data class CxrLinkState(
    val phase: String = "DISCONNECTED",
    val bluetoothConnected: Boolean = false,
    val p2pConnected: Boolean = false,
    val errorCode: String? = null,
    val p2pPhase: String = "IDLE",
    val p2pInitStatus: String? = null,
)

/** Only the official CXR-M SDK owns Bluetooth/P2P. No simulated connection states. */
class CxrPhoneLink(context: Context, private val credentials: CxrCredentialsProvider) : CxrLinkPort {
    private val context = context.applicationContext
    private val api by lazy { CxrApi.getInstance() }
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val preferences = context.getSharedPreferences("cxr-connection", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CxrLinkState())
    override val state: StateFlow<CxrLinkState> = _state.asStateFlow()
    private val incoming = Channel<String>(256)
    override val messages: Flow<String> = incoming.receiveAsFlow()
    private val connectMutex = Mutex()
    private val sendMutex = Mutex()
    @Volatile private var generation = 0L
    @Volatile private var p2pGeneration = 0L
    private var wifiDirect: StudyWifiDirect?=null
    /**
     * Set when a saved socket identity fails to connect, so the next attempt re-runs the official BLE
     * initialization instead of the saved fast path. The saved identity is deliberately kept: a socket
     * failure is usually transient (out of range), and discarding it made reconnection impossible
     * without the glasses being put back into its physical pairing state.
     */
    @Volatile private var identitySuspect = false

    private fun requirePermissions() {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        check(required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { "BLUETOOTH_PERMISSION_REQUIRED" }
        check(manager.adapter?.isEnabled == true) { "BLUETOOTH_DISABLED" }
    }

    fun pairedDevices(): List<CxrDevice> {
        if(Build.VERSION.SDK_INT>=31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return emptyList()
        val selected=preferences.getString("selected_address",null)
        return manager.adapter?.bondedDevices.orEmpty().mapNotNull { device ->
            val name=device.name.orEmpty()
            if(device.address==selected || name.startsWith("Glasses_",ignoreCase=true) || name.contains("Rokid",ignoreCase=true))
                CxrDevice(device.address,name.ifBlank {"Rokid glasses"},systemPaired=true) else null
        }.sortedBy {it.name}
    }

    override suspend fun scan(durationMs: Long): List<CxrDevice> {
        require(durationMs in 1_000..30_000)
        requirePermissions()
        val found = ConcurrentHashMap<String, CxrDevice>()
        pairedDevices().forEach {found[it.address]=it}
        val scanner = requireNotNull(manager.adapter.bluetoothLeScanner)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    found[result.device.address] = CxrDevice(result.device.address,result.scanRecord?.deviceName ?: result.device.name ?: "Rokid glasses",
                        systemPaired=result.device.bondState==android.bluetooth.BluetoothDevice.BOND_BONDED,advertising=true)
                } catch (_: SecurityException) { _state.update { it.copy(errorCode="BLUETOOTH_PERMISSION_REVOKED") } }
            }
            override fun onScanFailed(errorCode: Int) { _state.update { it.copy(errorCode="BLE_SCAN_$errorCode") } }
        }
        _state.update { it.copy(phase="SCANNING",errorCode=null) }
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb")).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback,
            )
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
            _state.update { it.copy(phase=if(it.bluetoothConnected) "CONNECTED" else "DISCONNECTED") }
        }
        return found.values.sortedWith(compareByDescending<CxrDevice> {it.advertising}.thenBy {it.name})
    }

    override fun savedDevice(): CxrDevice? = preferences.getString("selected_address",null)?.let {
        CxrDevice(it,preferences.getString("selected_name",null) ?: "Rokid glasses")
    }

    fun hasDeviceIdentity(): Boolean = preferences.getString("socket_uuid",null)!=null && preferences.getString("socket_address",null)!=null

    private fun rememberIdentity(device: CxrDevice,uuid: String?,address: String?) {
        require(!uuid.isNullOrBlank() && !address.isNullOrBlank()) { "INVALID_CONNECTION_INFO" }
        java.util.UUID.fromString(uuid)
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) { "INVALID_CONNECTION_INFO" }
        check(preferences.edit().putString("socket_uuid",uuid).putString("socket_address",address)
            .putString("selected_address",device.address).putString("selected_name",device.name).commit()) { "CONNECTION_INFO_SAVE_FAILED" }
        identitySuspect=false
    }

    /** Official BLE initialization only. This never calls the licensed data-channel API or reports SDK connected. */
    suspend fun identify(device: CxrDevice)=connectMutex.withLock {
        requirePermissions()
        check(!_state.value.bluetoothConnected) { "DISCONNECT_BEFORE_IDENTIFY" }
        val attempt=++generation
        val identity=CompletableDeferred<Unit>()
        _state.value=CxrLinkState(phase="IDENTIFYING")
        val callback=object : BluetoothStatusCallback {
            override fun onConnectionInfo(uuid: String?,macAddress: String?,account: String?,glassesType: Int) {
                if(attempt!=generation) return
                try {rememberIdentity(device,uuid,macAddress);identity.complete(Unit)}
                catch(error: Exception) {identity.completeExceptionally(error)}
            }
            override fun onConnected() = Unit
            override fun onInActiveConnected(uuid: String?,macAddress: String?) {
                if(attempt==generation) identity.completeExceptionally(IllegalStateException("CXR_OTHER_CLIENT_ACTIVE"))
            }
            override fun onDisconnected() {
                if(attempt==generation && !identity.isCompleted) identity.completeExceptionally(IllegalStateException("BLUETOOTH_DISCONNECTED"))
            }
            override fun onFailed(error: ValueUtil.CxrBluetoothErrorCode?) {
                if(attempt==generation) identity.completeExceptionally(IllegalStateException("CXR_${error?.name ?: "UNKNOWN"}"))
            }
        }
        try {
            api.initBluetooth(context,manager.adapter.getRemoteDevice(device.address),callback)
            withTimeout(20_000) {identity.await()}
            _state.value=CxrLinkState(phase="IDENTIFIED")
        } catch(error: Exception) {
            _state.value=CxrLinkState(phase="ERROR",errorCode=if(error is TimeoutCancellationException) "DEVICE_IDENTIFICATION_TIMEOUT" else error.message ?: "DEVICE_IDENTIFICATION_FAILED")
            throw error
        } finally {
            generation++
            runCatching {api.deinitBluetooth()}
        }
    }

    override suspend fun connect(device: CxrDevice) = connectMutex.withLock {
        requirePermissions()
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(device.address)) { "INVALID_BLUETOOTH_ADDRESS" }
        val secret = credentials.clientSecret()?.trim()?.replace("-", "")?.takeIf { it.isNotEmpty() }
            ?: authorizationRequired("CXR_CLIENT_SECRET_REQUIRED")
        val license = credentials.licenseBytes()?.takeIf { it.isNotEmpty() } ?: authorizationRequired("CXR_SN_LICENSE_REQUIRED")
        val attempt = ++generation
        val connected = CompletableDeferred<Unit>()
        _state.value = CxrLinkState(phase="CONNECTING")
        api.setCustomCmdListener { key, caps ->
            if (key == STUDY_CHANNEL && caps != null && caps.size() == 1) {
                val value = caps.at(0)
                if (value.type() == Caps.Value.TYPE_STRING) {
                    val text = value.string
                    if (text != null && text.toByteArray().size <= MAX_CONTROL_BYTES && incoming.trySend(text).isFailure) {
                        _state.update { it.copy(errorCode="CONTROL_QUEUE_OVERFLOW") }
                    }
                }
            }
        }
        lateinit var callback: BluetoothStatusCallback
        fun authenticate(uuid: String, address: String) {
            if (attempt != generation) return
            _state.update { it.copy(phase="AUTHENTICATING") }
            api.connectBluetooth(context,uuid,address,callback,license,secret)
        }
        callback = object : BluetoothStatusCallback {
            override fun onConnectionInfo(uuid: String?, macAddress: String?, account: String?, glassesType: Int) {
                if (attempt != generation) return
                if (uuid.isNullOrBlank() || macAddress.isNullOrBlank()) {
                    connected.completeExceptionally(IllegalStateException("INVALID_CONNECTION_INFO")); return
                }
                try {rememberIdentity(device,uuid,macAddress);authenticate(uuid,macAddress)}
                catch (error: Exception) { connected.completeExceptionally(IllegalStateException("CXR_AUTH_INIT_${error.javaClass.simpleName}")) }
            }
            override fun onConnected() {
                if (attempt != generation) return
                _state.value = CxrLinkState(phase="CONNECTED",bluetoothConnected=true)
                connected.complete(Unit)
            }
            override fun onInActiveConnected(uuid: String?, macAddress: String?) {
                if (attempt == generation) {
                    _state.update { it.copy(phase="INACTIVE",errorCode="CXR_OTHER_CLIENT_ACTIVE") }
                    connected.completeExceptionally(IllegalStateException("CXR_OTHER_CLIENT_ACTIVE"))
                }
            }
            override fun onDisconnected() {
                if (attempt != generation) return
                _state.value = CxrLinkState(errorCode="BLUETOOTH_DISCONNECTED")
                connected.completeExceptionally(IllegalStateException("BLUETOOTH_DISCONNECTED"))
            }
            override fun onFailed(error: ValueUtil.CxrBluetoothErrorCode?) {
                if (attempt != generation) return
                val code = "CXR_${error?.name ?: "UNKNOWN"}"
                _state.value = CxrLinkState(phase="ERROR",errorCode=code)
                connected.completeExceptionally(IllegalStateException(code))
            }
        }
        try {
            val savedUuid=preferences.getString("socket_uuid",null)
            val savedAddress=preferences.getString("socket_address",null)
            if (!identitySuspect && preferences.getString("selected_address",null)==device.address && savedUuid!=null && savedAddress!=null) {
                authenticate(savedUuid,savedAddress)
            } else {
                api.initBluetooth(context,manager.adapter.getRemoteDevice(device.address),callback)
            }
            withTimeout(30_000) { connected.await() }
        } catch (error: Exception) {
            generation++
            api.deinitBluetooth()
            // A new pairing/application can rotate the socket UUID while the Android bond remains valid,
            // but a socket failure is far more often transient (walked out of range). Never discard the
            // saved identity; alternate to the fresh official BLE initialization for the next attempt and
            // back again, so a momentary drop recovers on its own while a rotated UUID is still re-learned.
            if(_state.value.errorCode=="CXR_SOCKET_CONNECT_FAILED") identitySuspect=!identitySuspect
            _state.value = CxrLinkState(phase="ERROR",errorCode=if(error is TimeoutCancellationException) "BLUETOOTH_CONNECT_TIMEOUT" else _state.value.errorCode ?: "BLUETOOTH_CONNECT_FAILED")
            throw error
        }
    }

    private fun authorizationRequired(code: String): Nothing {
        _state.update {it.copy(phase="AUTH_REQUIRED",errorCode=code)}
        throw IllegalStateException(code)
    }

    fun forgetPairingMetadata() { preferences.edit().clear().apply() }

    @Synchronized override fun startP2p(): Result<Unit> {
        val attempt=generation
        val p2pAttempt=++p2pGeneration
        return runCatching {
        check(_state.value.bluetoothConnected) { "BLUETOOTH_NOT_CONNECTED" }
        wifiDirect?.close();wifiDirect=null
        _state.update {it.copy(p2pConnected=false,p2pPhase="CONNECTING",p2pInitStatus=null)}
        val status=api.initWifiP2P2(false,object : WifiP2PStatusCallback {
            override fun onConnected() { if(attempt==generation && p2pAttempt==p2pGeneration) _state.update { it.copy(p2pConnected=true,p2pPhase="CONNECTED",errorCode=null) } }
            override fun onDisconnected() { if(attempt==generation && p2pAttempt==p2pGeneration) _state.update { it.copy(p2pConnected=false,p2pPhase="DISCONNECTED",errorCode="P2P_DISCONNECTED") } }
            override fun onFailed(error: ValueUtil.CxrWifiErrorCode?) { if(attempt==generation && p2pAttempt==p2pGeneration) _state.update { it.copy(p2pConnected=false,p2pPhase="FAILED",errorCode="P2P_${error?.name ?: "UNKNOWN"}") } }
            override fun onP2pDeviceAvailable(name: String?, address: String?, extra: String?) {
                if(attempt!=generation || p2pAttempt!=p2pGeneration || wifiDirect!=null) return
                if(address.isNullOrBlank()) {_state.update {it.copy(p2pPhase="FAILED",errorCode="P2P_PEER_INFO_MISSING")};return}
                wifiDirect=StudyWifiDirect(context) {phase,error->
                    if(attempt==generation && p2pAttempt==p2pGeneration)
                        _state.update {it.copy(p2pConnected=phase=="CONNECTED",p2pPhase=phase,errorCode=error)}
                }.also {it.start(address)}
            }
        })
        if(attempt==generation && p2pAttempt==p2pGeneration) _state.update {it.copy(p2pInitStatus=status.name)}
        check(status==ValueUtil.CxrStatus.REQUEST_SUCCEED || status==ValueUtil.CxrStatus.REQUEST_WAITING || status==ValueUtil.CxrStatus.WIFI_INIT) { "P2P_REQUEST_${status.name}" }
    }.onFailure {error->
        val code=error.message?.takeIf {it.matches(Regex("[A-Z_0-9]{1,100}"))} ?: "P2P_INITIALIZATION_FAILED"
        if(attempt==generation && p2pAttempt==p2pGeneration) _state.update {it.copy(p2pConnected=false,p2pPhase="FAILED",errorCode=code)}
    }
    }

    override suspend fun send(message: String): Result<Unit> = sendMutex.withLock {
        runCatching {
            check(_state.value.bluetoothConnected) { "BLUETOOTH_NOT_CONNECTED" }
            require(message.toByteArray().size <= MAX_CONTROL_BYTES) { "CONTROL_MESSAGE_TOO_LARGE" }
            val status=api.sendCustomCmd(STUDY_CHANNEL,Caps().apply { write(message) })
            check(status==ValueUtil.CxrStatus.REQUEST_SUCCEED) { "CONTROL_SEND_${status.name}" }
        }
    }

    override fun disconnect() {
        generation++
        p2pGeneration++;wifiDirect?.close();wifiDirect=null
        api.setCustomCmdListener(null)
        runCatching { api.deinitWifiP2P() }
        api.deinitBluetooth()
        _state.value=CxrLinkState()
    }
}
