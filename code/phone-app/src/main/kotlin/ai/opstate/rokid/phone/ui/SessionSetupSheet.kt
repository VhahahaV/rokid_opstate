package ai.opstate.rokid.phone.ui

import ai.opstate.rokid.phone.runtime.SyntheticContextInput
import ai.opstate.rokid.phone.runtime.SyntheticContextType
import ai.opstate.rokid.phone.runtime.SessionMode
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many of a record's captures to use as history; 0 means all of them. */
internal const val PARTIAL_HISTORY=4
data class SessionLaunchOptions(val alias: String,val scenarioPositionMs: Long=0,val historyIndex: Int?=null,val demoMode: Boolean=true,val shadowInference: Boolean=false,
    val profile: String="BENCHMARK_ALIGNED",val mode: SessionMode=SessionMode.SERVICE,val captureIntervalMs: Long=60_000,
    val collectionHistorySessionId: String?=null,val collectionHistoryImageLimit: Int=0,val syncBatchSize: Int=60,
    /** Demo: always ask the model for a service, so a tap reliably produces something to show. */
    val forceService: Boolean=false)

data class ImportedHistory(val sourceSessionId: String?=null,val publicIndex: Int?=null,val imageLimit: Int=0,
    val title: String,val images: Int,val contexts: Int,val recent: List<String> = emptyList())

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SessionSetupPanel(state: OperatorViewState,actions: OperatorActions,connect: ()->Unit,importHistory: ()->Unit) {
    var mode by rememberSaveable {mutableStateOf(SessionMode.COLLECTION)}
    var forceService by rememberSaveable {mutableStateOf(false)}
    var interval by rememberSaveable {mutableLongStateOf(60_000)}
    var alias by rememberSaveable {mutableStateOf("")}
    var naming by rememberSaveable {mutableStateOf(false)}
    var batch by rememberSaveable {mutableStateOf(state.syncBatchSetting.toString())}
    var batching by rememberSaveable {mutableStateOf(false)}
    val history=state.importedHistory
    val preparing=state.runtime.phase=="PREPARING"
    Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("1  准备历史",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            OutlinedButton(onClick=importHistory,enabled=!state.busy && !preparing) {Text(if(history==null) "导入历史" else "更换历史")}
        }
        Text(history?.let {"${it.title} · ${it.images} 张图片 · ${it.contexts} 条合成数据"} ?: "可选。仅加载旧图片与合成数据，不触发服务。",color=Quiet,style=MaterialTheme.typography.bodySmall)
        if(history!=null) TextButton(onClick=actions.clearHistory,enabled=!preparing) {Text("清除已加载历史")}
    }
    Spacer(Modifier.height(16.dp))
    Panel {
        Text("2  选择模式",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=mode==SessionMode.COLLECTION,onClick={mode=SessionMode.COLLECTION},label={Text("采集")},modifier=Modifier.weight(1f))
            FilterChip(selected=mode==SessionMode.SERVICE,onClick={mode=SessionMode.SERVICE},label={Text("在线服务")},modifier=Modifier.weight(1f))
        }
        Text(if(mode==SessionMode.COLLECTION) "眼镜定时拍照并保存，分批同步到手机。" else "眼镜本地判断变化，触发后传图并结合历史提供服务。",color=Quiet,style=MaterialTheme.typography.bodyMedium)
        if(mode==SessionMode.SERVICE) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("强制服务",style=MaterialTheme.typography.titleSmall)
                Switch(checked=forceService,onCheckedChange={forceService=it})
            }
            Text("每次调用都要求模型给出服务，用来批量产出演示素材。这一条走的是另一份提示词，冻结契约里的提示词哈希不同，导出里能一眼分清。",
                style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
        if(mode==SessionMode.COLLECTION) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(5_000L to "5秒",30_000L to "30秒",60_000L to "1分钟",120_000L to "2分钟").forEach {(value,label)->
                    FilterChip(selected=interval==value,onClick={interval=value},label={Text(label,style=MaterialTheme.typography.labelMedium)},modifier=Modifier.weight(1f))
                }
            }
            if(batching) OutlinedTextField(batch,{batch=it.filter(Char::isDigit).take(3)},label={Text("每批同步张数（1–500）")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            else TextButton(onClick={batching=true}) {Text("每 $batch 张同步 · 修改",style=MaterialTheme.typography.bodySmall)}
        }
        if(naming) OutlinedTextField(alias,{alias=it.take(80)},singleLine=true,label={Text("记录名称（可选）")},placeholder={Text("例如：午间散步")},
            modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done))
        else TextButton(onClick={naming=true}) {Text("按时间命名 · 修改名称",style=MaterialTheme.typography.bodySmall)}
        if(mode==SessionMode.SERVICE && !state.modelKeyPresent) Text("请先在设置中填写模型 API Key。",color=MaterialTheme.colorScheme.error)
        val canStart=!state.busy && !preparing && (batch.toIntOrNull() in 1..500) && (mode==SessionMode.COLLECTION || state.modelKeyPresent)
        PrimaryButton(if(preparing) "正在准备…" else if(!state.peerReady) "连接眼镜" else if(mode==SessionMode.COLLECTION) "开始采集" else "开始在线服务",canStart) {
            if(!state.peerReady) connect() else actions.start(SessionLaunchOptions(
                alias=alias.ifBlank {SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date())},mode=mode,captureIntervalMs=interval,syncBatchSize=batch.toInt(),
                demoMode=false,historyIndex=history?.publicIndex,collectionHistorySessionId=history?.sourceSessionId,
                collectionHistoryImageLimit=history?.imageLimit ?: 0,forceService=mode==SessionMode.SERVICE && forceService))
        }
        state.runtime.error?.let {Text(sessionError(it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun HistoryImportSheet(state: OperatorViewState,actions: OperatorActions,onDismiss: ()->Unit) {
    var source by rememberSaveable {mutableStateOf(if(state.collections.isEmpty()) "R6" else "LOCAL")}
    var selectedId by rememberSaveable {mutableStateOf(state.collections.firstOrNull()?.sessionId)}
    // 0 seeds the session with the whole record; the model request still sends only the last four frames.
    var limit by rememberSaveable {mutableIntStateOf(0)}
    var index by rememberSaveable {mutableIntStateOf(0)}
    val selected=state.collections.firstOrNull {it.sessionId==selectedId}
    LaunchedEffect(source,index) {if(source=="R6" && state.dataset!=null) actions.previewHistory(index)}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Canvas) {
        Column(Modifier.fillMaxHeight(0.88f).padding(horizontal=24.dp)) {
            Text("导入历史",style=MaterialTheme.typography.headlineMedium)
            Text("仅加载图片与合成数据，完成后再选择运行模式。",color=Quiet,style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(source=="LOCAL",{source="LOCAL"},label={Text("手机记录")})
                FilterChip(source=="R6",{source="R6"},label={Text("内置历史数据")})
            }
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(source=="LOCAL") {
                    if(state.collections.isEmpty()) item {Text("还没有可导入的记录，请先完成一次采集。",color=Quiet)}
                    state.collections.forEach {entry->item {
                        FilterChip(selectedId==entry.sessionId,{selectedId=entry.sessionId},label={Column {
                            Text(entry.participantAlias)
                            Text("${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(entry.startedAtEpochMs))} · ${entry.validImages} 张图片 · ${entry.contextCount} 条合成数据",style=MaterialTheme.typography.bodySmall)
                        }},modifier=Modifier.fillMaxWidth())
                    }}
                    if(selected!=null) item {
                        Text("作为历史的范围",style=MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            FilterChip(limit<=0,{limit=0},label={Text("整条记录 · ${selected.validImages} 张")})
                            FilterChip(limit==PARTIAL_HISTORY,{limit=PARTIAL_HISTORY},label={Text("最近 $PARTIAL_HISTORY 张")})
                        }
                        Text("所选记录的全部图片与合成数据都会载入历史；模型每次请求仍只发送最近 4 张画面。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                } else {
                    item {
                        Text("选择历史截止位置",style=MaterialTheme.typography.titleMedium)
                        val total=state.dataset?.anchors ?: 1
                        Slider(index.toFloat(),{index=it.toInt()},valueRange=0f..(total-1).coerceAtLeast(1).toFloat(),enabled=state.dataset!=null)
                        Text("${index+1} / $total",color=Quiet)
                    }
                    state.historyPreview?.takeIf {it.index==index}?.let {preview->item {Panel {
                        Text(preview.sourceTime.replace("T"," ").removeSuffix("Z"))
                        Text("${preview.observationCount} 条历史，图片加载最近 4 张",color=Quiet)
                        preview.recent.forEach {Text(it,style=MaterialTheme.typography.bodySmall,maxLines=4)}
                    }}}
                }
            }
            PrimaryButton(if(state.busy) "正在加载…" else "加载为历史",!state.busy && (if(source=="LOCAL") selected!=null else state.dataset!=null)) {
                actions.loadHistory(if(source=="LOCAL") selectedId else null,if(source=="R6") index else null,limit,onDismiss)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SyntheticContextSheet(state: OperatorViewState,actions: OperatorActions,onDismiss: ()->Unit) {
    var type by rememberSaveable {mutableStateOf(SyntheticContextType.EMAIL)}
    var title by rememberSaveable {mutableStateOf("")};var body by rememberSaveable {mutableStateOf("")}
    var current by rememberSaveable {mutableStateOf(true)};var display by rememberSaveable {mutableStateOf(true)}
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Canvas) {
      Column(Modifier.fillMaxHeight(0.93f).imePadding()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("synthetic-context-form"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {SectionTitle("添加合成情境","PUBLIC CONTEXT")
                Text("内容会作为当前时刻收到的新信息，参与后续主动服务。",style=MaterialTheme.typography.bodyMedium,color=Quiet)}
            item {Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                SyntheticContextType.entries.forEach {value->FilterChip(selected=type==value,onClick={type=value},label={Text(value.label)})}
            }}
            item {OutlinedTextField(title,{title=it.take(80)},label={Text(if(type==SyntheticContextType.CALENDAR) "日程标题" else "标题")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("context-title"),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))}
            item {OutlinedTextField(body,{body=it.take(4000)},label={Text("具体内容")},placeholder={Text(if(type==SyntheticContextType.CALENDAR) "例如：今天 15:30 在 B302 开组会，请提前带好电脑。" else "例如：会议地点已改为 B302，请出发前确认。")},
                minLines=4,maxLines=8,modifier=Modifier.fillMaxWidth().testTag("context-body"))}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("更新当前任务",modifier=Modifier.weight(1f));Switch(current,{current=it})}
                Text(if(current) "作为当前任务的新进展，更新已有信息。" else "建立一项独立的新任务。",style=MaterialTheme.typography.bodySmall,color=Quiet)}
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("同时在眼镜显示情境卡",modifier=Modifier.weight(1f));Switch(display,{display=it})}}
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp)) {
            PrimaryButton("加入当前会话",title.isNotBlank() && body.isNotBlank() && !state.busy) {actions.addContext(SyntheticContextInput(type,title,body,current,display),onDismiss)}
        }
      }
    }
}
