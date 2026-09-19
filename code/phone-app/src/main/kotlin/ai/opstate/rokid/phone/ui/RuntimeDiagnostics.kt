package ai.opstate.rokid.phone.ui

import ai.opstate.rokid.phone.data.JournalEntity
import ai.opstate.protocol.StudyWire
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RuntimeLogEntry(val sequence: Long,val at: Long,val type: String,val detail: String)

/** Operational fields only; frozen configs, prompts, scheduler-private notes and credentials stay out of this view. */
fun JournalEntity.toRuntimeLogEntry(): RuntimeLogEntry {
    val payload=StudyWire.json.parseToJsonElement(canonicalJson).jsonObject
    val allowed=setOf("code","status","reason","reason_codes","phase","capture_id","request_id","packet_id","anchor_id","decision_id",
        "channel","quality_valid","model_latency_ms","decision_to_visible_ms","packet_to_visible_ms","position_ms","from_index","to_index",
        // A deletion names the observation it retires, so the log line says which photograph went.
        "observation_id")
    val detail=payload.filterKeys {it in allowed}.entries.joinToString("\n") {(key,value)->"$key: ${if(value is JsonPrimitive) value.content else value}"}
    return RuntimeLogEntry(sequence,eventAtEpochMs,eventType,detail)
}

@Composable internal fun RuntimeDiagnostics(state: OperatorViewState,actions: OperatorActions) {Panel {
    val runtime=state.runtime
    SectionTitle("运行详情","RUNTIME")
    if(runtime.shadowInference) Tag("仅记录结果 · 不投递服务",Quiet)
    Text("当前候选任务 · ${runtime.candidates.size}",style=MaterialTheme.typography.titleMedium)
    runtime.candidates.forEach {candidate->
        Text("${candidate.handle} · 版本 ${candidate.revision}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
    HorizontalDivider(color=Line)
    fun duration(ms: Long?)=ms?.let {"%,d ms".format(it)} ?: "—"
    SettingRow("最近采集往返",duration(runtime.captureRoundTripMs))
    SettingRow("指令确认往返",duration(runtime.commandAckMs))
    SettingRow("眼镜拍照处理",duration(runtime.cameraDurationMs))
    SettingRow("图像传输",duration(runtime.transferDurationMs))
    SettingRow("最近模型耗时",duration(runtime.modelLatencyMs))
    SettingRow("决策到实际显示",duration(runtime.decisionToVisibleMs))
    SettingRow("观察包到实际显示",duration(runtime.packetToVisibleMs))
    Text(runtime.latestPacketId ?: "尚未生成观察包",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=2,overflow=TextOverflow.Ellipsis)
    TextButton(onClick={runtime.sessionId?.let(actions.openLogs)},modifier=Modifier.fillMaxWidth()) {Text("查看运行日志")}
} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RuntimeLogSheet(entries: List<RuntimeLogEntry>,busy: Boolean,refresh: ()->Unit,close: ()->Unit) {
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Canvas) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("runtime-log"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {SectionTitle("运行日志","RECENT EVENTS");TextButton(refresh,enabled=!busy) {Text("刷新")}}}
            item {Text("最近 100 条运行、采集和投递记录。完整日志保存在会话导出中。",style=MaterialTheme.typography.bodySmall,color=Quiet)}
            if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(entries.isEmpty() && !busy) item {Text("暂无运行记录",color=Quiet)}
            items(entries,key={it.sequence}) {entry->Panel {
                Text(SimpleDateFormat("HH:mm:ss.SSS",Locale.getDefault()).format(Date(entry.at)),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelMedium,color=Quiet)
                Text(entry.type,style=MaterialTheme.typography.titleMedium)
                if(entry.detail.isNotBlank()) Text(entry.detail,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)
            }}
        }
    }
}
