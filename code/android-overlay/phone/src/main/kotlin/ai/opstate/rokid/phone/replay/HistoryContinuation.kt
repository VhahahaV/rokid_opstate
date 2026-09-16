package ai.opstate.rokid.phone.replay

import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import kotlinx.serialization.json.*

data class HistoryPreview(val index: Int,val total: Int,val sourceTime: String,val observationCount: Int,
    val recent: List<String>,val anchorId: String)
data class HistorySeed(val preview: HistoryPreview,val observations: List<ObservationRecord>,val candidate: CandidateEpisode,
    val sourceCutoffMs: Long,val alignmentMs: Long,val catalogHash: String)

fun PublicReplayDataset.preview(index: Int): HistoryPreview {
    require(index in anchors.indices) {"HISTORY_POSITION_INVALID"}
    val anchor=anchors[index];val cutoff=PublicReplayDataset.epoch(anchor.text("available_at"))
    val visible=observations.takeWhile {it.availableAtEpochMs<=cutoff}
    return HistoryPreview(index,anchors.size,anchor.text("available_at"),visible.size,
        visible.filter {it.kind!=ObservationKind.IMAGE}.takeLast(3).map {it.content},anchor.text("anchor_id"))
}

/** Replay only the causal public prefix. Keep original source times in the audit alongside the alignment. */
fun PublicReplayDataset.continuation(index: Int,nowEpochMs: Long): HistorySeed {
    val preview=preview(index);val cutoff=PublicReplayDataset.epoch(preview.sourceTime)
    val alignment=nowEpochMs-cutoff
    val rows=observations.takeWhile {it.availableAtEpochMs<=cutoff}.map {obs->
        ObservationRecord(obs.observationId,obs.kind,obs.occurredAtEpochMs+alignment,obs.availableAtEpochMs+alignment,
            obs.content,obs.assetId,obs.episodeHandle,obs.materialRevision)
    }
    val handle=anchors[index].text("task_episode_handle")
    val revision=rows.filter {it.episodeHandle==handle}.mapNotNull {it.materialRevision}.maxOrNull() ?: 0L
    return HistorySeed(preview,rows,CandidateEpisode(handle,"obj_${StudyWire.sha256(handle.toByteArray()).take(16)}",
        "intent_${StudyWire.sha256(handle.toByteArray()).take(16)}",ServiceMode.PREPARATION,true,materialRevision=revision),
        cutoff,alignment,StudyWire.sha256(catalog.toString().toByteArray()))
}
