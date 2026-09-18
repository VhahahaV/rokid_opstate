package ai.opstate.rokid.phone.runtime

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.episode.EpisodeRegistry
import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.core.receipt.DeliveryPolicy
import ai.opstate.core.receipt.ReceiptPolicy
import ai.opstate.core.scheduler.*
import ai.opstate.rokid.vision.VisualSignatureExtractor
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.scenario.StudyScenarioDto
import ai.opstate.rokid.phone.scenario.ScenarioPlayback
import ai.opstate.rokid.phone.scenario.ScenarioMarker
import ai.opstate.rokid.phone.scenario.timeline
import ai.opstate.rokid.phone.replay.*
import ai.opstate.rokid.phone.transport.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
enum class SessionMode { COLLECTION, SERVICE }

internal fun nextFixedCaptureElapsed(originElapsedMs: Long,intervalMs: Long,nowElapsedMs: Long): Long {
    require(intervalMs>0) {"CAPTURE_INTERVAL_INVALID"}
    val sinceOrigin=(nowElapsedMs-originElapsedMs).coerceAtLeast(0)
    return originElapsedMs+(sinceOrigin/intervalMs+1)*intervalMs
}

private const val MIN_COLLECTION_FREE_BYTES=128L*1024*1024

@Serializable
data class SessionSetup(val participantAlias: String,val endpoint: String,
    val scenarioAsset: String="scenarios/meeting-move.json",val profile: String="BENCHMARK_ALIGNED",val expectedModelId: String?=null,
    val directModel: DirectModelConfig?=null,val replayEnabled: Boolean=false,val initialPositionMs: Long=0,
    val replayParentSessionId: String?=null,val historyAnchorIndex: Int?=null,val glassesDemoMode: Boolean=false,val shadowInference: Boolean=false,
    val mode: SessionMode=SessionMode.SERVICE,val captureIntervalMs: Long=60_000,
    val collectionHistorySessionId: String?=null,val collectionHistoryImageLimit: Int=4,val startLiveAfterHistory: Boolean=false,
    val glassesCapture: Boolean=false,val syncBatchSize: Int=60)
data class RuntimeCandidate(val handle: String,val revision: Long,val mode: String)
data class RuntimeViewState(val phase: String="IDLE",val sessionId: String?=null,val scenario: String?=null,
    val observations: Int=0,val packets: Int=0,val receipts: Int=0,val rawDecision: String?=null,
    val delivery: String?=null,val latestImagePath: String?=null,val error: String?=null,val elapsedMs: Long=0,
    val replayEnabled: Boolean=false,val durationMs: Long=0,val markers: List<ScenarioMarker> = emptyList(),
    val currentContext: String?=null,val startupDisplay: String="WAITING",val replayComplete: Boolean=false,
    val historyPreview: HistoryPreview?=null,val automaticCapture: Boolean=true,val nextCaptureInMs: Long?=null,
    val liveCaptures: Int=0,val glassesDemoMode: Boolean=false,val observationStatus: String?=null,
    val candidates: List<RuntimeCandidate> = emptyList(),val latestPacketId: String?=null,
    val captureRoundTripMs: Long?=null,val modelLatencyMs: Long?=null,val decisionToVisibleMs: Long?=null,val packetToVisibleMs: Long?=null,
    val commandAckMs: Long?=null,val cameraDurationMs: Long?=null,val transferDurationMs: Long?=null,
    val shadowInference: Boolean=false,val mode: SessionMode=SessionMode.SERVICE,
    val captureIntervalMs: Long=60_000,val validCaptures: Int=0,val rejectedCaptures: Int=0,
    val savedImageBytes: Long=0,val latestImageBytes: Int?=null,val storageFreeBytes: Long?=null,
    val glassesCapture: Boolean=false,val bufferedPhotos: Int=0,val glassesCaptures: Int=0,val glassesFreeBytes: Long?=null,val syncBatchSize: Int=60,val syncing: Boolean=false)

/** Actor-owned session state; asynchronous work returns with an epoch/generation and immutable inputs. */
class StudyRuntimeEngine(
    private val context: Context,private val scope: CoroutineScope,private val transport: StudyTransport,
    val journal: StudyJournal,private val tokenProvider: ()->String,
    private val modelKeyProvider: (DirectModelConfig)->String={""},
    private val inferenceOverride: ((SessionSetup)->InferenceClient?)?=null,
) {
    private val _state=MutableStateFlow(RuntimeViewState())
    val state: StateFlow<RuntimeViewState> = _state.asStateFlow()
    private val inbox=Channel<Event>(256)
    private var generation=0L
    private var active: Session?=null
    private var preparation: Job?=null
    private var startingReply: CompletableDeferred<Result<Unit>>?=null
    private var ticker: Job?=null
    private val phoneBuild by lazy {installedBuildIdentity(context)}
    private data class PendingModel(val packet: PacketEvaluation,val episode: CandidateEpisode,val request: InferenceRequest,val frame: ImageFrame,
        val historicalJpegs: Map<String,ByteArray> = emptyMap())
    private data class PendingDelivery(val model: PendingModel,val decision: ServiceDecision)
    private class Session(val id: String,var epoch: Long,val setup: SessionSetup,val scenario: StudyScenarioDto,
        val client: InferenceClient?,val contract: FrozenHarnessContract?,val zeroEpoch: Long,val zeroElapsed: Long,val seed: HistorySeed?=null,
        val collectionSeed: LocalCollectionSeed?=null,val peerBuild: PeerBuildInfo?=null) {
        val timeline=scenario.timeline()
        val playback=ScenarioPlayback(setup.initialPositionMs,zeroElapsed,timeline.durationMs)
        val scenarioOrigin=zeroEpoch-setup.initialPositionMs
        fun position(elapsed: Long)=if(setup.replayEnabled) playback.position(elapsed) else elapsed-zeroElapsed
        val history=CausalContextStore();val episodes=EpisodeRegistry();val receipts=ReceiptPolicy()
        val packetEpisodes=linkedMapOf<String,CandidateEpisode>()
        val packets=StreamingPacketizer()
        val cadence=ObservationCadence();val quality=LocalImageQualityAnalyzer()
        val revealed=hashSetOf<String>();val deadlineSeen=hashSetOf<String>();val routineSeen=hashSetOf<String>()
        val auditDue=linkedMapOf<String,Long>();val queue=ArrayDeque<PendingModel>()
        var sensor: SensorWindowPacket?=null
        var latestFrame: ImageFrame?=null;var latestQuality: ImageQuality?=null
        var lastCapture: Long?=null;var lastBaseline: Long?=null;var lastEvaluation: Long?=null;var lastMotion: Long?=null
        var nextCollectionCaptureElapsed=zeroElapsed;var storageBlocked=false
        var materialAt: Long?=null;val materialPending=linkedSetOf<String>()
        var capture: CaptureCommand?=null;var captureRetry=0
        var captureStartedElapsed=0L
        var captureAckReceived=false
        var model: PendingModel?=null;var modelJob: Job?=null;var delivery: PendingDelivery?=null
        var serviceVisible=false;var visibleDecisionId: String?=null;var paused=false;var resumeOnReconnect=false;var phoneInteractionAt=0L
        var observationCount=0;var packetCount=0
        val historicalImages=linkedMapOf<String,ByteArray>()
        var historyReady=seed!=null || collectionSeed!=null;var automaticCapture=true;var demoMode=setup.glassesDemoMode;var lastDemoStatus: String?=null
        var pendingContextCard: JsonObject?=null
        var stopping=false
        val committedBuffered=hashSetOf<String>()
    }
    private sealed class Event {
        data class Start(val setup: SessionSetup,val reply: CompletableDeferred<Result<Unit>>): Event()
        data class Prepared(val token: Long,val session: Result<Session>): Event()
        data class Device(val event: RokidEvent): Event()
        data class Model(val token: Long,val pending: PendingModel,val response: Result<InferenceResponse>,val latencyMs: Long): Event()
        data class Pause(val reply: CompletableDeferred<Unit>): Event()
        data class Resume(val reply: CompletableDeferred<Unit>): Event()
        data class Stop(val reply: CompletableDeferred<Unit>): Event()
        data class Seek(val positionMs: Long,val reply: CompletableDeferred<Result<Unit>>): Event()
        data class SeekHistory(val index: Int,val reply: CompletableDeferred<Result<Unit>>): Event()
        data class ContextInput(val input: SyntheticContextInput,val reply: CompletableDeferred<Result<Unit>>): Event()
        data class AutoCapture(val enabled: Boolean): Event()
        data class DemoMode(val enabled: Boolean): Event()
        data object Tick: Event()
        data object Interaction: Event()
        data object CaptureNow: Event()
        data object SyncPhotos: Event()
    }
    init {
        scope.launch {transport.events.collect {inbox.send(Event.Device(it))}}
        scope.launch {
            for(event in inbox) try {
                when(event) {
                    is Event.Start -> begin(event)
                    is Event.Prepared -> prepared(event)
                    is Event.Device -> device(event.event)
                    is Event.Model -> modelResult(event)
                    is Event.Pause -> {pause();event.reply.complete(Unit)}
                    is Event.Resume -> {resume();event.reply.complete(Unit)}
                    is Event.Stop -> {requestStop();event.reply.complete(Unit)}
                    is Event.Seek -> seek(event)
                    is Event.SeekHistory -> seekHistory(event)
                    is Event.ContextInput -> {addContext(event.input);event.reply.complete(Result.success(Unit))}
                    is Event.AutoCapture -> active?.let {session->session.automaticCapture=event.enabled
                        journal.append(session.id,"runtime_events","AUTOMATIC_CAPTURE_CHANGED",StudyWire.payload("enabled" to event.enabled),System.currentTimeMillis())
                        _state.update {it.copy(automaticCapture=event.enabled)};configureGlasses(session)}
                    is Event.DemoMode -> active?.let {session->session.demoMode=event.enabled;session.lastDemoStatus=null
                        journal.append(session.id,"runtime_events","GLASSES_DEMO_MODE_CHANGED",StudyWire.payload("enabled" to event.enabled),System.currentTimeMillis())
                        _state.update {it.copy(glassesDemoMode=event.enabled)};sendDemoStatus(session)}
                    Event.Tick -> tick()
                    Event.Interaction -> active?.phoneInteractionAt=SystemClock.elapsedRealtime()
                    Event.CaptureNow -> active?.let {capture(it,setOf(CaptureReason.MANUAL_DEBUG))}
                    Event.SyncPhotos -> active?.let {transport.command(transport.controlMessage("FLUSH_CAPTURE_BUFFER")).getOrThrow()}
                }
            } catch(error: Exception) {
                if(error is CancellationException && !currentCoroutineContext().isActive) throw error
                _state.update {it.copy(error=error.message ?: error.javaClass.simpleName)}
                active?.let {session->runCatching {journal.append(session.id,"runtime_events","RUNTIME_ERROR",StudyWire.payload("code" to (error.message ?: error.javaClass.simpleName)),System.currentTimeMillis())}}
                when(event) {
                    is Event.Start -> event.reply.complete(Result.failure(error))
                    is Event.Seek -> event.reply.complete(Result.failure(error))
                    is Event.SeekHistory -> event.reply.complete(Result.failure(error))
                    is Event.ContextInput -> event.reply.complete(Result.failure(error))
                    is Event.Prepared -> {
                        startingReply?.complete(Result.failure(error));startingReply=null
                        active?.let {runCatching {journal.stop(it.id,System.currentTimeMillis())}}
                        active=null;_state.value=RuntimeViewState(phase="ERROR",error=error.message)
                        transport.command(transport.controlMessage("SESSION_STOP"))
                    }
                    is Event.Pause -> event.reply.completeExceptionally(error)
                    is Event.Resume -> event.reply.completeExceptionally(error)
                    is Event.Stop -> event.reply.completeExceptionally(error)
                    else -> Unit
                }
            }
        }
    }
    suspend fun start(setup: SessionSetup): Result<Unit> {val reply=CompletableDeferred<Result<Unit>>();inbox.send(Event.Start(setup,reply));return reply.await()}
    suspend fun pauseSession() {val reply=CompletableDeferred<Unit>();inbox.send(Event.Pause(reply));reply.await()}
    suspend fun resumeSession() {val reply=CompletableDeferred<Unit>();inbox.send(Event.Resume(reply));reply.await()}
    suspend fun stopSession() {val reply=CompletableDeferred<Unit>();inbox.send(Event.Stop(reply));reply.await()}
    suspend fun seekTo(positionMs: Long): Result<Unit> {val reply=CompletableDeferred<Result<Unit>>();inbox.send(Event.Seek(positionMs,reply));return reply.await()}
    suspend fun seekHistoryTo(index: Int): Result<Unit> {val reply=CompletableDeferred<Result<Unit>>();inbox.send(Event.SeekHistory(index,reply));return reply.await()}
    fun interaction() {inbox.trySend(Event.Interaction)}
    fun captureNow() {inbox.trySend(Event.CaptureNow)}
    fun syncPhotos() {inbox.trySend(Event.SyncPhotos)}
    fun setAutomaticCapture(enabled: Boolean) {inbox.trySend(Event.AutoCapture(enabled))}
    fun setGlassesDemoMode(enabled: Boolean) {inbox.trySend(Event.DemoMode(enabled))}
    suspend fun addSyntheticContext(input: SyntheticContextInput): Result<Unit> {val reply=CompletableDeferred<Result<Unit>>();inbox.send(Event.ContextInput(input,reply));return reply.await()}
    fun stopSoon() {scope.launch {runCatching {stopSession()}}}
    private fun nextEpoch(): Long {
        val preferences=context.getSharedPreferences("runtime-epoch",Context.MODE_PRIVATE)
        val epoch=maxOf(System.currentTimeMillis(),preferences.getLong("last_epoch",0)+1)
        check(preferences.edit().putLong("last_epoch",epoch).commit()) { "EPOCH_PERSIST_FAILED" }
        return epoch
    }
    private fun client(setup: SessionSetup): InferenceClient = inferenceOverride?.invoke(setup) ?: setup.directModel?.let {config->
        require(modelKeyProvider(config).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
        DirectModelClient.create(context,config) {modelKeyProvider(config)}
    } ?: RemoteHarnessClient(setup.endpoint,tokenProvider,StudyWire.json,routeClient=InternetRouting(context)::client)
    private suspend fun begin(event: Event.Start) {
        check(active==null && preparation?.isActive!=true) {"SESSION_ALREADY_ACTIVE"}
        require(event.setup.participantAlias.isNotBlank() && event.setup.participantAlias.length<=80)
        require(event.setup.captureIntervalMs in 5_000..3_600_000) {"CAPTURE_INTERVAL_INVALID"}
        require(event.setup.syncBatchSize in 1..500) {"SYNC_BATCH_SIZE_INVALID"}
        require(event.setup.collectionHistoryImageLimit in 0..LocalCollectionRepository.MAX_HISTORY_IMAGES) {"COLLECTION_HISTORY_LIMIT_INVALID"}
        require(event.setup.historyAnchorIndex==null || event.setup.collectionHistorySessionId==null) {"MULTIPLE_HISTORY_SOURCES"}
        val token=++generation
        startingReply=event.reply
        _state.value=RuntimeViewState(phase="PREPARING")
        preparation=scope.launch {
            val result=try {
                check(transport.ready.value) {"CONNECT_GLASSES_FIRST"}
                transport.refreshPeer().getOrThrow()
                val scenario=if(event.setup.mode==SessionMode.COLLECTION) StudyScenarioDto(
                    schemaVersion="study_scenario_v2",scenarioId="local-collection-v1",title="数据采集",events=emptyList(),episodes=emptyList(),
                    operatorSetup=ai.opstate.rokid.phone.scenario.OperatorSetupDto(emptyList(),"Capture egocentric images at a fixed interval.","Stop the collection session."))
                else if(event.setup.collectionHistorySessionId!=null) StudyScenarioDto(
                    schemaVersion="study_scenario_v2",scenarioId="local-collection-continuation-v1",title="采集历史续接",events=emptyList(),episodes=emptyList(),
                    operatorSetup=ai.opstate.rokid.phone.scenario.OperatorSetupDto(emptyList(),"Continue service from selected collected images.","Start a new service segment."))
                else if(event.setup.historyAnchorIndex!=null) StudyScenarioDto(
                    schemaVersion="study_scenario_v2",scenarioId="r6-history-continuation-v1",title="R6 历史续接",events=emptyList(),episodes=emptyList(),
                    operatorSetup=ai.opstate.rokid.phone.scenario.OperatorSetupDto(emptyList(),"Continue from the selected public history with live observations.","Start a new history segment."))
                else if(event.setup.scenarioAsset.isEmpty()) StudyScenarioDto(
                    schemaVersion="study_scenario_v2",scenarioId="live-service-v1",title="在线服务",events=emptyList(),episodes=emptyList(),
                    operatorSetup=ai.opstate.rokid.phone.scenario.OperatorSetupDto(emptyList(),"Serve from live observations and imported history.","End service."))
                else {
                    val scenarioText=context.assets.open(event.setup.scenarioAsset).bufferedReader().use {it.readText()}
                    ai.opstate.rokid.phone.scenario.decodePublicScenario(scenarioText)
                }
                require(scenario.schemaVersion=="study_scenario_v2") {"UNSUPPORTED_SCENARIO_VERSION"}
                require(scenario.events.all {it.availableAtOffsetMs>=it.occurredAtOffsetMs}) {"INVALID_SCENARIO_TIME"}
                require(scenario.events.map {it.eventId}.distinct().size==scenario.events.size) {"DUPLICATE_SCENARIO_EVENT"}
                require(event.setup.initialPositionMs in 0..scenario.timeline().durationMs) {"REPLAY_POSITION_INVALID"}
                require(event.setup.replayEnabled || event.setup.initialPositionMs==0L) {"REPLAY_DISABLED"}
                val client=if(event.setup.mode==SessionMode.SERVICE) client(event.setup) else null
                val contract=client?.contract(event.setup.expectedModelId)
                if(contract!=null) {
                    check(contract.protocolVersion=="opstate-online-v2") {"V5_CONTRACT_REQUIRED"}
                    check(contract.mockMode!="dataset") {"DATASET_ENDPOINT_REQUIRES_REPLAY_MODE"}
                }
                val epoch=nextEpoch()
                val id="session-${UUID.randomUUID()}"
                transport.startSession(id,epoch).getOrThrow()
                val now=System.currentTimeMillis()
                val seed=event.setup.historyAnchorIndex?.let {index->PublicReplayDataset {path->context.assets.open(PublicReplayDataset.ASSET_ROOT+path).use {it.readBytes()}}.continuation(index,now)}
                val collectionSeed=event.setup.collectionHistorySessionId?.let {sourceId->
                    LocalCollectionRepository(journal).load(sourceId,event.setup.collectionHistoryImageLimit)
                }
                phoneBuild // Read the installed artifact on the preparation worker before freezing this session.
                Result.success(Session(id,epoch,event.setup,scenario,client,contract,now,SystemClock.elapsedRealtime(),seed,collectionSeed,transport.peerBuildInfo()))
            } catch(cancelled: CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                Result.failure(IllegalStateException(if(cancelled is TimeoutCancellationException) "SESSION_PREPARATION_TIMEOUT" else "SESSION_PREPARATION_INTERRUPTED",cancelled))
            }
            catch(error: Exception) {Result.failure(error)}
            inbox.send(Event.Prepared(token,result))
        }
    }
    private suspend fun prepared(event: Event.Prepared) {
        if(event.token!=generation) return
        val session=event.session.getOrElse {
            transport.command(transport.controlMessage("SESSION_STOP"))
            _state.value=RuntimeViewState(phase="ERROR",error=it.message)
            startingReply?.complete(Result.failure(it));startingReply=null;return
        }
        active=session
        val config=StudyWire.payload("profile" to session.setup.profile,"session_mode" to session.setup.mode.name,
            "capture_interval_ms" to session.setup.captureIntervalMs,
            "glasses_capture" to session.setup.glassesCapture,"sync_batch_size" to session.setup.syncBatchSize,
            "inference_endpoint" to (session.setup.directModel?.resolvedEndpoint() ?: session.setup.endpoint),
            "connection_mode" to when {session.setup.mode==SessionMode.COLLECTION->"DISABLED_COLLECTION";session.contract?.mockMode=="local_scripted"->"LOCAL_MOCK";session.setup.directModel!=null->"DIRECT_API_KEY";else->"HARNESS"},
            "scenario" to StudyWire.json.encodeToJsonElement(session.scenario),
            "replay_enabled" to session.setup.replayEnabled,"initial_position_ms" to session.setup.initialPositionMs,
            "replay_parent_session_id" to session.setup.replayParentSessionId,"seek_policy" to "NEW_SESSION_SEGMENT",
            "history_seed" to session.seed?.let {StudyWire.payload("source_anchor_id" to it.preview.anchorId,"index" to it.preview.index,
                "source_cutoff_epoch_ms" to it.sourceCutoffMs,"alignment_ms" to it.alignmentMs,"catalog_hash" to it.catalogHash,
                "source" to "R6_PUBLIC_CAUSAL_PREFIX","teacher_receipts" to false,"body_text_rewritten" to false)},
            "collection_history_seed" to session.collectionSeed?.let {StudyWire.payload(
                "source" to "LOCAL_COLLECTION","source_session_id" to it.summary.sessionId,
                "selected_image_count" to it.images.size,
                "selected_observation_ids" to JsonArray(it.images.map {image->JsonPrimitive(image.sourceObservationId)}))},
            "glasses_demo_mode" to session.demoMode,"shadow_inference" to session.setup.shadowInference,
            "phone_build" to phoneBuild,"glasses_build" to session.peerBuild?.let {StudyWire.json.encodeToJsonElement(it)},
            "glasses_app_version" to session.peerBuild?.appVersion,
            "image_quality_policy" to StudyWire.payload("method" to "rgb-edge-32x24-v2","min_luma" to 18,"max_luma" to 238,"min_edge_energy" to 25,
                "dark_pixel_ratio_recorded" to true,"bright_pixel_ratio_recorded" to true,"ratio_threshold_gate" to false),
            "contract" to session.contract?.let {StudyWire.json.encodeToJsonElement(it)},
            "packet_frontend" to StudyWire.payload(*session.packets.configuration().map {it.key to it.value}.toTypedArray()),
            "capture_policy" to StudyWire.payload(*session.cadence.configuration().map {it.key to it.value}.toTypedArray()),"capture_codec" to "camera2-yuv-upright-jpeg-v1")
        journal.start(session.id,session.epoch,session.setup.participantAlias,session.scenario.scenarioId,session.setup.profile,
            StudyWire.sha256(StudyJournal.canonical(config).toString().toByteArray()),session.zeroEpoch,config)
        for(episode in if(!session.historyReady) session.scenario.episodes else emptyList()) {
            val candidate=CandidateEpisode(episode.episodeHandle,episode.objectHandle,episode.intentFingerprint,ServiceMode.valueOf(episode.serviceMode),
                episode.schedulerEligible,materialRevision=episode.materialRevision,deadlineEpochMs=episode.deadlineOffsetMs?.let {session.scenarioOrigin+it},
                routineWindowActive=episode.routineWindowActive,triggerKeys=episode.schedulerTriggerKeys)
            session.episodes.upsert(candidate)
            journal.append(session.id,"episodes","EPISODE_REGISTERED",StudyWire.payload("episode_handle" to candidate.episodeHandle,
                "object_handle" to candidate.objectHandle,"intent_fingerprint" to candidate.intentFingerprint,"material_revision" to candidate.materialRevision),session.zeroEpoch)
        }
        _state.value=RuntimeViewState(phase="RUNNING",sessionId=session.id,scenario=session.scenario.title,
            replayEnabled=session.setup.replayEnabled,elapsedMs=session.setup.initialPositionMs,
            durationMs=session.timeline.durationMs,markers=session.timeline.markers,glassesDemoMode=session.demoMode,
            shadowInference=session.setup.shadowInference,mode=session.setup.mode,captureIntervalMs=session.setup.captureIntervalMs,
            glassesCapture=session.setup.glassesCapture,syncBatchSize=session.setup.syncBatchSize,
            storageFreeBytes=context.filesDir.usableSpace)
        session.seed?.let {seed->
            val dataset=PublicReplayDataset {path->context.assets.open(PublicReplayDataset.ASSET_ROOT+path).use {it.readBytes()}}
            val imageMetadata=mutableMapOf<String,JsonObject>()
            for(record in seed.observations.filter {it.kind==ObservationKind.IMAGE && it.assetId?.let(dataset::hasImage)==true}.takeLast(4)) {
                val asset=requireNotNull(record.assetId)
                val jpeg=dataset.image(asset)
                session.historicalImages[asset]=jpeg
                val hash=StudyWire.sha256(jpeg)
                val path=journal.image(session.id,"history-${hash.take(16)}",jpeg,hash)
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,bounds)
                imageMetadata[record.observationId]=StudyWire.payload("local_relative_path" to path,"sha256" to hash,
                    "width" to bounds.outWidth,"height" to bounds.outHeight,"rotation_degrees" to 0,"byte_length" to jpeg.size)
            }
            session.episodes.upsert(seed.candidate)
            journal.database.runInTransaction {
                journal.append(session.id,"episodes","EPISODE_REGISTERED",StudyWire.payload("episode_handle" to seed.candidate.episodeHandle,
                    "object_handle" to seed.candidate.objectHandle,"intent_fingerprint" to seed.candidate.intentFingerprint,
                    "material_revision" to seed.candidate.materialRevision),session.zeroEpoch)
                for(source in seed.observations) {
                    val record=if(source.observationId in imageMetadata) source.copy(content="Historical egocentric image available as visual context.") else source
                    session.history.append(record);appendObservation(session,record,JsonObject(imageMetadata[record.observationId].orEmpty()+StudyWire.payload(
                    "source" to "R6_PUBLIC_HISTORY","source_available_at_epoch_ms" to record.availableAtEpochMs-seed.alignmentMs,
                    "source_occurred_at_epoch_ms" to record.occurredAtEpochMs-seed.alignmentMs)))}
            }
            session.paused=session.setup.mode==SessionMode.SERVICE
            _state.update {it.copy(phase="HISTORY_READY",scenario="R6 历史续接",historyPreview=seed.preview,
                currentContext=seed.preview.recent.lastOrNull())}
        }
        session.collectionSeed?.let {seed->
            for(record in seed.contexts) {
                session.history.append(record)
                appendObservation(session,record,StudyWire.payload("source" to "LOCAL_COLLECTION_HISTORY","source_session_id" to seed.summary.sessionId))
            }
            for((index,image) in seed.images.withIndex()) {
                val copyId="history-${index.toString().padStart(3,'0')}-${image.sha256.take(16)}"
                val path=journal.image(session.id,copyId,image.jpeg,image.sha256)
                session.history.append(image.record)
                image.record.assetId?.let {session.historicalImages[it]=image.jpeg}
                val metadataKeys=setOf("phone_metadata_at_epoch_ms","phone_model","phone_battery_percent","location_status","phone_location","byte_length","quality_status","camera_duration_ms","transfer_duration_ms","capture_round_trip_ms","transfer_channel","source_occurred_at_epoch_ms")
                appendObservation(session,image.record,JsonObject(image.metadata.filterKeys {it in metadataKeys}+StudyWire.payload(
                    "source" to "LOCAL_COLLECTION_HISTORY","source_session_id" to seed.summary.sessionId,
                    "source_observation_id" to image.sourceObservationId,"local_relative_path" to path,"sha256" to image.sha256,
                    "width" to image.width,"height" to image.height,"rotation_degrees" to image.rotationDegrees,"quality_valid" to true)))
            }
            session.paused=session.setup.mode==SessionMode.SERVICE
            _state.update {it.copy(phase="HISTORY_READY",scenario="采集历史续接",
                currentContext="已载入 ${seed.images.size} 张历史采集图片")}
        }
        journal.append(session.id,"runtime_events","SESSION_PEER_READY",StudyWire.payload("session_epoch" to session.epoch),System.currentTimeMillis())
        // Operator status is separate from context, model output and service receipts.
        val status=transport.command(transport.controlMessage("SHOW_SESSION_STATUS",StudyWire.payload(
            "event_id" to "start-${session.id}","title" to when {session.historyReady->"历史已就绪";session.setup.mode==SessionMode.COLLECTION->"采集已开始";else->"服务已开始"},
            "body" to when {session.historyReady->"请在手机开始实时服务，继续这段历史。";session.setup.mode==SessionMode.COLLECTION->"眼镜会按固定间隔拍照并保存到手机。";else->"由手机控制进度，眼镜将按计划观察。"},"visible_for_ms" to 3000),10_000))
        if(status.isFailure) _state.update {it.copy(startupDisplay="FAILED",error="SESSION_STATUS_SEND_FAILED")}
        if(session.setup.initialPositionMs>0) reveal(session,System.currentTimeMillis(),SystemClock.elapsedRealtime(),restoring=true)
        sendDemoStatus(session)
        refreshCandidates(session);checkpoint(session)
        startingReply?.complete(Result.success(Unit));startingReply=null
        ticker?.cancel();ticker=scope.launch {while(isActive) {inbox.send(Event.Tick);delay(250)}}
        if(session.setup.mode==SessionMode.COLLECTION) {
            session.historyReady=false;session.paused=false;updatePhase(session)
            if(session.setup.glassesCapture) configureGlasses(session) else capture(session,setOf(CaptureReason.PERIODIC_BASELINE))
        } else if(session.historyReady && session.setup.startLiveAfterHistory) resume()
        else if(!session.historyReady) configureGlasses(session)
    }
    private suspend fun tick() {
        val session=active ?: return
        val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        val position=session.position(elapsed)
        _state.update {it.copy(elapsedMs=position)}
        val nextCapture=if(!session.automaticCapture || session.paused) null else if(session.setup.mode==SessionMode.COLLECTION)
            (session.nextCollectionCaptureElapsed-elapsed).coerceAtLeast(0)
        else ((session.lastBaseline ?: session.zeroElapsed-56000)+60000-elapsed).coerceAtLeast(0)
        if(!session.setup.glassesCapture) _state.update {it.copy(nextCaptureInMs=nextCapture)}
        sendDemoStatus(session)
        if(session.paused) return
        if(!session.setup.glassesCapture) recordPackets(session,session.packets.advance(now))
        if(_state.value.startupDisplay=="WAITING" && elapsed-session.zeroElapsed>6000)
            _state.update {it.copy(startupDisplay="UNCONFIRMED")}
        if(session.setup.mode==SessionMode.SERVICE && session.seed==null) reveal(session,now,elapsed)
        if(session.setup.replayEnabled && position>=session.timeline.durationMs) {
            pause();_state.update {it.copy(replayComplete=true)};return
        }
        session.capture?.let {if(elapsed>=it.deadlineElapsedMs) {
            journal.append(session.id,"captures","CAPTURE_TIMEOUT",StudyWire.payload("capture_id" to it.captureId,"status" to "TIMEOUT"),now)
            session.capture=null;_state.update {state->state.copy(error="CAPTURE_TIMEOUT",observationStatus="CAPTURE_TIMEOUT")};updatePhase(session)
        }}
        session.delivery?.let {if(now>=it.decision.expiresAtEpochMs) {
            endPendingDelivery(session,"DISPLAY_FAILED","VISIBLE_ACK_TIMEOUT",now)
            transport.command(transport.controlMessage("CLEAR_SERVICE_CARD"))
        }}
        if(session.setup.mode==SessionMode.COLLECTION) {
            if(!session.setup.glassesCapture && session.automaticCapture && session.capture==null && elapsed>=session.nextCollectionCaptureElapsed)
                capture(session,setOf(CaptureReason.PERIODIC_BASELINE))
            return
        }
        kickModel(session)
        if(session.setup.glassesCapture) return
        if(elapsed-session.zeroElapsed<4_000 || session.capture!=null || session.model!=null || session.delivery!=null) return
        val snapshot=snapshot(session,null)
        val plan=session.cadence.plan(snapshot)
        if(session.automaticCapture && plan.due) capture(session,plan.reasons)
    }
    private suspend fun reveal(session: Session,now: Long,elapsed: Long,restoring: Boolean=false) {
        val offset=if(restoring) session.setup.initialPositionMs else session.position(elapsed)
        val latestVisible=if(restoring) session.scenario.events.filter {it.availableAtOffsetMs<=offset && it.runtimeDelivery=="SHOW_CONTEXT_CARD"}
            .maxByOrNull {it.availableAtOffsetMs}?.eventId else null
        for(event in session.scenario.events.sortedBy {it.availableAtOffsetMs}) {
            if(event.eventId in session.revealed || event.availableAtOffsetMs>offset) continue
            val deadline=session.episodes.allForAudit().firstOrNull()?.deadlineEpochMs ?: session.zeroEpoch+720000
            val time=Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault())
            val text=event.publicText.replace("{{meeting_time}}",time.format(DateTimeFormatter.ofPattern("HH:mm")))
                .replace("{{meeting_date}}",time.format(DateTimeFormatter.ISO_LOCAL_DATE))
            val available=if(restoring || event.availableAtOffsetMs<=0) session.scenarioOrigin+event.availableAtOffsetMs else now
            val observation=ObservationRecord(event.eventId,ObservationKind.SYNTHETIC_CONTEXT,session.scenarioOrigin+event.occurredAtOffsetMs,
                available,text,episodeHandle=event.episodeHandle,materialRevision=event.materialRevision)
            session.history.append(observation);appendObservation(session,observation)
            recordPackets(session,session.packets.onDigital(observation.observationId,observation.availableAtEpochMs))
            journal.append(session.id,"context_events","CONTEXT_EVENT",observationJson(observation),now)
            event.episodeHandle?.let {handle->event.materialRevision?.let {revision->
                session.episodes.get(handle)?.let {if(revision>it.materialRevision) {
                    session.episodes.updateMaterialRevision(handle,revision)
                    if(session.delivery?.model?.episode?.episodeHandle==handle) {
                        endPendingDelivery(session,"STALE_DECISION","MATERIAL_REVISION_CHANGED",now)
                        transport.command(transport.controlMessage("CLEAR_SERVICE_CARD"))
                    }
                }}
            }}
            if(event.materialChangeForScheduler) {
                session.materialAt=now
                session.materialPending+=event.episodeHandle?.let(::setOf) ?: session.episodes.schedulerCandidates().map {it.episodeHandle}
            }
            session.revealed+=event.eventId
            if(event.runtimeDelivery=="SHOW_CONTEXT_CARD") _state.update {it.copy(currentContext=text)}
            if(event.runtimeDelivery=="SHOW_CONTEXT_CARD" && (!restoring || event.eventId==latestVisible)) {
                val sent=transport.command(transport.controlMessage("SHOW_CONTEXT_CARD",StudyWire.payload(
                    "event_id" to event.eventId,"title" to "情境更新","body" to text,"visible_for_ms" to 8000),30_000))
                if(sent.isFailure) journal.append(session.id,"runtime_events","CONTEXT_DELIVERY_FAILED",StudyWire.payload("event_id" to event.eventId),now)
            }
        }
    }
    private suspend fun capture(session: Session,reasons: Set<CaptureReason>) {
        if(session.paused || session.capture!=null || session.model!=null) return
        if(session.setup.mode==SessionMode.COLLECTION) {
            val free=context.filesDir.usableSpace
            _state.update {it.copy(storageFreeBytes=free)}
            if(free<MIN_COLLECTION_FREE_BYTES) {
                session.automaticCapture=false
                if(!session.storageBlocked) journal.append(session.id,"runtime_events","COLLECTION_PAUSED_STORAGE_LOW",
                    StudyWire.payload("free_bytes" to free,"minimum_free_bytes" to MIN_COLLECTION_FREE_BYTES),System.currentTimeMillis())
                session.storageBlocked=true
                _state.update {it.copy(automaticCapture=false,error="COLLECTION_STORAGE_LOW",observationStatus="COLLECTION_STORAGE_LOW")}
                return
            }
            session.storageBlocked=false
        }
        _state.update {it.copy(error=null,observationStatus="CAPTURING")}
        val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
        session.captureStartedElapsed=elapsed
        session.captureAckReceived=false
        val command=CaptureCommand(session.id,session.epoch,"capture-${UUID.randomUUID()}",reasons.mapTo(hashSetOf()) {it.name},deadlineElapsedMs=elapsed+25_000,captureTimeoutMs=5_000)
        session.capture=command
        session.lastCapture=elapsed
        if(CaptureReason.PERIODIC_BASELINE in reasons) {
            session.lastBaseline=elapsed
            if(session.setup.mode==SessionMode.COLLECTION) session.nextCollectionCaptureElapsed=
                nextFixedCaptureElapsed(session.zeroElapsed,session.setup.captureIntervalMs,elapsed)
        }
        if(CaptureReason.DEADLINE_WINDOW in reasons) session.deadlineSeen+=session.episodes.allForAudit().map {it.episodeHandle}
        if(CaptureReason.ROUTINE_WINDOW in reasons) session.routineSeen+=session.episodes.allForAudit().map {it.episodeHandle}
        journal.append(session.id,"captures","CAPTURE_REQUEST",StudyWire.payload("capture_id" to command.captureId,"status" to "REQUESTED",
            "reason_codes" to JsonArray(reasons.map {JsonPrimitive(it.name)}),"requested_at_epoch_ms" to now,"requested_elapsed_ms" to elapsed,
            "quality_retry" to session.captureRetry),now)
        val result=transport.requestCapture(command)
        if(result.isFailure) {session.capture=null;journal.append(session.id,"captures","CAPTURE_FAILED",StudyWire.payload("capture_id" to command.captureId,"status" to "FAILED","code" to "TRANSPORT_SEND_FAILED"),now)}
        updatePhase(session)
    }
    private suspend fun device(event: RokidEvent) {
        val session=active ?: return
        if(session.paused && event !is RokidEvent.TransportError && event !is RokidEvent.ConnectionLost && event !is RokidEvent.DeviceReady && event !is RokidEvent.SessionStatusVisible &&
            event !is RokidEvent.CaptureBufferStatus && !(event is RokidEvent.ImageReceived && event.frame.buffered)) return
        val now=System.currentTimeMillis()
        when(event) {
            is RokidEvent.CaptureBufferStatus -> {
                val p=event.payload
                _state.update {it.copy(bufferedPhotos=p.getValue("pending").jsonPrimitive.int,glassesCaptures=p.getValue("captured").jsonPrimitive.int,
                    glassesFreeBytes=p.getValue("free_bytes").jsonPrimitive.long,syncing=p.getValue("syncing").jsonPrimitive.boolean,
                    nextCaptureInMs=p.getValue("next_capture_in_ms").jsonPrimitive.long,error=p["error"]?.jsonPrimitive?.contentOrNull,
                    automaticCapture=p.getValue("automatic").jsonPrimitive.boolean)}
                if(session.stopping && p["automatic"]==JsonPrimitive(false) && p["capturing"]==JsonPrimitive(false) && p["pending"]==JsonPrimitive(0)) stop()
            }
            is RokidEvent.CaptureAcknowledged -> if(session.capture?.captureId==event.captureId && !session.captureAckReceived) {
                session.captureAckReceived=true
                val latency=event.receivedElapsedMs-session.captureStartedElapsed
                _state.update {it.copy(commandAckMs=latency)}
                journal.append(session.id,"runtime_events","CAPTURE_COMMAND_ACK",StudyWire.payload("capture_id" to event.captureId,
                    "command_ack_rtt_ms" to latency),now)
            }
            is RokidEvent.SessionStatusVisible -> if(event.sessionId==session.id && event.sessionEpoch==session.epoch &&
                event.statusId=="start-${session.id}" && event.renderFrameCount>=2 && _state.value.startupDisplay!="VISIBLE") {
                _state.update {it.copy(startupDisplay="VISIBLE")}
                journal.append(session.id,"runtime_events","SESSION_STATUS_VISIBLE",StudyWire.payload(
                    "status_id" to event.statusId,"render_frame_count" to event.renderFrameCount),now)
            }
            is RokidEvent.ConnectionLost -> {
                _state.update {it.copy(error=event.code)}
                journal.append(session.id,"runtime_events","SESSION_LINK_INTERRUPTED",StudyWire.payload(
                    "code" to event.code,"session_epoch" to session.epoch,"was_paused" to session.paused),now)
                if(!session.paused) {
                    session.resumeOnReconnect=true;session.paused=true
                    if(session.setup.replayEnabled) session.playback.pause(SystemClock.elapsedRealtime())
                    cancelPendingWork(session,"CONNECTION_LOST")
                    session.sensor=null
                    updatePhase(session);checkpoint(session)
                }
            }
            is RokidEvent.DeviceReady -> if(session.resumeOnReconnect) resume("LINK_RECONNECTED")
            is RokidEvent.SensorWindowReceived -> {
                session.sensor=event.window
                if(event.window.isMoving) session.lastMotion=event.window.windowEndElapsedMs
                val w=event.window
                journal.append(session.id,"sensor_windows","SENSOR_SUMMARY",StudyWire.payload(
                    "window_start_elapsed_ms" to w.windowStartElapsedMs,"window_end_elapsed_ms" to w.windowEndElapsedMs,"sample_count" to w.sampleCount,
                    "angular_velocity_rms_deg_s" to w.angularVelocityRmsDegPerSec,"angular_velocity_p95_deg_s" to w.angularVelocityP95DegPerSec,
                    "linear_acceleration_rms_m_s2" to w.linearAccelerationRmsMps2,"linear_acceleration_p95_m_s2" to w.linearAccelerationP95Mps2,
                    "stable_ratio" to w.stableRatio,"is_stable" to w.isStable,"is_moving" to w.isMoving,"speech_ratio" to w.speechRatio,"raw_audio_stored" to false),now)
            }
            is RokidEvent.ImageReceived -> image(session,event.frame)
            is RokidEvent.CaptureFailed -> if(session.capture?.captureId==event.captureId) {
                session.capture=null;journal.append(session.id,"captures","CAPTURE_FAILED",StudyWire.payload("capture_id" to event.captureId,"status" to "FAILED","code" to event.code),now)
                _state.update {it.copy(error=event.code,observationStatus=event.code)};updatePhase(session)
            }
            is RokidEvent.DisplayVisibleAck -> {
                val pending=session.delivery ?: return
                if(event.sessionId!=session.id || event.sessionEpoch!=session.epoch || event.decisionId!=pending.decision.decisionId || event.renderFrameCount<2) return
                val current=session.packetEpisodes[pending.model.episode.episodeHandle] ?: return
                if(current.materialRevision!=pending.model.episode.materialRevision) {
                    endPendingDelivery(session,"STALE_DECISION","MATERIAL_REVISION_CHANGED",now)
                    transport.command(transport.controlMessage("CLEAR_SERVICE_CARD"));updatePhase(session);return
                }
                var persisted=false
                val outcome=session.receipts.finalizeDelivery(pending.decision,pending.model.episode,current.objectHandle,event.visibleAtEpochMs,
                    InferenceContract.allowedEvidenceIds(pending.model.request),now) { receipt ->
                    journal.database.runInTransaction {
                        delivery(session,pending.decision.decisionId,"DISPLAY_VISIBLE","service visibly rendered; receipt committed",now,receipt.receiptId,updateUi=false)
                        journal.append(session.id,"receipts","RECEIPT_COMMITTED",receiptJson(receipt,event.renderFrameCount,pending.model.packet.packetId),now)
                    }
                    persisted=true
                }
                if(!persisted) delivery(session,pending.decision.decisionId,outcome.status.name,outcome.reason,now)
                else _state.update {it.copy(delivery=outcome.status.name)}
                outcome.receipt?.let {
                    session.auditDue[current.episodeHandle]=SystemClock.elapsedRealtime()+30_000
                    session.serviceVisible=true;session.visibleDecisionId=pending.decision.decisionId
                    val visibleLatency=event.visibleAtEpochMs-pending.decision.producedAtEpochMs
                    val packetLatency=event.visibleAtEpochMs-pending.model.packet.snapshotAtEpochMs
                    _state.update {state->state.copy(decisionToVisibleMs=visibleLatency,packetToVisibleMs=packetLatency)}
                    journal.append(session.id,"runtime_events","VISIBLE_TIMING",StudyWire.payload("decision_id" to pending.decision.decisionId,
                        "packet_id" to pending.model.packet.packetId,"decision_to_visible_ms" to visibleLatency,"packet_to_visible_ms" to packetLatency),now)
                }
                session.packetEpisodes.remove(pending.model.episode.episodeHandle)
                session.delivery=null;_state.update {it.copy(receipts=session.receipts.allReceipts().size)};checkpoint(session);updatePhase(session)
            }
            is RokidEvent.DisplayDismissed -> {
                if(event.decisionId==session.delivery?.decision?.decisionId)
                    endPendingDelivery(session,"DISPLAY_FAILED","DISMISSED_BEFORE_VISIBLE_ACK:${event.reason}",now)
                if(event.decisionId==session.visibleDecisionId) {session.serviceVisible=false;session.visibleDecisionId=null}
                updatePhase(session)
            }
            is RokidEvent.DisplayFailed -> {
                if(event.sessionId!=session.id || event.sessionEpoch!=session.epoch || event.decisionId!=session.delivery?.decision?.decisionId) return
                endPendingDelivery(session,"DISPLAY_FAILED",event.code,now);updatePhase(session)
            }
            is RokidEvent.TransportError -> {
                journal.append(session.id,"runtime_events","TRANSPORT_ERROR",StudyWire.payload("code" to event.code,"recoverable" to event.recoverable),now)
                _state.update {it.copy(error=event.code)}
            }
            else -> Unit
        }
    }
    private suspend fun image(session: Session,frame: ImageFrame) {
        val pending=session.capture
        if(!frame.buffered && (pending==null || frame.captureId!=pending.captureId || session.paused)) return
        if(frame.buffered && !session.setup.glassesCapture) return
        if(frame.buffered && frame.captureId in session.committedBuffered) {
            commitBuffered(session,frame);return
        }
        if(frame.buffered && context.filesDir.usableSpace<MIN_COLLECTION_FREE_BYTES) {
            session.automaticCapture=false;configureGlasses(session)
            _state.update {it.copy(error="COLLECTION_STORAGE_LOW")};return
        }
        val roundTripMs=if(frame.buffered) frame.cameraDurationMs?.plus(frame.transferDurationMs ?: 0) else SystemClock.elapsedRealtime()-session.captureStartedElapsed
        if(pending?.captureId==frame.captureId) session.capture=null
        val quality=session.quality.analyze(frame)
        val path=journal.image(session.id,frame.captureId,frame.jpegBytes,frame.sha256)
        val observation=ObservationRecord("obs-${frame.captureId}",ObservationKind.IMAGE,frame.capturedAtEpochMs,frame.receivedAtEpochMs,
            "Current egocentric image observation.",assetId="asset-${frame.sha256.take(16)}")
        session.history.append(observation)
        appendObservation(session,observation,JsonObject(phoneCaptureMetadata(context)+frame.sourceMetadata+StudyWire.payload("local_relative_path" to path,"sha256" to frame.sha256,"width" to frame.width,"height" to frame.height,
            "buffered_sync" to frame.buffered,"location_relation" to "PHONE_RECEIVE_TIME",
            "byte_length" to frame.jpegBytes.size,"camera_duration_ms" to frame.cameraDurationMs,"transfer_duration_ms" to frame.transferDurationMs,"capture_round_trip_ms" to roundTripMs,
            "rotation_degrees" to frame.rotationDegrees,"quality_valid" to quality.valid,"quality_status" to quality.status.name,
            "mean_luma" to quality.meanLuma,"blur_score" to quality.blurScore,"dark_pixel_ratio" to quality.darkPixelRatio,"bright_pixel_ratio" to quality.brightPixelRatio,
            "transfer_channel" to frame.transferChannel,"asset_source" to if(frame.transferChannel=="TEST_FIXTURE") "TEST_FIXTURE" else "LIVE_CAMERA")))
        journal.append(session.id,"captures","CAPTURE_COMPLETED",StudyWire.payload("capture_id" to frame.captureId,"status" to "COMPLETED",
            "channel" to frame.transferChannel,"quality_valid" to quality.valid,"received_at_epoch_ms" to frame.receivedAtEpochMs,
            "camera_duration_ms" to frame.cameraDurationMs,"transfer_duration_ms" to frame.transferDurationMs,"capture_round_trip_ms" to roundTripMs),frame.receivedAtEpochMs)
        _state.update {it.copy(latestImagePath=journal.localImage(session.id,path).absolutePath,liveCaptures=it.liveCaptures+1,
            validCaptures=it.validCaptures+if(quality.valid) 1 else 0,rejectedCaptures=it.rejectedCaptures+if(quality.valid) 0 else 1,
            savedImageBytes=it.savedImageBytes+frame.jpegBytes.size,latestImageBytes=frame.jpegBytes.size,storageFreeBytes=context.filesDir.usableSpace,
            captureRoundTripMs=roundTripMs,cameraDurationMs=frame.cameraDurationMs,transferDurationMs=frame.transferDurationMs,
            observationStatus=if(quality.valid) "IMAGE_SAVED" else it.observationStatus)}
        if(frame.buffered) {session.committedBuffered+=frame.captureId;commitBuffered(session,frame)}
        if(!quality.valid) {
            val code=when(quality.status) {ImageQualityStatus.TOO_DARK->"IMAGE_TOO_DARK";ImageQualityStatus.TOO_BRIGHT->"IMAGE_TOO_BRIGHT";
                ImageQualityStatus.BLURRY->"IMAGE_TOO_BLURRY";else->"IMAGE_CORRUPTED"}
            if(session.captureRetry<1) {session.captureRetry++;capture(session,setOf(CaptureReason.QUALITY_RETRY))}
            else {session.captureRetry=0;_state.update {it.copy(error=code)}}
            _state.update {it.copy(observationStatus=code)}
            updatePhase(session);return
        }
        session.captureRetry=0
        session.latestFrame=frame;session.latestQuality=quality
        if(session.setup.mode==SessionMode.COLLECTION) _state.update {it.copy(observationStatus="IMAGE_SAVED")}
        if(session.setup.glassesCapture && session.setup.mode==SessionMode.SERVICE) {
            val triggers=frame.sourceMetadata["trigger_types"]?.jsonArray?.map {it.jsonPrimitive.content}?.toSet() ?: setOf("MANUAL")
            if(triggers.isNotEmpty()) recordPackets(session,listOf(ObservationPacket("edge-${frame.captureId}",frame.receivedAtEpochMs,triggers,
                listOf(observation.observationId),listOf(requireNotNull(observation.assetId)),emptyList(),frame.receivedAtEpochMs-frame.capturedAtEpochMs,false)))
        } else if(session.setup.mode==SessionMode.SERVICE) recordPackets(session,session.packets.onImage(observation.observationId,requireNotNull(observation.assetId),frame.receivedAtEpochMs,
            VisualSignatureExtractor.fromJpeg(frame.jpegBytes)))
        kickModel(session);updatePhase(session)
    }
    private fun kickModel(session: Session) {
        if(session.setup.mode!=SessionMode.SERVICE) return
        if(session.paused || session.model!=null || session.queue.isEmpty() || session.delivery!=null || session.serviceVisible ||
            session.sensor?.contextCardVisible==true || session.sensor?.serviceCardVisible==true || !transport.ready.value) return
        val pending=session.queue.removeFirst();session.model=pending
        _state.update {it.copy(latestPacketId=pending.packet.packetId)}
        val token=generation
        journal.append(session.id,"inference_requests","INFERENCE_SENT",requestJournalJson(pending.request),System.currentTimeMillis())
        session.modelJob=scope.launch {
            val started=SystemClock.elapsedRealtime()
            val result=try {Result.success(requireNotNull(session.client).evaluateWithHistory(
                pending.request,pending.frame.jpegBytes,pending.historicalJpegs) {raw->
                journal.append(session.id,"raw_responses",if(raw.format.startsWith("PROVIDER_")) "PROVIDER_RESPONSE" else "RAW_DECISION",
                    StudyWire.payload("request_id" to raw.requestId,"packet_id" to pending.packet.packetId,
                    "http_status" to raw.httpStatus,"raw_response_json" to raw.body,"raw_sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs,
                    "raw_format" to raw.format,"redacted" to raw.redacted),raw.receivedAtEpochMs)
            })} catch(cancelled: CancellationException) {throw cancelled} catch(error: Exception) {Result.failure(error)}
            inbox.send(Event.Model(token,pending,result,SystemClock.elapsedRealtime()-started))
        }
        updatePhase(session)
    }
    private suspend fun modelResult(event: Event.Model) {
        val session=active ?: return
        if(event.token!=generation || session.model?.request?.requestId!=event.pending.request.requestId) return
        session.model=null
        _state.update {it.copy(modelLatencyMs=event.latencyMs)}
        val now=System.currentTimeMillis()
        val response=event.response.getOrElse {
            val code=it.message?.takeIf {message->message.matches(Regex("[A-Z0-9_]{1,100}"))} ?: it.javaClass.simpleName
            journal.append(session.id,"runtime_events","TECHNICAL_FAILURE",StudyWire.payload("request_id" to event.pending.request.requestId,
                "packet_id" to event.pending.packet.packetId,"code" to code,"model_latency_ms" to event.latencyMs),now)
            session.packetEpisodes.remove(event.pending.episode.episodeHandle)
            _state.update {state->state.copy(error=it.message)};kickModel(session);updatePhase(session);return
        }
        journal.append(session.id,"decisions","MODEL_DECISION",StudyWire.payload("request_id" to response.requestId,"packet_id" to event.pending.packet.packetId,
            "decision_id" to response.decisionId,"decision" to response.decision.name,"evidence_observation_ids" to JsonArray(response.evidenceObservationIds.map(::JsonPrimitive)),
            "service_mode" to response.serviceMode?.name,"title" to response.title,"body" to response.body,
            "produced_at_epoch_ms" to response.producedAtEpochMs,"expires_at_epoch_ms" to response.expiresAtEpochMs,"model_latency_ms" to event.latencyMs),now)
        val verdict=DeliveryPolicy.evaluate(event.pending.request,response,event.pending.episode,session.packetEpisodes[event.pending.episode.episodeHandle],
            session.id,session.epoch,session.id,session.epoch,now,session.receipts,session.serviceVisible || session.delivery!=null)
        _state.update {it.copy(rawDecision=response.decision.name,delivery=verdict.status.name,error=null)}
        if(verdict.status==DeliveryPolicy.Status.DELIVER && session.setup.shadowInference) {
            delivery(session,response.decisionId,"NOT_ATTEMPTED","SHADOW_INFERENCE",now)
            session.packetEpisodes.remove(event.pending.episode.episodeHandle)
        } else if(verdict.status==DeliveryPolicy.Status.DELIVER) {
            val decision=InferenceContract.toDecision(response)
            session.delivery=PendingDelivery(event.pending,decision)
            delivery(session,decision.decisionId,"NOT_ATTEMPTED","PENDING_VISIBLE_ACK",now)
            val payload=StudyWire.payload("decision_id" to decision.decisionId,"service_mode" to decision.serviceMode!!.name,
                "title" to decision.title,"body" to decision.body,"visible_for_ms" to 10_000)
            val command=transport.controlMessage("SHOW_SERVICE_CARD",payload,decision.expiresAtEpochMs-now).copy(idempotency_key="display-${decision.decisionId}")
            if(transport.command(command).isFailure) {delivery(session,decision.decisionId,"TRANSPORT_FAILED","SERVICE_SEND_FAILED",now)
                session.packetEpisodes.remove(event.pending.episode.episodeHandle);session.delivery=null}
        } else {
            val status=when(verdict.status) {
                DeliveryPolicy.Status.NO_SERVICE->"NO_SERVICE_SILENCE"
                DeliveryPolicy.Status.SUPPRESS_DUPLICATE->"SUPPRESSED_BY_RECEIPT"
                DeliveryPolicy.Status.SUPPRESS_INVALID_EVIDENCE->"INVALID_EVIDENCE"
                DeliveryPolicy.Status.SUPPRESS_STALE->"STALE_DECISION"
                else->"NOT_ATTEMPTED"
            }
            delivery(session,response.decisionId,status,verdict.reason,now)
            session.packetEpisodes.remove(event.pending.episode.episodeHandle)
        }
        kickModel(session);checkpoint(session);updatePhase(session)
    }
    private fun snapshot(session: Session,quality: ImageQuality?): SchedulerSnapshot {
        val now=SystemClock.elapsedRealtime();val epoch=System.currentTimeMillis();val sensor=session.sensor
        val motion=sensor?.let {MotionFeatures(it.windowStartElapsedMs,it.windowEndElapsedMs,it.sampleCount,it.windowEndElapsedMs-it.windowStartElapsedMs,
            it.angularVelocityRmsDegPerSec,it.angularVelocityP95DegPerSec,it.linearAccelerationRmsMps2,it.linearAccelerationP95Mps2,it.stableRatio,it.isStable,it.isMoving,
            it.sampleCount>=8 && now-it.windowEndElapsedMs in 0..5000)}
        val episodes=session.episodes.schedulerCandidates()
        return SchedulerSnapshot(epoch,now,session.lastCapture,session.lastEvaluation,session.lastMotion,session.materialAt,session.materialPending.isNotEmpty(),motion,
            InteractionSnapshot(speechRatio=sensor?.speechRatio ?: 0.0,voiceSessionActive=sensor?.voiceSessionActive ?: false,
                millisSinceLastUserInteraction=minOf(sensor?.millisSinceLastInteraction ?: Long.MAX_VALUE,now-session.phoneInteractionAt),
                contextCardVisible=sensor?.contextCardVisible ?: false,serviceCardVisible=session.serviceVisible || session.delivery!=null || sensor?.serviceCardVisible==true),
            quality,episodes,session.model!=null,transport.ready.value,powerBlocked(),
            lastBaselineCaptureElapsedMs=session.lastBaseline,
            deadlineCapturePending=episodes.any {it.episodeHandle !in session.deadlineSeen && it.deadlineEpochMs?.let {deadline->deadline-epoch in 0..1800000}==true},
            routineCapturePending=episodes.any {it.routineWindowActive && it.episodeHandle !in session.routineSeen},
            postServiceAuditPending=session.auditDue.values.any {it<=now},materialChangedEpisodeHandles=session.materialPending.toSet())
    }
    private fun powerBlocked(): Boolean {
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100) ?: 100
        val charging=battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
        val hot=Build.VERSION.SDK_INT>=29 && context.getSystemService(PowerManager::class.java).currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        return hot || (level>=0 && scale>0 && level*100/scale<15 && !charging)
    }
    private suspend fun pause() {
        val session=active ?: return
        val wasResuming=session.resumeOnReconnect
        session.resumeOnReconnect=false
        if(session.paused) {
            if(wasResuming) journal.append(session.id,"runtime_events","SESSION_RECONNECT_CANCELLED",
                StudyWire.payload("reason" to "OPERATOR_PAUSE"),System.currentTimeMillis())
            return
        }
        session.paused=true
        if(session.setup.replayEnabled) session.playback.pause(SystemClock.elapsedRealtime())
        cancelPendingWork(session,"SESSION_PAUSED")
        transport.command(transport.controlMessage("SESSION_PAUSE"))
        journal.append(session.id,"runtime_events","SESSION_PAUSED",StudyWire.payload("world_clock_continues" to true,
            "scenario_playback_paused" to session.setup.replayEnabled,"position_ms" to session.position(SystemClock.elapsedRealtime())),System.currentTimeMillis())
        updatePhase(session);checkpoint(session)
    }
    private suspend fun resume(reason: String="OPERATOR") {
        val session=active ?: return
        if(!session.paused) return
        session.resumeOnReconnect=false
        check(transport.ready.value) {"CONNECT_GLASSES_FIRST"}
        val peer=transport.refreshPeer().getOrThrow()
        if(session.peerBuild!=null && peer!=null && session.peerBuild!=peer) {
            journal.append(session.id,"runtime_events","PEER_BUILD_CHANGED",StudyWire.payload(
                "frozen" to StudyWire.json.encodeToJsonElement(session.peerBuild),"connected" to StudyWire.json.encodeToJsonElement(peer)),System.currentTimeMillis())
            error("PEER_BUILD_CHANGED")
        }
        val epoch=nextEpoch()
        journal.advanceEpoch(session.id,epoch,System.currentTimeMillis())
        session.epoch=epoch
        try {transport.startSession(session.id,epoch).getOrThrow()}
        catch(error: Exception) {
            transport.command(transport.controlMessage("SESSION_PAUSE"))
            checkpoint(session)
            throw error
        }
        val wasHistory=session.historyReady
        session.historyReady=false;session.paused=false;session.resumeOnReconnect=false;session.sensor=null
        if(session.setup.replayEnabled) session.playback.resume(SystemClock.elapsedRealtime())
        _state.update {it.copy(error=null)}
        journal.append(session.id,"runtime_events","SESSION_RESUMED",StudyWire.payload("reason" to reason,"session_epoch" to epoch),System.currentTimeMillis())
        if(wasHistory) journal.append(session.id,"runtime_events","HISTORY_CONTINUATION_STARTED",StudyWire.payload(
            "history_observations" to (session.seed?.observations?.size ?: session.collectionSeed?.images?.size ?: 0),"receipts_imported" to 0),System.currentTimeMillis())
        if(wasHistory) transport.command(transport.controlMessage("SHOW_SESSION_STATUS",StudyWire.payload(
            "event_id" to "live-${session.epoch}","title" to "实时服务已开始","body" to if(session.automaticCapture) "每分钟自动观察，也可在手机手动拍照。" else "自动观察已关闭，可在手机手动拍照。","visible_for_ms" to 3000),10_000))
        session.pendingContextCard?.let {payload->
            if(transport.command(transport.controlMessage("SHOW_CONTEXT_CARD",payload,30000)).isSuccess) session.pendingContextCard=null
        }
        session.lastDemoStatus=null;sendDemoStatus(session)
        configureGlasses(session)
        if(session.stopping) transport.command(transport.controlMessage("FLUSH_CAPTURE_BUFFER"))
        updatePhase(session);checkpoint(session)
    }
    private suspend fun seek(event: Event.Seek) {
        val session=active ?: error("NO_ACTIVE_SESSION")
        check(session.setup.replayEnabled) {"REPLAY_DISABLED"}
        require(event.positionMs in 0..session.timeline.durationMs) {"REPLAY_POSITION_INVALID"}
        check(transport.ready.value) {"CONNECT_GLASSES_FIRST"}
        val setup=session.setup.copy(initialPositionMs=event.positionMs,replayParentSessionId=session.id)
        journal.append(session.id,"runtime_events","REPLAY_SEEK",StudyWire.payload(
            "from_position_ms" to session.position(SystemClock.elapsedRealtime()),"to_position_ms" to event.positionMs,
            "policy" to "NEW_SESSION_SEGMENT"),System.currentTimeMillis())
        stop()
        begin(Event.Start(setup,event.reply))
    }
    private suspend fun addContext(input: SyntheticContextInput) {
        input.validate()
        val session=active ?: error("NO_ACTIVE_SESSION")
        check(session.setup.mode==SessionMode.SERVICE) {"CONTEXT_REQUIRES_SERVICE_MODE"}
        val now=System.currentTimeMillis()
        val current=if(input.updateCurrentTask) session.episodes.schedulerCandidates().firstOrNull() else null
        val handle=current?.episodeHandle ?: "episode-${UUID.randomUUID()}"
        val candidate=current?.copy(materialRevision=current.materialRevision+1) ?: CandidateEpisode(handle,"object-${UUID.randomUUID()}",
            "intent-${UUID.randomUUID()}",ServiceMode.PREPARATION,true,materialRevision=1)
        // An edited task must not finish an inference using its old public context.
        generation++;cancelPendingWork(session,"CONTEXT_UPDATED")
        transport.command(transport.controlMessage("CLEAR_SERVICE_CARD"))
        session.episodes.upsert(candidate)
        val observation=ObservationRecord("manual-${UUID.randomUUID()}",ObservationKind.SYNTHETIC_CONTEXT,now,now,input.publicText(),
            episodeHandle=handle,materialRevision=candidate.materialRevision)
        session.history.append(observation)
        appendObservation(session,observation,StudyWire.payload("source" to "OPERATOR_SYNTHETIC","synthetic_type" to input.type.name))
        if(session.setup.glassesCapture) transport.command(transport.controlMessage("EDGE_DIGITAL_EVENT",StudyWire.payload("event_id" to observation.observationId))).getOrThrow()
        else recordPackets(session,session.packets.onDigital(observation.observationId,observation.availableAtEpochMs))
        journal.append(session.id,"context_events","OPERATOR_CONTEXT_ADDED",StudyWire.payload("observation" to observationJson(observation),
            "synthetic_type" to input.type.name,"title" to input.title,"show_on_glasses" to input.showOnGlasses),now)
        session.materialAt=now;session.materialPending+=handle
        _state.update {it.copy(currentContext=input.publicText(),rawDecision=null,delivery=null,error=null)}
        session.pendingContextCard=null
        if(input.showOnGlasses) {
          val payload=StudyWire.payload(
            "event_id" to observation.observationId,"title" to "${input.type.label} · ${input.title}".take(40),
            "body" to input.body.take(140),"visible_for_ms" to 8000)
          if(session.paused) session.pendingContextCard=payload
          else transport.command(transport.controlMessage("SHOW_CONTEXT_CARD",payload,30_000)).getOrThrow()
        }
        refreshCandidates(session);updatePhase(session);checkpoint(session);sendDemoStatus(session)
    }
    private suspend fun seekHistory(event: Event.SeekHistory) {
        val session=active ?: error("NO_ACTIVE_SESSION")
        val seed=session.seed ?: error("HISTORY_REPLAY_REQUIRED")
        require(event.index in 0 until seed.preview.total) {"HISTORY_POSITION_INVALID"}
        check(transport.ready.value) {"CONNECT_GLASSES_FIRST"}
        val setup=session.setup.copy(historyAnchorIndex=event.index,replayParentSessionId=session.id)
        journal.append(session.id,"runtime_events","HISTORY_REPLAY_SEEK",StudyWire.payload("from_index" to seed.preview.index,
            "to_index" to event.index,"policy" to "NEW_SESSION_SEGMENT"),System.currentTimeMillis())
        stop();begin(Event.Start(setup,event.reply))
    }
    private suspend fun sendDemoStatus(session: Session) {
        val collection=session.setup.mode==SessionMode.COLLECTION
        val enabled=session.demoMode || collection
        if(!enabled && session.lastDemoStatus=="DISABLED") return
        val state=_state.value
        val phase=when {session.historyReady->"历史已载入 · 等待继续";session.paused->"会话已暂停";session.capture!=null->"正在观察";
            session.setup.mode==SessionMode.COLLECTION->"按固定间隔采集中";session.model!=null->"正在理解当前情境";
            state.error=="IMAGE_TOO_DARK"->"画面偏暗 · 请调整镜头";state.error=="IMAGE_TOO_BLURRY"->"画面不清晰 · 请保持稳定";state.error=="IMAGE_TOO_BRIGHT"->"画面过亮 · 请调整镜头";else->"实时服务中"}
        val payload=if(!enabled) StudyWire.payload("enabled" to false) else StudyWire.payload("enabled" to true,"phase" to phase,"context" to (state.currentContext ?: "等待新的情境").take(140),
            "captures" to state.liveCaptures,"receipts" to state.receipts,"automatic_capture" to session.automaticCapture,
            "collection" to collection,"interval_seconds" to session.setup.captureIntervalMs/1000)
        if(payload.toString()==session.lastDemoStatus) return
        if(transport.command(transport.controlMessage("UPDATE_DEMO_STATUS",payload)).isSuccess) session.lastDemoStatus=if(enabled) payload.toString() else "DISABLED"
    }
    private suspend fun commitBuffered(session: Session,frame: ImageFrame) {
        val result=transport.command(transport.controlMessage("IMAGE_COMMITTED",StudyWire.payload(
            "capture_id" to frame.captureId,"sha256" to frame.sha256,"byte_length" to frame.jpegBytes.size)))
        if(result.isFailure) journal.append(session.id,"runtime_events","BUFFER_COMMIT_RETRY_PENDING",StudyWire.payload("capture_id" to frame.captureId),System.currentTimeMillis())
    }
    private suspend fun configureGlasses(session: Session) {
        if(!session.setup.glassesCapture || !transport.ready.value) return
        transport.command(transport.controlMessage("CONFIGURE_CAPTURE",StudyWire.payload("mode" to session.setup.mode.name,
            "interval_ms" to if(session.setup.mode==SessionMode.COLLECTION) session.setup.captureIntervalMs else 60_000,
            "batch_size" to session.setup.syncBatchSize,"automatic" to (session.automaticCapture && !session.stopping && !session.paused)))).getOrThrow()
    }
    private suspend fun requestStop() {
        val session=active
        if(session!=null && session.setup.glassesCapture && session.setup.mode==SessionMode.COLLECTION) {
            session.stopping=true;session.automaticCapture=false
            if(session.paused && transport.ready.value) resume()
            configureGlasses(session)
            transport.command(transport.controlMessage("FLUSH_CAPTURE_BUFFER"))
            updatePhase(session);return
        }
        stop()
    }
    private suspend fun stop() {
        preparation?.cancelAndJoin();preparation=null
        startingReply?.complete(Result.failure(IllegalStateException("SESSION_START_CANCELLED")));startingReply=null
        generation++
        ticker?.cancel();ticker=null
        val session=active
        if(session!=null) {
            session.paused=true
            updatePhase(session)
            cancelPendingWork(session,"SESSION_STOPPED")
            transport.command(transport.controlMessage("SESSION_STOP"))
            journal.stop(session.id,System.currentTimeMillis())
        }
        active=null;_state.value=RuntimeViewState()
    }
    private fun endPendingDelivery(session: Session,status: String,reason: String,now: Long) {
        val pending=session.delivery ?: return
        delivery(session,pending.decision.decisionId,status,reason,now)
        session.packetEpisodes.remove(pending.model.episode.episodeHandle)
        session.delivery=null
        updatePhase(session)
    }
    /** Quiesce the child writer first, then leave a terminal explanation for every abandoned operation. */
    private suspend fun cancelPendingWork(session: Session,reason: String) {
        session.modelJob?.cancelAndJoin();session.modelJob=null
        val now=System.currentTimeMillis()
        session.capture?.let {journal.append(session.id,"captures","CAPTURE_CANCELLED",StudyWire.payload(
            "capture_id" to it.captureId,"status" to "CANCELLED","code" to reason),now)}
        session.model?.let {journal.append(session.id,"runtime_events","INFERENCE_CANCELLED",StudyWire.payload(
            "request_id" to it.request.requestId,"packet_id" to it.packet.packetId,"code" to reason),now)}
        session.queue.forEach {journal.append(session.id,"runtime_events","PACKET_EVALUATION_CANCELLED",StudyWire.payload(
            "packet_id" to it.packet.packetId,"code" to reason),now)}
        endPendingDelivery(session,"DISPLAY_FAILED",reason,now)
        session.model=null;session.queue.clear();session.packetEpisodes.clear();session.capture=null;session.captureRetry=0
        session.serviceVisible=false;session.visibleDecisionId=null
    }
    private fun refreshCandidates(session: Session) {_state.update {it.copy(candidates=session.episodes.schedulerCandidates().map {candidate->
        RuntimeCandidate(candidate.episodeHandle,candidate.materialRevision,candidate.serviceMode.name)})}}
    private fun updatePhase(session: Session) {refreshCandidates(session);_state.update {it.copy(phase=when {
        session.stopping->"SYNCING";session.historyReady->"HISTORY_READY";session.paused->"PAUSED";session.model!=null->"EVALUATING";session.delivery!=null->"DELIVERING";session.capture!=null->"CAPTURING";else->"RUNNING"
    })}}
    private fun checkpoint(session: Session) {journal.checkpoint(session.id,session.epoch,_state.value.phase,StudyWire.payload(
        "scenario_asset" to session.setup.scenarioAsset,"revealed_event_ids" to JsonArray(session.revealed.map(::JsonPrimitive)),
        "replay_position_ms" to session.position(SystemClock.elapsedRealtime()),
        "phone_build" to phoneBuild,"glasses_build" to session.peerBuild?.let {StudyWire.json.encodeToJsonElement(it)},
        "glasses_app_version" to session.peerBuild?.appVersion,
        "session_mode" to session.setup.mode.name,"capture_interval_ms" to session.setup.captureIntervalMs,
        "contract" to session.contract?.let {StudyWire.json.encodeToJsonElement(it)}),System.currentTimeMillis())}
    private fun appendObservation(session: Session,record: ObservationRecord,extra: JsonObject=JsonObject(emptyMap())) {
        journal.append(session.id,"observations","OBSERVATION_PERSISTED",JsonObject(observationJson(record)+extra),record.availableAtEpochMs)
        session.observationCount++;_state.update {it.copy(observations=session.observationCount)}
    }
    private fun recordPackets(session: Session,packets: List<ObservationPacket>) {
        if(session.setup.mode==SessionMode.COLLECTION) return
        packets.forEach {packet->
            journal.append(session.id,"packets","OBSERVATION_PACKET",StudyWire.payload(
            "schema_version" to "1.0","packet_id" to packet.packetId,"cutoff_at_epoch_ms" to packet.cutoffEpochMs,
            "trigger_types" to JsonArray(packet.triggerTypes.sorted().map(::JsonPrimitive)),
            "new_event_ids" to JsonArray(packet.newEventIds.map(::JsonPrimitive)),
            "image_ids" to JsonArray(packet.imageIds.map(::JsonPrimitive)),
            "reused_image_ids" to JsonArray(packet.reusedImageIds.map(::JsonPrimitive)),
            "freshness" to StudyWire.payload("latest_image_age_ms" to packet.latestImageAgeMs,"observation_gap" to packet.observationGap)),packet.cutoffEpochMs)
            session.packetCount++;_state.update {it.copy(packets=session.packetCount)}
            if(session.setup.mode==SessionMode.COLLECTION) return@forEach
            val frame=session.latestFrame ?: return@forEach
            val quality=session.latestQuality ?: return@forEach
            if(packet.imageIds.isEmpty() || packet.observationGap) return@forEach
            val episode=CandidateEpisode("stream-${packet.packetId}","stream","packet-stream",ServiceMode.AWARENESS,materialRevision=0)
            session.packetEpisodes[episode.episodeHandle]=episode
            val evaluation=PacketEvaluation(packet.packetId,packet.cutoffEpochMs,packet.newEventIds.lastOrNull() ?: "heartbeat",
                packet.imageIds.last(),packet.triggerTypes)
            val retrieved=(session.history.historyRetrievalSnapshot(packet.cutoffEpochMs,packet.newEventIds) +
                session.history.causalSnapshot(packet.cutoffEpochMs).filter {it.assetId==packet.imageIds.last()})
                .distinctBy {it.observationId}.sortedBy {it.availableAtEpochMs}
            journal.append(session.id,"runtime_events","HISTORY_RETRIEVAL",StudyWire.payload("packet_id" to packet.packetId,
                "new_event_ids" to JsonArray(packet.newEventIds.map(::JsonPrimitive)),
                "injected_observation_ids" to JsonArray(retrieved.map {JsonPrimitive(it.observationId)}),"injected_count" to retrieved.size),packet.cutoffEpochMs)
            val contract=requireNotNull(session.contract)
            val request=InferenceContract.buildPacketRequest("request-${UUID.randomUUID()}",evaluation,quality,retrieved,session.receipts.allReceipts(),
                contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash,contract.protocolVersion,
                packet.newEventIds,packet.latestImageAgeMs,packet.observationGap)
            val historicalJpegs=retrieved.mapNotNull {record->record.assetId?.let {asset->session.historicalImages[asset]?.let {asset to it}}}
                .takeLast(4).toMap(linkedMapOf())
            session.queue.addLast(PendingModel(evaluation,episode,request,frame,historicalJpegs))
            _state.update {it.copy(observationStatus="PACKET_QUEUED")}
        }
    }
    private fun requestJournalJson(request: InferenceRequest): JsonObject {
        val legacy=StudyWire.json.encodeToJsonElement(request.toWire()).jsonObject
        return JsonObject(legacy.filterKeys {it !in setOf("anchor_id","anchor_time_epoch_ms","candidate")} + StudyWire.payload(
            "packet_id" to request.anchorId,"snapshot_at_epoch_ms" to request.anchorTimeEpochMs,
            "trigger_types" to JsonArray(request.packetTriggerTypes.sorted().map(::JsonPrimitive)),
            "new_event_ids" to JsonArray(request.packetNewEventIds.map(::JsonPrimitive)),
            "freshness" to StudyWire.payload("latest_image_age_ms" to request.packetLatestImageAgeMs,"observation_gap" to request.packetObservationGap)))
    }
    private fun observationJson(record: ObservationRecord)=StudyWire.json.encodeToJsonElement(WireObservation(record.observationId,record.kind.name,
        record.occurredAtEpochMs,record.availableAtEpochMs,record.content,record.assetId,record.episodeHandle,record.materialRevision)).jsonObject
    private fun delivery(session: Session,id: String,status: String,reason: String,now: Long,receipt: String?=null,updateUi: Boolean=true) {
        journal.append(session.id,"deliveries","DELIVERY_DECISION",StudyWire.payload("delivery_id" to "delivery-${UUID.randomUUID()}","decision_id" to id,
            "status" to status,"reason" to reason,"event_at_epoch_ms" to now,"receipt_id" to receipt),now)
        if(updateUi) _state.update {it.copy(delivery=status)}
    }
    private fun receiptJson(receipt: ActionReceipt,frames: Int,anchor: String)=StudyWire.payload("receipt_id" to receipt.receiptId,"decision_id" to receipt.decisionId,
        "episode_handle" to receipt.episodeHandle,"object_handle" to receipt.objectHandle,"intent_fingerprint" to receipt.intentFingerprint,
        "service_mode" to receipt.serviceMode.name,"served_material_revision" to receipt.servedMaterialRevision,"visible_at_epoch_ms" to receipt.visibleAtEpochMs,
        "evidence_observation_ids" to JsonArray(receipt.evidenceObservationIds.map(::JsonPrimitive)),"visible_ack_frame_count" to frames,"packet_id" to anchor)
}
