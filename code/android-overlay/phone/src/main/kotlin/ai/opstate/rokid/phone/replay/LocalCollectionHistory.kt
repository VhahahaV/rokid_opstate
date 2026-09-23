package ai.opstate.rokid.phone.replay

import ai.opstate.core.model.ObservationKind
import ai.opstate.core.model.ObservationRecord
import ai.opstate.core.hr.HrAgentOutput
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.data.contextObservationKey
import kotlinx.serialization.json.*
import java.io.File

data class LocalCollectionSummary(
    val sessionId: String,
    val participantAlias: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val validImages: Int,
    val contextCount: Int=0,
)

data class CollectedHistoryImage(
    val record: ObservationRecord,
    val jpegFile: File,
    val sha256: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sourceObservationId: String,
    val metadata: JsonObject=JsonObject(emptyMap()),
)

data class LocalCollectionSeed(val summary: LocalCollectionSummary,val images: List<CollectedHistoryImage>,
    val contexts: List<ObservationRecord> = emptyList(),val priorServices: List<HrAgentOutput> = emptyList())

/** Reads immutable, completed collection sessions and freezes a bounded visual prefix for a service session. */
class LocalCollectionRepository(private val journal: StudyJournal) {
    fun list(): List<LocalCollectionSummary> = journal.dao.sessions().mapNotNull {session->
        val ended=session.endedAtEpochMs ?: return@mapNotNull null
        if(configuration(session.sessionId)["session_mode"] !in setOf(JsonPrimitive("COLLECTION"),JsonPrimitive("SERVICE"))) return@mapNotNull null
        LocalCollectionSummary(session.sessionId,session.participantAlias,session.startedAtEpochMs,ended,validRows(session.sessionId).size,contextRows(session.sessionId).size)
    }.filter {it.validImages>0 || it.contextCount>0}

    /**
     * Seeds a service session from a record. [imageLimit] of 0 takes the whole record; a positive value
     * takes that many of the most recent captures. The model request still carries at most four
     * historical frames (`takeLast(4)` where the request is built), so how much history a session holds
     * is independent of what any single call sends.
     */
    fun load(sessionId: String,imageLimit: Int): LocalCollectionSeed {
        require(imageLimit in 0..MAX_HISTORY_IMAGES) {"COLLECTION_HISTORY_LIMIT_INVALID"}
        val summary=list().singleOrNull {it.sessionId==sessionId} ?: error("COLLECTION_SESSION_NOT_AVAILABLE")
        val all=validRows(sessionId)
        val rows=if(imageLimit<=0) all else all.takeLast(imageLimit)
        val sourceToken=sessionId.removePrefix("session-").replace("-","").take(12)
        val images=rows.mapIndexed {index,row->
            val path=row.getValue("local_relative_path").jsonPrimitive.content
            val sha=row.getValue("sha256").jsonPrimitive.content
            val jpegFile=journal.localImage(sessionId,path)
            check(jpegFile.isFile && StudyJournal.hashFile(jpegFile)==sha) {"COLLECTION_IMAGE_HASH_MISMATCH"}
            val sourceObservationId=row.getValue("observation_id").jsonPrimitive.content
            val occurred=row.getValue("occurred_at_epoch_ms").jsonPrimitive.long
            val available=row.getValue("available_at_epoch_ms").jsonPrimitive.long
            val asset="history-${sha.take(16)}"
            val record=ObservationRecord("collection-$sourceToken-${index.toString().padStart(3,'0')}",ObservationKind.IMAGE,
                occurred,available,"Collected historical image ${index+1} of ${rows.size} from session $sessionId.",assetId=asset)
            CollectedHistoryImage(record,jpegFile,sha,row.getValue("width").jsonPrimitive.int,row.getValue("height").jsonPrimitive.int,
                row.getValue("rotation_degrees").jsonPrimitive.int,sourceObservationId,row)
        }
        // `content` is the observation's text field; notes written before it was supplied carry the
        // same text in `public_text`, and the journal cannot be rewritten, so read either.
        val contexts=contextRows(sessionId).mapIndexedNotNull {index,row->
            val text=row["content"]?.jsonPrimitive?.contentOrNull ?: row["public_text"]?.jsonPrimitive?.contentOrNull
                ?: return@mapIndexedNotNull null
            ObservationRecord("history-context-$sourceToken-$index",ObservationKind.SYNTHETIC_CONTEXT,
                row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                row["available_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L,text)
        }
        return LocalCollectionSeed(summary,images,contexts,priorServices(sessionId))
    }

    /** Previous model outputs stay separate from public observations and cannot become factual citations. */
    private fun priorServices(sessionId: String): List<HrAgentOutput> {
        val delivered=journal.dao.stream(sessionId,"receipts").filter {it.eventType=="RECEIPT_COMMITTED"}
            .mapNotNull {row->StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["decision_id"]
                ?.jsonPrimitive?.contentOrNull}.toSet()
        return journal.dao.stream(sessionId,"decisions").asSequence()
            .filter {it.eventType=="MODEL_DECISION"}
            .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            .filter {it["decision"]==JsonPrimitive("SERVICE")}
            .mapNotNull {row->
                val title=row["title"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()} ?: return@mapNotNull null
                val body=row["body"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()} ?: return@mapNotNull null
                val decisionId=row["decision_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                HrAgentOutput(row["packet_id"]?.jsonPrimitive?.contentOrNull ?: decisionId,
                    row["produced_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    title,body,row["evidence_observation_ids"]?.jsonArray?.mapNotNull {it.jsonPrimitive.contentOrNull}.orEmpty(),
                    decisionId in delivered)
            }.toList()
    }

    // A note added by an operator is an observation like any other, so its payload decides what it is
    // rather than the event type that carried it. Accepting both also keeps notes written before the
    // writer used OBSERVATION_PERSISTED usable, which matters because the journal is append-only and
    // its chain hash covers the event type - those rows cannot be rewritten in place.
    private fun contextRows(sessionId: String): List<JsonObject> {
        val withdrawn=journal.retractedContextObservations(sessionId)
        return journal.dao.stream(sessionId,"observations")
            .filter {it.eventType=="OBSERVATION_PERSISTED" || it.eventType=="SYNTHETIC_CONTEXT"}
            .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            .filter {it["kind"]==JsonPrimitive("SYNTHETIC_CONTEXT")}
            // A note the ledger has withdrawn is not history any more, whichever copy of it this is.
            .filter {row->row["observation_id"]?.jsonPrimitive?.contentOrNull?.let {it in withdrawn}!=true}
            // A merged collection may contain the same source note once per imported segment. Keep one
            // causal fact, not one copy per merge, and present it in event time rather than append order.
            .distinctBy(::contextObservationKey)
            .sortedWith(compareBy<JsonObject> {it["available_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L}
                .thenBy {it["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L})
    }

    private fun configuration(sessionId: String): JsonObject = journal.dao.stream(sessionId,"runtime_events")
        .firstOrNull {it.eventType=="SESSION_CONFIGURATION"}?.canonicalJson
        ?.let {StudyWire.json.parseToJsonElement(it).jsonObject} ?: JsonObject(emptyMap())

    /** Photographs this record can still hand to a new session. A picture the operator removed is not
     *  one of them - its bytes are gone, so seeding it would fail on the read. */
    private fun validRows(sessionId: String): List<JsonObject> {
        val removed=journal.deletedPhotoObservations(sessionId)
        return journal.dao.stream(sessionId,"observations")
        .asSequence().filter {it.eventType=="OBSERVATION_PERSISTED"}
        .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
        .filter {it["kind"]==JsonPrimitive("IMAGE") && it["quality_valid"]==JsonPrimitive(true) &&
            it["observation_id"]?.jsonPrimitive?.contentOrNull?.let {id->id in removed}!=true &&
            it["local_relative_path"]?.jsonPrimitive?.contentOrNull!=null && it["sha256"]?.jsonPrimitive?.contentOrNull!=null}
        // Historical segments can be copied into the same merged collection several times. A
        // hash and capture time identify that occurrence; preserve a genuinely later identical
        // frame while removing copies of the same one. Then `takeLast` means recent capture time.
        .distinctBy {it["sha256"]!!.jsonPrimitive.content to
            (it["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L)}
        .sortedWith(compareBy<JsonObject> {it["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0L}
            .thenBy {it["observation_id"]?.jsonPrimitive?.contentOrNull.orEmpty()})
        .toList()
    }

    companion object {
        /** An upper bound on how much of a record a session will copy, not a model-input limit. */
        const val MAX_HISTORY_IMAGES=1000
    }
}
