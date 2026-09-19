package ai.opstate.rokid.phone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.opstate.rokid.phone.review.DemoFrameRenderer
import ai.opstate.rokid.phone.review.ShowcaseFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/** Long edge a list thumbnail decodes to. A row is 56–72dp, so this is already more than it shows. */
private const val THUMBNAIL=480

/** Long edge the full-size preview decodes to: enough for a 720x1280 scene to come through whole. */
private const val FULL_SIZE=1600

/**
 * Composites rendered for this list, kept for the life of the process and capped.
 *
 * Drawing one is a decode plus a text layout - cheap once, not once per frame of a scroll. The cap is
 * deliberately small because this is a shortlist: the set is bounded by how many proactive services a
 * person has actually been shown. If the renderer's look ever changes the cache has to be cleared,
 * which is why the key carries [RENDER_VERSION].
 */
private object CompositeCache {
    private const val RENDER_VERSION="1"
    private const val LIMIT=48
    private val entries=object : LinkedHashMap<String,ImageBitmap>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,ImageBitmap>?)=size>LIMIT
    }
    fun key(frame: ShowcaseFrame,width: Int)="${frame.decisionId}@$width@$RENDER_VERSION"
    @Synchronized fun get(key: String)=entries[key]
    @Synchronized fun put(key: String,value: ImageBitmap) {entries[key]=value}
}

/** Decodes the scene down to [width] and draws the card on it, so a thumbnail costs a thumbnail. */
private fun renderComposite(frame: ShowcaseFrame,width: Int): ImageBitmap? {
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeFile(frame.scene.path,bounds)
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>width) sample*=2
    val scene=BitmapFactory.decodeFile(frame.scene.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return null
    val composed=DemoFrameRenderer.render(scene,DemoFrameRenderer.Card(frame.title,frame.body,
        DemoFrameRenderer.modeLabel(frame.mode),true))
    scene.recycle()
    return composed.asImageBitmap()
}

@Composable private fun Composite(frame: ShowcaseFrame,modifier: Modifier,width: Int,full: Boolean) {
    val key=CompositeCache.key(frame,width)
    var bitmap by remember(key) {mutableStateOf(CompositeCache.get(key))}
    var settled by remember(key) {mutableStateOf(bitmap!=null) }
    LaunchedEffect(key) {
        if(!settled) {
            val rendered=withContext(Dispatchers.IO) {renderComposite(frame,width)}
            rendered?.let {CompositeCache.put(key,it)}
            bitmap=rendered;settled=true
        }
    }
    Box(modifier.background(if(full) Color.Black else Line),contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it,"主动服务卡片叠在当时的画面上",Modifier.fillMaxSize(),contentScale=if(full) ContentScale.Fit else ContentScale.Crop)}
            ?: Text(if(settled) "原图无法读取" else "正在渲染…",style=MaterialTheme.typography.bodySmall,
                color=if(full) Color.White else Quiet)
    }
}

/**
 * The 展示 tab: every proactive service that was really on the glasses, across every record, and the
 * shortlist an operator assembles out of them for showing to somebody else.
 */
internal fun LazyListScope.showcase(state: OperatorViewState,actions: OperatorActions,openGroup: ()->Unit,openFrame: (ShowcaseFrame)->Unit) {
    val showcase=state.showcase
    val chosen=showcase.chosen
    val missing=showcase.selected.size-chosen.size

    item(key="showcase-summary") {Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            SectionTitle("可展示的时刻","SHOWCASE")
            Spacer(Modifier.weight(1f))
            TextButton(onClick=actions.refreshShowcase,enabled=!state.busy && !showcase.loading) {Text("刷新")}
        }
        Text("已选 ${chosen.size} 个 · 共 ${showcase.frames.size} 个真实显示过的主动服务",style=MaterialTheme.typography.titleMedium)
        // A record deleted after its moments were chosen. Saying so beats exporting a shorter set than
        // the one on screen and letting the operator wonder which one went.
        if(missing>0) Text("其中 $missing 个的来源记录已删除，导出时会跳过",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.error)
        if(showcase.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("组名",style=MaterialTheme.typography.bodySmall,color=Quiet)
                Text(showcase.group,style=MaterialTheme.typography.titleMedium,overflow=TextOverflow.Ellipsis,maxLines=1)
            }
            TextButton(onClick=openGroup,enabled=!state.busy) {Text("改组名")}
        }
        PrimaryButton("导出到手机相册",chosen.isNotEmpty() && !state.busy,actions.exportShowcase)
        // Where each piece goes, said out loud because it is two folders and the reason is not
        // obvious: a gallery directory takes images and nothing else.
        Text("每个时刻三件：卡片叠在原图上的合成图、当时那张原图、卡片文字。图片写进相册 Pictures/OPSTATE/${showcase.group}，文字文件写进 Download/OPSTATE/${showcase.group}；索引留在应用内。",
            style=MaterialTheme.typography.bodySmall,color=Quiet)
    }}

    if(chosen.isNotEmpty()) {
        item(key="showcase-assembly-title") {SectionTitle("已选 ${chosen.size} 个 · 导出按这个顺序","ASSEMBLY")}
        itemsIndexed(chosen,key={_,frame->"chosen-${frame.decisionId}"}) { index,frame->
            Surface(Modifier.fillMaxWidth().testTag("chosen-${frame.decisionId}"),shape=RoundedCornerShape(16.dp),
                color=MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text((index+1).toString().padStart(2,'0'),style=MaterialTheme.typography.titleMedium,
                        color=Quiet,modifier=Modifier.width(28.dp))
                    Composite(frame,Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),THUMBNAIL,false)
                    Column(Modifier.weight(1f).clickable {openFrame(frame)},verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(frame.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(photoTime(frame.at,"MM月dd日 HH:mm"),style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                    // Two taps on a 40dp arrow rather than a drag: reordering a shortlist is rare, and a
                    // drag would fight the vertical scroll of the list it lives in.
                    IconButton(onClick={actions.moveShowcaseFrame(frame,-1)},enabled=index>0) {
                        Icon(Icons.Default.KeyboardArrowUp,"上移",Modifier.size(20.dp))}
                    IconButton(onClick={actions.moveShowcaseFrame(frame,1)},enabled=index<chosen.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown,"下移",Modifier.size(20.dp))}
                    TextButton({actions.removeShowcaseFrame(frame)}) {Text("移出",color=Quiet)}
                }
            }
        }
    }

    item(key="showcase-list-title") {SectionTitle("全部时刻 ${showcase.frames.size}","MOMENTS")}
    if(showcase.frames.isEmpty()) {
        item(key="showcase-empty") {Panel {
            Text(if(showcase.loaded) "还没有可展示的时刻" else "正在读取记录",style=MaterialTheme.typography.titleMedium)
            Text("在线服务模式下真的在眼镜上显示过的主动服务会出现在这里；被抑制、过期或没被确认的不会。",
                style=MaterialTheme.typography.bodySmall,color=Quiet)
        }}
    }
    items(showcase.frames,key={"frame-${it.decisionId}"}) {frame->
        val selected=showcase.selected.any {it.decisionId==frame.decisionId}
        Surface(Modifier.fillMaxWidth().testTag("frame-${frame.decisionId}"),shape=RoundedCornerShape(16.dp),
            color=if(selected) Color(0xFFE0EBDF) else MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Composite(frame,Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),THUMBNAIL,false)
                Column(Modifier.weight(1f).clickable {openFrame(frame)},verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Tag(DemoFrameRenderer.modeLabel(frame.mode) ?: "服务",Quiet)
                        Text(photoTime(frame.at,"MM.dd HH:mm"),style=MaterialTheme.typography.labelMedium,color=Quiet)
                    }
                    Text(frame.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(buildString {
                        append(frame.recordName)
                        frame.latencyMs?.let {append(" · 模型 ");append(it);append(" ms")}
                    },style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Checkbox(checked=selected,onCheckedChange={actions.toggleShowcaseFrame(frame)})
            }
        }
    }
}

/**
 * One moment at full size, with the original a tap away.
 *
 * The two views are the pair the export ships, so what is being judged here is exactly what gets saved:
 * the composite is what the wearer saw, and the original is what proves the composite is that picture.
 */
@Composable internal fun ShowcasePreviewDialog(frame: ShowcaseFrame,selected: Boolean,enabled: Boolean,
    toggle: ()->Unit,replay: ()->Unit,close: ()->Unit) {
    var original by remember(frame.decisionId) {mutableStateOf(false)}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(close) {Text("关闭")}
                    Text(frame.title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                    Tag(DemoFrameRenderer.modeLabel(frame.mode) ?: "服务",Quiet)
                }
                if(original) PhotoPreview(frame.scene,"这张主动服务当时读到的原图",Modifier.fillMaxWidth().height(320.dp),full=true)
                else Composite(frame,Modifier.fillMaxWidth().height(320.dp),FULL_SIZE,true)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!original,onClick={original=false},label={Text("合成图")})
                    FilterChip(selected=original,onClick={original=true},label={Text("原图")})
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={original=!original}) {Text(if(original) "看合成图" else "看原图")}
                }
                LazyColumn(Modifier.weight(1f).testTag("showcase-details"),contentPadding=PaddingValues(20.dp,4.dp,20.dp,24.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(frame.title,style=MaterialTheme.typography.titleLarge)
                        Text(frame.body,style=MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        SettingRow("记录",frame.recordName)
                        SettingRow("时间",photoTime(frame.at))
                        SettingRow("服务模式","${frame.mode ?: "未记录"}${DemoFrameRenderer.modeLabel(frame.mode)?.let {" · $it"} ?: ""}")
                        SettingRow("模型时延",frame.latencyMs?.let {"$it ms"} ?: "未记录")
                        SettingRow("投递","已在眼镜实际显示")
                        Spacer(Modifier.height(6.dp))
                        Text(frame.anchorId,style=MaterialTheme.typography.bodySmall,color=Quiet)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(if(selected) "从展示中移出" else "加入展示",enabled) {toggle()}
                        TextButton(onClick=replay,enabled=enabled,modifier=Modifier.fillMaxWidth()) {Text("回放到眼镜")}
                        Text("回放不是投递：卡片带自己的 decision id，不会产生新的回执。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                }
            }
        }
    }
}
