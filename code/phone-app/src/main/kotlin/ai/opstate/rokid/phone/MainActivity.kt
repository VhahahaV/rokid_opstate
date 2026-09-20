package ai.opstate.rokid.phone

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ai.opstate.rokid.phone.data.readImportLedger
import ai.opstate.rokid.phone.review.*
import ai.opstate.rokid.phone.replay.DatasetReplayService
import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import ai.opstate.rokid.phone.app.BuildConfig
import ai.opstate.rokid.phone.ui.*
import ai.opstate.rokid.phone.runtime.StudyRuntimeService
import ai.opstate.rokid.phone.runtime.requiresPhotoReload
import ai.opstate.rokid.phone.runtime.SessionSetup
import ai.opstate.rokid.phone.runtime.SessionMode
import ai.opstate.rokid.phone.runtime.ChosenPhoto
import ai.opstate.rokid.phone.runtime.SyntheticContextInput
import ai.opstate.rokid.phone.runtime.SyntheticContextType
import ai.opstate.rokid.phone.scenario.StudyScenarioDto
import ai.opstate.rokid.phone.scenario.timeline
import ai.opstate.rokid.phone.runtime.RemoteHarnessClient
import ai.opstate.rokid.phone.runtime.DirectModelConfig
import ai.opstate.rokid.phone.runtime.DirectModelClient
import ai.opstate.rokid.phone.runtime.DirectModelProbe
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val scope=MainScope()
    private val graph get()=application as PhoneApplication
    private val viewState=MutableStateFlow(OperatorViewState())
    private var pendingExport: File?=null

    private val createExport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val archive=pendingExport;pendingExport=null
        if(uri==null) archive?.delete()
        else if(archive!=null) operate {
            try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"w")!!.use {output->archive.inputStream().use {it.copyTo(output)}} };message("完整会话记录已保存") }
            finally {archive.delete()}
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport=savedInstanceState?.getString("pending_export")?.let { path->File(path).takeIf {it.parentFile?.canonicalFile==File(cacheDir,"exports").canonicalFile && it.isFile} }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor=android.graphics.Color.rgb(243,245,240)
        window.navigationBarColor=android.graphics.Color.rgb(252,253,249)
        val endpoint=graph.settings.getString("harness_url",getPreferences(MODE_PRIVATE).getString("harness_url","http://127.0.0.1:8765"))!!
        viewState.update { it.copy(endpoint=endpoint,expectedModelId=graph.settings.getString("expected_model_id","").orEmpty(),directModel=graph.modelConfig(),syncBatchSetting=graph.settings.getInt("sync_batch_size",60)) }
        // The showcase shortlist outlives the process on purpose: picking the moments worth showing is
        // something an operator comes back to over days, not something to redo after every restart.
        val (showcaseRefs,showcaseGroup)=ShowcaseStore.read(graph.settings)
        viewState.update {it.copy(showcase=it.showcase.copy(selected=showcaseRefs,group=showcaseGroup))}
        // Restore the history selection made before this process started. Only the reference and the
        // counts are kept here; the seed itself is read from the journal when the session starts.
        graph.settings.getString("history_source_session_id",null)?.let {sourceId->
            viewState.update {it.copy(importedHistory=ImportedHistory(sourceSessionId=sourceId,
                imageLimit=graph.settings.getInt("history_image_limit",0),
                title=graph.settings.getString("history_title","").orEmpty(),
                images=graph.settings.getInt("history_images",0),contexts=graph.settings.getInt("history_contexts",0)))}
        }
        scope.launch { graph.link.state.collect { link -> viewState.update { it.copy(link=link,sdkIdentityCached=graph.link.hasDeviceIdentity()) } } }
        scope.launch { graph.transport.ready.collect { ready -> viewState.update { it.copy(peerReady=ready) } } }
        scope.launch {
            // Losing the link by walking out of range must not need an operator to retry by hand. Rebuilding
            // the saved device here also arms the transport's unbounded reconnect loop, so a launch while
            // still out of range keeps trying instead of going quiet.
            val saved=graph.link.savedDevice() ?: return@launch
            val authorized=withContext(Dispatchers.IO) {runCatching {
                graph.credentials.clientSecret()!=null && graph.credentials.licenseBytes()!=null
            }.getOrDefault(false)}
            if(!authorized) return@launch
            runCatching { graph.transport.connect(ai.opstate.rokid.phone.transport.RokidDevice(saved.address,saved.name)) }
        }
        scope.launch { graph.database.dao().observeSessions().collect { sessions ->
            val collections=withContext(Dispatchers.IO) {LocalCollectionRepository(graph.journal).list()}
            viewState.update { it.copy(sessions=sessions,collections=collections) }
        } }
        scope.launch {
            var previous=graph.studyRuntime.state.value
            graph.studyRuntime.state.collect {runtime->
            viewState.update {it.copy(runtime=runtime)}
            if(runtime.requiresPhotoReload(previous,viewState.value.livePhotos.isEmpty())) {
                val photos=withContext(Dispatchers.IO) {runtime.sessionId?.let {SessionReviewRepository(graph.journal).photos(it)}.orEmpty()}
                viewState.update {it.copy(livePhotos=photos)}
            }
            if(runtime.phase=="ERROR" && runtime.error!=null) showError(IllegalStateException(runtime.error))
            if(previous.sessionId!=null && runtime.sessionId==null && previous.phase!="ERROR") message(
                if(previous.mode==SessionMode.COLLECTION) "采集已保存：${previous.liveCaptures-previous.deletedCaptures} 张，其中 ${previous.validCaptures} 张可用于服务历史。"
                else "服务会话已结束并保存。")
            previous=runtime
        }}
        scope.launch {graph.datasetReplay.state.collect {replay->viewState.update {it.copy(replay=replay)}}}
        scope.launch { refreshCredentials()
            val scenario=withContext(Dispatchers.IO) {StudyWire.json.decodeFromString(StudyScenarioDto.serializer(),assets.open("scenarios/meeting-move.json").bufferedReader().use {it.readText()})}
            viewState.update {it.copy(scenarioTimeline=scenario.timeline())}
        }
        setContent {
            val state by viewState.collectAsState()
            val screenState=rememberSaveableStateHolder()
            val selected=state.selectedSession
            val selectedTrial=state.selectedTrial
            // Sources for a staged run: what is already on the phone, offered when the operator asks.
            var pickPhoto by remember { mutableStateOf(false) }
            var pickContext by remember { mutableStateOf(false) }
            var photoChoices by remember { mutableStateOf<List<CapturedPhoto>>(emptyList()) }
            var contextChoices by remember { mutableStateOf<List<ContextEntry>>(emptyList()) }
            StudyTheme {
                if(selected!=null) screenState.SaveableStateProvider("review-${selected.session.sessionId}-${selectedTrial?.summary?.anchorId ?: "list"}") {
                    SessionReviewScreen(selected,selectedTrial,state.busy,state.message,
                    back={viewState.update {if(it.selectedTrial!=null) it.copy(selectedTrial=null) else it.copy(selectedSession=null)}},
                    openTrial={row->
                        val id=selected!!.session.sessionId
                        operate {
                            val loaded=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).trial(id,row)}
                            viewState.update {it.copy(selectedTrial=loaded,busy=false)}
                        }
                    },
                    export={exportSession(selected!!.session.sessionId)},delete={deleteSession(selected!!.session.sessionId)},
                    addContext={type,title,body,at->addRecordContext(selected!!.session.sessionId,type,title,body,at)},
                    importContext={importRecordContext(selected!!.session.sessionId)},
                    replayCard={anchor->replayServiceCard(selected!!.session.sessionId,anchor)},
                    refresh={openSession(selected.session.sessionId)},dismissMessage={viewState.update {it.copy(message=null)}},logs={openLogs(selected.session.sessionId)},
                    deletePhoto={observationId->deletePhoto(selected.session.sessionId,observationId)})
                } else screenState.SaveableStateProvider("operator") { OperatorScreen(state,OperatorActions(
                scan={ scan() },reconnect={ operate { val device=graph.link.savedDevice() ?: error("NO_SAVED_DEVICE")
                    graph.transport.connect(ai.opstate.rokid.phone.transport.RokidDevice(device.address,device.name)).getOrThrow() } },
                disconnect={operate {graph.transport.disconnect()}},p2p={graph.link.startP2p().onFailure(::showError)},
                permissions=::grantPermissions,bluetooth={runCatching {startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))}.onFailure {if(it is SecurityException) grantPermissions() else showError(it)}},probe={probe()},
                saveSecret={value->operate {withContext(Dispatchers.IO) {graph.credentials.put("client-secret",value.trim().toByteArray())};refreshCredentials();message("开发者凭证已加密保存")}},
                openDeveloperConsole={runCatching {startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://ar.rokid.com")))}.onFailure(::showError)},
                importLicense={startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},REQUEST_LICENSE)},
                saveModel=::saveModel,clearModelKey={operate {checkConfigurationEditable();withContext(Dispatchers.IO) {graph.credentials.remove("model-api-key")};refreshCredentials();viewState.update {it.copy(modelContract=null,modelApiStatus="尚未验证")};message("模型 API Key 已移除")}},openSession={openSession(it.sessionId)},
                dismissMessage={viewState.update {it.copy(message=null)}},
                start={options->operate {check(!graph.datasetReplay.state.value.running)
                    graph.settings.edit().putInt("sync_batch_size",options.syncBatchSize).apply()
                    viewState.update {it.copy(syncBatchSetting=options.syncBatchSize)}
                    startForegroundService(Intent(this,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                    .putExtra("setup",StudyWire.json.encodeToString(SessionSetup(options.alias,viewState.value.directModel.resolvedEndpoint(),
                        profile=options.profile,expectedModelId=viewState.value.directModel.modelId.takeIf {options.mode==SessionMode.SERVICE},
                        directModel=viewState.value.directModel.takeIf {options.mode==SessionMode.SERVICE},
                        scenarioAsset="",replayEnabled=false,startLiveAfterHistory=true,glassesCapture=true,syncBatchSize=options.syncBatchSize,
                        initialPositionMs=options.scenarioPositionMs,glassesDemoMode=options.demoMode,
                        shadowInference=options.shadowInference,mode=options.mode,captureIntervalMs=options.captureIntervalMs,forceService=options.forceService,
                        frontEndEnabled=options.frontEndEnabled,
                        collectionHistorySessionId=options.collectionHistorySessionId,collectionHistoryImageLimit=options.collectionHistoryImageLimit))))}},
                pause={operate {graph.studyRuntime.pauseSession()}},
                syncPhotos={graph.studyRuntime.syncPhotos()},
                renameSession={id,name->renameSession(id,name)},deleteSessions={ids->deleteSessions(ids)},mergeSessions={ids,name->mergeSessions(ids,name)},
                deleteLivePhoto={observationId->viewState.value.runtime.sessionId?.let {sessionId->deletePhoto(sessionId,observationId)}},
                refreshShowcase={refreshShowcase()},loadShowcasePhotos={loadShowcasePhotos()},
                pickShowcaseCard={card->updateShowcase {it.copy(draft=ShowcaseDraft(card=card))}},
                pickShowcaseScene={photo->updateShowcase {it.copy(draft=it.draft.copy(scene=photo))}},
                useShowcaseCardScene={updateShowcase {it.copy(draft=it.draft.copy(scene=null))}},
                addShowcaseDraft={addShowcaseDraft()},
                removeShowcaseAt={position->removeShowcase(position)},
                renameShowcaseGroup={renameShowcaseGroup(it)},exportShowcase={exportShowcase()},
                replayShowcaseCard={replayServiceCard(it.sessionId,it.anchorId)},
                resume={operate {graph.studyRuntime.resumeSession()}},
                seek={position->operate {graph.studyRuntime.seekTo(position).getOrThrow()}},
                automaticCapture=graph.studyRuntime::setAutomaticCapture,demoMode=graph.studyRuntime::setGlassesDemoMode,
                configureCapture={captureInterval,frontEndEnabled,batch->operate {
                    graph.studyRuntime.configureCapture(captureInterval,frontEndEnabled,batch).getOrThrow()
                    message("已更新拍照、上传与前端触发设置")
                }},
                addContext={input,done->operate {graph.studyRuntime.addSyntheticContext(input).getOrThrow();message("合成情境已加入，后续评估将使用这条信息");done()}},
                forceService={enabled->operate {graph.studyRuntime.setForceService(enabled).getOrThrow();
                    message(if(enabled) "已开启强制服务：每次调用都会给出服务" else "已关闭强制服务")}},
                pickPhoto={ operate {
                    val sessionId=state.runtime.sessionId ?: return@operate
                    photoChoices=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).photos(sessionId)}
                    if(photoChoices.isEmpty()) message("这次会话还没有照片") else pickPhoto=true
                } },
                pickContext={ operate {
                    val sessionId=state.runtime.sessionId ?: return@operate
                    contextChoices=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).contexts(sessionId)}
                    if(contextChoices.isEmpty()) message("这次会话还没有情境") else pickContext=true
                } },
                stop={startService(Intent(this,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_STOP))},
                capture={graph.studyRuntime.captureNow()},
                startReplay={limit->startForegroundService(Intent(this,DatasetReplayService::class.java).putExtra("limit",limit))},
                stopReplay={startService(Intent(this,DatasetReplayService::class.java).setAction(DatasetReplayService.STOP))},
                openSessionId=::openSession,
                openLogs=::openLogs,
                clearHistory={viewState.update {it.copy(importedHistory=null)};graph.settings.edit().remove("history_source_session_id").apply()},
                loadHistory={sessionId,limit,done->operate {
                    check(graph.studyRuntime.state.value.sessionId==null) {"SESSION_ALREADY_ACTIVE"}
                    val imported=withContext(Dispatchers.IO) {
                        val seed=LocalCollectionRepository(graph.journal).load(sessionId,limit)
                        ImportedHistory(sourceSessionId=sessionId,imageLimit=limit,title=seed.summary.participantAlias,
                            images=seed.images.size,contexts=seed.contexts.size,recent=seed.contexts.takeLast(3).map {it.content})
                    }
                    viewState.update {it.copy(importedHistory=imported)}
                    // The selection has to outlive this process. It was in memory only, so any restart
                    // silently turned a history-continuation session into a live-only one, and the model
                    // got historical photographs with none of the notes that make a service worth showing.
                    // Only the reference is stored; the images are loaded from the journal at session start.
                    graph.settings.edit().putString("history_source_session_id",sessionId)
                        .putInt("history_image_limit",limit).putString("history_title",imported.title)
                        .putInt("history_images",imported.images).putInt("history_contexts",imported.contexts).apply()
                    message("历史已加载，请选择采集或在线服务")
                    done()
                }},
            )) }
                state.logSessionId?.let {id->RuntimeLogSheet(state.runtimeLogs,state.busy,refresh={openLogs(id)},close={viewState.update {it.copy(logSessionId=null)}})}
                if(pickPhoto) ChosenPhotoPicker(photoChoices,onDismiss={pickPhoto=false}) { photo->
                    pickPhoto=false
                    operate {
                        val meta=photo.metadata
                        graph.studyRuntime.evaluateChosenPhoto(ChosenPhoto(photo.file.path,
                            meta.string("width")?.toIntOrNull() ?: 0,meta.string("height")?.toIntOrNull() ?: 0,
                            meta.string("rotation_degrees")?.toIntOrNull() ?: 0,meta.string("sha256").orEmpty())).getOrThrow()
                        message("已用选中的照片跑一次完整链路")
                    }
                }
                if(pickContext) ChosenContextPicker(contextChoices,onDismiss={pickContext=false}) { entry->
                    pickContext=false
                    operate {
                        graph.studyRuntime.evaluateChosenContext(SyntheticContextInput(
                            runCatching {SyntheticContextType.valueOf(entry.type)}.getOrDefault(SyntheticContextType.NOTE),
                            entry.title,entry.text)).getOrThrow()
                        message("已用选中的情境跑一次完整链路")
                    }
                }
            }
        }
        importDebugCredentials(intent)
        savedInstanceState?.getString("selected_session")?.let(::openSession)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export",pendingExport?.absolutePath)
        outState.putString("selected_session",viewState.value.selectedSession?.session?.sessionId)
        super.onSaveInstanceState(outState)
    }
    private fun openLogs(id: String)=operate {
        viewState.update {it.copy(logSessionId=id,runtimeLogs=emptyList())}
        val rows=withContext(Dispatchers.IO) {graph.database.dao().operationalEvents(id).map {it.toRuntimeLogEntry()}}
        viewState.update {it.copy(runtimeLogs=rows)}
    }
    override fun onResume() {
        super.onResume()
        scope.launch { refreshSystemPairing() }
    }
    private suspend fun refreshSystemPairing() {
        val names=withContext(Dispatchers.IO) {runCatching {graph.link.pairedDevices().map {it.name}}.getOrDefault(emptyList())}
        viewState.update {it.copy(systemPairedNames=names)}
    }
    private fun openSession(id: String)=operate {
        val loaded=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).load(id)}
        viewState.update {it.copy(selectedSession=loaded,selectedTrial=null,busy=false)}
    }
    /**
     * Sends a recorded service card back to the glasses, for demonstrating a session that has already
     * run. It is a replay and not a delivery: the card carries its own decision id, so it cannot be
     * confused with the original service or produce a receipt against it, and nothing is journalled as
     * a new delivery.
     */
    private fun replayServiceCard(sessionId: String,anchorId: String)=operate {
        check(graph.transport.ready.value) { "CONNECT_GLASSES_FIRST" }
        val decision=withContext(Dispatchers.IO) {
            graph.journal.dao.eventForAnchor(sessionId,"decisions",anchorId)?.canonicalJson
                ?.let {StudyWire.json.parseToJsonElement(it).jsonObject}
        } ?: error("DECISION_NOT_FOUND")
        check(decision["decision"]?.jsonPrimitive?.contentOrNull=="SERVICE") { "DECISION_NOT_A_SERVICE" }
        val decisionId=decision["decision_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title=decision["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val body=decision["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        check(title.isNotBlank() && body.isNotBlank()) { "DECISION_INCOMPLETE" }
        val payload=StudyWire.payload("decision_id" to "replay-$decisionId",
            "service_mode" to (decision["service_mode"]?.jsonPrimitive?.contentOrNull ?: "AWARENESS"),
            "title" to title,"body" to body,"visible_for_ms" to 10_000)
        withContext(Dispatchers.IO) {graph.transport.replayServiceCard(payload).getOrThrow()}
        message("已回放到眼镜")
    }
    private fun addRecordContext(id: String,type: String,title: String,body: String,at: Long)=operate {
        withContext(Dispatchers.IO) {graph.journal.addSyntheticContext(id,type,title,body,at)}
        val loaded=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).load(id)}
        viewState.update {it.copy(selectedSession=loaded,selectedTrial=null,busy=false)}
        message("已插入一条情境")
    }
    /** The record a running session was seeded from, if any: its notes are pinned into that session. */
    private fun liveHistorySource(): String? {
        val live=graph.studyRuntime.state.value.sessionId ?: return null
        return graph.journal.dao.stream(live,"runtime_events").firstOrNull {it.eventType=="SESSION_CONFIGURATION"}
            ?.canonicalJson?.let {StudyWire.json.parseToJsonElement(it).jsonObject}
            ?.get("collection_history_seed")?.jsonObject?.get("source_session_id")?.jsonPrimitive?.contentOrNull
    }
    /**
     * Reads the ledger authored outside the app and applies it. The file is a JSON array of
     * `{type,title,body,at_epoch_ms}`, and a line carrying `retract: true` withdraws the note that was
     * written with those same four fields instead of adding one.
     *
     * Withdrawals are applied first, whatever order the file is in, and an addition whose note is
     * already there is skipped rather than repeated. Those two make the file safe to re-run, which is
     * what the file's own life depends on: it is removed only when every line found its note, so a file
     * that disagrees with the record stays put until the operator fixes it.
     *
     * The record must not be in use. A service session seeded from it holds those notes in memory and
     * keeps pinning them into every request, so withdrawing one would not reach the model anyway.
     */
    private fun importRecordContext(id: String)=operate {
        val file=File(filesDir,"synthetic-import.json")
        val outcome=withContext(Dispatchers.IO) {
            check(file.isFile) { "SYNTHETIC_IMPORT_FILE_MISSING" }
            check(graph.studyRuntime.state.value.sessionId!=id) { "RECORD_IN_USE" }
            check(liveHistorySource()!=id) { "RECORD_SEEDS_RUNNING_SESSION" }
            val ledger=readImportLedger(StudyWire.json.parseToJsonElement(file.readText()).jsonArray.map {it.jsonObject})
            // A ledger line is one fact; the rows it names are that fact plus every copy a merge made of
            // it, which is why the two are counted apart.
            var facts=0;var copies=0;var withdrawnBefore=0;var added=0;var existing=0
            val unmatched=mutableListOf<String>()
            val now=System.currentTimeMillis()
            ledger.retractions.forEach {line->
                val result=graph.journal.retractSyntheticContext(id,line.type,line.title,line.body,line.atEpochMs,line.reason,now)
                when {
                    result==null -> unmatched+="${line.type} ${line.title}"
                    else -> {
                        if(result.retracted.isNotEmpty()) facts++
                        copies+=result.retracted.size;withdrawnBefore+=result.alreadyRetracted.size
                    }
                }
            }
            ledger.additions.forEach {line->
                if(graph.journal.hasSyntheticContext(id,line.type,line.title,line.body,line.atEpochMs)) existing++
                else {graph.journal.addSyntheticContext(id,line.type,line.title,line.body,line.atEpochMs);added++}
            }
            // A line that found its note is spent, whether it wrote one or found it already there; only a
            // line the record disagrees with keeps the file, so the operator can see what did not apply.
            if(unmatched.isEmpty()) file.delete()
            listOf(
                if(facts>0) "已撤回 $facts 条情境（含合并副本 ${copies-facts} 处）" else null,
                if(withdrawnBefore>0) "$withdrawnBefore 处此前已撤回" else null,
                if(added>0) "导入 $added 条" else null,
                if(existing>0) "跳过 $existing 条已存在" else null,
                if(unmatched.isNotEmpty()) "${unmatched.size} 条未找到对应情境（${unmatched.first()}），文件保留以便修正" else null
            ).filterNotNull().joinToString("，").ifBlank {"没有需要处理的条目"}
        }
        val loaded=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).load(id)}
        viewState.update {it.copy(selectedSession=loaded,selectedTrial=null,busy=false)}
        message(outcome)
    }
    private fun exportSession(id: String)=operate {
        pendingExport=withContext(Dispatchers.IO) {graph.journal.export(id)}
        createExport.launch("opstate-$id.zip")
    }
    /**
     * Takes one photograph out of a record, from either screen that shows one.
     *
     * When the record is the session running right now the actor does the removal, so the session's own
     * view of that picture - the causal history it cites and the bytes it would attach to a request -
     * goes at the same moment as the file, which a plain append could not reach. A finished record has
     * no such view.
     */
    private fun deletePhoto(sessionId: String,observationId: String)=operate {
        // Both entry points pass through here, so the refusal is stated once instead of trusted to each
        // screen. The actor path below writes the journal directly, and would otherwise have let a
        // photograph a delivered service was reading be removed from the live view.
        val repository=SessionReviewRepository(graph.journal)
        val photo=withContext(Dispatchers.IO) {repository.photos(sessionId).firstOrNull {it.id==observationId}}
            ?: error("PHOTO_NOT_FOUND")
        check(!photo.served) {"PHOTO_BACKS_DELIVERED_SERVICE"}
        val live=graph.studyRuntime.state.value.sessionId==sessionId
        val removed=withContext(Dispatchers.IO) {
            if(live) graph.studyRuntime.deletePhoto(observationId).getOrThrow()
            else repository.deletePhoto(sessionId,observationId,System.currentTimeMillis())
        }
        if(viewState.value.selectedSession?.session?.sessionId==sessionId) {
            val loaded=withContext(Dispatchers.IO) {SessionReviewRepository(graph.journal).load(sessionId)}
            viewState.update {it.copy(selectedSession=loaded,busy=false)}
        }
        message(if(removed) "照片已删除" else "这张照片已经不在记录里")
    }
    private fun deleteSession(id: String)=operate {
        check(graph.studyRuntime.state.value.sessionId!=id && !(graph.datasetReplay.state.value.running && graph.datasetReplay.state.value.sessionId==id))
        withContext(Dispatchers.IO) {graph.journal.delete(id)}
        viewState.update {it.copy(selectedSession=null,selectedTrial=null,busy=false)};message("本地会话已删除")
    }
    private fun renameSession(id: String,name: String)=operate {
        withContext(Dispatchers.IO) {graph.journal.rename(id,name)}
        message("记录已重命名")
    }
    private fun deleteSessions(ids: List<String>)=operate {
        check(ids.none {it==graph.studyRuntime.state.value.sessionId}) { "RECORD_IN_USE" }
        check(!(graph.datasetReplay.state.value.running && graph.datasetReplay.state.value.sessionId in ids)) { "RECORD_IN_USE" }
        withContext(Dispatchers.IO) {graph.journal.deleteMany(ids)}
        viewState.update {it.copy(selectedSession=null,selectedTrial=null)}
        message(if(ids.size==1) "记录已删除" else "已删除 ${ids.size} 条记录")
    }
    private fun mergeSessions(ids: List<String>,name: String)=operate {
        check(ids.none {it==graph.studyRuntime.state.value.sessionId}) { "RECORD_IN_USE" }
        val merged=withContext(Dispatchers.IO) {graph.journal.merge(ids,name)}
        message("已合并为「$name」，共 ${ids.size} 段")
    }
    /**
     * Reads every record for the services that were really on the glasses.
     *
     * Its own launcher rather than [operate], because it is the one long action that must not block the
     * rest of the console: it runs while the operator keeps reading the list, and its own `loading` flag
     * is what the tab shows progress from.
     */
    private fun refreshShowcase() {
        if(viewState.value.showcase.loading) return
        viewState.update {it.copy(showcase=it.showcase.copy(loading=true))}
        scope.launch {
            try {
                graph.storageReady.await()
                val cards=withContext(Dispatchers.IO) {ShowcaseRepository.scan(graph.journal,graph.database.dao().sessions())}
                updateShowcase {it.copy(cards=cards,loaded=true,loading=false)}
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: Exception) {
                viewState.update {it.copy(showcase=it.showcase.copy(loading=false))}
                showError(error)
            }
        }
    }
    /**
     * Reads every photograph on the phone, for the picture slot.
     *
     * Separate from the scan and started only when the picker is first opened, because it is the more
     * expensive of the two - it parses the observations of every record, where the scan parses none.
     */
    private fun loadShowcasePhotos() {
        val showcase=viewState.value.showcase
        if(showcase.photosLoading || showcase.photosLoaded) return
        updateShowcase {it.copy(photosLoading=true)}
        scope.launch {
            try {
                graph.storageReady.await()
                val photos=withContext(Dispatchers.IO) {ShowcaseRepository.allPhotos(graph.journal,graph.database.dao().sessions())}
                updateShowcase {it.copy(photos=photos,photosLoading=false,photosLoaded=true)}
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: Exception) {
                updateShowcase {it.copy(photosLoading=false)}
                showError(error)
            }
        }
    }
    /** Appended, not sorted: the order the moments are chosen in is the order they will be shown in. */
    private fun addShowcaseDraft() {
        val showcase=viewState.value.showcase
        val card=showcase.draft.card ?: return
        updateShowcase {it.copy(selected=it.selected+ShowcaseRepository.refOf(card,showcase.draft.scene))}
    }
    private fun removeShowcase(index: Int)=updateShowcase {
        if(index in it.selected.indices) it.copy(selected=it.selected.filterIndexed {position,_->position!=index}) else it
    }
    private fun renameShowcaseGroup(name: String)=updateShowcase {it.copy(group=name.trim().ifBlank {ShowcaseState.DEFAULT_GROUP})}
    /**
     * Every change to the shortlist is written straight through, so a selection cannot be lost to a
     * crash. `chosen` is recomputed here rather than stored, so the rendered/exported set can never
     * drift from the ids it was made from.
     */
    private fun updateShowcase(change: (ShowcaseState)->ShowcaseState) {
        viewState.update {state->state.copy(showcase=ShowcaseRepository.withChosen(change(state.showcase),graph.journal::localImage))}
        val showcase=viewState.value.showcase
        ShowcaseStore.write(graph.settings,showcase.selected,showcase.group)
    }
    private fun exportShowcase()=operate {
        val showcase=viewState.value.showcase
        val chosen=showcase.chosen
        check(chosen.isNotEmpty()) {"SHOWCASE_NOTHING_SELECTED"}
        val published=withContext(Dispatchers.IO) {
            // Assembled afresh every time: a shorter selection than last time would otherwise leave the
            // frames that were dropped still sitting in the staging directory and in the album.
            val root=File(requireNotNull(getExternalFilesDir(null)),"showcase/${showcase.group}")
            root.deleteRecursively()
            ShowcaseExporter.write(root,chosen)
            ShowcaseExporter.publish(this@MainActivity,showcase.group,root)
        }
        message("已导出 ${chosen.size} 个时刻：图片在相册 Pictures/OPSTATE/${showcase.group}，卡片文字在 Download/OPSTATE/${showcase.group}（共 $published 个文件）")
    }
    private fun message(text: String) { viewState.update {it.copy(message=text)} }
    private fun showError(error: Throwable) {
        if(BuildConfig.DEBUG) android.util.Log.e("OPSTATE_UI",error.javaClass.simpleName+"\n"+error.stackTrace.take(8).joinToString("\n"))
        val text=when(error.message) {
            "DEVICE_IDENTIFICATION_TIMEOUT" -> "设备识别超时。切换应用时需折叠镜腿并三击功能键，重新进入配对模式。"
            "CXR_CLIENT_SECRET_REQUIRED" -> "请先在设置中保存开发者 Client Secret。"
            "CXR_SN_LICENSE_REQUIRED" -> "请先导入这副眼镜的 SN 授权文件。"
            "BLUETOOTH_PERMISSION_REQUIRED" -> "需要蓝牙和定位权限才能扫描眼镜。"
            "BLUETOOTH_DISABLED" -> "系统蓝牙尚未打开。"
            "NO_SAVED_DEVICE" -> "尚无保存的设备，请先扫描附近眼镜。"
            "GLASSES_APP_HANDSHAKE_TIMEOUT" -> "眼镜研究应用尚未响应，请打开眼镜端 OPSTATE 应用。"
            "RECORD_IN_USE" -> "这段记录正在采集中，结束采集后才能重命名、合并、撤回或删除。"
            "RECORD_SEEDS_RUNNING_SESSION" -> "这段记录正被当前服务会话当作历史使用，先结束服务再撤回。"
            "PHOTO_BACKS_DELIVERED_SERVICE" -> "这张照片是一次已投递主动服务的画面，删除后无法再生成演示帧，已保留。"
            "PHOTO_NOT_FOUND" -> "这张照片已经不在记录里，列表已刷新。"
            "STOP_SESSION_BEFORE_MERGE" -> "只有已结束的记录才能合并。"
            "STOP_SESSION_BEFORE_DELETE" -> "这段记录还没有正常结束，先结束采集再删除。"
            "INVALID_RECORD_NAME" -> "记录名称不能为空，且不超过 80 个字。"
            "MERGE_IMAGES_TOO_LARGE" -> "合并后的照片过多，请分批合并。"
            "MERGE_IMAGE_ID_COLLISION" -> "两条记录里有同名的照片，无法自动合并，请先导出其中之一。"
            "CXR_SOCKET_CONNECT_FAILED" -> "应用连接信息可能已失效。请折叠镜腿并三击功能键，再重新扫描连接。系统音频配对可以保持不变。"
            "CXR_OTHER_CLIENT_ACTIVE" -> "眼镜的应用通道正被其他客户端使用，请先在那个客户端断开后再连接。"
            "CXR_SN_CHECK_FAILED" -> "设备授权校验失败，请核对 Client Secret 和 SN 文件。"
            "CONFIGURED_MODEL_MISMATCH" -> "服务实际模型与绑定的模型 ID 不一致，请检查模型配置。"
            "MODEL_CONFIG_LOCKED" -> "请先结束当前会话或回放，再修改模型配置。"
            "MODEL_API_KEY_REQUIRED" -> "请填写并保存当前接口的 API Key；更换接口地址后需重新填写。"
            "MODEL_API_KEY_INVALID" -> "API Key 格式不正确，请检查是否含换行或多余内容。"
            "MODEL_API_URL_INVALID" -> "请填写不含密钥、查询参数的 HTTPS API 地址。"
            "MODEL_API_HTTP_401", "MODEL_API_HTTP_403" -> "模型接口拒绝授权，请检查 API Key 及模型访问权限。"
            "MODEL_API_HTTP_404" -> "未找到模型或接口，请核对模型 ID 和 API 地址。"
            "MODEL_API_HTTP_429" -> "模型接口暂时限流或额度不足，请稍后重试。"
            "MODEL_OUTPUT_INCOMPLETE" -> "模型输出未完成，可检查最大输出长度或稍后重试。"
            "INVALID_HARNESS_URL" -> "请使用不含密钥或查询参数的 HTTPS 服务地址；调试时可用本机 HTTP。"
            "CONNECT_GLASSES_FIRST" -> "请先连接眼镜并完成设备准备。"
            "DATASET_ENDPOINT_REQUIRES_REPLAY_MODE" -> "当前服务是数据集回放模式，现场验证需要现场 mock 或冻结模型端点。"
            else -> "操作未完成：${error.message ?: error.javaClass.simpleName}"
        }
        message(text)
    }
    private fun operate(block: suspend ()->Unit) {
        if(viewState.value.busy) return
        viewState.update {it.copy(busy=true)}
        scope.launch {
        try {graph.storageReady.await();block()} catch(cancelled: CancellationException) {throw cancelled}
        catch(error: Exception) {showError(error)}
        finally {viewState.update {it.copy(busy=false)}}
    } }
    private fun scan() = operate {
        val devices=graph.transport.scan().getOrThrow()
        refreshSystemPairing()
        if(devices.isEmpty()) {message("没有发现眼镜，请确认眼镜已进入配对状态。");return@operate}
        AlertDialog.Builder(this@MainActivity).setTitle("附近的 Rokid 眼镜")
            .setItems(devices.map {"${it.displayName}\n${if(it.advertising) "已发现应用配对广播" else "系统已配对 · 需进入应用配对模式"}\n${it.id}"}.toTypedArray()) {_,index->
                operate {
                    val device=devices[index]
                    if(viewState.value.secretPresent && viewState.value.licensePresent) {
                        graph.transport.connect(device).getOrThrow();message("眼镜应用通道已就绪")
                    } else {
                        graph.link.identify(ai.opstate.rokid.phone.rokid.CxrDevice(device.id,device.displayName))
                        refreshSystemPairing();message("眼镜设备已识别。请配置 SDK 授权后建立应用数据通道。")
                    }
                }
            }.setNegativeButton("取消",null).show()
    }
    private suspend fun refreshCredentials() {
        val presence=withContext(Dispatchers.IO) {runCatching {(graph.credentials.clientSecret()!=null) to (graph.credentials.licenseBytes()!=null)}}
        val harnessTokenPresent=withContext(Dispatchers.IO) {graph.credentials.harnessToken().isNotBlank()}
        val modelKeyPresent=withContext(Dispatchers.IO) {graph.credentials.modelApiKey(viewState.value.directModel.credentialScope()).isNotBlank()}
        viewState.update {it.copy(harnessTokenPresent=harnessTokenPresent,modelKeyPresent=modelKeyPresent)}
        presence.onSuccess { (secret,license)->viewState.update {it.copy(secretPresent=secret,licensePresent=license)} }.onFailure(::showError)
    }
    private fun grantPermissions() {
        val permissions=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT>=31) permissions+=listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        if(Build.VERSION.SDK_INT>=33) permissions+=listOf(Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.POST_NOTIFICATIONS)
        val missing=permissions.filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty()) message("所需权限已具备") else requestPermissions(missing.toTypedArray(),10)
    }
    private fun checkConfigurationEditable() {
        check(!graph.datasetReplay.state.value.running && graph.studyRuntime.state.value.sessionId==null && graph.studyRuntime.state.value.phase!="PREPARING") { "MODEL_CONFIG_LOCKED" }
    }
    private fun saveModel(config: DirectModelConfig,key: String)=operate {
        checkConfigurationEditable()
        config.validate()
        val saved=config.copy(endpoint=config.resolvedEndpoint(),modelId=config.modelId.trim())
        withContext(Dispatchers.IO) {
            if(key.isNotBlank()) graph.credentials.putModelApiKey(saved.credentialScope(),key.trim())
            require(graph.credentials.modelApiKey(saved.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
            check(graph.settings.edit().putString("model_api_config",StudyWire.json.encodeToString(saved)).commit())
        }
        viewState.update {it.copy(directModel=saved,modelApiStatus="已保存 · 尚未验证",modelContract=null)}
        refreshCredentials();message("模型配置与 API Key 已保存")
    }
    private fun probe() = operate {
        val config=viewState.value.directModel
        viewState.update {it.copy(modelApiStatus="正在验证 API Key…")}
        try {
            val contract=withContext(Dispatchers.IO) {
                val client=DirectModelClient.create(this@MainActivity,config) {graph.credentials.modelApiKey(config.credentialScope())}
                val records=mutableListOf<JsonObject>()
                try {DirectModelProbe.run(client) {raw->records+=StudyWire.payload("format" to raw.format,"http_status" to raw.httpStatus,
                    "body" to raw.body,"sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs)}}
                finally {File(filesDir,"validation").apply {mkdirs()}.resolve("model-api-probe.json").writeText(JsonArray(records).toString())}
                client.contract()
            }
            viewState.update {it.copy(modelContract=contract,modelApiStatus="API Key 验证通过")}
            message("API Key 可用，手机已直连模型")
        } catch(error: Exception) {viewState.update {it.copy(modelApiStatus="验证未通过")};throw error}
    }
    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_LICENSE && resultCode==RESULT_OK) operate {
            val bytes=withContext(Dispatchers.IO) {
                contentResolver.openInputStream(requireNotNull(data?.data))?.use {input->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val read=input.read(buffer);if(read<0) break;require(output.size()+read<=1024*1024) {"授权文件过大"};output.write(buffer,0,read)}
                    output.toByteArray()
                } ?: error("无法读取授权文件")
            }
            withContext(Dispatchers.IO) {graph.credentials.put("sn-license",bytes)}
            refreshCredentials();message("SN 授权文件已加密保存")
        }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);importDebugCredentials(intent)}
    private fun importDebugCredentials(intent: Intent) {
        if(!BuildConfig.DEBUG || intent.action!="ai.opstate.action.IMPORT_CREDENTIALS") return
        operate {
            withContext(Dispatchers.IO) {
                val directory=File(filesDir,"import")
                listOf("client-secret" to "client-secret.txt","sn-license" to "glasses.lc","harness-token" to "harness-token.txt").forEach {(key,name)->
                    val file=File(directory,name)
                    if(file.isFile) {require(file.length() in 1L..1024L*1024L);graph.credentials.put(key,file.readBytes());check(file.delete())}
                }
            }
            refreshCredentials();message("本机凭证已导入并加密保存")
        }
    }
    override fun onDestroy() {scope.cancel();super.onDestroy()}
    companion object {private const val REQUEST_LICENSE=11}
}
