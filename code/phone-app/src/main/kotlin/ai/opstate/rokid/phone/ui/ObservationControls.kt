package ai.opstate.rokid.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.opstate.rokid.phone.runtime.SessionMode

/** One way to start a service by hand, as a row that can say what it does. */
@Composable private fun SourceRow(title: String,hint: String,enabled: Boolean,action: ()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled=enabled,onClick=action)
        .padding(horizontal=12.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if(enabled) Success else Line))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,color=if(enabled) Ink else Quiet)
            Text(hint,style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
    }
}

@Composable internal fun ObservationControls(state: OperatorViewState,actions: OperatorActions,addContext: ()->Unit) {Panel {
    val runtime=state.runtime
    val nextCapture=runtime.nextCaptureInMs
    Text(if(runtime.mode==SessionMode.COLLECTION) "最近的照片" else "实时观察与情境",style=MaterialTheme.typography.titleMedium)
    RecentPhotos(state.livePhotos) {photo->actions.deleteLivePhoto(photo.id)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if(runtime.mode==SessionMode.SERVICE) Text("每分钟自动拍照",style=MaterialTheme.typography.titleMedium)
            Text(when {runtime.phase=="HISTORY_READY"->"开始实时服务后启用";runtime.phase=="PAUSED"->"已随会话暂停";!runtime.automaticCapture->"自动采集已关闭";
                runtime.phase=="CAPTURING"->"正在拍摄第 ${runtime.liveCaptures+1} 张";runtime.nextCaptureInMs==0L->"等待当前拍摄完成";
                else->"${((runtime.nextCaptureInMs ?: 0)+999)/1000} 秒后拍摄下一张"},style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
        if(runtime.mode==SessionMode.SERVICE) Switch(runtime.automaticCapture,actions.automaticCapture,enabled=!state.busy)
    }
    if(runtime.mode==SessionMode.COLLECTION && runtime.automaticCapture && nextCapture!=null) {
        val progress=(1f-nextCapture.toFloat()/runtime.captureIntervalMs.coerceAtLeast(1).toFloat()).coerceIn(0f,1f)
        LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),color=Success,trackColor=Line)
    }
    if(runtime.mode==SessionMode.SERVICE) Text("新情境和关键变化可触发额外观察；同一时间仅处理一次采集。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    if(runtime.mode==SessionMode.COLLECTION) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("有效 ${runtime.validCaptures} · 需重拍 ${runtime.rejectedCaptures}",style=MaterialTheme.typography.bodySmall,color=Quiet)
        runtime.storageFreeBytes?.let {Text("手机剩余 ${storageSize(it)}",style=MaterialTheme.typography.bodySmall,color=Quiet)}
    }
    // One entry point for everything a service can be started by hand. Four controls used to sit here
    // at once and none of them said how they related; now the sources and the mode live together behind
    // one button, and the only thing kept apart is adding a note, which is a different act - it feeds
    // memory rather than triggering an evaluation.
    if(runtime.mode==SessionMode.COLLECTION) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(actions.capture,enabled=runtime.phase=="RUNNING" && state.peerReady && !state.busy,
                modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text("立即拍一张")}
        }
    } else {
        var manual by remember { mutableStateOf(false) }
        val ready=runtime.phase=="RUNNING" && !state.busy
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button({manual=!manual},enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),
                shape=RoundedCornerShape(12.dp)) {Text(if(manual) "收起源选择" else "手动服务")}
            OutlinedButton(addContext,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),
                shape=RoundedCornerShape(12.dp)) {Text("添加合成情境")}
        }
        if(manual) Column(Modifier.fillMaxWidth().background(Canvas,RoundedCornerShape(16.dp))
            .padding(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(bottom=6.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("强制服务",style=MaterialTheme.typography.titleMedium)
                    Text("每次调用都要求模型给出服务，走另一份提示词。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                }
                Switch(runtime.forceService,actions.forceService,enabled=!state.busy)
            }
            HorizontalDivider(color=Line,modifier=Modifier.padding(bottom=12.dp))
            Text("用哪种来源走一遍完整链路",style=MaterialTheme.typography.labelLarge,color=Quiet,
                modifier=Modifier.padding(start=4.dp,bottom=4.dp))
            // Three rows, not three squeezed buttons: what separates these is what they do, and a
            // half-width label cannot say that. Each one keeps its own line of explanation.
            SourceRow("拍照","现在拍一张，从拍摄开始",ready,actions.capture)
            SourceRow("选择照片","用手机上已经存下的照片",ready,actions.pickPhoto)
            SourceRow("合成数据","用已有的一条情境",ready,actions.pickContext)
        }
    }
    if(runtime.latestImagePath!=null) {
        runtime.observationStatus?.let {status->Text(when(status) {
            "IMAGE_TOO_DARK","IMAGE_TOO_BRIGHT","IMAGE_TOO_BLURRY","IMAGE_CORRUPTED"->sessionError(status)
            "COLLECTION_STORAGE_LOW"->sessionError(status)
            "IMAGE_SAVED"->"第 ${runtime.liveCaptures} 张保存完成 · 往返 ${runtime.captureRoundTripMs ?: 0} ms"
            "IMAGE_DELETED"->"照片已删除 · 本次采集已移除 ${runtime.deletedCaptures} 张"
            "EVALUATING","PACKET_QUEUED"->"在线检测已触发，观察包进入评估"
            "MOTION_HIGH"->"照片已保存，等待眼镜稳定后评估"
            "RECENT_INTERACTION","DISPLAY_BUSY"->"照片已保存，等待本次交互结束"
            "CAPTURING"->"正在请求第 ${runtime.liveCaptures+1} 张照片"
            else->"照片已保存，等待下一次合适的评估时机"
        },style=MaterialTheme.typography.bodySmall,color=Quiet)}
    }
    if(runtime.mode==SessionMode.SERVICE) {
    HorizontalDivider(color=Line)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text("眼镜演示视图",style=MaterialTheme.typography.titleMedium);Text(if(runtime.glassesDemoMode) "展示阶段、情境与计数" else "安静视图 · 无服务时保持沉默",style=MaterialTheme.typography.bodySmall,color=Quiet)}
        Switch(runtime.glassesDemoMode,actions.demoMode,enabled=!state.busy)
    }
    }
} }
