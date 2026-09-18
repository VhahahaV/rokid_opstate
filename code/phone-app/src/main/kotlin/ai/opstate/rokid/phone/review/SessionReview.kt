package ai.opstate.rokid.phone.review

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import kotlinx.serialization.json.*
import java.io.File

data class TrialSummary(val anchorId: String,val at: Long,val episode: String,val decision: String?,val title: String?,
    val evidenceCount: Int,val latencyMs: Long?,val delivery: String?)
data class SessionReview(val session: StudySessionEntity,val phase: String,val counts: Map<String,Int>,val verified: Boolean,val trials: List<TrialSummary>,val interrupted: Boolean=false,val photos: List<CapturedPhoto> = emptyList(),val contexts: List<ContextEntry> = emptyList())
/** Operator-authored context shown alongside the captures it sits between in time. */
data class ContextEntry(val id: String,val type: String,val title: String,val text: String,val occurredAt: Long)
sealed interface TimelineItem {
    val at: Long
    data class Shots(val photos: List<CapturedPhoto>) : TimelineItem { override val at get()=photos.first().capturedAt }
    data class Note(val context: ContextEntry) : TimelineItem { override val at get()=context.occurredAt }
}
/**
 * Photos and context merged into one stream. Consecutive photos collapse into a single grid slot so a
 * long capture run stays compact, while a context note keeps its own place in the sequence — the
 * visual order is then the order events actually happened, in either direction.
 */
fun timelineItems(photos: List<CapturedPhoto>,contexts: List<ContextEntry>,descending: Boolean): List<TimelineItem> {
    val ordered=(photos.map {TimelineItem.Shots(listOf(it))} as List<TimelineItem> + contexts.map {TimelineItem.Note(it)})
        .sortedBy {it.at}
    val sequenced=if(descending) ordered.reversed() else ordered
    val merged=mutableListOf<TimelineItem>()
    for(item in sequenced) {
        val last=merged.lastOrNull()
        if(item is TimelineItem.Shots && last is TimelineItem.Shots) merged[merged.size-1]=TimelineItem.Shots(last.photos+item.photos)
        else merged.add(item)
    }
    return merged
}
data class CapturedPhoto(val id: String,val file: File,val capturedAt: Long,val receivedAt: Long,val metadata: JsonObject) {
    val historical get()=metadata.string("source")?.endsWith("HISTORY")==true
}
data class TrialReview(val summary: TrialSummary,val request: JsonObject?,val raw: JsonObject?,val decision: JsonObject?,val image: File?,val providerRaw: JsonObject?=null)
class SessionReviewRepository(private val journal: StudyJournal) {
    private val dao get()=journal.dao
    private fun JournalEntity.json()=StudyWire.json.parseToJsonElement(canonicalJson).jsonObject
    private fun find(id: String,stream: String,anchor: String)=dao.eventForAnchor(id,stream,anchor)?.json()
    fun photos(id: String): List<CapturedPhoto> = dao.stream(id,"observations").mapNotNull {event->
        val row=event.json()
        if(row.string("kind")!="IMAGE") return@mapNotNull null
        val path=row.string("local_relative_path") ?: return@mapNotNull null
        CapturedPhoto(row.string("observation_id") ?: event.sequence.toString(),journal.localImage(id,path),
            row["source_occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,
            row["available_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,row)
    }.sortedByDescending {it.capturedAt}
    fun contexts(id: String): List<ContextEntry> = dao.stream(id,"observations").mapNotNull {event->
        val row=event.json()
        if(row.string("kind")!="SYNTHETIC_CONTEXT") return@mapNotNull null
        // `body` is stored separately now; rows written before it existed keep the "[TYPE] title"
        // header inside public_text, so strip that rather than showing it twice beside the title.
        val title=row.string("title") ?: ""
        val text=row.string("body") ?: (row.string("public_text") ?: "").removePrefix("[${row.string("synthetic_type") ?: "NOTE"}] $title").trimStart('\n')
        ContextEntry(row.string("observation_id") ?: event.sequence.toString(),
            row.string("synthetic_type") ?: "NOTE",title,text,
            row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0)
    }.sortedBy {it.occurredAt}
    fun load(id: String): SessionReview {
        val session=requireNotNull(dao.session(id)) { "SESSION_NOT_FOUND" }
        fun key(row: JsonObject)=row.string("packet_id") ?: row.string("anchor_id")
        val decisions=dao.stream(id,"decisions").map { it.json() }.associateBy(::key)
        val raw=dao.stream(id,"raw_responses").map { it.json() }.associateBy(::key)
        val deliveries=dao.stream(id,"deliveries").map { it.json() }.associateBy { it.string("decision_id") }
        val trials=(dao.stream(id,"anchors")+dao.stream(id,"packets")).sortedBy {it.sequence}.map {it.json()}.map { row ->
            val rowKey=key(row)!!;val decision=decisions[rowKey]
            TrialSummary(rowKey,(row["cutoff_at_epoch_ms"] ?: row.getValue("anchor_time_epoch_ms")).jsonPrimitive.long,
                row.string("candidate_episode_handle") ?: "online-packet",decision?.string("decision"),decision?.string("title"),
                decision?.get("evidence_observation_ids")?.jsonArray?.size ?: 0,
                raw[rowKey]?.get("latency_ms")?.jsonPrimitive?.longOrNull,deliveries[decision?.string("decision_id")]?.string("status"))
        }
        return SessionReview(session,dao.checkpoint(id)?.state ?: "UNKNOWN",dao.counts(id).associate { it.stream to it.count },journal.verify(id),trials,dao.checkpoint(id)?.stateJson?.let {StudyWire.json.parseToJsonElement(it).jsonObject.string("recovery_reason")}!=null,photos(id),contexts(id))
    }
    fun trial(id: String,trial: TrialSummary): TrialReview {
        val request=find(id,"inference_requests",trial.anchorId)
        val raw=find(id,"raw_responses",trial.anchorId)
        val decision=find(id,"decisions",trial.anchorId)
        val asset=request?.string("current_asset_id")
        val image=asset?.let { value -> dao.imageForAsset(id,value)?.json()?.string("local_relative_path")?.let {journal.localImage(id,it)} }
        val provider=dao.eventForAnchorOfType(id,"raw_responses",trial.anchorId,"PROVIDER_RESPONSE")?.json()
        return TrialReview(trial,request,raw,decision,image,provider)
    }
}
fun JsonObject.string(key: String)=get(key)?.jsonPrimitive?.contentOrNull
