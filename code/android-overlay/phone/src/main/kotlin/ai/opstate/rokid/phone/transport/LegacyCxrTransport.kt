package ai.opstate.rokid.phone.transport

import ai.opstate.protocol.*
import ai.opstate.rokid.phone.rokid.CxrDevice
import ai.opstate.rokid.phone.rokid.CxrLinkPort
import android.os.SystemClock
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Reconnect pacing. Leaving and re-entering Bluetooth range takes minutes, so retries continue. */
private const val INITIAL_RECONNECT_BACKOFF_MS=2_000L
private const val MAX_RECONNECT_BACKOFF_MS=60_000L

/** Maps real CXR messages and P2P bodies into domain events. SDK enqueue is never a display ACK. */
class LegacyCxrTransport(
    private val link: CxrLinkPort,private val scope: CoroutineScope,
    private val elapsedNow: ()->Long = SystemClock::elapsedRealtime,
    private val rpcTimeoutMs: Long=8_000,private val readinessPollMs: Long=250,
) : StudyTransport {
    private val _state=MutableStateFlow<RokidConnectionState>(RokidConnectionState.Disconnected)
    override val state: StateFlow<RokidConnectionState> = _state.asStateFlow()
    private val _events=MutableSharedFlow<RokidEvent>(extraBufferCapacity=256)
    override val events: Flow<RokidEvent> = _events.asSharedFlow()
    private val _ready=MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _handshakeDiagnostics=MutableStateFlow(StudyWire.payload("stage" to "NOT_STARTED"))
    val handshakeDiagnostics: StateFlow<JsonObject> = _handshakeDiagnostics.asStateFlow()
    private val peerSync=Mutex()
    private val replies=ConcurrentHashMap<String,CompletableDeferred<StudyMessage>>()
    private val requested=ConcurrentHashMap<String,CaptureCommand>()
    private val assemblies=ConcurrentHashMap<String,ImageAssembly>()
    private data class TransferTiming(val offeredElapsedMs: Long,val cameraMs: Long?)
    private val transferTimings=ConcurrentHashMap<String,TransferTiming>()
    private val bufferedMetadata=ConcurrentHashMap<String,JsonObject>()
    @Volatile private var bufferedEnabled=false
    @Volatile private var sessionId="link"
    @Volatile private var sessionEpoch=0L
    @Volatile private var connectionGeneration=0L
    @Volatile private var sessionConnectionGeneration: Long?=null
    @Volatile private var sessionExpected=false
    private val completedImages=ConcurrentHashMap<String,ImageDescriptor>()
    private val imageJobs=ConcurrentHashMap<String,Job>()
    private var selected: RokidDevice?=null
    @Volatile private var peerClock: PeerClockEstimate?=null
    @Volatile private var peerBuild: PeerBuildInfo?=null
    override fun peerBuildInfo(): PeerBuildInfo?=peerBuild
    private var handshakeJob: Job?=null
    private var heartbeatJob: Job?=null
    private var reconnectJob: Job?=null
    private var reconnectEnabled=false
    private val http=OkHttpClient.Builder().connectTimeout(800,TimeUnit.MILLISECONDS).readTimeout(2,TimeUnit.SECONDS)
        .callTimeout(3,TimeUnit.SECONDS).retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY).build()
    init {
        scope.launch { link.messages.collect { text ->
            try { receive(StudyWire.decode(text)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { error("PROTOCOL_${error.javaClass.simpleName}",true) }
        } }
        scope.launch {
            var previouslyConnected=false
            link.state.collect { value ->
            val disconnected=previouslyConnected && !value.bluetoothConnected
            previouslyConnected=value.bluetoothConnected
            if(value.bluetoothConnected) {
                if(!_ready.value && handshakeJob?.isActive!=true) handshakeJob=scope.launch { handshake() }
                if(_ready.value) updateConnected()
            } else {
                _ready.value=false;connectionGeneration++;sessionConnectionGeneration=null;peerClock=null
                handshakeJob?.cancel();handshakeJob=null
                heartbeatJob?.cancel()
                imageJobs.values.forEach {it.cancel()};imageJobs.clear()
                val dropped=requested.values.toList();requested.clear();assemblies.clear();transferTimings.clear();completedImages.clear()
                if(disconnected) _events.emit(RokidEvent.ConnectionLost(value.errorCode ?: "BLUETOOTH_DISCONNECTED"))
                dropped.forEach {_events.emit(RokidEvent.CaptureFailed(it.captureId,"CONNECTION_LOST"))}
                replies.values.forEach { it.cancel() }; replies.clear()
                if(value.phase=="CONNECTING" || value.phase=="AUTHENTICATING") {
                    selected?.let { _state.value=RokidConnectionState.Connecting(it) }
                } else if(value.errorCode!=null) {
                    _state.value=RokidConnectionState.Failed(value.errorCode,true)
                    error(value.errorCode,true)
                    if(reconnectEnabled && value.errorCode=="BLUETOOTH_DISCONNECTED") reconnect()
                } else _state.value=RokidConnectionState.Disconnected
            }
        } }
    }
    override suspend fun scan(): Result<List<RokidDevice>> = runCatching { link.scan().map { RokidDevice(it.address,it.name,it.systemPaired,it.advertising) } }
    override suspend fun connect(device: RokidDevice): Result<Unit> = try {
        selected=device; reconnectEnabled=true
        link.connect(CxrDevice(device.id,device.displayName))
        withTimeout(30_000) { ready.first { it } }
        Result.success(Unit)
    } catch(cancelled: CancellationException) {
        if(cancelled !is TimeoutCancellationException) throw cancelled
        // The CXR link came up but the glasses app never answered the handshake. That is exactly the
        // state after a glasses reboot, before its service has started, so keep retrying rather than
        // reporting a failure the operator would have to resolve by hand.
        reconnect()
        Result.failure(IllegalStateException("GLASSES_APP_HANDSHAKE_TIMEOUT"))
    } catch(error: Exception) {
        reconnect()
        Result.failure(error)
    }
    private fun reconnect() {
        if(reconnectJob?.isActive==true) return
        val device=selected ?: return
        reconnectJob=scope.launch {
            // A fixed short retry window used to leave the link permanently dead once the wearer had
            // been out of range for more than a few seconds, so retry while the transport is enabled.
            var waitMs=INITIAL_RECONNECT_BACKOFF_MS
            while(isActive && reconnectEnabled) {
                delay(waitMs)
                if(!reconnectEnabled) return@launch
                if(connect(device).isSuccess) return@launch
                waitMs=(waitMs*2).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
            }
        }
    }
    private suspend fun rpc(message: StudyMessage,timeoutMs: Long=rpcTimeoutMs): StudyMessage {
        val reply=CompletableDeferred<StudyMessage>()
        replies[message.message_id]=reply
        try {
            return withTimeout(timeoutMs) {
                repeat(2) {
                    link.send(StudyWire.encode(message)).getOrThrow()
                    val received=withTimeoutOrNull(maxOf(1,timeoutMs/2)) {reply.await()}
                    if(received!=null) return@withTimeout received
                }
                reply.await()
            }
        } finally { replies.remove(message.message_id) }
    }
    override suspend fun refreshPeer(): Result<PeerBuildInfo?> = try {
        check(link.state.value.bluetoothConnected) {"BLUETOOTH_NOT_CONNECTED"}
        Result.success(synchronizePeer())
    } catch(cancelled: CancellationException) {
        if(!currentCoroutineContext().isActive) throw cancelled
        _ready.value=false
        Result.failure(IllegalStateException("GLASSES_APP_HANDSHAKE_TIMEOUT",cancelled))
    } catch(error: Exception) {
        _ready.value=false;Result.failure(error)
    }
    private suspend fun synchronizePeer(): PeerBuildInfo = peerSync.withLock {
        _handshakeDiagnostics.value=StudyWire.payload("stage" to "WAITING_FOR_PEER")
        val generation=connectionGeneration
        var best: PeerClockEstimate?=null
        var info: StudyMessage?=null
        var samples=0
        withTimeout(25_000) {
            while(samples<3) {
                check(link.state.value.bluetoothConnected && connectionGeneration==generation) { "CONNECTION_CHANGED" }
                val hello=StudyMessage(session_id="link",session_epoch=0,sent_at_epoch_ms=System.currentTimeMillis(),type="HELLO")
                val response=rpc(hello)
                _handshakeDiagnostics.value=StudyWire.payload("stage" to "PEER_REPLIED","reply_type" to response.type,
                    "camera_ready" to response.payload["camera_ready"],"display_ready" to response.payload["display_ready"],"ready_samples" to samples)
                check(response.type=="READY") { "INVALID_HANDSHAKE_REPLY" }
                if(response.payload["camera_ready"]?.jsonPrimitive?.boolean!=true) {
                    samples=0;best=null;delay(readinessPollMs);continue
                }
                val estimate=PeerClockEstimate.fromPing(hello.sent_at_epoch_ms,response.payload.getValue("glasses_time_ms").jsonPrimitive.long,System.currentTimeMillis())
                if(best==null || estimate.uncertaintyMs<best!!.uncertaintyMs) best=estimate
                info=response;samples++
            }
        }
        val clock=requireNotNull(best)
        check(clock.uncertaintyMs<=500) { "CLOCK_UNCERTAIN" }
        val response=requireNotNull(info)
        check(response.payload["camera_ready"]?.jsonPrimitive?.boolean==true) { "GLASSES_CAMERA_NOT_READY" }
        val sync=StudyMessage(session_id="link",session_epoch=0,sent_at_epoch_ms=System.currentTimeMillis(),type="CLOCK_SYNC",
            payload=StudyWire.payload("phone_minus_glasses_ms" to -clock.remoteMinusLocalMs,"uncertainty_ms" to clock.uncertaintyMs))
        check(rpc(sync).payload["clock_ready"]?.jsonPrimitive?.boolean==true) { "CLOCK_SYNC_FAILED" }
        check(link.state.value.bluetoothConnected && connectionGeneration==generation) { "CONNECTION_CHANGED" }
        peerClock=clock
        peerBuild=PeerBuildInfo(response.payload["firmware"]?.jsonPrimitive?.content.orEmpty(),response.payload["app_version"]?.jsonPrimitive?.content.orEmpty(),
            response.payload["apk_sha256"]?.jsonPrimitive?.contentOrNull?.takeIf {it.matches(Regex("[a-f0-9]{64}"))})
        _ready.value=true; updateConnected()
        _handshakeDiagnostics.update {JsonObject(it+("stage" to JsonPrimitive("READY")))}
        requireNotNull(peerBuild)
    }
    private suspend fun handshake() {
        try {
            val peer=synchronizePeer()
            _events.emit(RokidEvent.DeviceReady(peer.firmware,peer.appVersion))
            link.startP2p().onFailure {error("P2P_INITIALIZATION_FAILED",true)}
            heartbeatJob?.cancel()
            heartbeatJob=scope.launch {
                while(isActive) {
                    delay(3000)
                    try {
                        val pong=rpc(controlMessage("PING"),5000)
                        check(pong.type=="PONG") {"INVALID_HEARTBEAT_REPLY"}
                        if(pong.payload["clock_ready"]==JsonPrimitive(false) || (sessionExpected &&
                            (pong.payload["active_session_id"]?.jsonPrimitive?.contentOrNull!=sessionId || pong.payload["active_session_epoch"]?.jsonPrimitive?.longOrNull!=sessionEpoch))) {
                            _ready.value=false
                            _events.emit(RokidEvent.ConnectionLost("PEER_SESSION_LOST"))
                            val restarted=synchronizePeer()
                            _events.emit(RokidEvent.DeviceReady(restarted.firmware,restarted.appVersion))
                            continue
                        }
                        _ready.value=link.state.value.bluetoothConnected && pong.payload["camera_ready"]?.jsonPrimitive?.boolean!=false
                    }
                    catch(cancelled: CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled; error("HEARTBEAT_TIMEOUT",true); link.disconnect(); reconnect(); return@launch }
                    catch(error: Exception) { error("HEARTBEAT_FAILED",true); link.disconnect(); reconnect(); return@launch }
                }
            }
        } catch(cancelled: CancellationException) {
            if(cancelled !is TimeoutCancellationException) throw cancelled
            _handshakeDiagnostics.update {JsonObject(it+("stage" to JsonPrimitive("TIMEOUT")))}
            _state.value=RokidConnectionState.Failed("GLASSES_APP_HANDSHAKE_TIMEOUT",true)
            error("GLASSES_APP_HANDSHAKE_TIMEOUT",true)
        } catch(error: Exception) {
            val code=error.message?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "HANDSHAKE_FAILED"
            _handshakeDiagnostics.update {JsonObject(it+("stage" to JsonPrimitive("FAILED"))+("code" to JsonPrimitive(code)))}
            _state.value=RokidConnectionState.Failed(code,true); error(code,true)
        }
    }
    private fun updateConnected() {
        val device=selected ?: link.savedDevice()?.let { RokidDevice(it.address,it.name,it.systemPaired,it.advertising) } ?: return
        _state.value=RokidConnectionState.Connected(device,"CXR_BLUETOOTH",if(link.state.value.p2pConnected) "WIFI_P2P" else "BLUETOOTH_FALLBACK")
    }
    override fun controlMessage(type: String,payload: JsonObject,ttlMs: Long?): StudyMessage = StudyMessage(
        session_id=sessionId,session_epoch=sessionEpoch,sent_at_epoch_ms=System.currentTimeMillis(),type=type,payload=payload,
        expires_at_epoch_ms=ttlMs?.let { System.currentTimeMillis()+it },
    )
    override suspend fun startSession(id: String,epoch: Long): Result<Unit> = runCatching {
        check(_ready.value) { "GLASSES_NOT_READY" }
        val connection=connectionGeneration
        if(sessionId!="link" && sessionConnectionGeneration!=connection) check(epoch>sessionEpoch) { "RECONNECT_REQUIRES_NEW_EPOCH" }
        sessionConnectionGeneration=null
        bufferedEnabled=false;bufferedMetadata.clear()
        sessionId=id;sessionEpoch=epoch;imageJobs.values.forEach {it.cancel()};imageJobs.clear();requested.clear();assemblies.clear();transferTimings.clear();completedImages.clear()
        val response=rpc(controlMessage("SESSION_START"))
        check(response.type=="SESSION_READY" && response.payload["camera_ready"]?.jsonPrimitive?.boolean==true) {"GLASSES_CAMERA_NOT_READY"}
        check(connectionGeneration==connection && link.state.value.bluetoothConnected) { "CONNECTION_CHANGED" }
        sessionConnectionGeneration=connection
        sessionExpected=true
    }
    /**
     * Sends a card that belongs to no session, for demonstrating a recorded service. Deliberately not
     * routed through [command]: that guards against delivering into a stale session, and a replay has no
     * session at all - the glasses renders it without ever producing a receipt.
     */
    suspend fun replayServiceCard(payload: JsonObject): Result<Unit> =
        link.send(StudyWire.encode(controlMessage("REPLAY_SERVICE_CARD",payload,10_000)
            .copy(idempotency_key="replay-${UUID.randomUUID()}")))
    override suspend fun command(message: StudyMessage): Result<Unit> {
        if(message.type=="CONFIGURE_CAPTURE") bufferedEnabled=true
        if(message.type in setOf("CAPTURE_REQUEST","IMAGE_GET_BLUETOOTH","SHOW_CONTEXT_CARD","SHOW_SERVICE_CARD","SHOW_SESSION_STATUS") &&
            (sessionConnectionGeneration!=connectionGeneration || message.session_id!=sessionId || message.session_epoch!=sessionEpoch))
            return Result.failure(IllegalStateException("SESSION_REBIND_REQUIRED"))
        val result=link.send(StudyWire.encode(message))
        if(result.isSuccess && message.type in setOf("SESSION_STOP","SESSION_RESET")) sessionExpected=false
        return result
    }
    override suspend fun sendControl(message: ByteArray): Result<Unit> = try {command(StudyWire.decode(message.decodeToString()))}
        catch(error: Exception) {Result.failure(error)}
    override suspend fun requestCapture(command: CaptureCommand): Result<Unit> {
        if(command.sessionId!=sessionId || command.sessionEpoch!=sessionEpoch) return Result.failure(IllegalStateException("CAPTURE_SESSION_MISMATCH"))
        val ttl=command.deadlineElapsedMs-elapsedNow()
        if(ttl<=0) return Result.failure(IllegalStateException("CAPTURE_EXPIRED"))
        requested[command.captureId]=command
        val result=this.command(controlMessage("CAPTURE_REQUEST",StudyWire.payload("capture_id" to command.captureId,"width" to command.width,
            "height" to command.height,"jpeg_quality" to command.jpegQuality,"capture_timeout_ms" to command.captureTimeoutMs),ttl))
        if(result.isFailure) requested.remove(command.captureId)
        else scope.launch {
            delay((command.deadlineElapsedMs-elapsedNow()).coerceAtLeast(0))
            if(requested.remove(command.captureId,command)) {
                assemblies.remove(command.captureId);transferTimings.remove(command.captureId);imageJobs.remove(command.captureId)?.cancel()
                _events.emit(RokidEvent.CaptureFailed(command.captureId,"IMAGE_TRANSFER_TIMEOUT"))
            }
        }
        return result
    }
    override suspend fun clearAllDisplays(sessionEpoch: Long): Result<Unit> {
        if(sessionEpoch!=this.sessionEpoch) return Result.failure(IllegalStateException("STALE_EPOCH"))
        return command(controlMessage("EMERGENCY_CLEAR"))
    }
    override suspend fun disconnect() {
        reconnectEnabled=false; reconnectJob?.cancel(); heartbeatJob?.cancel(); handshakeJob?.cancel()
        imageJobs.values.forEach {it.cancel()};imageJobs.clear();requested.clear();assemblies.clear();transferTimings.clear();completedImages.clear()
        sessionConnectionGeneration=null;_ready.value=false;link.disconnect()
    }
    private suspend fun receive(message: StudyMessage) {
        val replyTo=message.payload["reply_to"]?.jsonPrimitive?.content
        if(replyTo!=null && replies[replyTo]?.complete(message)==true) return
        if(message.type=="SESSION_READY") return
        if(message.session_id!=sessionId || message.session_epoch!=sessionEpoch) return
        val p=message.payload
        when(message.type) {
            "CAPTURE_BUFFER_STATUS" -> if(bufferedEnabled) _events.emit(RokidEvent.CaptureBufferStatus(p))
            "CAPTURE_STARTED" -> {
                val id=p.getValue("capture_id").jsonPrimitive.content
                if(requested.containsKey(id)) _events.emit(RokidEvent.CaptureAcknowledged(id,elapsedNow()))
            }
            "SESSION_STATUS_VISIBLE_ACK" -> _events.emit(RokidEvent.SessionStatusVisible(message.session_id,message.session_epoch,
                p.getValue("status_id").jsonPrimitive.content,p.getValue("render_frame_count").jsonPrimitive.int))
            "SENSOR_SUMMARY" -> {
                val nowEpoch=System.currentTimeMillis(); val nowElapsed=elapsedNow()
                val endEpoch=requireNotNull(peerClock).remoteToLocal(p.getValue("window_end_epoch_ms").jsonPrimitive.long)
                val endElapsed=nowElapsed-(nowEpoch-endEpoch).coerceAtLeast(0)
                val duration=p.getValue("window_end_elapsed_ms").jsonPrimitive.long-p.getValue("window_start_elapsed_ms").jsonPrimitive.long
                _events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(endElapsed-duration,endElapsed,
                    p.getValue("sample_count").jsonPrimitive.int,p.getValue("angular_rms").jsonPrimitive.double,p.getValue("angular_p95").jsonPrimitive.double,
                    p.getValue("linear_rms").jsonPrimitive.double,p.getValue("linear_p95").jsonPrimitive.double,p.getValue("stable_ratio").jsonPrimitive.double,
                    p.getValue("stable").jsonPrimitive.boolean,p.getValue("moving").jsonPrimitive.boolean,null,false,
                    p.getValue("millis_since_interaction").jsonPrimitive.long,p.getValue("context_card_visible").jsonPrimitive.boolean,p.getValue("service_card_visible").jsonPrimitive.boolean)))
            }
            "DISPLAY_VISIBLE_ACK" -> _events.emit(RokidEvent.DisplayVisibleAck(message.session_id,message.session_epoch,
                p.getValue("decision_id").jsonPrimitive.content,p.getValue("visible_at_epoch_ms").jsonPrimitive.long,p.getValue("render_frame_count").jsonPrimitive.int))
            "DISPLAY_DISMISSED" -> _events.emit(RokidEvent.DisplayDismissed(p.getValue("decision_id").jsonPrimitive.content,p.getValue("reason").jsonPrimitive.content))
            "IMAGE_OFFER" -> offer(message)
            "IMAGE_CHUNK" -> chunk(message)
            "CAPTURE_FAILED" -> {
                val id=p.getValue("capture_id").jsonPrimitive.content
                failCapture(id,p["code"]?.jsonPrimitive?.content ?: "CAPTURE_FAILED")
            }
            "ERROR" -> {
                val code=p["code"]?.jsonPrimitive?.content ?: "DEVICE_ERROR"
                val capture=p["capture_id"]?.jsonPrimitive?.content
                val decision=p["decision_id"]?.jsonPrimitive?.content
                when {
                    capture!=null -> failCapture(capture,code)
                    decision!=null -> _events.emit(RokidEvent.DisplayFailed(message.session_id,message.session_epoch,decision,code))
                    else -> error(code,true)
                }
            }
        }
    }
    private suspend fun failCapture(id: String,code: String) {
        if(requested.remove(id)==null) return
        assemblies.remove(id);transferTimings.remove(id);imageJobs.remove(id)?.cancel()
        _events.emit(RokidEvent.CaptureFailed(id,code))
    }
    private fun offer(message: StudyMessage) {
        val descriptor=StudyWire.json.decodeFromJsonElement<ImageDescriptor>(message.payload.getValue("image"))
        descriptor.validate()
        if(descriptor.session_id!=sessionId || descriptor.session_epoch!=sessionEpoch) return
        if(message.payload["buffered"]?.jsonPrimitive?.boolean==true && bufferedEnabled) {
            val id=descriptor.capture_id
            if(requested[id]?.deadlineElapsedMs?.let {it<elapsedNow()}==true) {requested.remove(id);assemblies.remove(id);imageJobs.remove(id)?.cancel()}
            requested.putIfAbsent(id,CaptureCommand(sessionId,sessionEpoch,id,setOf("GLASSES_BUFFER"),deadlineElapsedMs=elapsedNow()+20_000))
            bufferedMetadata[id]=message.payload.filterKeys {it in setOf("captured_phone_epoch_ms","source_session_id","capture_mode","trigger_types",
                "front_end","front_end_score","front_end_seed_scores","glasses_battery_percent")}.let(::JsonObject)
        }
        if(requested[descriptor.capture_id]==null) return
        val pending=requested.getValue(descriptor.capture_id)
        if(assemblies.putIfAbsent(descriptor.capture_id,ImageAssembly(descriptor,minOf(pending.deadlineElapsedMs,elapsedNow()+20_000)))!=null) return
        transferTimings[descriptor.capture_id]=TransferTiming(elapsedNow(),message.payload["camera_duration_ms"]?.jsonPrimitive?.longOrNull?.takeIf {it>=0})
        val generation=connectionGeneration
        val job=scope.launch(start=CoroutineStart.LAZY) {
            if(link.state.value.p2pConnected && message.payload["transfer_protocol"]?.jsonPrimitive?.content==SessionPeerTls.PROTOCOL) {
                val addresses=message.payload["addresses"]?.jsonArray.orEmpty().map { it.jsonObject }
                    .filter { it["interface"]?.jsonPrimitive?.content?.contains("p2p")==true }
                for(address in addresses) {
                    val host=address.getValue("address").jsonPrimitive.content
                    if(!host.matches(Regex("[0-9.]{7,15}")) || !java.net.InetAddress.getByName(host).isSiteLocalAddress) continue
                    val port=message.payload.getValue("port").jsonPrimitive.int
                    if(port !in 1..65535) continue
                    try {
                        val peer=SessionPeerTls.client(http,message.payload.getValue("certificate_pem").jsonPrimitive.content,
                            message.payload.getValue("certificate_sha256").jsonPrimitive.content,java.net.InetAddress.getByName(host))
                        val request=Request.Builder().url("https://${SessionPeerTls.HOST}:$port/image/${descriptor.capture_id}")
                            .header("Authorization","Bearer ${message.payload.getValue("token").jsonPrimitive.content}").build()
                        val bytes=peer.newCall(request).readImageBody(descriptor.byte_length).also {verifyImage(descriptor,it)}
                        complete(descriptor,bytes,"WIFI_P2P"); return@launch
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { /* Actual P2P failure selects the explicit Bluetooth fallback below. */ }
                }
            }
            if(connectionGeneration==generation && requested[descriptor.capture_id]?.sessionEpoch==descriptor.session_epoch && sessionId==descriptor.session_id)
                command(controlMessage("IMAGE_GET_BLUETOOTH",StudyWire.payload("capture_id" to descriptor.capture_id)))
        }
        imageJobs[descriptor.capture_id]=job
        job.invokeOnCompletion {imageJobs.remove(descriptor.capture_id,job)}
        job.start()
    }
    private suspend fun chunk(message: StudyMessage) {
        val p=message.payload; val id=p.getValue("capture_id").jsonPrimitive.content
        val index=p.getValue("index").jsonPrimitive.int
        val assembly=assemblies[id] ?: run {
            completedImages[id]?.let {descriptor->
                if(index in 0 until descriptor.chunkCount) {
                    command(controlMessage("IMAGE_CHUNK_ACK",StudyWire.payload("capture_id" to id,"index" to index)))
                    acknowledgeImage(descriptor)
                }
            }
            return
        }
        val finished=assembly.add(index,Base64.getDecoder().decode(p.getValue("data").jsonPrimitive.content),elapsedNow())
        command(controlMessage("IMAGE_CHUNK_ACK",StudyWire.payload("capture_id" to id,"index" to index)))
        if(finished) complete(assembly.descriptor,assembly.finish(elapsedNow()),"CXR_BLUETOOTH")
    }
    private suspend fun complete(descriptor: ImageDescriptor,bytes: ByteArray,channel: String) {
        verifyImage(descriptor,bytes)
        val pending=requested.remove(descriptor.capture_id) ?: return
        val timing=transferTimings.remove(descriptor.capture_id)
        assemblies.remove(descriptor.capture_id)
        if(pending.sessionEpoch!=sessionEpoch || pending.sessionId!=sessionId) return
        if(elapsedNow()>=pending.deadlineElapsedMs) {
            _events.emit(RokidEvent.CaptureFailed(descriptor.capture_id,"IMAGE_TRANSFER_TIMEOUT"));return
        }
        val received=System.currentTimeMillis()
        val source=bufferedMetadata.remove(descriptor.capture_id)
        val captured=source?.get("captured_phone_epoch_ms")?.jsonPrimitive?.longOrNull ?: requireNotNull(peerClock).remoteToLocal(descriptor.captured_at_epoch_ms)
        if(captured>received+requireNotNull(peerClock).uncertaintyMs) {
            _events.emit(RokidEvent.CaptureFailed(descriptor.capture_id,"CAMERA_CLOCK_INVALID"));return
        }
        if(completedImages.size>=32) completedImages.keys.firstOrNull()?.let {completedImages.remove(it)}
        completedImages[descriptor.capture_id]=descriptor
        _events.emit(RokidEvent.ImageReceived(ImageFrame(descriptor.capture_id,minOf(captured,received),received,bytes,
            descriptor.width,descriptor.height,descriptor.rotation_degrees,descriptor.sha256,channel,
            timing?.cameraMs,timing?.let {elapsedNow()-it.offeredElapsedMs},source!=null,source ?: JsonObject(emptyMap()))))
        acknowledgeImage(descriptor)
    }
    private suspend fun acknowledgeImage(descriptor: ImageDescriptor) {
        command(controlMessage("IMAGE_ACK",StudyWire.payload("capture_id" to descriptor.capture_id,"sha256" to descriptor.sha256,"byte_length" to descriptor.byte_length)))
    }
    private suspend fun error(code: String,recoverable: Boolean) { _events.emit(RokidEvent.TransportError(code,code,recoverable)) }
}
