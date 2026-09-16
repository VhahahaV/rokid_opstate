package ai.opstate.rokid.phone.ui

import ai.opstate.rokid.phone.scenario.ScenarioMarker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun playbackTime(ms: Long)="%02d:%02d".format(ms/60_000,ms/1000%60)
internal fun contextLabel(text: String)=when(text) {
    "Meeting moved to B302."->"会议地点改为 B302"
    "The meeting has moved earlier. Please check the updated start time before leaving."->"会议提前，出发前核对开始时间"
    else->text
}

/** A draft thumb position never sends commands until the user releases it. */
@Composable internal fun ScenarioPlaybackPanel(positionMs: Long,durationMs: Long,markers: List<ScenarioMarker>,
    enabled: Boolean=true,onSeek: (Long)->Unit) {
    var draft by remember {mutableStateOf<Float?>(null)}
    val position=(draft?.toLong() ?: positionMs).coerceIn(0,durationMs)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(if(draft!=null) "松手跳转到" else "情境回放",style=MaterialTheme.typography.labelLarge,color=Quiet)
        Text("${playbackTime(position)} / ${playbackTime(durationMs)}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.labelLarge,color=Ink)
    }
    Slider(value=position.toFloat(),onValueChange={draft=it},onValueChangeFinished={draft?.let {onSeek(it.toLong())};draft=null},
        valueRange=0f..durationMs.coerceAtLeast(1).toFloat(),enabled=enabled,
        modifier=Modifier.fillMaxWidth().testTag("scenario-progress").semantics {contentDescription="情境回放进度"},
        colors=SliderDefaults.colors(thumbColor=Ink,activeTrackColor=Success,inactiveTrackColor=Line))
    val next=markers.firstOrNull {it.positionMs>position}
    Text(next?.let {"下一条 ${playbackTime(it.positionMs)} · ${contextLabel(it.label)}"} ?: "已到达最后一条情境，继续观察至回放结束",
        style=MaterialTheme.typography.bodySmall,color=Quiet,maxLines=2,overflow=TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        val previous=markers.lastOrNull {it.positionMs<position-1000}?.positionMs ?: 0L
        OutlinedButton(onClick={onSeek(previous)},enabled=enabled && position>0,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)) {Text("上一情境")}
        OutlinedButton(onClick={next?.let {onSeek(it.positionMs)}},enabled=enabled && next!=null,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)) {Text("下一情境")}
    }
}
