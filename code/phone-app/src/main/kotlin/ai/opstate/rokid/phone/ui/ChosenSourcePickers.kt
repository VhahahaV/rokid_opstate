package ai.opstate.rokid.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.opstate.rokid.phone.review.CapturedPhoto
import ai.opstate.rokid.phone.review.ContextEntry

/**
 * Picking the evidence a demonstration runs on.
 *
 * Both pickers feed the same chain a live capture does - observation, history, packet, model, card,
 * two-frame ack - so a staged run is not a different code path, only a different source. Newest first,
 * because that is the frame you just looked at.
 */
@Composable internal fun ChosenPhotoPicker(photos: List<CapturedPhoto>,onDismiss: ()->Unit,choose: (CapturedPhoto)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("选一张照片跑一次")},
        text={
            if(photos.isEmpty()) Text("这次会话还没有照片。先拍一张，或在开始前导入一段历史。",color=Quiet)
            else LazyColumn(Modifier.heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(photos.asReversed(),key={it.id}) { photo ->
                    Surface(Modifier.fillMaxWidth().clickable {choose(photo)},shape=RoundedCornerShape(10.dp),
                        color=MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(photoTime(photo.capturedAt),style=MaterialTheme.typography.bodyMedium)
                                Text(if(photo.historical) "历史照片" else "本会话拍摄",
                                    style=MaterialTheme.typography.bodySmall,color=Quiet)
                            }
                            if(photo.served) Tag("曾触发服务",Success)
                        }
                    }
                }
            }
        },
        confirmButton={},dismissButton={TextButton(onDismiss) {Text("取消")}})
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
