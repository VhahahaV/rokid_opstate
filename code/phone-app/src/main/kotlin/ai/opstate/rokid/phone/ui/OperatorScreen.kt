package ai.opstate.rokid.phone.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import ai.opstate.rokid.phone.app.R
import ai.opstate.rokid.phone.replay.ReplayState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opstate.rokid.phone.data.StudySessionEntity
import ai.opstate.rokid.phone.rokid.CxrLinkState
import ai.opstate.rokid.phone.runtime.RuntimeViewState
import ai.opstate.rokid.phone.runtime.SessionMode
import ai.opstate.rokid.phone.runtime.FrozenHarnessContract
import ai.opstate.rokid.phone.runtime.DirectModelConfig
import ai.opstate.rokid.phone.scenario.ScenarioTimeline
import ai.opstate.rokid.phone.replay.HistoryPreview
import ai.opstate.rokid.phone.replay.LocalCollectionSummary
import ai.opstate.rokid.phone.runtime.SyntheticContextInput
import ai.opstate.rokid.phone.review.SessionReview
import ai.opstate.rokid.phone.review.TrialReview
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DatasetSummary(val title: String,val anchors: Int,val observations: Int,val images: Int,val hash: String)
data class OperatorViewState(
    val link: CxrLinkState=CxrLinkState(),val peerReady: Boolean=false,val busy: Boolean=false,
    val endpoint: String="http://127.0.0.1:8765",val harnessStatus: String="未检查",
    val secretPresent: Boolean=false,val licensePresent: Boolean=false,
    val sessions: List<StudySessionEntity> = emptyList(),val collections: List<LocalCollectionSummary> = emptyList(),val dataset: DatasetSummary?=null,
    val message: String?=null,
    val selectedSession: SessionReview?=null,val selectedTrial: TrialReview?=null,
    val runtime: RuntimeViewState=RuntimeViewState(),
    val livePhotos: List<ai.opstate.rokid.phone.review.CapturedPhoto> = emptyList(),
    val syncBatchSetting: Int=60,
    val replay: ReplayState=ReplayState(),
    val systemPairedNames: List<String> = emptyList(),val sdkIdentityCached: Boolean=false,
    val expectedModelId: String="",val harnessTokenPresent: Boolean=false,val modelContract: FrozenHarnessContract?=null,
    val directModel: DirectModelConfig=DirectModelConfig.qwen(),val modelKeyPresent: Boolean=false,val modelApiStatus: String="尚未验证",
    val scenarioTimeline: ScenarioTimeline=ScenarioTimeline(720000,emptyList()),
    val historyPreview: HistoryPreview?=null,val importedHistory: ImportedHistory?=null,
    val logSessionId: String?=null,val runtimeLogs: List<RuntimeLogEntry> = emptyList(),
)
data class OperatorActions(
    val scan: ()->Unit,val reconnect: ()->Unit,val disconnect: ()->Unit,val p2p: ()->Unit,
    val permissions: ()->Unit,val bluetooth: ()->Unit,val probe: ()->Unit,
    val saveSecret: (String)->Unit,val importLicense: ()->Unit,val openDeveloperConsole: ()->Unit,
    val saveModel: (DirectModelConfig,String)->Unit,val clearModelKey: ()->Unit,
    val openSession: (StudySessionEntity)->Unit,val dismissMessage: ()->Unit,
    val startReplay: (Int)->Unit,val stopReplay: ()->Unit,val openSessionId: (String)->Unit,
    val start: (SessionLaunchOptions)->Unit,val pause: ()->Unit,val resume: ()->Unit,val stop: ()->Unit,val capture: ()->Unit,val seek: (Long)->Unit,
    val previewHistory: (Int)->Unit,val seekHistory: (Int)->Unit,val automaticCapture: (Boolean)->Unit,val demoMode: (Boolean)->Unit,
    val addContext: (SyntheticContextInput,()->Unit)->Unit,
    val openLogs: (String)->Unit,
    val loadHistory: (String?,Int?,Int,()->Unit)->Unit,val clearHistory: ()->Unit,
    val syncPhotos: ()->Unit={},
)

@Composable fun OperatorScreen(state: OperatorViewState,actions: OperatorActions) {
    val pageScrollStates=List(5) { rememberLazyListState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var connection by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var contextForm by rememberSaveable {mutableStateOf(false)}
    var advanced by rememberSaveable {mutableStateOf(false)}
    val screenScope=rememberCoroutineScope()
    val openCredentials: ()->Unit={connection=false;tab=3;screenScope.launch {pageScrollStates[3].scrollToItem(1)}}
    BackHandler(enabled=connection || tab!=0) { if(connection) connection=false else tab=0 }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); actions.dismissMessage() }
    }
    Scaffold(
        containerColor=Canvas,
        snackbarHost={SnackbarHost(snackbar)},
        topBar={ Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=24.dp,vertical=14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Image(painterResource(R.drawable.opstate_brand),"OPSTATE 应用图标",Modifier.size(25.dp).clip(RoundedCornerShape(7.dp)))
                Spacer(Modifier.width(10.dp))
                Text("OPSTATE",fontFamily=FontFamily.Monospace,fontSize=14.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick={connection=true}) {Text(if(state.peerReady) "眼镜已连接" else if(state.link.bluetoothConnected) "确认眼镜应用中" else "连接眼镜",color=if(state.peerReady) Success else Quiet)}
            }
            Spacer(Modifier.height(8.dp))
            Text(if(connection) "连接眼镜" else listOf("开始","我的记录","历史","设置")[tab],style=MaterialTheme.typography.headlineLarge)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
        } },
        bottomBar={ NavigationBar(containerColor=Color(0xFFFCFDF9),tonalElevation=0.dp) {
            listOf(0 to ("开始" to Icons.Default.Home),1 to ("记录" to Icons.Default.List),3 to ("设置" to Icons.Default.Settings)).forEach { (index,pair) ->
                val (label,icon)=pair
                NavigationBarItem(selected=tab==index && !connection,onClick={tab=index;connection=false},
                    icon={Icon(icon,label,Modifier.size(21.dp))},label={Text(label)},
                    colors=NavigationBarItemDefaults.colors(selectedIconColor=Ink,selectedTextColor=Ink,indicatorColor=Color(0xFFE0EBDF),unselectedIconColor=Quiet,unselectedTextColor=Quiet))
            }
        } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("operator-content-$tab"),state=pageScrollStates[if(connection) 4 else tab],contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(connection) {
                item { TextButton(onClick={connection=false},contentPadding=PaddingValues(0.dp)) { Icon(Icons.Default.ArrowBack,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("返回采集") } }
                item { Panel {
                    Icon(Icons.Default.Phone,contentDescription=null,tint=Success,modifier=Modifier.size(32.dp))
                    Text(if(state.peerReady) "手机与眼镜已互相确认" else "先打开眼镜上的 OPSTATE",style=MaterialTheme.typography.titleLarge)
                    Text(if(state.peerReady) "可以开始采集，照片会保存在这台手机。" else "眼镜显示未连接时，轻触侧边选择手机；然后在这里连接眼镜。",color=Quiet)
                    PrimaryButton(if(state.peerReady) "完成，去采集" else if(state.busy) "正在连接…" else if(state.sdkIdentityCached) "连接我的眼镜" else "查找眼镜",!state.busy) {
                        if(state.peerReady) connection=false else if(state.sdkIdentityCached) actions.reconnect() else actions.scan()
                    }
                    if(!state.peerReady && state.sdkIdentityCached) TextButton(onClick=actions.scan,enabled=!state.busy) {Text("查找其他眼镜")}
                    state.link.errorCode?.let {Text(connectionError(it),color=MaterialTheme.colorScheme.error)}
                    if(state.peerReady) TextButton(onClick=actions.disconnect) {Text("断开眼镜")}
                    TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "收起连接详情" else "连接遇到问题")}
                } }
                if(advanced) {
                item { DevicePanel(state) }
                item { Panel {
                    SectionTitle("连接准备","CONNECTION CHECK")
                    SettingRow("系统配对",if(state.systemPairedNames.isNotEmpty()) "已检测到眼镜" else "未检测到配对")
                    SettingRow("应用连接信息",if(state.sdkIdentityCached || state.link.bluetoothConnected) "已识别并保存" else "尚未识别")
                    SettingRow("SDK 开发者授权",if(state.secretPresent && state.licensePresent) "已配置" else "待配置")
                    SettingRow("SDK 数据通道",if(state.link.bluetoothConnected) "已建立" else "未建立")
                    SettingRow("研究应用握手",if(state.peerReady) "已就绪" else "待确认")
                    if(!state.secretPresent || !state.licensePresent) {
                        Text("OPSTATE 需要自己的 Rokid Client Secret 和对应眼镜的 SN 授权文件。手机系统配对不会自动提供这些凭证。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                        TextButton(onClick=openCredentials) {Text("前往设置 · Rokid 开发者授权")}
                    }
                    Text("首次使用本应用，或从 Rokid 官方 App 切换过来：折叠镜腿并三击功能键，进入应用可发现状态后再扫描。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    state.link.errorCode?.let {code->Text(connectionError(code),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)}
                } }
                item { Panel {
                    SectionTitle("连接操作","CONNECTIVITY")
                    PrimaryButton(if(state.busy) "正在扫描或识别…" else if(state.secretPresent && state.licensePresent) "扫描并连接眼镜" else "扫描并识别眼镜",!state.busy && !state.link.bluetoothConnected,actions.scan)
                    OutlinedButton(onClick=actions.reconnect,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) { Text("连接上次设备") }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick=actions.permissions,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) { Text("检查权限") }
                        OutlinedButton(onClick=actions.bluetooth,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) { Text("打开蓝牙") }
                    }
                    if(state.link.bluetoothConnected) {
                        OutlinedButton(onClick=actions.p2p,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) { Text("建立 P2P 图像通道") }
                        TextButton(onClick=actions.disconnect,modifier=Modifier.fillMaxWidth()) { Text("断开连接",color=MaterialTheme.colorScheme.error) }
                    }
                    Text("首次连接：折叠镜腿后，三击功能键进入配对状态。请在设置中导入这副眼镜的授权文件。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                } }
                }
            } else when(tab) {
                0 -> {
                    item { if(state.runtime.sessionId!=null) LivePanel(state,actions) else SessionSetupPanel(state,actions,
                        connect={connection=true},importHistory={setup=true}) }
                    if(state.runtime.sessionId!=null) item {ObservationControls(state,actions) {contextForm=true}}
                    if(state.runtime.sessionId!=null) {
                        item {TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "收起运行详情" else "运行详情")}}
                        if(advanced) item {RuntimeDiagnostics(state,actions)}
                    }
                }
                1 -> {
                    item { Text("采集的照片保存在手机。结束采集后，可选作服务历史。",style=MaterialTheme.typography.bodyMedium,color=Quiet) }
                    if(state.sessions.isEmpty()) item { Panel {
                        Spacer(Modifier.height(12.dp))
                        Icon(Icons.Default.List,null,Modifier.size(32.dp),tint=Quiet)
                        Text("还没有采集记录",style=MaterialTheme.typography.titleLarge)
                        Text("戴上眼镜，记录第一段生活。",style=MaterialTheme.typography.bodyMedium,color=Quiet)
                        PrimaryButton("去采集",true) {tab=0}
                        Spacer(Modifier.height(8.dp))
                    } } else items(state.sessions,key={it.sessionId}) { session -> SessionRow(session) {actions.openSession(session)} }
                }
                3 -> {
                    item {Panel {
                        Text("设备与连接",style=MaterialTheme.typography.titleMedium)
                        SettingRow("眼镜",if(state.peerReady) "已连接" else "未连接")
                        PrimaryButton("管理眼镜",!state.busy) {connection=true}
                        Text("采集不需要电脑或模型 API；手机和眼镜通过蓝牙与 Wi-Fi 直接通信。",color=Quiet,style=MaterialTheme.typography.bodySmall)
                    }}
                    item { ModelConfigurationPanel(state,actions) }
                    item {TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "收起高级设置" else "高级设置")}}
                    if(advanced) {
                    item { CredentialsPanel(state,actions) }
                    item { Panel {
                        SectionTitle("运行边界","STUDY DEFAULTS")
                        SettingRow("原始音频","不保存")
                        SettingRow("原始 IMU","不保存，仅保留摘要")
                        SettingRow("NO_SERVICE","眼镜保持沉默")
                        SettingRow("服务回执","实际显示后提交")
                    } }
                    }
                }
            }
        }
    }
    if(setup) HistoryImportSheet(state,actions) {setup=false}
    if(contextForm && state.runtime.sessionId!=null) SyntheticContextSheet(state,actions) {contextForm=false}
}

@Composable private fun LivePanel(state: OperatorViewState,actions: OperatorActions) {
    if(state.runtime.mode==SessionMode.COLLECTION) {
        val runtime=state.runtime
        Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(runtime.phase=="SYNCING") "正在收尾同步" else "正在采集",style=MaterialTheme.typography.titleLarge)
                Tag(if(runtime.phase=="PAUSED") "已暂停" else if(!state.peerReady) "眼镜本地采集" else "进行中",if(state.peerReady) Success else Amber)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${runtime.liveCaptures} 张",fontSize=32.sp,fontWeight=FontWeight.Bold)
                Column(horizontalAlignment=Alignment.End) {
                    Text("手机已保存 · 眼镜待同步 ${runtime.bufferedPhotos} 张",color=Quiet,style=MaterialTheme.typography.bodySmall)
                    Text("${playbackTime(runtime.elapsedMs)} · 每 ${runtime.captureIntervalMs/1000} 秒",color=Quiet,style=MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick=if(runtime.phase=="PAUSED") actions.resume else actions.pause,enabled=!state.busy && state.peerReady && runtime.phase!="SYNCING",modifier=Modifier.weight(1f).heightIn(min=50.dp)) {Text(if(runtime.phase=="PAUSED") "继续采集" else "暂停")}
                OutlinedButton(onClick=actions.stop,enabled=!state.busy && runtime.phase!="SYNCING",modifier=Modifier.weight(1f).heightIn(min=50.dp)) {Text("结束并同步")}
            }
            if(runtime.glassesCapture) TextButton(actions.syncPhotos,enabled=state.peerReady && runtime.bufferedPhotos>0) {Text(if(runtime.syncing) "正在同步…" else "立即同步 · 每 ${runtime.syncBatchSize} 张自动同步")}
            Text("返回桌面或锁屏后继续采集；停止请点「结束并同步」。",color=Quiet,style=MaterialTheme.typography.bodySmall)
            if(!state.peerReady) Text("待同步张数为上次连接时的状态，重连后更新。",color=Quiet,style=MaterialTheme.typography.bodySmall)
            runtime.error?.let {Text(sessionError(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
        return
    }
    Panel {
    val runtime=state.runtime
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("当前会话",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
        Tag(if(runtime.replayComplete) "回放结束" else when(runtime.phase) {"HISTORY_READY"->"历史已就绪";"CAPTURING"->"采集中";"EVALUATING"->"模型评估中";"DELIVERING"->"等待显示确认";"PAUSED"->"已暂停";else->"运行中"},Success)
    }
    Text(if(runtime.scenario=="Meeting location change") "会议地点与时间变更" else runtime.scenario.orEmpty(),style=MaterialTheme.typography.titleLarge)
    Text(if(runtime.mode==SessionMode.COLLECTION) "采集模式 · 照片只保存在手机" else "服务模式",style=MaterialTheme.typography.bodySmall,color=Quiet)
    Text(when(runtime.startupDisplay) {"VISIBLE"->"眼镜已显示启动提示";"WAITING"->"眼镜已接收会话，等待显示确认…";else->"尚未收到启动提示的显示确认，请检查眼镜画面"},style=MaterialTheme.typography.bodySmall,color=if(runtime.startupDisplay=="VISIBLE") Success else Quiet)
    if(runtime.replayEnabled) key(runtime.sessionId) {
        ScenarioPlaybackPanel(runtime.elapsedMs,runtime.durationMs,runtime.markers,enabled=!state.busy && state.peerReady,onSeek=actions.seek)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=if(runtime.replayComplete) { {actions.seek(0)} } else if(runtime.phase=="PAUSED") actions.resume else actions.pause,
                enabled=!state.busy && state.peerReady,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text(if(runtime.replayComplete) "重新播放" else if(runtime.phase=="PAUSED") "继续播放" else "暂停播放")}
            OutlinedButton(onClick=actions.stop,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text("结束会话")}
        }
        runtime.currentContext?.let {Text("当前情境 · ${contextLabel(it)}",style=MaterialTheme.typography.bodyMedium)}
        Text("拖动会保存当前片段，从选定位置重新开始。暂停时停止情境播放与采集，现实时间继续。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    } else if(runtime.historyPreview!=null) key(runtime.sessionId) {
        val preview=requireNotNull(runtime.historyPreview)
        var draft by remember {mutableStateOf<Float?>(null)}
        Text("历史位置 ${((draft ?: preview.index.toFloat()).toInt()+1)} / ${preview.total}",fontFamily=FontFamily.Monospace,color=Quiet)
        Slider(draft ?: preview.index.toFloat(),{draft=it},onValueChangeFinished={draft?.let {actions.seekHistory(it.toInt())};draft=null},
            enabled=!state.busy && state.peerReady,valueRange=0f..(preview.total-1).coerceAtLeast(1).toFloat(),modifier=Modifier.fillMaxWidth().testTag("live-history-progress"),
            colors=SliderDefaults.colors(thumbColor=Ink,activeTrackColor=Success,inactiveTrackColor=Line))
        Text("${preview.observationCount} 条历史已载入 · ${preview.sourceTime.take(16).replace("T"," ")}",style=MaterialTheme.typography.bodySmall,color=Quiet)
        Text("拖动会保存当前记录，建立新的历史续接片段。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    } else Text(if(runtime.mode==SessionMode.COLLECTION) "已持续 ${playbackTime(runtime.elapsedMs)} · 间隔 ${runtime.captureIntervalMs/1000} 秒" else "场景时间 ${playbackTime(runtime.elapsedMs)}",
        fontFamily=FontFamily.Monospace,color=Quiet)
    if(!runtime.replayEnabled) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(onClick=if(runtime.phase in setOf("PAUSED","HISTORY_READY")) actions.resume else actions.pause,enabled=!state.busy && state.peerReady,
            modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text(when {
                runtime.phase=="HISTORY_READY"->"开始实时服务"
                runtime.mode==SessionMode.COLLECTION && runtime.phase=="PAUSED"->"继续采集"
                runtime.mode==SessionMode.COLLECTION->"暂停采集"
                runtime.phase=="PAUSED"->"继续服务"
                else->"暂停服务"})}
        OutlinedButton(onClick=actions.stop,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text("结束会话")}
    }
    if(!runtime.replayEnabled) runtime.currentContext?.let {Text("当前情境 · ${contextLabel(it)}",style=MaterialTheme.typography.bodyMedium,maxLines=4,overflow=TextOverflow.Ellipsis)}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        if(runtime.mode==SessionMode.COLLECTION) {
            MetricText(runtime.liveCaptures.toString(),"已保存")
            MetricText(runtime.validCaptures.toString(),"可用于历史")
            MetricText(storageSize(runtime.savedImageBytes),"图片占用")
        } else {
            MetricText(runtime.observations.toString(),"观察")
            MetricText(runtime.packets.toString(),"观察包")
            MetricText(runtime.receipts.toString(),"真实回执")
        }
    }
    HorizontalDivider(color=Line)
    if(runtime.mode==SessionMode.SERVICE) {
        SettingRow("最近模型决策",runtime.rawDecision ?: "尚未评估")
        SettingRow("最近投递结果",when(runtime.delivery) {"DISPLAY_VISIBLE"->"已实际显示";"NO_SERVICE_SILENCE"->"保持沉默";"SUPPRESSED_BY_RECEIPT"->"重复服务已抑制";"STALE_DECISION"->"结果已过期";"DISPLAY_FAILED"->"未确认显示";null->"—";else->runtime.delivery.orEmpty()})
    }
    runtime.error?.let {Text(sessionError(it),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
} }
@Composable private fun DevicePanel(state: OperatorViewState,onOpen: (()->Unit)?=null) { Panel {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("设备链路",style=MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f)); if(onOpen!=null) TextButton(onClick=onOpen) {Text("管理")}
    }
    DeviceRow("P","手机", "${Build.MODEL} · Android ${Build.VERSION.RELEASE}","运行中",true)
    HorizontalDivider(color=Line)
    DeviceRow("G","Rokid 眼镜",if(state.link.bluetoothConnected) "SDK 数据通道已连接" else if(state.systemPairedNames.isNotEmpty()) "系统已配对 · SDK 通道未建立" else "CXR 通信尚未建立",if(state.peerReady) "就绪" else "待连接",state.peerReady)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Tag(if(state.link.bluetoothConnected) "SDK BT 已连接" else "SDK BT 待连接",if(state.link.bluetoothConnected) Success else Quiet)
        Tag(if(state.link.p2pConnected) "P2P 已连接" else "P2P 待协商",if(state.link.p2pConnected) Success else Quiet)
    }
} }
@Composable private fun DeviceRow(letter: String,title: String,subtitle: String,status: String,ok: Boolean) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(40.dp).background(Color(0xFFEDF1E8),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center) {
            Text(letter,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,color=Ink)
        }
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleMedium); Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Quiet) }
        Text(status,style=MaterialTheme.typography.labelMedium,color=if(ok) Success else Quiet)
    }
}

internal fun sessionError(code: String)=when(code) {
    "CONNECT_GLASSES_FIRST","GLASSES_NOT_READY"->"请打开眼镜端 OPSTATE 应用，并在手机重新连接眼镜。"
    "GLASSES_DISPLAY_NOT_READY","DISPLAY_NOT_READY"->"眼镜显示未就绪，请唤醒眼镜并打开 OPSTATE 应用。"
    "MODEL_API_KEY_REQUIRED"->"请先在设置中保存当前模型的 API Key。"
    "CAPTURE_TIMEOUT","IMAGE_TRANSFER_TIMEOUT"->"这次图像采集超时，请检查眼镜连接。"
    "COLLECTION_STORAGE_LOW"->"手机可用空间低于 128 MB，自动采集已暂停。请释放空间后再继续。"
    "IMAGE_TOO_DARK"->"照片过暗，暂未评估。请移开镜头遮挡或朝向明亮场景，再拍照观察。"
    "IMAGE_TOO_BRIGHT"->"照片过亮，暂未评估。请避免镜头直对强光，再拍照观察。"
    "IMAGE_TOO_BLURRY"->"画面不够清晰，暂未评估。请保持眼镜稳定、朝向有细节的场景后重试。"
    "IMAGE_CORRUPTED"->"图像无法解码或元数据不一致，暂未评估。请重新拍照观察。"
    "PEER_BUILD_CHANGED"->"眼镜应用或固件版本已改变。请结束当前会话，再开始新会话。"
    "MODEL_OUTPUT_TOO_LONG"->"模型回复超过眼镜显示长度，已保留原文并取消显示。可重新拍照评估。"
    "MODEL_OUTPUT_EVIDENCE_INVALID"->"模型引用了本次观察之外的信息，已取消显示。可重新拍照评估。"
    "MODEL_OUTPUT_INVALID","MODEL_OUTPUT_FIELDS_INVALID","MODEL_OUTPUT_INCOMPLETE"->"模型未返回可用的服务结果，已保留原文。可重新拍照评估。"
    "GLASSES_APP_HANDSHAKE_TIMEOUT"->"眼镜研究应用未响应，请打开眼镜端 OPSTATE 应用后重试。"
    "SESSION_PREPARATION_TIMEOUT","SESSION_PREPARATION_INTERRUPTED"->"眼镜会话准备未完成，请确认眼镜应用在前台，然后重新连接。"
    else->"操作未完成 · $code"
}
@Composable private fun CredentialsPanel(state: OperatorViewState,actions: OperatorActions) {
    var secret by remember { mutableStateOf("") }
    Panel {
        SectionTitle("Rokid 开发者授权","DEVICE AUTHORIZATION")
        Text("此处填写 Rokid SDK 的设备授权。官方 App 的登录或配对状态不会自动授权第三方应用。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        TextButton(onClick=actions.openDeveloperConsole,contentPadding=PaddingValues(0.dp)) {Text("打开 Rokid 开发者控制台")}
        Text("完成开发者认证 → 获取 Client Secret → 绑定当前眼镜 SN → 下载 .lc 文件。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        SettingRow("Client Secret",if(state.secretPresent) "已加密保存" else "待配置")
        OutlinedTextField(value=secret,onValueChange={secret=it},label={Text("Client Secret")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
        PrimaryButton("加密保存",secret.isNotBlank()) {actions.saveSecret(secret);secret=""}
        HorizontalDivider(color=Line)
        SettingRow("SN 授权文件",if(state.licensePresent) "已导入" else "待导入")
        OutlinedButton(onClick=actions.importLicense,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) { Text("导入 .lc 文件") }
        Text("凭证仅保存在此手机的应用私有空间，不进入日志和模型请求。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    }
}
@Composable private fun SessionRow(session: StudySessionEntity,onClick: ()->Unit) {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("session-${session.sessionId}").clickable(onClick=onClick)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row {Text(session.participantAlias,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Tag(if(session.endedAtEpochMs==null) "未结束" else "已结束",if(session.endedAtEpochMs==null) Amber else Quiet)}
            Text(SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(Date(session.startedAtEpochMs)),style=MaterialTheme.typography.bodySmall,color=Quiet)
            Text(if(session.scenarioId=="local-collection-v1") "图片采集" else "在线服务与历史",style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
    }
}
@Composable internal fun Panel(content: @Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(19.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}
@Composable internal fun SectionTitle(title: String,eyebrow: String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(eyebrow,style=MaterialTheme.typography.labelSmall,color=Quiet);Text(title,style=MaterialTheme.typography.titleMedium) }
}
@Composable internal fun Tag(text: String,color: Color) { Surface(color=color.copy(alpha=0.08f),shape=RoundedCornerShape(6.dp)) { Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=color) } }
@Composable internal fun PrimaryButton(text: String,enabled: Boolean,action: ()->Unit) { Button(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text(text)} }
@Composable internal fun MetricText(value: String,label: String) { Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {Text(value,fontSize=24.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Monospace);Text(label,style=MaterialTheme.typography.bodySmall,color=Quiet)} }
@Composable internal fun SettingRow(title: String,value: String) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(title,style=MaterialTheme.typography.bodyMedium);Text(value,style=MaterialTheme.typography.bodySmall,color=Quiet)} }

internal fun storageSize(bytes: Long): String=when {
    bytes>=1024L*1024*1024->String.format(Locale.CHINA,"%.1f GB",bytes/(1024.0*1024*1024))
    bytes>=1024L*1024->String.format(Locale.CHINA,"%.1f MB",bytes/(1024.0*1024))
    bytes>=1024L->"${bytes/1024} KB"
    else->"$bytes B"
}

private fun connectionError(code: String)=when(code) {
    "CXR_CLIENT_SECRET_REQUIRED"->"尚未配置本应用的 Client Secret。"
    "CXR_SN_LICENSE_REQUIRED"->"尚未导入这副眼镜的 SN 授权文件。"
    "CXR_SOCKET_CONNECT_FAILED"->"旧的应用连接信息已失效。请重新进入配对模式并扫描；下次连接会重新初始化。"
    "CXR_OTHER_CLIENT_ACTIVE"->"应用通道正在被其他客户端使用。"
    "CXR_BLE_CONNECT_FAILED"->"BLE 初始化未成功，请确认眼镜正在广播配对状态。"
    "DEVICE_IDENTIFICATION_TIMEOUT"->"设备识别超时。请从官方 App 切换到本应用的配对模式后再试。"
    "BLUETOOTH_CONNECT_TIMEOUT"->"连接超时，请核对配对模式、授权与距离。"
    else->"连接状态：$code"
}
