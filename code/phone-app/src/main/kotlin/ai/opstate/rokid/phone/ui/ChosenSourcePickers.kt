package ai.opstate.rokid.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.review.ContextEntry
import ai.opstate.rokid.phone.review.string
import kotlinx.serialization.json.*

/**
 * Picking the evidence a demonstration runs on.
 *
 * Both pickers feed the same chain a live capture does - observation, history, packet, model, card,
 * two-frame ack - so a staged run is not a different code path, only a different source. Newest first,
 * because that is the frame you just looked at.
 */

/**
 * Choosing the photograph a staged run will be made on.
 *
 * This was a list of timestamps, which is a strange thing to choose a picture by: the only way to know
 * what you had picked was to pick it and watch the chain run. It is the pictures now - a grid of them,
 * and a full view of whichever one you are considering, with the run offered from there rather than on
 * the first tap.
 */
@Composable internal fun ChosenPhotoPicker(photos: List<CapturedPhoto>,onDismiss: ()->Unit,choose: (CapturedPhoto)->Unit) {
    var chosenId by remember {mutableStateOf<String?>(null)}
    val chosen=photos.firstOrNull {it.id==chosenId}
    Dialog(onDismissRequest=if(chosen==null) onDismiss else {{chosenId=null}},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Canvas) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton({if(chosen==null) onDismiss() else chosenId=null}) {Text(if(chosen==null) "取消" else "返回")}
                    Text(if(chosen==null) "选一张照片跑一次" else "用它跑一次？",modifier=Modifier.weight(1f),
                        style=MaterialTheme.typography.titleMedium)
                    if(chosen==null) Text("${photos.size} 张",style=MaterialTheme.typography.bodySmall,color=Quiet)
                }
                if(photos.isEmpty()) {
                    Text("这次会话还没有照片。先拍一张，或在开始前导入一段历史。",color=Quiet,
                        modifier=Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium)
                } else if(chosen==null) {
                    Text("这次会话的照片。点一张看大图，再决定要不要用它跑。",style=MaterialTheme.typography.bodySmall,
                        color=Quiet,modifier=Modifier.padding(horizontal=20.dp,vertical=6.dp))
                    LazyColumn(Modifier.fillMaxSize().testTag("chosen-photo-grid"),
                        contentPadding=PaddingValues(20.dp,8.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        photoGrid(photos) {id->chosenId=id}
                    }
                } else {
                    PhotoPreview(chosen,Modifier.fillMaxWidth().height(360.dp),full=true)
                    LazyColumn(Modifier.weight(1f).testTag("chosen-photo-details"),
                        contentPadding=PaddingValues(20.dp,14.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(photoTime(chosen.capturedAt),style=MaterialTheme.typography.titleLarge)
                                if(chosen.served) Tag("曾触发服务",Success)
                            }
                            Text(if(chosen.historical) "历史照片" else "本会话拍摄",style=MaterialTheme.typography.bodySmall,color=Quiet)
                            Spacer(Modifier.height(6.dp))
                            SettingRow("图片尺寸","${chosen.metadata.string("width") ?: "—"} × ${chosen.metadata.string("height") ?: "—"}")
                            SettingRow("文件大小",storageSize(chosen.file.length()))
                            SettingRow("质量",when(chosen.metadata.string("quality_status")) {
                                "VALID"->"可用";"TOO_DARK"->"光线偏暗";"TOO_BRIGHT"->"曝光偏亮";"BLURRY"->"画面模糊"
                                else->if(chosen.metadata["quality_valid"]?.jsonPrimitive?.booleanOrNull==true) "可用" else "未评估"
                            })
                            Spacer(Modifier.height(14.dp))
                            PrimaryButton("用这张跑一次",true) {choose(chosen)}
                            Text("会走和真实拍摄一样的链路：观察 → 观察包 → 模型 → 卡片 → 两帧可见确认。",
                                style=MaterialTheme.typography.bodySmall,color=Quiet)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ChosenContextPicker(entries: List<ContextEntry>,onDismiss: ()->Unit,choose: (ContextEntry)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("选一条情境跑一次")},
        text={
            if(entries.isEmpty()) Text("这次会话还没有情境。历史里导入的合成数据也会出现在这里。",color=Quiet)
            else LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(entries.asReversed(),key={it.id}) { entry ->
                    Surface(Modifier.fillMaxWidth().clickable {choose(entry)},shape=RoundedCornerShape(10.dp),
                        color=MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Tag(entry.type,Quiet)
                                Text(entry.title,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f),
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Text(entry.text,style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=3,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton={},dismissButton={TextButton(onDismiss) {Text("取消")}})
}
