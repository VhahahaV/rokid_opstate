package ai.opstate.rokid.phone.review

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import kotlinx.serialization.json.*
import java.io.File

data class TrialSummary(val anchorId: String,val at: Long,val episode: String,val decision: String?,val title: String?,
    val evidenceCount: Int,val latencyMs: Long?,val delivery: String?)
data class SessionReview(val session: StudySessionEntity,val phase: String,val counts: Map<String,Int>,val verified: Boolean,val trials: List<TrialSummary>,val interrupted: Boolean=false,val photos: List<CapturedPhoto> = emptyList(),val contexts: List<ContextEntry> = emptyList(),
    /** Photographs an operator took out of this record. Their rows are still in the journal, which is
     *  why the count is worth showing: the album is smaller than the record, and this says by how much. */
    val deletedPhotos: Int = 0,
    /** Notes an operator has withdrawn, and the merge copies of them: same reason, said the same way. */
    val retractedContexts: Int = 0)
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
data class CapturedPhoto(val id: String,val file: File,val capturedAt: Long,val receivedAt: Long,val metadata: JsonObject,
    /** True when this frame is the one a delivered service was reading. Worth marking: it is the shot. */
    val served: Boolean=false,
    /** What that service actually said, so opening the photograph shows it without a lookup. */
    val servedDecisionId: String?=null,val servedTitle: String?=null,val servedBody: String?=null,
    val servedMode: String?=null,val servedAnchor: String?=null) {
    val historical get()=metadata.string("source")?.endsWith("HISTORY")==true
    /** Automatic CVTT verdict or the operator's explicit trigger, persisted beside the picture. */
    val triggers get()=metadata["trigger_types"]?.jsonArray?.mapNotNull {it.jsonPrimitive.contentOrNull}.orEmpty()
    /** A visual front-end hit or an explicit manual trigger. */
    val triggered get()=triggers.isNotEmpty()
    val frontEnd get()=metadata.string("front_end")
    val frontEndScore get()=metadata.string("front_end_score")?.toDoubleOrNull()
    /** Whether the frame carries a verdict at all: a history frame or one from before the front end. */
    val frontEndDecided get()=frontEnd!=null && frontEnd!="DISABLED" && frontEnd!="BYPASSED_MANUAL"
    /** The verdict as one phrase, for a badge or a detail row. */
    val triggerLabel get()=when {"MANUAL" in triggers->"手动触发";triggered->"触发";frontEndDecided->"未触发";else->"无判定"}
}
data class TrialReview(val summary: TrialSummary,val request: JsonObject?,val raw: JsonObject?,val decision: JsonObject?,val image: File?,val providerRaw: JsonObject?=null)
class SessionReviewRepository(private val journal: StudyJournal) {
    private val dao get()=journal.dao
    private fun JournalEntity.json()=StudyWire.json.parseToJsonElement(canonicalJson).jsonObject
    private fun find(id: String,stream: String,anchor: String)=dao.eventForAnchor(id,stream,anchor)?.json()
    /**
     * The delivered service each frame was reading, keyed by the asset it was reading it from.
     * The link is the request's `current_asset_id` joined to the observation carrying that asset.
     */
    private fun servedByAsset(id: String): Map<String,Pair<String,JsonObject>> {
        fun key(row: JsonObject)=row.string("packet_id") ?: row.string("anchor_id")
        val requests=dao.stream(id,"inference_requests").map {it.json()}.associateBy(::key)
        val out=linkedMapOf<String,Pair<String,JsonObject>>()
        for(decision in dao.stream(id,"decisions").map {it.json()}) {
            if(decision.string("decision")!="SERVICE") continue
            val anchor=key(decision) ?: continue
            val asset=requests[anchor]?.string("current_asset_id") ?: continue
            out[asset]=anchor to decision
        }
        return out
    }
    fun photos(id: String): List<CapturedPhoto> {
        val served=servedByAsset(id)
        val removed=journal.deletedPhotoObservations(id)
        return dao.stream(id,"observations").mapNotNull {event->
            val row=event.json()
            if(row.string("kind")!="IMAGE") return@mapNotNull null
            if(row.string("observation_id")?.let {it in removed}==true) return@mapNotNull null
            val path=row.string("local_relative_path") ?: return@mapNotNull null
            val hit=row.string("asset_id")?.let {served[it]}
            CapturedPhoto(row.string("observation_id") ?: event.sequence.toString(),journal.localImage(id,path),
                row["source_occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                row["available_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,row,
                served=hit!=null,servedDecisionId=hit?.second?.string("decision_id"),servedTitle=hit?.second?.string("title"),
                servedBody=hit?.second?.string("body"),servedMode=hit?.second?.string("service_mode"),servedAnchor=hit?.first)
        }.sortedByDescending {it.capturedAt}
    }
    /**
     * The operator-authored context in a record, in the three shapes it has been written in.
     *
     * The dialog stores `title` and `body` apart. A record seeded from an earlier one is written by
     * `appendObservation`, which keeps only `content` - no title, no body. The imported public history
     * keeps only `public_text`. The two older shapes put the "[TYPE] title" header inside the text
     * itself, which is where their title has to come from, because the separate field is simply absent.
     *
     * Reading only `body` and `public_text` - which is what this did - left every seeded note blank:
     * the picker that offers them to the operator drew empty rows, and choosing one failed validation.
     *
     * What the page draws is the facts, not the rows: a note withdrawn from the ledger is gone, and a
     * note the merge copied in several times is drawn once. The rows themselves stay in the journal.
     */
    fun contexts(id: String): List<ContextEntry> {
        // Two notes that share a time and a text are one fact: a merge copies an imported note into every
        // record that had imported it, and the page draws it once. Withdrawn notes go first, and both
        // rules read that identity from whichever field the note's writer used.
        val withdrawn=journal.retractedContextObservations(id)
        val rows=dao.stream(id,"observations").map {event->event to event.json()}
            .filter {(_,row)->row.string("kind")=="SYNTHETIC_CONTEXT"}
            .filter {(event,row)->(row.string("observation_id") ?: event.sequence.toString()) !in withdrawn}
            .distinctBy {(_,row)->contextObservationKey(row)}
        return rows.map {(event,row)->buildContextEntry(row,event.sequence)}.sortedBy {it.occurredAt}
    }

    private fun buildContextEntry(row: JsonObject,sequence: Long): ContextEntry {
        val body=row.string("body")
        val stored=body ?: row.string("content") ?: row.string("public_text") ?: ""
        val header=if(body==null) CONTEXT_HEADER.find(stored) else null
        val withoutHeader=if(header!=null) stored.substring(header.value.length) else stored
        val title=row.string("title")?.takeIf {it.isNotBlank()}
            ?: withoutHeader.substringBefore('\n').trim()
        val text=if(header!=null) withoutHeader.substringAfter('\n',"").trimStart('\n') else withoutHeader
        return ContextEntry(row.string("observation_id") ?: sequence.toString(),
            row.string("synthetic_type") ?: header?.groupValues?.get(1) ?: "NOTE",title,text,
            row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0)
    }
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
        return SessionReview(session,dao.checkpoint(id)?.state ?: "UNKNOWN",dao.counts(id).associate { it.stream to it.count },journal.verify(id),trials,dao.checkpoint(id)?.stateJson?.let {StudyWire.json.parseToJsonElement(it).jsonObject.string("recovery_reason")}!=null,photos(id),contexts(id),journal.deletedPhotoObservations(id).size,journal.retractedContextObservations(id).size)
    }
    fun trial(id: String,trial: TrialSummary): TrialReview {
        val request=find(id,"inference_requests",trial.anchorId)
        val raw=find(id,"raw_responses",trial.anchorId)
        val decision=find(id,"decisions",trial.anchorId)
        val asset=request?.string("current_asset_id")
        // The frame this trial read may have been removed since. `imageForAsset` cannot tell - a deleted
        // observation is still a row - so the lookup asks the tombstones.
        val removed=journal.deletedPhotoObservations(id)
        val image=asset?.let { value -> dao.imageForAsset(id,value)?.json()
            ?.takeIf {row->row.string("observation_id")?.let {it in removed}!=true}
            ?.string("local_relative_path")?.let {journal.localImage(id,it)} }
        val provider=dao.eventForAnchorOfType(id,"raw_responses",trial.anchorId,"PROVIDER_RESPONSE")?.json()
        return TrialReview(trial,request,raw,decision,image,provider)
    }
    /**
     * Takes one photograph out of a record.
     *
     * Refuses the frame a delivered service was reading. That frame is the scene half of a demo frame -
     * the card the wearer actually saw, rendered over the picture the model was looking at - and it is
     * the one thing this record exists to produce, so it is not recoverable by re-rendering. Everything
     * else is the operator's to remove.
     *
     * The policy lives here rather than in the journal because `served` is a join across the request and
     * decision streams that only this layer already does.
     */
    fun deletePhoto(id: String,observationId: String,now: Long): Boolean {
        val photo=photos(id).firstOrNull {it.id==observationId} ?: error("PHOTO_NOT_FOUND")
        check(!photo.served) {"PHOTO_BACKS_DELIVERED_SERVICE"}
        return journal.deletePhoto(id,observationId,now)!=null
    }
}
fun JsonObject.string(key: String)=get(key)?.jsonPrimitive?.contentOrNull

/** The "[TYPE] title" line the two older context writers left at the head of the text. */
private val CONTEXT_HEADER=Regex("^\\[([A-Z_]+)] ?")
