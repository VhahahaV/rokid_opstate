package ai.opstate.rokid.phone.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.opstate.rokid.phone.review.DemoFrameRenderer
import ai.opstate.rokid.phone.review.ShowcaseCard
import ai.opstate.rokid.phone.review.ShowcaseItem
import ai.opstate.rokid.phone.review.ShowcasePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

/** Long edge a list thumbnail decodes to. A row is 56–72dp, so this is already more than it shows. */
private const val THUMBNAIL=480

/** Long edge the composed preview decodes to: enough for a 720x1280 scene to come through whole. */
private const val FULL_SIZE=1600

/**
 * Composites rendered in this session, kept for the life of the process and capped.
 *
 * Drawing one is a decode plus a text layout - cheap once, not once per frame of a scroll, and not
 * once per keystroke of choosing a different picture. The cap is small on purpose: the set is bounded
 * by how many pairings a person actually tries. A renderer whose look changes needs the version
 * bumped, which is what it is for.
 */
private object CompositeCache {
    private const val RENDER_VERSION="1"
    private const val LIMIT=48
    private val entries=object : LinkedHashMap<String,ImageBitmap>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,ImageBitmap>?)=size>LIMIT
    }
    fun key(card: ShowcaseCard,scene: File,width: Int)="${card.decisionId}@${scene.path}@$width@$RENDER_VERSION"
    @Synchronized fun get(key: String)=entries[key]
    @Synchronized fun put(key: String,value: ImageBitmap) {entries[key]=value}
}

/** Decodes the scene down to [width] and draws the card on it, so a thumbnail costs a thumbnail. */
private fun renderComposite(card: ShowcaseCard,scene: File,width: Int): ImageBitmap? {
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeFile(scene.path,bounds)
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>width) sample*=2
    val decoded=BitmapFactory.decodeFile(scene.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return null
    val composed=DemoFrameRenderer.render(decoded,DemoFrameRenderer.Card(card.title,card.body,
        DemoFrameRenderer.modeLabel(card.mode),true))
    decoded.recycle()
    return composed.asImageBitmap()
}

/**
 * The composite itself, drawn as soon as both halves are known.
 *
 * This is the preview and the export at once: the same call produces the picture in the composer, the
 * thumbnail in the list and the JPEG in the album, so what is being judged is what will be saved.
 */
@Composable private fun Composite(card: ShowcaseCard,scene: File,modifier: Modifier,width: Int,full: Boolean) {
    val key=CompositeCache.key(card,scene,width)
    var bitmap by remember(key) {mutableStateOf(CompositeCache.get(key))}
    var settled by remember(key) {mutableStateOf(bitmap!=null) }
    LaunchedEffect(key) {
        if(!settled) {
            val rendered=withContext(Dispatchers.IO) {renderComposite(card,scene,width)}
            rendered?.let {CompositeCache.put(key,it)}
            bitmap=rendered;settled=true
        }
    }
    Box(modifier.background(if(full) Color.Black else Line),contentAlignment=Alignment.Center) {
        bitmap?.let {Image(it,"主动服务卡片叠在选中的画面上",Modifier.fillMaxSize(),contentScale=if(full) ContentScale.Fit else ContentScale.Crop)}
            ?: Text(if(settled) "画面无法读取" else "正在合成…",style=MaterialTheme.typography.bodySmall,
                color=if(full) Color.White else Quiet)
    }
}

/** One of the two things being chosen: a label, what is in it, and where to change it. */
@Composable private fun RowScope.Slot(label: String,action: String,onAction: ()->Unit,secondary: String?=null,
    onSecondary: (()->Unit)?=null,content: @Composable ()->Unit) {
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.bodySmall,color=Quiet)
        content()
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onAction,contentPadding=PaddingValues(horizontal=4.dp)) {Text(action)}
            if(secondary!=null && onSecondary!=null) TextButton(onClick=onSecondary,contentPadding=PaddingValues(horizontal=4.dp)) {
                Text(secondary,color=Quiet)}
        }
    }
}

/**
 * The 展示 tab, which is four things: pick a picture, pick a card, see the composite, export it.
 *
 * The card and the picture are chosen apart on purpose. The words a wearer read and the view they read
 * them over are separate things, and the frame the model happened to be sent is rarely the best
 * illustration of what the card said.
 */
internal fun LazyListScope.showcase(state: OperatorViewState,actions: OperatorActions,openGroup: ()->Unit,
    openCardPicker: ()->Unit,openScenePicker: ()->Unit,openItem: (Int)->Unit) {
    val showcase=state.showcase
    val chosen=showcase.chosen
    val missing=showcase.selected.size-chosen.size
    val draft=showcase.draft
    val draftScene=draft.scene?.photo?.file ?: draft.card?.scene

    item(key="showcase-compose") {Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            SectionTitle("组装","COMPOSE")
            Spacer(Modifier.weight(1f))
            TextButton(onClick=actions.refreshShowcase,enabled=!state.busy && !showcase.loading) {Text("刷新")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Slot("画面","选图",openScenePicker,
                secondary=if(draft.scene!=null) "卡片原图" else null,
                onSecondary=if(draft.scene!=null) actions.useShowcaseCardScene else null) {
                val size=Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp))
                // With a card, the slot shows what will come out; without one it shows the picture
                // itself, because that is what has been chosen so far.
                when {
                    draftScene==null -> Box(size.background(Line),contentAlignment=Alignment.Center) {
                        Text("未选择",style=MaterialTheme.typography.bodySmall,color=Quiet)}
                    draft.card!=null -> Composite(draft.card,draftScene,size,THUMBNAIL,false)
                    else -> PhotoPreview(draftScene,"选中的画面",size)
                }
            }
            Slot("卡片",if(draft.card==null) "选卡片" else "换卡片",openCardPicker) {
                if(draft.card!=null) Column(Modifier.fillMaxWidth().height(96.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Tag(DemoFrameRenderer.modeLabel(draft.card.mode) ?: "服务",Quiet)
                    Text(draft.card.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
                } else Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(Line),contentAlignment=Alignment.Center) {
                    Text("未选择",style=MaterialTheme.typography.bodySmall,color=Quiet)}
            }
        }
        if(draft.card!=null && draftScene!=null) {
            HorizontalDivider(color=Line)
            // Rendered the moment either half changes - that is the point of choosing them apart.
            Composite(draft.card,draftScene,Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).testTag("showcase-draft"),FULL_SIZE,true)
            Text(if(draft.scene==null) "画面是这段卡片当时读到的那张" else "画面是你另选的这张，卡片来自 ${draft.card.recordName}",
                style=MaterialTheme.typography.bodySmall,color=Quiet)
            PrimaryButton("加入展示",!state.busy,actions.addShowcaseDraft)
        } else if(showcase.loaded && showcase.cards.isEmpty())
            Text("还没有真正在眼镜上显示过的主动服务。先跑一次在线服务会话，卡片会出现在这里。",
                style=MaterialTheme.typography.bodySmall,color=Quiet)
        else Text("选一段卡片、一张画面，这里直接出合成图。可选的卡片共 ${showcase.cards.size} 段，都是真的在眼镜上显示过的。",
            style=MaterialTheme.typography.bodySmall,color=Quiet)
    }}

    item(key="showcase-selected") {Panel {
        SectionTitle("已选 ${chosen.size} 个","SELECTED")
        // A record deleted after its moments were chosen, or a picture since removed. Saying so beats
        // exporting a shorter set than the one on screen and letting the operator wonder which went.
        if(missing>0) Text("其中 $missing 个的来源记录或画面已不在，导出时会跳过",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.error)
        if(chosen.isEmpty()) Text("这里还是空的。在上面组好一张，点「加入展示」。",style=MaterialTheme.typography.bodySmall,color=Quiet)
        else LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            itemsIndexed(chosen,key={_,item->"chosen-${item.card.decisionId}-${item.scene.path}"}) { index,item->
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).clickable {openItem(index)}
                    .testTag("chosen-${item.card.decisionId}")) {
                    Composite(item.card,item.scene,Modifier.fillMaxSize(),THUMBNAIL,false)
                    Text((index+1).toString().padStart(2,'0'),color=Color.White,style=MaterialTheme.typography.labelSmall,
                        modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha=.45f)).padding(4.dp))
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("组名",style=MaterialTheme.typography.bodySmall,color=Quiet)
                Text(showcase.group,style=MaterialTheme.typography.titleMedium,overflow=TextOverflow.Ellipsis,maxLines=1)
            }
            TextButton(onClick=openGroup,enabled=!state.busy) {Text("改组名")}
        }
        PrimaryButton("导出到手机相册",chosen.isNotEmpty() && !state.busy,actions.exportShowcase)
        // Where each piece goes, said out loud because it is two folders and the reason is not obvious:
        // a gallery directory takes images and nothing else.
        Text("每张两件：合成图 + 卡片文字。图片写进相册 Pictures/OPSTATE/${showcase.group}，文字写进 Download/OPSTATE/${showcase.group}。",
            style=MaterialTheme.typography.bodySmall,color=Quiet)
    }}
}

/** The historical cards, as the list the card slot picks from. */
@Composable internal fun ShowcaseCardPicker(cards: List<ShowcaseCard>,onDismiss: ()->Unit,choose: (ShowcaseCard)->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onDismiss) {Text("关闭")}
                    Text("选一段卡片",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    Text("${cards.size} 段",style=MaterialTheme.typography.bodySmall,color=Quiet)
                }
                Text("只有真的在眼镜上显示过的主动服务在这里；被抑制、过期或没被确认的不算。",
                    style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(horizontal=20.dp))
                LazyColumn(Modifier.fillMaxSize().testTag("showcase-card-picker"),contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(cards.isEmpty()) item {Panel {
                        Text("还没有可选的卡片",style=MaterialTheme.typography.titleMedium)
                        Text("在线服务模式下真的显示过的主动服务会出现在这里。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }}
                    items(cards,key={it.decisionId}) {card->
                        Surface(Modifier.fillMaxWidth().testTag("card-${card.decisionId}").clickable {choose(card)},
                            shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Composite(card,card.scene,Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),THUMBNAIL,false)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Tag(DemoFrameRenderer.modeLabel(card.mode) ?: "服务",Quiet)
                                        Text(photoTime(card.at,"MM.dd HH:mm"),style=MaterialTheme.typography.labelMedium,color=Quiet)
                                    }
                                    Text(card.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(buildString {
                                        append(card.recordName)
                                        card.latencyMs?.let {append(" · 模型 ");append(it);append(" ms")}
                                    },style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Every photograph on the phone, grouped by day, as the list the picture slot picks from. */
@Composable internal fun ShowcaseScenePicker(photos: List<ShowcasePhoto>,loading: Boolean,onDismiss: ()->Unit,
    choose: (ShowcasePhoto)->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onDismiss) {Text("关闭")}
                    Text("选一张画面",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(!loading) Text("${photos.size} 张",style=MaterialTheme.typography.bodySmall,color=Quiet)
                }
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp))
                Text("所有记录里的照片，同一张只出现一次。",style=MaterialTheme.typography.bodySmall,color=Quiet,
                    modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))
                LazyColumn(Modifier.fillMaxSize().testTag("showcase-scene-picker"),contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    // The same grid and tile the record album uses, so a picture is recognised by the
                    // place it was already seen rather than by a second rendering of it.
                    photoGrid(photos.map {it.photo}) {id->
                        photos.firstOrNull {it.photo.id==id}?.let(choose)
                    }
                }
            }
        }
    }
}

/**
 * One chosen item at full size. The card is what it is worth reading, and the picture is the one it
 * was paired with - there is no original to compare against any more, because that pairing is the
 * thing being decided here.
 */
@Composable internal fun ShowcasePreviewDialog(item: ShowcaseItem,enabled: Boolean,remove: ()->Unit,replay: ()->Unit,close: ()->Unit) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(close) {Text("关闭")}
                    Text(item.card.title,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                    Tag(DemoFrameRenderer.modeLabel(item.card.mode) ?: "服务",Quiet)
                }
                Composite(item.card,item.scene,Modifier.fillMaxWidth().height(340.dp),FULL_SIZE,true)
                LazyColumn(Modifier.weight(1f).testTag("showcase-details"),contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(item.card.title,style=MaterialTheme.typography.titleLarge)
                        Text(item.card.body,style=MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        SettingRow("记录",item.card.recordName)
                        SettingRow("时间",photoTime(item.card.at))
                        SettingRow("服务模式","${item.card.mode ?: "未记录"}${DemoFrameRenderer.modeLabel(item.card.mode)?.let {" · $it"} ?: ""}")
                        SettingRow("模型时延",item.card.latencyMs?.let {"$it ms"} ?: "未记录")
                        SettingRow("投递","已在眼镜实际显示")
                        Spacer(Modifier.height(6.dp))
                        Text(item.card.anchorId,style=MaterialTheme.typography.bodySmall,color=Quiet)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("从展示中移出",enabled) {remove()}
                        TextButton(onClick=replay,enabled=enabled,modifier=Modifier.fillMaxWidth()) {Text("回放到眼镜")}
                        Text("回放不是投递：卡片带自己的 decision id，不会产生新的回执。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                    }
                }
            }
        }
    }
}
