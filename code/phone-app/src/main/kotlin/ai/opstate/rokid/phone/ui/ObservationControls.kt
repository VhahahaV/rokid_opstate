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

/** Where the next shot stands, in the words a collection and a service can both use. */
private fun cadenceLine(runtime: ai.opstate.rokid.phone.runtime.RuntimeViewState)=when {
    runtime.phase=="HISTORY_READY"->"开始实时服务后启用"
    runtime.phase=="PAUSED"->"已随会话暂停"
    !runtime.automaticCapture->"自动采集已关闭"
    runtime.phase=="CAPTURING"->"正在拍摄第 ${runtime.liveCaptures+1} 张"
    runtime.nextCaptureInMs==0L->"等待当前拍摄完成"
    else->"${((runtime.nextCaptureInMs ?: 0)+999)/1000} 秒后拍摄下一张"
}

@Composable internal fun ObservationControls(state: OperatorViewState,actions: OperatorActions,addContext: ()->Unit) {Panel {
    val runtime=state.runtime
    val nextCapture=runtime.nextCaptureInMs
    var captureSettings by remember {mutableStateOf(false)}
    Text(if(runtime.mode==SessionMode.COLLECTION) "最近的照片" else "实时观察与情境",style=MaterialTheme.typography.titleMedium)
    // This session's frames. A session seeded from a record carries that record's photographs as its
    // history, and they are not what the glasses are sending now: the strip is about the capture that
    // is happening, and the history has the album for when it is wanted.
    val captured=state.livePhotos.filterNot {it.historical}
    val historical=state.livePhotos.size-captured.size

    RecentPhotos(captured) {photo->actions.deleteLivePhoto(photo.id)}
    if(historical>0) Text("已载入历史 $historical 张 · 在记录里查看",style=MaterialTheme.typography.bodySmall,color=Quiet)

    HorizontalDivider(color=Line,modifier=Modifier.padding(vertical=14.dp))

    // Capture and upload, in one block and the same words in both modes: the glasses shoot the same way
    // and a batch goes up the same way; what a mode changes is what the phone does with a frame.
    Text("拍摄与上传",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text("每 ${runtime.captureIntervalMs/1000} 秒自动拍照",style=MaterialTheme.typography.bodyLarge)
            Text(cadenceLine(runtime),style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
        Switch(runtime.automaticCapture,actions.automaticCapture,enabled=!state.busy)
    }
    if(runtime.automaticCapture && nextCapture!=null) {
        val progress=(1f-nextCapture.toFloat()/runtime.captureIntervalMs.coerceAtLeast(1).toFloat()).coerceIn(0f,1f)
        LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),color=Success,trackColor=Line)
    }
    if(runtime.glassesCapture) Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("眼镜已拍 ${runtime.glassesCaptures} 张 · 待同步 ${runtime.bufferedPhotos} 张 · 本次已收 ${runtime.liveCaptures-runtime.deletedCaptures} 张",
            style=MaterialTheme.typography.bodyMedium)
        Text(if(runtime.mode==SessionMode.COLLECTION) "攒够 ${runtime.syncBatchSize} 张整批上传。"
            else "每张立刻上传；前端在手机上判断，命中的帧进入评估。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(actions.syncPhotos,enabled=state.peerReady && runtime.bufferedPhotos>0,
            modifier=Modifier.weight(1f).heightIn(min=46.dp),shape=RoundedCornerShape(12.dp)) {
            Text(if(runtime.syncing) "正在同步…" else "立即同步")
        }
        // Short enough to stay on one line beside its neighbour: the two buttons are the only controls
        // of this block, and a wrapped label makes them look like different-sized things.
        OutlinedButton(onClick={captureSettings=true},enabled=!state.busy,
            modifier=Modifier.weight(1f).heightIn(min=46.dp),shape=RoundedCornerShape(12.dp)) {Text("拍摄设置")}
    }
    if(runtime.mode==SessionMode.SERVICE) Text("新情境和关键变化可触发额外观察；同一时间仅处理一次采集。",style=MaterialTheme.typography.bodySmall,color=Quiet)
    if(runtime.mode==SessionMode.COLLECTION) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("有效 ${runtime.validCaptures} · 需重拍 ${runtime.rejectedCaptures}",style=MaterialTheme.typography.bodySmall,color=Quiet)
        runtime.storageFreeBytes?.let {Text("手机剩余 ${storageSize(it)}",style=MaterialTheme.typography.bodySmall,color=Quiet)}
    }

    HorizontalDivider(color=Line,modifier=Modifier.padding(vertical=14.dp))

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
                    Text("每次调用都要求模型给出服务，走另一份提示词。演示用：跳过记录依据和去重检查。",style=MaterialTheme.typography.bodySmall,color=Quiet)
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
    if(captureSettings) CaptureSettingsDialog(runtime,actions.configureCapture) {captureSettings=false}
} }

/**
 * A running capture may change cadence, batch and front end without restarting its history.
 *
 * These are the capture settings, and they are the same set in both modes: the batch is an image count
 * and a batch of them goes up together, in a collection and in a service session alike. Only a frame a
 * trigger produced is exempt from the count, because that is the frame the service is waiting for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CaptureSettingsDialog(runtime: ai.opstate.rokid.phone.runtime.RuntimeViewState,
    apply: (Long,Boolean,Int)->Unit,onDismiss: ()->Unit) {
    var capture by remember(runtime.captureIntervalMs) {mutableLongStateOf(runtime.captureIntervalMs)}
    var frontEnd by remember(runtime.frontEndEnabled) {mutableStateOf(runtime.frontEndEnabled)}
    var batch by remember(runtime.syncBatchSize) {mutableIntStateOf(runtime.syncBatchSize)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("拍照与上传设置")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("拍照频率",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(5_000L to "5秒",15_000L to "15秒",30_000L to "30秒",60_000L to "1分钟",120_000L to "2分钟").forEach {(value,label)->
                    FilterChip(capture==value,{capture=value},label={Text(label)})
                }
            }
            // A collection batches; a service session sends every frame as it is taken.
            if(runtime.mode==SessionMode.COLLECTION) {
                Text("每批同步张数",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "每张",3 to "3 张",6 to "6 张",12 to "12 张",30 to "30 张").forEach {(value,label)->
                        FilterChip(batch==value,{batch=value},label={Text(label)})
                    }
                }
                Text("攒够这个张数，眼镜开始上传；一批传完再等下一批。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            } else Text("在线服务不设批量：每张照片拍完立刻上传，前端在手机上判断。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            if(runtime.mode==SessionMode.SERVICE) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动前端触发")
                        Text("关闭后不再自动因视觉变化上传；手动服务仍可立即触发。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                    Switch(frontEnd,{frontEnd=it})
                }
                Text("30 秒拍照最接近 CVTT 的训练帧距。每张都会上传到手机，命中的帧立刻进入评估。",style=MaterialTheme.typography.bodySmall,color=Quiet)
            }
        }
    },confirmButton={TextButton(onClick={apply(capture,frontEnd,batch);onDismiss()}) {Text("应用")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
