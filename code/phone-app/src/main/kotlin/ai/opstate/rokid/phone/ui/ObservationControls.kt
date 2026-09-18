package ai.opstate.rokid.phone.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ai.opstate.rokid.phone.runtime.SessionMode

@Composable internal fun ObservationControls(state: OperatorViewState,actions: OperatorActions,addContext: ()->Unit) {Panel {
    val runtime=state.runtime
    val nextCapture=runtime.nextCaptureInMs
    Text(if(runtime.mode==SessionMode.COLLECTION) "最近的照片" else "实时观察与情境",style=MaterialTheme.typography.titleMedium)
    RecentPhotos(state.livePhotos)
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
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(actions.capture,enabled=runtime.phase=="RUNNING" && state.peerReady && !state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text(if(runtime.mode==SessionMode.COLLECTION) "立即拍一张" else "拍照观察一次")}
        if(runtime.mode==SessionMode.SERVICE) Button(addContext,enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)) {Text("添加合成情境")}
    }
    // The same chain, on material that is already on the phone: pick a stored frame or a note and it
    // runs from there to the card exactly as a live capture would. For staging a demonstration without
    // having to point the glasses at anything.
    if(runtime.mode==SessionMode.SERVICE) {
        val ready=runtime.phase=="RUNNING" && !state.busy
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(actions.pickPhoto,enabled=ready,modifier=Modifier.weight(1f).heightIn(min=44.dp),shape=RoundedCornerShape(12.dp)) {Text("选图跑一次")}
            OutlinedButton(actions.pickContext,enabled=ready,modifier=Modifier.weight(1f).heightIn(min=44.dp),shape=RoundedCornerShape(12.dp)) {Text("选情境跑一次")}
        }
        Text("用已有的照片或合成情境走一遍完整链路，不用现场拍摄。",
            style=MaterialTheme.typography.bodySmall,color=Quiet)
    }
    if(runtime.latestImagePath!=null) {
        runtime.observationStatus?.let {status->Text(when(status) {
            "IMAGE_TOO_DARK","IMAGE_TOO_BRIGHT","IMAGE_TOO_BLURRY","IMAGE_CORRUPTED"->sessionError(status)
            "COLLECTION_STORAGE_LOW"->sessionError(status)
            "IMAGE_SAVED"->"第 ${runtime.liveCaptures} 张保存完成 · 往返 ${runtime.captureRoundTripMs ?: 0} ms"
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
