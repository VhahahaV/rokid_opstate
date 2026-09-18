package ai.opstate.rokid.phone.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opstate.rokid.phone.review.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun SessionReviewScreen(review: SessionReview,trial: TrialReview?,busy: Boolean,message: String?,
    back: ()->Unit,openTrial: (TrialSummary)->Unit,export: ()->Unit,delete: ()->Unit,refresh: ()->Unit,dismissMessage: ()->Unit,logs: ()->Unit,
    addContext: (String,String,String,Long)->Unit={_,_,_,_->},importContext: ()->Unit={},replayCard: (String)->Unit={},demoFrames: ()->Unit={}) {
    val overviewScroll=rememberLazyListState()
    val trialScroll=key(trial?.summary?.anchorId) { rememberLazyListState() }
    var filter by rememberSaveable(review.session.sessionId) { mutableStateOf("全部") }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedPhoto by remember {mutableStateOf<String?>(null)}
    // Newest-first is the default because that is how the captures were already listed.
    var descending by rememberSaveable(review.session.sessionId) { mutableStateOf(true) }
    var insertOpen by remember { mutableStateOf(false) }
    val collection=review.session.scenarioId=="local-collection-v1"
    val timeline=remember(review.photos,review.contexts,descending) {timelineItems(review.photos,review.contexts,descending)}
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it);dismissMessage() } }
    BackHandler(onBack=back)
    Scaffold(containerColor=Canvas,snackbarHost={SnackbarHost(snackbar)},topBar={
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=back) { Icon(Icons.Default.ArrowBack,"返回") }
                Text(if(trial==null) "照片与记录" else "试次详情",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                Tag(if(collection) "采集" else "在线服务",Quiet)
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("review-content"),state=if(trial==null) overviewScroll else trialScroll,contentPadding=PaddingValues(24.dp,8.dp,24.dp,32.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(trial!=null) {
                item { TrialHeader(trial) }
                item { TrialImage(trial) }
                item { Panel {
                    SectionTitle("模型输出","DECISION")
                    val service=trial.decision
                    Text(service?.string("title") ?: if(trial.summary.decision=="NO_SERVICE") "保持沉默" else "尚无有效决策",style=MaterialTheme.typography.titleLarge)
                    service?.string("body")?.let {Text(it,style=MaterialTheme.typography.bodyLarge)}
                    Text(if(review.session.conditionCode=="CONTRACT_REPLAY") "这是协议回放结果，未向眼镜投递，也未生成显示回执。" else deliveryLabel(trial.summary.delivery),style=MaterialTheme.typography.bodySmall,color=Quiet)
                    val evidence=service?.get("evidence_observation_ids")?.jsonArray.orEmpty()
                    if(evidence.isNotEmpty()) {
                        HorizontalDivider(color=Line)
                        Text("证据引用 · ${evidence.size}",style=MaterialTheme.typography.labelLarge)
                        evidence.forEach {Text(it.jsonPrimitive.content,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Quiet)}
                    }
                } }
                item { Panel {
                    SectionTitle("请求上下文","CAUSAL INPUT")
                    val observations=trial.request?.get("observations")?.jsonArray.orEmpty()
                    SettingRow("可见观察",observations.size.toString())
                    SettingRow("已提交回执",trial.request?.get("receipts")?.jsonArray?.size?.toString() ?: "0")
                    HorizontalDivider(color=Line)
                    observations.takeLast(4).forEach { element ->
                        val row=element.jsonObject
                        Text(row.string("content").orEmpty(),style=MaterialTheme.typography.bodyMedium)
                        Text(row.string("observation_id").orEmpty(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                    Text("上方展示最近 4 条观察；完整请求随会话导出。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                } }
                item { JsonPreview("请求原文",trial.request) }
                if(trial.providerRaw==null || trial.raw?.string("raw_format")=="OPSTATE_V2_NORMALIZED")
                    item { JsonPreview(if(trial.providerRaw!=null) "标准化响应" else "响应原文",responseContent(trial.raw)) }
                if(trial.providerRaw!=null) item { JsonPreview("模型 API 原文",responseContent(trial.providerRaw)) }
            } else if(collection) {
                item {Panel {
                    Text(review.session.participantAlias,style=MaterialTheme.typography.headlineMedium)
                    Text("${review.photos.size} 张照片 · ${review.contexts.size} 条情境 · ${photoTime(review.session.startedAtEpochMs)}",color=Quiet)
                    Text("按时间排版；情境与照片同列显示。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(export,enabled=!busy && review.verified && review.phase in setOf("STOPPED","PAUSED")) {Text("导出原图与记录")}
                        TextButton(refresh,enabled=!busy) {Text("刷新")}
                    }
                }}
                item { TimelineToolbar(descending,{descending=!descending},insertOpen={insertOpen=true},importContext,enabled=!busy) }
                if(review.photos.isEmpty() && review.contexts.isEmpty()) item {Panel {Text("还没有保存的照片",color=Quiet)}}
                timeline(timeline) {selectedPhoto=it}
                item {Column {
                    Text(if(review.verified) "原图与元数据已保存在手机" else "记录完整性校验失败，请查看运行日志",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(logs) {Text("运行日志")}
                    TextButton({confirmDelete=true},enabled=!busy && review.phase=="STOPPED") {Text("删除这次记录",color=MaterialTheme.colorScheme.error)}
                }}
            } else {
                // What you came to do leads: the summary, then the modules that act on the record, and
                // only then the raw timeline. Opening a record is usually about exporting it or reading
                // its trials, not about scrolling back through photographs.
                item { Surface(shape=RoundedCornerShape(20.dp),color=Ink) {
                    Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text(if(review.session.conditionCode=="CONTRACT_REPLAY") "CONTRACT REPLAY" else "FIELD SESSION",style=MaterialTheme.typography.labelSmall,color=Canvas.copy(alpha=.7f))
                        Text(review.session.scenarioId,style=MaterialTheme.typography.headlineMedium,color=Canvas)
                        Text("${review.session.participantAlias} · ${time(review.session.startedAtEpochMs)}",style=MaterialTheme.typography.bodyMedium,color=Canvas.copy(alpha=.8f))
                        HorizontalDivider(color=Canvas.copy(alpha=.15f))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            WhiteMetric(review.trials.size.toString(),"试次")
                            WhiteMetric(review.trials.count {it.decision=="SERVICE"}.toString(),"SERVICE")
                            WhiteMetric(review.trials.count {it.decision=="NO_SERVICE"}.toString(),"NO SERVICE")
                        }
                    }
                } }
                item { Panel {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("记录完整性",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        Tag(if(review.verified) "审计链通过" else "校验失败",if(review.verified) Success else MaterialTheme.colorScheme.error)
                    }
                    SettingRow("会话状态",if(review.interrupted) "进程中断 · 已收尾" else when(review.phase) {"STOPPED"->"已结束";"PAUSED"->"已暂停";else->"进行中"})
                    SettingRow("保存的观察",(review.counts["observations"] ?: 0).toString())
                    SettingRow("真实显示回执",(review.counts["receipts"] ?: 0).toString())
                    PrimaryButton("导出完整记录",!busy && review.verified && review.phase in setOf("STOPPED","PAUSED"),export)
                    Text("ZIP 包含请求、响应、图像、审计链和 SHA-256 清单。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(onClick=demoFrames,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("生成演示帧")}
                    Text("把每次真实显示过的主动服务渲染成「当时的画面 + 卡片」，用于挑选和展示。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    TextButton(logs,modifier=Modifier.fillMaxWidth()) {Text("查看运行日志")}
                } }
                item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    SectionTitle("试次时间线","TRIALS")
                    Spacer(Modifier.weight(1f));TextButton(onClick=refresh,enabled=!busy) {Text("刷新")}
                } }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("全部","服务","沉默","待查").forEach { label -> FilterChip(selected=filter==label,onClick={filter=label},label={Text(label)}) }
                } }
                val trials=review.trials.filter {when(filter) {"服务"->it.decision=="SERVICE";"沉默"->it.decision=="NO_SERVICE";"待查"->it.decision==null;else->true}}
                if(trials.isEmpty()) item { Panel {Text("这里还没有试次",style=MaterialTheme.typography.titleMedium);Text("完成评估后，时间线会显示每次输入和决策。",style=MaterialTheme.typography.bodySmall,color=Quiet)} }
                items(trials,key={it.anchorId}) { row ->
                    Surface(Modifier.fillMaxWidth().testTag("trial-${row.anchorId}").clickable(enabled=!busy) {openTrial(row)},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(time(row.at,"MM.dd  HH:mm"),style=MaterialTheme.typography.labelMedium,color=Quiet)
                                    Tag(decisionLabel(row.decision),if(row.decision=="SERVICE") Success else Quiet)
                                }
                                Text(row.title ?: row.anchorId.substringAfterLast('.').replace('-',' '),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text("${row.episode} · ${row.evidenceCount} 条证据 · ${row.latencyMs?.let {"$it ms"} ?: "未返回"}",style=MaterialTheme.typography.bodySmall,color=Quiet)
                                if(row.decision=="SERVICE" && row.title!=null) TextButton(onClick={replayCard(row.anchorId)},enabled=!busy,contentPadding=PaddingValues(0.dp)) {Text("回放到眼镜")}
                            }
                            Icon(Icons.Default.ArrowForward,"查看试次",Modifier.size(18.dp),tint=Quiet)
                        }
                    }
                }
                if(review.photos.isNotEmpty() || review.contexts.isNotEmpty()) {
                    item {Text("照片与情境 · ${review.photos.size} 张 / ${review.contexts.size} 条",style=MaterialTheme.typography.titleLarge)}
                    item { TimelineToolbar(descending,{descending=!descending},insertOpen={insertOpen=true},importContext,enabled=!busy) }
                    timeline(timeline) {selectedPhoto=it}
                }
                item { Column {
                    SelectionContainer {Text(review.session.sessionId,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,color=Quiet)}
                    TextButton(onClick={confirmDelete=true},enabled=!busy && review.phase=="STOPPED") {Text("删除此会话",color=MaterialTheme.colorScheme.error)}
                } }
            }
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除本地会话？")},text={Text("将删除这次会话的观察、图像、请求、响应及本地导出缓存。已另存的 ZIP 不受影响。")},
        confirmButton={TextButton(onClick={confirmDelete=false;delete()}) {Text("删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDelete=false}) {Text("保留")}})
    selectedPhoto?.let {PhotoViewer(review.photos,it,{selectedPhoto=it},{selectedPhoto=null})}
    if(insertOpen) SyntheticContextDialog(startedAt=review.session.startedAtEpochMs,endedAt=review.session.endedAtEpochMs,
        onConfirm={ type,title,body,at -> addContext(type,title,body,at);insertOpen=false },
        onDismiss={insertOpen=false})
}
@Composable private fun WhiteMetric(value: String,label: String) {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
    Text(value,fontSize=28.sp,fontWeight=FontWeight.SemiBold,fontFamily=FontFamily.Monospace,color=Canvas)
    Text(label,style=MaterialTheme.typography.labelSmall,color=Canvas.copy(alpha=.7f))
}}
@Composable private fun TrialHeader(trial: TrialReview) {Panel {
    Tag(decisionLabel(trial.summary.decision),if(trial.summary.decision=="SERVICE") Success else Quiet)
    Text(trial.summary.title ?: trial.summary.anchorId.substringAfterLast('.').replace('-',' '),style=MaterialTheme.typography.headlineMedium)
    Text("${time(trial.summary.at)} · ${trial.summary.episode}",style=MaterialTheme.typography.bodySmall,color=Quiet)
    Text(trial.summary.anchorId,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,color=Quiet)
}}
private data class PreviewImage(val loading: Boolean,val bitmap: androidx.compose.ui.graphics.ImageBitmap?=null)
@Composable private fun TrialImage(trial: TrialReview) {
    val preview by produceState(PreviewImage(true),trial.image) {
        val bitmap=withContext(Dispatchers.IO) {
            trial.image?.let { file ->
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})?.asImageBitmap()
            }
        }
        value=PreviewImage(false,bitmap)
    }
    Panel {
        SectionTitle("当前图像","CURRENT FRAME")
        preview.bitmap?.let { frame ->
            BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                val width=minOf(maxWidth,300.dp*(frame.width.toFloat()/frame.height))
                val height=width*(frame.height.toFloat()/frame.width)
                Image(frame,"该试次的实际请求图像",Modifier.width(width).height(height).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Fit)
            }
        }
            ?: Text(if(trial.image==null) "此试次未保存当前图像" else if(preview.loading) "正在读取图像…" else "图片无法预览，可导出原文件检查",style=MaterialTheme.typography.bodySmall,color=Quiet)
        Text(trial.request?.string("current_asset_id").orEmpty(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall,color=Quiet)
    }
}
@Composable private fun JsonPreview(title: String,value: JsonElement?) {
    var expanded by remember { mutableStateOf(false) }
    Panel {
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) { Text(if(expanded) "收起$title" else "查看$title") }
        if(expanded) {
            val text=remember(value) { value?.let {Json { prettyPrint=true }.encodeToString(JsonElement.serializer(),it)} ?: "未记录" }
            SelectionContainer {Text(text.take(16000),style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)}
            if(text.length>16000) Text("预览已截取。导出 ZIP 包含完整原文。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
    }
}
private fun responseContent(raw: JsonObject?): JsonElement?=raw?.string("raw_response_json")?.let {text->
    runCatching {Json.parseToJsonElement(text)}.getOrElse {JsonPrimitive(text)}
}
private fun time(epoch: Long,pattern: String="yyyy.MM.dd  HH:mm:ss")=SimpleDateFormat(pattern,Locale.CHINA).format(Date(epoch))
internal fun decisionLabel(value: String?)=when(value) {"SERVICE"->"服务";"NO_SERVICE"->"沉默";else->"待查"}
internal fun deliveryLabel(value: String?)=when(value) {"DISPLAY_VISIBLE"->"已在眼镜实际显示";"REPLAY_NOT_DELIVERED"->"回放 · 未投递";"NO_SERVICE_SILENCE"->"眼镜保持沉默";"SUPPRESSED_BY_RECEIPT"->"已抑制重复服务";null->"未记录投递结果";else->value}

/** Order toggle plus the two ways context enters a record: typed here, or imported from a file. */
@Composable private fun TimelineToolbar(descending: Boolean,onToggle: ()->Unit,insertOpen: ()->Unit,importContext: ()->Unit,enabled: Boolean) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if(descending) "最新在前" else "最早在前",style=MaterialTheme.typography.labelLarge,color=Quiet)
        TextButton(onClick=onToggle) {Text(if(descending) "改为正序" else "改为倒序")}
        Spacer(Modifier.weight(1f))
        TextButton(onClick=insertOpen,enabled=enabled) {Text("插入情境")}
        TextButton(onClick=importContext,enabled=enabled) {Text("导入")}
    }
}

/**
 * Context is authored relative to the recording: a default timestamp inside the session, adjustable,
 * because the note has to land between the captures it belongs with.
 */
@Composable private fun SyntheticContextDialog(startedAt: Long,endedAt: Long?,onConfirm: (String,String,String,Long)->Unit,onDismiss: ()->Unit) {
    val types=listOf("EMAIL" to "邮件","CALENDAR" to "日程","MESSAGE" to "消息","NOTE" to "备忘")
    var type by remember { mutableStateOf("MESSAGE") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf(0L) }
    val span=(((endedAt ?: startedAt)-startedAt)/60000L).coerceAtLeast(1L)
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("插入合成情境")},
        text={ Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                types.forEach { (value,label) ->
                    FilterChip(selected=type==value,onClick={type=value},label={Text(label)})
                }
            }
            OutlinedTextField(title,{title=it.take(80)},singleLine=true,label={Text("标题（≤80）")})
            OutlinedTextField(body,{body=it.take(4000)},label={Text("正文（≤4000）")},minLines=3)
            Text("插入位置：记录开始后 ${minutes} 分钟（全程约 ${span} 分钟）",style=MaterialTheme.typography.bodySmall,color=Quiet)
            Slider(minutes.toFloat(),{minutes=it.toLong()},valueRange=0f..span.toFloat(),steps=0)
        } },
        confirmButton={TextButton(onClick={onConfirm(type,title,body,startedAt+minutes*60000L)},enabled=title.isNotBlank()) {Text("插入")}},
        dismissButton={TextButton(onClick=onDismiss) {Text("取消")}},
    )
}
