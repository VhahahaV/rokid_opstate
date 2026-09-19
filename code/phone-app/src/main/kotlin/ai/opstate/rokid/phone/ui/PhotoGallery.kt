package ai.opstate.rokid.phone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.review.ContextEntry
import ai.opstate.rokid.phone.review.TimelineItem
import ai.opstate.rokid.phone.review.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun photoTime(at: Long,pattern: String="MM月dd日 HH:mm:ss")=if(at>0) SimpleDateFormat(pattern,Locale.CHINA).format(Date(at)) else "未记录"

@Composable internal fun PhotoPreview(photo: CapturedPhoto,modifier: Modifier=Modifier,full: Boolean=false) {
    var loading by remember(photo.file.path) {mutableStateOf(true)}
    val bitmap by produceState<ImageBitmap?>(null,photo.file.path,full) {
        value=withContext(Dispatchers.IO) {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(photo.file.path,bounds)
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>(if(full) 2048 else 480)) sample*=2
            BitmapFactory.decodeFile(photo.file.path,BitmapFactory.Options().apply {inSampleSize=sample})?.asImageBitmap()
        }
        loading=false
    }
    Box(modifier.background(if(full) Color.Black else Line),contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it,"拍摄于 ${photoTime(photo.capturedAt)}",Modifier.fillMaxSize(),contentScale=if(full) ContentScale.Fit else ContentScale.Crop)}
            ?: Text(if(loading) "加载中…" else "原图无法读取",color=if(full) Color.White else Quiet,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PhotoTile(photo: CapturedPhoto,modifier: Modifier,open: ()->Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick=open).testTag("photo-${photo.id}")) {
        PhotoPreview(photo,Modifier.fillMaxSize())
        Text(photoTime(photo.capturedAt,"HH:mm:ss"),color=Color.White,style=MaterialTheme.typography.labelSmall,
            modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha=.45f)).padding(6.dp))
        // The shot that produced a delivered service. Marked hard, because in a grid of thumbnails it is
        // the one that matters and the one you would otherwise have to open every image to find.
        if(photo.served) Surface(Modifier.align(Alignment.TopStart).padding(4.dp),color=Success,shape=RoundedCornerShape(4.dp)) {
            Text("主动服务",style=MaterialTheme.typography.labelSmall,color=Color.White,modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp))
        }
        if(photo.historical) Text("历史",style=MaterialTheme.typography.labelSmall,color=Color.White,
            modifier=Modifier.align(Alignment.TopEnd).background(Ink.copy(alpha=.75f)).padding(4.dp))
    }
}

@Composable internal fun RecentPhotos(photos: List<CapturedPhoto>,delete: ((CapturedPhoto)->Unit)?=null) {
    var selectedId by remember {mutableStateOf<String?>(null)}
    if(photos.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)).background(Canvas),contentAlignment=Alignment.Center) {
            Text("照片同步后在此显示\n可点击「立即同步」提前查看",color=Quiet,style=MaterialTheme.typography.bodySmall)
        }
    } else {
        PhotoPreview(photos.first(),Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).clickable {selectedId=photos.first().id})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${photos.size} 张 · 最新 ${photoTime(photos.first().capturedAt,"HH:mm:ss")}",style=MaterialTheme.typography.bodySmall,color=Quiet)
            Text("点开查看详情",style=MaterialTheme.typography.bodySmall,color=Quiet)
        }
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(photos,key={it.id}) {photo->PhotoTile(photo,Modifier.size(84.dp)) {selectedId=photo.id}}
        }
    }
    selectedId?.let {id->PhotoViewer(photos,id,{selectedId=it},{selectedId=null},delete=delete)}
}

internal fun LazyListScope.photoGrid(photos: List<CapturedPhoto>,open: (String)->Unit) {
    photos.groupBy {photoTime(it.capturedAt,"yyyy年MM月dd日")}.forEach {(day,entries)->
        item(key="day-$day") {Text("$day · ${entries.size} 张",style=MaterialTheme.typography.titleSmall,color=Quiet)}
        items(entries.chunked(3),key={"row-${it.first().id}"}) {row->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach {photo->PhotoTile(photo,Modifier.weight(1f).aspectRatio(1f)) {open(photo.id)}}
                repeat(3-row.size) {Spacer(Modifier.weight(1f))}
            }
        }
    }
}

/** A synthetic context note as it sits between captures in the timeline. */
@Composable internal fun ContextNoteCard(context: ContextEntry) {
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("context-${context.id}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Tag(if(context.type=="NOTE") "备忘" else context.type,Ink)
                Spacer(Modifier.width(8.dp))
                Text(photoTime(context.occurredAt),style=MaterialTheme.typography.bodySmall,color=Quiet)
            }
            Text(context.title,style=MaterialTheme.typography.titleMedium)
            Text(context.text,style=MaterialTheme.typography.bodyMedium,color=Quiet)
        }
    }
}

/**
 * Captures and context in one visual order. Consecutive captures share a grid slot, so a long run stays
 * compact, while a context note takes a full row at the point it belongs.
 */
internal fun LazyListScope.timeline(items: List<TimelineItem>,open: (String)->Unit) {
    val shotsPerDay=items.filterIsInstance<TimelineItem.Shots>().flatMap {it.photos}.groupBy {photoTime(it.capturedAt,"yyyy年MM月dd日")}
    val notesPerDay=items.filterIsInstance<TimelineItem.Note>().map {it.context}.groupBy {photoTime(it.occurredAt,"yyyy年MM月dd日")}
    var lastDay: String?=null
    items.forEachIndexed { index,item ->
        val day=photoTime(item.at,"yyyy年MM月dd日")
        if(day!=lastDay) {
            lastDay=day
            item(key="day-$day-at-$index") {
                val notes=notesPerDay[day]?.size ?: 0
                Text("$day · ${shotsPerDay[day]?.size ?: 0} 张${if(notes>0) " · $notes 条情境" else ""}",style=MaterialTheme.typography.titleSmall,color=Quiet)
            }
        }
        when(item) {
            is TimelineItem.Note -> item(key="note-${item.context.id}") {ContextNoteCard(item.context)}
            is TimelineItem.Shots -> items(item.photos.chunked(3),key={"row-${it.first().id}"}) { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach {photo->PhotoTile(photo,Modifier.weight(1f).aspectRatio(1f)) {open(photo.id)}}
                    repeat(3-row.size) {Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}

@Composable internal fun PhotoViewer(photos: List<CapturedPhoto>,selectedId: String,select: (String)->Unit,close: ()->Unit,
    replay: (String)->Unit={},delete: ((CapturedPhoto)->Unit)?=null) {
    // What has been removed in this sitting. The host reloads the record behind this dialog, and until
    // it answers the viewer would otherwise still show - and still offer to delete - the shot the
    // operator just took out, so the list it pages through is the record minus these.
    var removed by remember {mutableStateOf(emptySet<String>())}
    var pending by remember {mutableStateOf<CapturedPhoto?>(null)}
    val visible=photos.filterNot {it.id in removed}
    if(visible.isEmpty()) {LaunchedEffect(Unit) {close()};return}
    val index=visible.indexOfFirst {it.id==selectedId}.takeIf {it>=0} ?: 0
    val metadata=visible[index].metadata
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                // A filmstrip, not a form: the list is already newest-first, so the page you are on is the
                // most recent and dragging left walks back in time. No prev/next buttons - they fought the
                // gesture and made the reader translate positions into directions.
                val pager=rememberPagerState(initialPage=index) {visible.size}
                LaunchedEffect(visible.size) {if(pager.currentPage>visible.lastIndex) pager.scrollToPage(visible.lastIndex)}
                LaunchedEffect(pager.currentPage) {visible.getOrNull(pager.currentPage)?.let {select(it.id)}}
                val photo=visible.getOrNull(pager.currentPage) ?: return@Column
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(close) {Text("关闭")}
                    Text("${pager.currentPage+1} / ${visible.size}",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(photo.served) Tag("主动服务",Success)
                    // Only where deleting is something the operator may actually do: a frame that a
                    // delivered service was reading is not offered, and says why further down instead.
                    if(delete!=null && !photo.served) TextButton({pending=photo},contentPadding=PaddingValues(horizontal=8.dp),
                        modifier=Modifier.testTag("photo-delete")) {Text("删除",color=MaterialTheme.colorScheme.error)}
                }
                HorizontalPager(state=pager,modifier=Modifier.fillMaxWidth()) { page ->
                    PhotoPreview(visible[page],Modifier.fillMaxWidth().height(300.dp),full=true)
                }
                Text("向左拖动看更早的照片",style=MaterialTheme.typography.bodySmall,color=Quiet,
                    modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
                // A marked photograph is only useful if opening it says what the service was. The card
                // that was on the glasses is shown here, with the replay that puts it back there.
                if(photo.served) Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Tag("主动服务",Success)
                            Tag(when(photo.servedMode) {"AWARENESS"->"提醒";"DECISION"->"建议";"PREPARATION"->"准备";"EXECUTION"->"执行";else->"服务"},Quiet)
                            Spacer(Modifier.weight(1f))
                            TextButton({photo.servedAnchor?.let {replay(it)}},enabled=photo.servedAnchor!=null,
                                contentPadding=PaddingValues(0.dp)) {Text("回放到眼镜")}
                        }
                        Text(photo.servedTitle.orEmpty(),style=MaterialTheme.typography.titleMedium)
                        Text(photo.servedBody.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=Quiet)
                    }
                }
                LazyColumn(Modifier.weight(1f).testTag("photo-details"),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                    item {Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(photoTime(photo.capturedAt),style=MaterialTheme.typography.titleLarge)
                        Text(if(photo.historical) "导入的历史照片" else "眼镜拍摄 · 原图保存在手机",style=MaterialTheme.typography.bodySmall,color=Quiet)
                        // The one photograph the operator cannot take away, and the reason has to be
                        // stated where the button would have been rather than only after a refusal: this
                        // frame is the scene half of a demo frame, and it cannot be re-rendered.
                        if(photo.served) Text("这张照片是一次已投递主动服务的画面，删除后无法再生成演示帧，因此不能删除。",
                            style=MaterialTheme.typography.bodySmall,color=Quiet)
                        SettingRow("手机收到时间",photoTime(photo.receivedAt))
                        SettingRow("图片尺寸","${metadata.string("width") ?: "—"} × ${metadata.string("height") ?: "—"}")
                        SettingRow("文件大小",storageSize(photo.file.length()))
                        metadata.string("rotation_degrees")?.let {SettingRow("旋转角度","$it°")}
                        SettingRow("质量",when(metadata.string("quality_status")) {
                            "VALID"->"可用";"TOO_DARK"->"光线偏暗";"TOO_BRIGHT"->"曝光偏亮";"BLURRY"->"画面模糊"
                            else->if(metadata["quality_valid"]?.jsonPrimitive?.booleanOrNull==true) "可用" else "未评估"
                        })
                        HorizontalDivider(color=Line)
                        Text("手机接收时的位置",style=MaterialTheme.typography.titleMedium)
                        val location=metadata["phone_location"] as? JsonObject
                        if(location==null) Text(when(metadata.string("location_status")) {
                            "PERMISSION_REQUIRED"->"未获取定位 · 请允许手机位置权限"
                            "LOCATION_DISABLED"->"未获取定位 · 手机定位开关已关闭"
                            "NO_RECENT_FIX"->"未获取定位 · 暂无两分钟内的有效位置"
                            else->"未获取定位 · 该照片没有位置记录"
                        },color=Quiet,style=MaterialTheme.typography.bodyMedium)
                        else {
                            SettingRow("纬度 / 经度","${location.string("latitude")} / ${location.string("longitude")}")
                            SettingRow("定位来源",when(location.string("provider")) {"gps"->"手机 GPS";"network"->"手机网络定位";else->"手机 ${location.string("provider") ?: "定位"}"})
                            location.string("accuracy_m")?.let {SettingRow("水平精度","约 $it 米")}
                            SettingRow("位置更新时间",photoTime(location["fix_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0))
                            location["age_ms"]?.jsonPrimitive?.longOrNull?.let {SettingRow("接收图片时位置距今","${it/1000} 秒")}
                            location.string("altitude_m")?.let {SettingRow("海拔","$it 米")}
                            location.string("speed_mps")?.let {SettingRow("速度","$it 米/秒")}
                            location.string("bearing_degrees")?.let {SettingRow("移动方向","$it°")}
                            if(location["mock"]?.jsonPrimitive?.booleanOrNull==true) Text("此位置来自模拟定位",color=MaterialTheme.colorScheme.error)
                            Text("记录手机收到图片时可用的近期位置，可能与眼镜拍摄位置存在偏差。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                        }
                        HorizontalDivider(color=Line)
                        metadata.string("phone_model")?.let {SettingRow("接收手机",it)}
                        metadata.string("glasses_battery_percent")?.let {SettingRow("拍摄时眼镜电量","$it%")}
                        if(metadata["buffered_sync"]==JsonPrimitive(true)) Text("眼镜本地保存后批量同步；接收位置不代表拍摄位置。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                        metadata.string("phone_battery_percent")?.let {SettingRow("接收时手机电量","$it%")}
                        metadata.string("transfer_channel")?.let {SettingRow("传输方式",it)}
                        metadata.string("camera_duration_ms")?.let {SettingRow("相机耗时","$it ms")}
                        metadata.string("transfer_duration_ms")?.let {SettingRow("传输耗时","$it ms")}
                        metadata.string("capture_round_trip_ms")?.let {SettingRow("拍摄与接收总耗时","$it ms")}
                        Text(photo.file.name,style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }}
                }
            }
        }
    }
    // A sibling of the viewer, not a child of it: nesting one dialog window inside another is a second
    // window over a full-screen surface, and it reads as one.
    pending?.let {target-> AlertDialog(onDismissRequest={pending=null},title={Text("删除这张照片？")},
        text={Text("原图会从这台手机移除，无法恢复。记录里会留下一条删除记录。\n已经复制到其他记录的历史副本不受影响。")},
        confirmButton={TextButton({removed=removed+target.id;pending=null;delete?.invoke(target)},modifier=Modifier.testTag("photo-delete-confirm")) {
            Text("删除",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton({pending=null},modifier=Modifier.testTag("photo-delete-cancel")) {Text("取消")}}) }
}
