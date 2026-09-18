package ai.opstate.rokid.glasses

import ai.opstate.protocol.*
import ai.opstate.rokid.glasses.capture.AndroidCameraSession
import ai.opstate.rokid.glasses.capture.CameraJpeg
import ai.opstate.rokid.glasses.capture.PhotoBuffer
import ai.opstate.rokid.vision.VisualSignatureExtractor
import ai.opstate.core.scheduler.StreamingPacketizer
import ai.opstate.rokid.glasses.sensor.AndroidImuSampler
import ai.opstate.rokid.glasses.sensor.MotionWindowSummary
import android.app.*
import android.content.Intent
import android.os.*
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import android.content.pm.ServiceInfo
import java.net.NetworkInterface
import java.io.File
import java.util.UUID

/**
 * Tick period with no active session. Capture scheduling and card expiry need the fine period, but a
 * connected-and-idle glasses only watches for the camera and a lost heartbeat, so it can idle slowly.
 */
private const val IDLE_TICK_MS=2_000L
private const val ACTIVE_TICK_MS=250L

/** Periodic refresh of an unchanged buffer status; every real change is sent immediately. */
private const val STATUS_REFRESH_MS=5_000L

/** Card commands: the ones that need a page that is actually showing. */
private val CARD_TYPES=setOf("SHOW_CONTEXT_CARD","SHOW_SERVICE_CARD","SHOW_SESSION_STATUS","REPLAY_SERVICE_CARD")

/** One actor owns commands/session/cards. Capture and transfer run as correlated child jobs. */
class GlassesStudyService : Service() {
    companion object { const val EXTRA_FROM_BOOT="from_boot" }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val inbox=Channel<Event>(128)
    private val graph get()=application as GlassesApplication
    private lateinit var camera: AndroidCameraSession
    private lateinit var imu: AndroidImuSampler
    private val gate by lazy {
        val saved=getSharedPreferences("study-session-epoch",MODE_PRIVATE)
        SessionCommandGate(initialEpoch=saved.getLong("max_epoch",Long.MIN_VALUE),persistEpoch={ epoch ->
            check(saved.edit().putLong("max_epoch",epoch).commit()) { "EPOCH_PERSIST_FAILED" }
        })
    }
    private val commandReplies=CommandReplyCache()
    private var paused=false
    @Volatile private var imageSender: BluetoothImageSender?=null
    private var activeSession: String?=null
    private var activeEpoch=Long.MIN_VALUE
    private var generation=0L
    private var phoneMinusGlassesMs=0L
    private var clockReady=false
    private var lastHello: StudyMessage?=null
    private var captureJob: Job?=null
    private var transferJob: Job?=null
    private var captureId: String?=null
    private var images: SessionImageServer?=null
    private var contextCard: GlassesCard?=null
    private var serviceCard: GlassesCard?=null
    private var statusCard: GlassesCard?=null
    private val visibleAcks=linkedMapOf<String,StudyMessage>()
    private var lastInteractionElapsed=0L
    private var lastHeartbeatElapsed=0L
    private var heartbeatLost=false
    private lateinit var buffer: PhotoBuffer
    private var autonomousMode: String?=null
    private var automatic=true
    private var intervalMs=60_000L
    private var batchSize=60
    private var nextCaptureElapsed=0L
    private var lastLocalCaptureElapsed=0L
    private var flushing=false
    private var offeredId: String?=null
    private var offerElapsed=0L
    private var capturedCount=0
    private var localTrigger=StreamingPacketizer()
    private var pendingTrigger=setOf<String>()
    private var lastBufferStatus=0L
    private var lastStatusSignature=""
    private var heldCard: StudyMessage?=null
    private var cameraTypeActive=false
    private var cameraWarmUpStarted=false
    private var bufferError: String?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private sealed class Event {
        data class Message(val value: String): Event()
        data class CameraReady(val error: String?=null): Event()
        data class Captured(val token: Long,val request: StudyMessage,val result: Result<CameraJpeg>,val durationMs: Long): Event()
        data class Sensor(val summary: MotionWindowSummary): Event()
        data class Visible(val value: VisibleEvent): Event()
        data class Dismiss(val id: String): Event()
        data object Tick: Event()
        data object PageVisible: Event()
    }
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("study-camera","OPSTATE 拍照与采样",NotificationManager.IMPORTANCE_LOW))
        buffer=PhotoBuffer(File(filesDir,"photo-buffer"))
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OPSTATE:glasses-capture").apply {setReferenceCounted(false)}
        camera=AndroidCameraSession(this)
        imu=AndroidImuSampler(this,scope,{ inbox.send(Event.Sensor(it)) })
        graph.mutableUi.value=GlassesUiState(running=true,bufferedPhotos=buffer.entries().size)
        scope.launch {
            try { graph.link.start() }
            catch(error: Exception) { graph.mutableUi.update { it.copy(setupError="CXR 初始化失败：${error.javaClass.simpleName}") } }
            graph.link.messages.collect { inbox.send(Event.Message(it)) }
        }
        scope.launch { graph.displayVisible.collect { if(it) inbox.send(Event.PageVisible) } }
        scope.launch { graph.visibleEvents.receiveAsFlow().collect { inbox.send(Event.Visible(it)) } }
        scope.launch { graph.dismissEvents.receiveAsFlow().collect { inbox.send(Event.Dismiss(it)) } }
        scope.launch {
            for(event in inbox) try {
                when(event) {
                    is Event.Message -> handle(StudyWire.decode(event.value))
                    is Event.CameraReady -> {
                        graph.mutableUi.update { it.copy(cameraReady=event.error==null,setupError=event.error ?: it.setupError) }
                        lastHello?.let { ready(it) }
                    }
                    is Event.Captured -> captured(event)
                    is Event.Sensor -> sensor(event.summary)
                    is Event.Visible -> visible(event.value)
                    is Event.Dismiss -> dismiss(event.id,"DISMISS")
                    Event.Tick -> tick()
                    Event.PageVisible -> flushHeldCard()
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { android.util.Log.e("OPSTATE","GLASSES_EVENT_${error.javaClass.simpleName}") }
        }
        scope.launch { while(isActive) { delay(if(activeSession!=null) ACTIVE_TICK_MS else IDLE_TICK_MS); inbox.send(Event.Tick) } }
    }
    private fun buildNotification()=Notification.Builder(this,"study-camera").setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("OPSTATE 眼镜").setContentText("照片先保存到眼镜，按需同步手机")
        .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
    /**
     * Claims the camera type and warms the camera. The handshake the phone runs reports `camera_ready`
     * from this probe and refuses to settle while it is false, so it has to run on every start path,
     * including a restart after a reboot.
     */
    private fun enableCameraType() {
        if(!cameraTypeActive) {
            cameraTypeActive=true
            startForeground(5201,buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        if(cameraWarmUpStarted) return
        cameraWarmUpStarted=true
        scope.launch {
            val result=runCatching { camera.openLowResolutionPreview();camera.close() }
            inbox.send(Event.CameraReady(result.exceptionOrNull()?.javaClass?.simpleName))
        }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        enableCameraType()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?)=null
    private fun phoneNow()=System.currentTimeMillis()+phoneMinusGlassesMs
    private suspend fun send(type: String,payload: JsonObject,request: StudyMessage?=null,key: String?=null) {
        val message=StudyMessage(session_id=request?.session_id ?: activeSession ?: "link",
            session_epoch=request?.session_epoch ?: activeEpoch.takeUnless { it==Long.MIN_VALUE } ?: 0,
            sent_at_epoch_ms=System.currentTimeMillis(),type=type,payload=payload)
        val references=if(type=="ERROR") request?.payload.orEmpty().filterKeys {it in setOf("capture_id","decision_id","event_id")} else emptyMap()
        val correlated=if(request!=null) message.copy(payload=JsonObject(references+payload+("reply_to" to JsonPrimitive(request.message_id)))) else message
        val reply=if(key!=null) correlated.copy(idempotency_key=key) else correlated
        if(request!=null && (type=="ERROR" || request.type in setOf("SESSION_START","SESSION_STOP","SESSION_RESET","SESSION_PAUSE","CAPTURE_REQUEST"))) commandReplies.remember(request,reply)
        graph.link.send(StudyWire.encode(reply)).getOrThrow()
    }
    /**
     * Whether the page can show a card. A sleeping panel still counts: drawing a card wakes it, so the
     * only state that cannot display is a page that is not showing at all.
     */
    private fun displayReady()=graph.displayVisible.value
    private suspend fun ready(request: StudyMessage) = send("READY",StudyWire.payload(
        "reply_to" to request.message_id,"glasses_time_ms" to System.currentTimeMillis(),
        "camera_ready" to graph.ui.value.cameraReady,"display_ready" to displayReady(),
        "firmware" to Build.VERSION.INCREMENTAL,"app_version" to graph.appVersion,"apk_sha256" to graph.apkSha256,
    ),request)
    private suspend fun handle(message: StudyMessage) {
        if(message.type=="HELLO") { lastHello=message; lastHeartbeatElapsed=SystemClock.elapsedRealtime(); ready(message); return }
        if(message.type=="CLOCK_SYNC") {
            phoneMinusGlassesMs=message.payload.getValue("phone_minus_glasses_ms").jsonPrimitive.long
            clockReady=message.payload.getValue("uncertainty_ms").jsonPrimitive.long in 0..500
            send("CLOCK_READY",StudyWire.payload("reply_to" to message.message_id,"clock_ready" to clockReady),message)
            lastHeartbeatElapsed=SystemClock.elapsedRealtime()
            heartbeatLost=false
            graph.mutableUi.update {it.copy(phoneConnected=clockReady)}
            return
        }
        if(message.type=="PING") {
            lastHeartbeatElapsed=SystemClock.elapsedRealtime(); heartbeatLost=false
            send("PONG",StudyWire.payload("reply_to" to message.message_id,"glasses_time_ms" to System.currentTimeMillis(),
                "camera_ready" to graph.ui.value.cameraReady,"display_ready" to displayReady(),"clock_ready" to clockReady,
                "active_session_id" to activeSession,"active_session_epoch" to activeEpoch),message)
            graph.mutableUi.update {it.copy(phoneConnected=clockReady)}
            return
        }
        if(!clockReady) { send("ERROR",StudyWire.payload("code" to "CLOCK_SYNC_REQUIRED"),message); return }
        if(message.type in setOf("SESSION_STOP","SESSION_RESET")) commandReplies.replay(message)?.let {graph.link.send(StudyWire.encode(it));return}
        // A card that cannot be shown yet must not spend its idempotency key here.
        //
        // Admitting first and holding afterwards meant a held card was admitted on arrival and then
        // re-admitted when the page came up, which came back DUPLICATE and dropped it - so the card
        // that was held precisely so the wearer could see it was the one card that never drew.
        // Measured on the glasses: the startup card woke the panel, the page became visible, and the
        // card was still never shown. Decide first, admit once, when it is actually handled.
        if(message.type in CARD_TYPES && !graph.displayVisible.value) {
            if(!getSystemService(PowerManager::class.java).isInteractive && heldCard==null) {
                heldCard=message;presentPage();return
            }
            send("ERROR",StudyWire.payload("code" to "DISPLAY_NOT_READY"),message);return
        }
        // A replayed card belongs to no session: it demonstrates a recorded service, so it is exempt
        // from session admission and never produces a receipt.
        val admission=if(message.type=="REPLAY_SERVICE_CARD") SessionCommandGate.Admission.ACCEPT else gate.admit(message,phoneNow())
        if(admission==SessionCommandGate.Admission.DUPLICATE) {
            commandReplies.replay(message)?.let {graph.link.send(StudyWire.encode(it));return}
            if(message.type=="SHOW_SERVICE_CARD") {
                message.payload["decision_id"]?.jsonPrimitive?.content?.let { visibleAcks[it] }?.let { graph.link.send(StudyWire.encode(it)) }
            }
            return
        }
        if(admission!=SessionCommandGate.Admission.ACCEPT) {
            send("ERROR",StudyWire.payload("code" to admission.name,"reply_to" to message.message_id),message); return
        }
        if(paused && message.type in setOf("CAPTURE_REQUEST","IMAGE_GET_BLUETOOTH","SHOW_CONTEXT_CARD","SHOW_SERVICE_CARD","SHOW_SESSION_STATUS")) {
            send("ERROR",StudyWire.payload("code" to "SESSION_PAUSED"),message);return
        }
        when(message.type) {
            "CONFIGURE_CAPTURE" -> {
                val p=message.payload
                val mode=p.getValue("mode").jsonPrimitive.content
                require(mode in setOf("COLLECTION","SERVICE"))
                val interval=p.getValue("interval_ms").jsonPrimitive.long
                val batch=p.getValue("batch_size").jsonPrimitive.int
                require(interval in 5_000..300_000 && batch in 1..500)
                autonomousMode=mode;intervalMs=interval;batchSize=batch
                automatic=p["automatic"]?.jsonPrimitive?.boolean ?: true
                if(nextCaptureElapsed==0L) nextCaptureElapsed=SystemClock.elapsedRealtime()
                if(automatic) wakeLock?.acquire() else wakeLock?.takeIf {it.isHeld}?.release()
                // Motion summaries only feed the SERVICE path (kickModel and the scheduler snapshot are
                // both SERVICE-gated), so COLLECTION does not pay for three sensors at ~50 Hz.
                if(mode=="SERVICE") imu.start() else imu.stop()
                // Retained originals from an interrupted collection are recovered into the next collection.
                flushing=flushing || (mode=="COLLECTION" && buffer.entries().any {it.descriptor.session_id!=activeSession})
                sendBufferStatus()
            }
            "FLUSH_CAPTURE_BUFFER" -> {flushing=true;sendBufferStatus()}
            "EDGE_DIGITAL_EVENT" -> if(autonomousMode=="SERVICE") {
                pendingTrigger=pendingTrigger+localTrigger.onDigital(message.payload.getValue("event_id").jsonPrimitive.content,System.currentTimeMillis()).flatMap {it.triggerTypes}
            }
            "IMAGE_COMMITTED" -> {
                val p=message.payload;val id=p.getValue("capture_id").jsonPrimitive.content
                buffer.commit(id,p.getValue("sha256").jsonPrimitive.content,p.getValue("byte_length").jsonPrimitive.int)
                imageSender?.acknowledgeImage(id,p.getValue("sha256").jsonPrimitive.content,p.getValue("byte_length").jsonPrimitive.int)
                images?.acknowledge(id)
                if(offeredId==id) offeredId=null
                sendBufferStatus()
            }
            "UPDATE_DEMO_STATUS" -> {
                val p=message.payload
                graph.mutableUi.update {it.copy(demoStatus=if(p["enabled"]?.jsonPrimitive?.boolean==true)
                    GlassesDemoStatus(p.getValue("phase").jsonPrimitive.content.take(60),p.getValue("context").jsonPrimitive.content.take(140),
                        p.getValue("captures").jsonPrimitive.int,p.getValue("receipts").jsonPrimitive.int,
                        p.getValue("automatic_capture").jsonPrimitive.boolean,p["collection"]?.jsonPrimitive?.boolean ?: false,
                        p["interval_seconds"]?.jsonPrimitive?.long ?: 60) else null)}
            }
            "SESSION_START" -> {
                if(activeSession!=message.session_id || activeEpoch!=message.session_epoch) {
                    resetTransient()
                    activeSession=message.session_id; activeEpoch=message.session_epoch
                    images=SessionImageServer(SystemClock::elapsedRealtime)
                    lastHeartbeatElapsed=SystemClock.elapsedRealtime()
                    graph.mutableUi.update { it.copy(sessionId=activeSession,card=null) }
                    // A service restarted after a reboot may have been brought up by the job before any UI
                    // existed; make sure the camera type is claimed before capture begins.
                    enableCameraType()
                    imu.start()
                }
                paused=false
                send("SESSION_READY",StudyWire.payload("camera_ready" to graph.ui.value.cameraReady,"display_ready" to displayReady(),"reply_to" to message.message_id),message)
            }
            "SESSION_STOP", "SESSION_RESET" -> {
                resetTransient(); gate.stop(); activeSession=null
                graph.mutableUi.update { it.copy(sessionId=null,card=null) }
                send("SESSION_STOPPED",StudyWire.payload("reply_to" to message.message_id),message)
            }
            "SESSION_PAUSE" -> {
                paused=true;captureJob?.cancel();transferJob?.cancel();captureId=null;imageSender=null;imu.stop();clearCards()
                wakeLock?.takeIf {it.isHeld}?.release()
                send("SESSION_PAUSED",StudyWire.payload("paused" to true),message)
            }
            "CAPTURE_REQUEST" -> capture(message)
            "IMAGE_GET_BLUETOOTH" -> transfer(message)
            "IMAGE_CHUNK_ACK" -> {
                val id=message.payload.getValue("capture_id").jsonPrimitive.content
                val index=message.payload.getValue("index").jsonPrimitive.int
                imageSender?.acknowledgeChunk(id,index)
            }
            "IMAGE_ACK" -> {
                val p=message.payload;val id=p.getValue("capture_id").jsonPrimitive.content
                val descriptor=imageSender?.descriptor?.takeIf {it.capture_id==id} ?: images?.image(id)?.first
                if(descriptor!=null) {
                    val sha=p["sha256"]?.jsonPrimitive?.content ?: descriptor.sha256
                    val length=p["byte_length"]?.jsonPrimitive?.int ?: descriptor.byte_length
                    if(sha==descriptor.sha256 && length==descriptor.byte_length) {
                        imageSender?.acknowledgeImage(id,sha,length)
                        images?.acknowledge(id)
                    } else send("ERROR",StudyWire.payload("code" to "IMAGE_ACK_MISMATCH","capture_id" to id),message)
                }
            }
            "SHOW_CONTEXT_CARD" -> {
                contextCard=card(message,"CONTEXT"); updateCard()
            }
            "SHOW_SESSION_STATUS" -> {statusCard=card(message,"STATUS");updateCard()}
            "SHOW_SERVICE_CARD" -> {
                if(serviceCard!=null) { send("ERROR",StudyWire.payload("code" to "DISPLAY_BUSY"),message); return }
                serviceCard=card(message,"SERVICE"); updateCard()
            }
            // Shown exactly like a service so a demo reads the same, but it is a replay, not a delivery.
            "REPLAY_SERVICE_CARD" -> {
                if(serviceCard!=null) { send("ERROR",StudyWire.payload("code" to "DISPLAY_BUSY"),message); return }
                serviceCard=card(message,"REPLAY"); updateCard()
            }
            "CLEAR_CONTEXT_CARD" -> { contextCard=null; updateCard() }
            "CLEAR_SERVICE_CARD" -> { serviceCard=null; updateCard() }
            "CLEAR_DISPLAY", "CLEAR_ALL", "EMERGENCY_CLEAR" -> clearCards()
            else -> send("ERROR",StudyWire.payload("code" to "UNSUPPORTED_COMMAND","reply_to" to message.message_id),message)
        }
    }
    private fun card(message: StudyMessage,kind: String): GlassesCard {
        val p=message.payload
        val ttl=message.expires_at_epoch_ms?.minus(phoneNow()) ?: 10_000
        require(ttl>0) { "CARD_EXPIRED" }
        val duration=(p["visible_for_ms"]?.jsonPrimitive?.long ?: 10_000).coerceIn(1000,30_000)
        val title=p["title"]?.jsonPrimitive?.content ?: "情境更新"
        val body=p.getValue("body").jsonPrimitive.content
        require(title.codePointCount(0,title.length)<=40 && body.codePointCount(0,body.length)<=140)
        return GlassesCard((p[if(kind=="SERVICE") "decision_id" else "event_id"] ?: JsonPrimitive(message.message_id)).jsonPrimitive.content,
            message.session_epoch,kind,p["service_mode"]?.jsonPrimitive?.content.orEmpty(),title,body,SystemClock.elapsedRealtime()+minOf(ttl,duration))
    }
    private fun updateCard() {
        graph.mutableUi.update { it.copy(card=serviceCard ?: statusCard ?: contextCard) }
        android.util.Log.i("OPSTATE","CARD_UI kind=${graph.mutableUi.value.card?.kind ?: "none"} id=${graph.mutableUi.value.card?.id ?: "none"}")
        if(graph.mutableUi.value.card!=null) presentPage()
    }
    /**
     * Brings the page forward so a card can actually be seen.
     *
     * While the glasses are being worn but not looked at, the panel sleeps and the activity is paused;
     * a window flag cannot light a stopped page, so a card sent then would never be drawn and its
     * two-frame ACK could never be produced. The service holds a camera foreground service, which lets
     * it start its own activity, and the activity's KEEP_SCREEN_ON flag keeps the panel lit afterwards.
     */
    @Suppress("DEPRECATION")
    private fun presentPage() {
        val power=getSystemService(PowerManager::class.java)
        if(power.isInteractive) return
        // Starting the activity is not enough to light the panel. When this page is already the
        // resumed activity - which it is whenever the panel merely slept - the start arrives as
        // onNewIntent, the activity's turnScreenOn never fires, and the display stays off. Then the
        // page can never report itself visible, so a held card is never flushed and every later card
        // is refused DISPLAY_NOT_READY. Measured on the glasses: a held startup card sat unflushed
        // while the panel stayed asleep. The wake lock is what actually lights it.
        runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OPSTATE:present").acquire(3_000)
        }.onFailure { android.util.Log.w("OPSTATE","PAGE_WAKE_FAILED ${it.javaClass.simpleName}") }
        runCatching {
            startActivity(Intent(this,MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { android.util.Log.w("OPSTATE","CARD_PRESENT_${it.javaClass.simpleName}") }
    }
    /** Delivers a card that was held while the page was being brought up. */
    private suspend fun flushHeldCard() {
        val held=heldCard ?: return
        heldCard=null
        if(!graph.displayVisible.value) return
        handle(held)
    }
    private fun clearCards() { serviceCard=null; contextCard=null; statusCard=null; updateCard() }
    private suspend fun visible(event: VisibleEvent) {
        statusCard?.takeIf {it.id==event.decisionId && event.epoch==activeEpoch && event.frames>=2 && SystemClock.elapsedRealtime()<it.expiresElapsedMs}?.let {
            if(event.decisionId !in visibleAcks) {
                val ack=StudyMessage(session_id=requireNotNull(activeSession),session_epoch=activeEpoch,sent_at_epoch_ms=System.currentTimeMillis(),
                    type="SESSION_STATUS_VISIBLE_ACK",payload=StudyWire.payload("status_id" to it.id,"render_frame_count" to event.frames))
                visibleAcks[it.id]=ack;graph.link.send(StudyWire.encode(ack)).getOrThrow()
            }
            return
        }
        val card=serviceCard ?: return
        if(card.id!=event.decisionId || event.epoch!=activeEpoch || event.frames<2 || SystemClock.elapsedRealtime()>=card.expiresElapsedMs) return
        if(event.decisionId in visibleAcks) return
        val ack=StudyMessage(session_id=requireNotNull(activeSession),session_epoch=activeEpoch,sent_at_epoch_ms=System.currentTimeMillis(),
            type="DISPLAY_VISIBLE_ACK",payload=StudyWire.payload("decision_id" to event.decisionId,
                "visible_at_epoch_ms" to event.visibleAtEpochMs+phoneMinusGlassesMs,"render_frame_count" to event.frames),idempotency_key="visible-${event.decisionId}")
        visibleAcks[event.decisionId]=ack
        while(visibleAcks.size>128) visibleAcks.remove(visibleAcks.keys.first())
        graph.link.send(StudyWire.encode(ack)).getOrThrow()
    }
    private suspend fun dismiss(id: String,reason: String) {
        lastInteractionElapsed=SystemClock.elapsedRealtime()
        if(serviceCard?.id==id) serviceCard=null
        if(contextCard?.id==id) contextCard=null
        if(statusCard?.id==id) {statusCard=null;updateCard();return}
        updateCard()
        send("DISPLAY_DISMISSED",StudyWire.payload("decision_id" to id,"reason" to reason))
    }
    private suspend fun tick() {
        val now=SystemClock.elapsedRealtime()
        if(graph.ui.value.cameraReady && captureId==null && camera.isOpen &&
            (activeSession==null || paused || !automatic || heartbeatLost && autonomousMode!="COLLECTION")) camera.close()
        serviceCard?.takeIf { now>=it.expiresElapsedMs }?.let { dismiss(it.id,"TIMEOUT") }
        contextCard?.takeIf { now>=it.expiresElapsedMs }?.let { contextCard=null; updateCard() }
        statusCard?.takeIf { now>=it.expiresElapsedMs }?.let { statusCard=null; updateCard() }
        if(!heartbeatLost && lastHeartbeatElapsed>0 && now-lastHeartbeatElapsed>10_000) {
            heartbeatLost=true; clearCards(); transferJob?.cancel();offeredId=null
            if(autonomousMode!="COLLECTION") {captureJob?.cancel();captureId=null}
            graph.mutableUi.update {it.copy(phoneConnected=false)}
            android.util.Log.w("OPSTATE","HEARTBEAT_LOST")
        }
        if(autonomousMode!=null && activeSession!=null) {
            if(autonomousMode=="SERVICE" && !paused && !heartbeatLost) pendingTrigger=pendingTrigger+localTrigger.advance(System.currentTimeMillis()).flatMap {it.triggerTypes}
            if(!paused && automatic && captureId==null && now-lastLocalCaptureElapsed>=5_000 && (autonomousMode=="COLLECTION" || !heartbeatLost) && (now>=nextCaptureElapsed || pendingTrigger.isNotEmpty())) {
                lastLocalCaptureElapsed=now
                if(nextCaptureElapsed<=now) nextCaptureElapsed+=((now-nextCaptureElapsed)/intervalMs+1)*intervalMs
                capture(StudyMessage(session_id=requireNotNull(activeSession),session_epoch=activeEpoch,sent_at_epoch_ms=phoneNow(),
                    expires_at_epoch_ms=phoneNow()+8_000,type="CAPTURE_REQUEST",payload=StudyWire.payload("capture_id" to "buffer-${UUID.randomUUID()}","autonomous" to true)))
            }
            if(!heartbeatLost) {
                syncNext(now)
                // A changed status (pending/capturing/automatic/…) goes out at once; an unchanged one
                // only refreshes periodically, which mostly feeds the phone's countdown display.
                if(statusSignature()!=lastStatusSignature || now-lastBufferStatus>=STATUS_REFRESH_MS) sendBufferStatus()
            } else updateBufferUi()
            if((automatic && !paused) || flushing) wakeLock?.acquire() else wakeLock?.takeIf {it.isHeld}?.release()
        }
    }
    private fun eligiblePhotos()=buffer.entries().filter {autonomousMode=="COLLECTION" || it.descriptor.session_id==activeSession}
    private fun updateBufferUi() {graph.mutableUi.update {it.copy(bufferedPhotos=eligiblePhotos().size,localCaptures=capturedCount,
        collectingLocally=autonomousMode=="COLLECTION" && automatic && !paused,setupError=bufferError)}}
    /** Everything the phone acts on; excludes the volatile countdown so steady state compares equal. */
    private fun statusSignature()="$capturedCount/${eligiblePhotos().size}/${captureId!=null}/$automatic/$flushing/$bufferError"
    private suspend fun sendBufferStatus() {
        if(autonomousMode==null) return
        lastBufferStatus=SystemClock.elapsedRealtime();updateBufferUi();lastStatusSignature=statusSignature()
        send("CAPTURE_BUFFER_STATUS",StudyWire.payload("captured" to capturedCount,"pending" to eligiblePhotos().size,"batch_size" to batchSize,
            "free_bytes" to buffer.freeBytes,"capturing" to (captureId!=null),"automatic" to automatic,"syncing" to flushing,
            "next_capture_in_ms" to (nextCaptureElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0),"error" to bufferError))
    }
    private suspend fun syncNext(now: Long) {
        val queued=eligiblePhotos()
        if(queued.isEmpty()) {flushing=false;return}
        if(autonomousMode=="SERVICE" || queued.size>=batchSize) flushing=true
        if(!flushing || (offeredId!=null && now-offerElapsed<25_000)) return
        val entry=queued.first()
        val descriptor=entry.descriptor.copy(session_id=requireNotNull(activeSession),session_epoch=activeEpoch)
        val server=images ?: return
        offeredId?.let(server::acknowledge)
        server.offer(descriptor,entry.file.readBytes())
        offeredId=descriptor.capture_id;offerElapsed=now
        val addresses=NetworkInterface.getNetworkInterfaces().toList().flatMap {iface->iface.inetAddresses.toList()
            .filter {it is java.net.Inet4Address && !it.isLoopbackAddress}.map {StudyWire.payload("interface" to iface.name,"address" to it.hostAddress)}}
        send("IMAGE_OFFER",JsonObject(entry.metadata+StudyWire.payload("image" to StudyWire.json.encodeToJsonElement(descriptor),"buffered" to true,
            "port" to server.port,"token" to server.token,"addresses" to JsonArray(addresses),"transfer_protocol" to SessionPeerTls.PROTOCOL,
            "certificate_pem" to server.certificatePem,"certificate_sha256" to server.certificateSha256)))
    }
    private suspend fun sensor(window: MotionWindowSummary) {
        if(activeSession==null || heartbeatLost || autonomousMode!="SERVICE") return
        send("SENSOR_SUMMARY",StudyWire.payload(
            "window_start_elapsed_ms" to window.startElapsedMs,"window_end_elapsed_ms" to window.endElapsedMs,
            "sample_count" to window.sampleCount,"angular_rms" to window.angularRms,"angular_p95" to window.angularP95,
            "linear_rms" to window.linearRms,"linear_p95" to window.linearP95,"stable_ratio" to window.stableRatio,
            "stable" to window.stable,"moving" to window.moving,"valid" to window.valid,
            "context_card_visible" to (contextCard!=null),"service_card_visible" to (serviceCard!=null),
            "millis_since_interaction" to (SystemClock.elapsedRealtime()-lastInteractionElapsed),
            "emitted_elapsed_ms" to SystemClock.elapsedRealtime(),
            "window_end_epoch_ms" to (System.currentTimeMillis()-(SystemClock.elapsedRealtime()-window.endElapsedMs)),
        ))
    }
    private suspend fun capture(request: StudyMessage) {
        val local=request.payload["autonomous"]?.jsonPrimitive?.boolean==true
        if(captureId!=null || (heartbeatLost && !local)) { if(!local) send("ERROR",StudyWire.payload("code" to "CAPTURE_BUSY_OR_DISCONNECTED"),request); return }
        val p=request.payload
        val id=p.getValue("capture_id").jsonPrimitive.content
        val ttl=(request.expires_at_epoch_ms ?: phoneNow()+5000)-phoneNow()
        if(ttl<=0) { send("ERROR",StudyWire.payload("code" to "CAPTURE_EXPIRED"),request); return }
        captureId=id
        val token=generation
        if(!local) send("CAPTURE_STARTED",StudyWire.payload("capture_id" to id),request)
        captureJob=scope.launch {
            val started=SystemClock.elapsedRealtime()
            val result=try { Result.success(withTimeout(minOf(ttl,p["capture_timeout_ms"]?.jsonPrimitive?.long ?: 5000)) { camera.captureJpeg(
                p["width"]?.jsonPrimitive?.int ?: 1280,p["height"]?.jsonPrimitive?.int ?: 720,p["jpeg_quality"]?.jsonPrimitive?.int ?: 80) }) }
                catch(cancelled: CancellationException) { if(cancelled !is TimeoutCancellationException) throw cancelled; Result.failure(cancelled) }
                catch(error: Exception) { Result.failure(error) }
            inbox.send(Event.Captured(token,request,result,SystemClock.elapsedRealtime()-started))
        }
    }
    private suspend fun captured(event: Event.Captured) {
        if(event.token!=generation || event.request.session_epoch!=activeEpoch) return
        val id=captureId ?: return
        captureId=null
        // Frequent shots reuse the warm pipeline; minute-scale collection releases sensor/ISP between shots.
        if(autonomousMode=="COLLECTION" && intervalMs>=60_000) camera.close()
        val frame=event.result.getOrElse {
            if(event.request.payload["autonomous"]?.jsonPrimitive?.boolean==true) {bufferError="CAMERA_${it.javaClass.simpleName}";return}
            send("CAPTURE_FAILED",StudyWire.payload("capture_id" to id,"code" to "CAMERA_${it.javaClass.simpleName}"),event.request); return
        }
        val descriptor=ImageDescriptor(id,requireNotNull(activeSession),activeEpoch,frame.capturedAtEpochMs ?: System.currentTimeMillis(),
            frame.width,frame.height,frame.rotationDegrees,frame.jpeg.size,StudyWire.sha256(frame.jpeg))
        if(autonomousMode!=null) {
            capturedCount++
            val manual=event.request.payload["autonomous"]?.jsonPrimitive?.boolean!=true
            if(manual) flushing=true
            val triggers=if(autonomousMode=="SERVICE" && manual) setOf("MANUAL") else if(autonomousMode=="SERVICE") {
                if(!VisualSignatureExtractor.usable(frame.jpeg)) {bufferError="等待清晰画面";return}
                val detected=localTrigger.onImage("obs-$id","asset-${descriptor.sha256.take(16)}",descriptor.captured_at_epoch_ms,VisualSignatureExtractor.fromJpeg(frame.jpeg))
                (pendingTrigger+detected.flatMap {it.triggerTypes}).also {pendingTrigger=emptySet()}
            } else emptySet()
            if(autonomousMode=="COLLECTION" || triggers.isNotEmpty()) {
                try {
                    buffer.put(descriptor,frame.jpeg,StudyWire.payload("camera_duration_ms" to event.durationMs,
                        "captured_phone_epoch_ms" to descriptor.captured_at_epoch_ms+phoneMinusGlassesMs,
                        "source_session_id" to activeSession,"capture_mode" to autonomousMode,
                        "trigger_types" to JsonArray(triggers.map(::JsonPrimitive)),
                        "glasses_battery_percent" to getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)))
                    bufferError=null
                } catch(error: Exception) {automatic=false;bufferError=error.message ?: "BUFFER_SAVE_FAILED";wakeLock?.takeIf {it.isHeld}?.release()}
            }
            if(!heartbeatLost) sendBufferStatus()
            return
        }
        val server=requireNotNull(images)
        server.offer(descriptor,frame.jpeg)
        val addresses=NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            iface.inetAddresses.toList().filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.map { address ->
                StudyWire.payload("interface" to iface.name,"address" to address.hostAddress)
            }
        }
        send("IMAGE_OFFER",StudyWire.payload("image" to StudyWire.json.encodeToJsonElement(descriptor),"port" to server.port,
            "camera_duration_ms" to event.durationMs,
            "token" to server.token,"addresses" to JsonArray(addresses),"transfer_protocol" to SessionPeerTls.PROTOCOL,
            "certificate_pem" to server.certificatePem,"certificate_sha256" to server.certificateSha256),event.request)
    }
    private suspend fun transfer(request: StudyMessage) {
        val id=request.payload.getValue("capture_id").jsonPrimitive.content
        val pair=images?.image(id) ?: run { send("ERROR",StudyWire.payload("code" to "IMAGE_EXPIRED","capture_id" to id),request); return }
        if(transferJob?.isActive==true) return
        val sender=BluetoothImageSender(pair.first);imageSender=sender
        transferJob=scope.launch {
            try {
                sender.send(pair.second) {index,data,key->
                    send("IMAGE_CHUNK",StudyWire.payload("capture_id" to id,"index" to index,"data" to Base64.encodeToString(data,Base64.NO_WRAP)),request,key)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { send("ERROR",StudyWire.payload("code" to "IMAGE_TRANSFER_FAILED","capture_id" to id),request) }
            finally {if(imageSender===sender) imageSender=null}
        }
    }
    private fun resetTransient() {
        generation++
        captureJob?.cancel(); transferJob?.cancel(); captureId=null
        imageSender=null;paused=false
        autonomousMode=null;automatic=true;nextCaptureElapsed=0;offeredId=null;flushing=false;capturedCount=0
        lastStatusSignature=""
        localTrigger=StreamingPacketizer();pendingTrigger=emptySet();bufferError=null
        wakeLock?.takeIf {it.isHeld}?.release()
        images?.close(); images=null
        imu.stop(); clearCards(); visibleAcks.clear(); heartbeatLost=false
        graph.mutableUi.update {it.copy(demoStatus=null)}
    }
    override fun onDestroy() {
        resetTransient(); scope.cancel()
        runBlocking(Dispatchers.IO) { camera.close() }
        graph.mutableUi.value=GlassesUiState()
        super.onDestroy()
    }
}
