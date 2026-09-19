package ai.opstate.rokid.phone.data

import ai.opstate.protocol.StudyWire
import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Transactional append-only event journal plus private immutable JPEGs. */
class StudyJournal(private val context: Context,val database: StudyDatabase) {
    val dao get()=database.dao()
    private val root=File(context.filesDir,"sessions").apply { mkdirs() }
    val streams=setOf("observations","sensor_windows","context_events","episodes","captures","packets","anchors",
        "inference_requests","raw_responses","decisions","deliveries","receipts","user_feedback","runtime_events")
    private fun sessionDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "INVALID_SESSION_ID" }
        return File(root,id)
    }
    fun start(id: String,epoch: Long,alias: String,scenario: String,condition: String,configHash: String,now: Long,configuration: JsonObject?=null) {
        sessionDirectory(id).mkdirs()
        database.runInTransaction {
            val version=configuration?.get("phone_build")?.jsonObject?.get("version_name")?.jsonPrimitive?.contentOrNull
                ?: runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull() ?: "unknown"
            dao.insertSession(StudySessionEntity(id,epoch,alias,scenario,condition,now,null,version,configHash))
            append(id,"runtime_events","SESSION_START",StudyWire.payload("session_id" to id,"session_epoch" to epoch,
                "scenario_id" to scenario,"condition" to condition,"config_hash" to configHash),now)
            if(configuration!=null) {
                append(id,"runtime_events","SESSION_CONFIGURATION",configuration,now)
                checkpoint(id,epoch,"PREPARING",configuration,now)
            }
        }
    }
    /** The single definition of the chain link, shared by live appends and by merges that re-chain. */
    private fun chained(id: String,sequence: Long,stream: String,type: String,payload: JsonObject,atEpochMs: Long,previousHash: String): JournalEntity {
        val canonical=canonical(payload).toString()
        val payloadHash=StudyWire.sha256(canonical.toByteArray())
        val hash=StudyWire.sha256("$sequence|$type|$atEpochMs|$payloadHash|$previousHash".toByteArray())
        return JournalEntity(id,sequence,stream,type,atEpochMs,canonical,payloadHash,previousHash,hash,
            (payload["packet_id"] ?: payload["anchor_id"])?.jsonPrimitive?.contentOrNull,payload["asset_id"]?.jsonPrimitive?.contentOrNull)
    }
    fun append(id: String,stream: String,type: String,payload: JsonObject,atEpochMs: Long): JournalEntity {
        require(stream in streams) { "UNKNOWN_EXPORT_STREAM" }
        return database.runInTransaction<JournalEntity> {
            check(dao.session(id)!=null) { "UNKNOWN_SESSION" }
            val previous=dao.lastJournal(id)
            chained(id,(previous?.sequence ?: -1)+1,stream,type,payload,atEpochMs,previous?.chainHash ?: "0".repeat(64))
                .also(dao::insertJournal)
        }
    }
    fun image(id: String,captureId: String,bytes: ByteArray,expectedHash: String): String {
        require(captureId.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        require(bytes.size<=8*1024*1024 && StudyWire.sha256(bytes)==expectedHash) { "IMAGE_HASH_MISMATCH" }
        val directory=File(sessionDirectory(id),"images").apply { mkdirs() }
        val target=File(directory,"$captureId.jpg")
        if(target.exists()) {
            check(StudyWire.sha256(target.readBytes())==expectedHash) { "IMAGE_ID_COLLISION" }
        } else {
            val atomic=AtomicFile(target); val output=atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch(error: Exception) { atomic.failWrite(output); throw error }
        }
        return "images/$captureId.jpg"
    }
    fun localImage(id: String,relative: String): File {
        require(relative.matches(Regex("images/[A-Za-z0-9_-]+\\.jpg")))
        return File(sessionDirectory(id),relative)
    }
    fun checkpoint(id: String,epoch: Long,state: String,data: JsonObject,now: Long) {
        dao.checkpoint(RuntimeCheckpointEntity(id,epoch,state,now,canonical(data).toString()))
    }
    fun advanceEpoch(id: String,epoch: Long,now: Long) {
        database.runInTransaction {
            val current=requireNotNull(dao.session(id))
            check(current.endedAtEpochMs==null && epoch>current.sessionEpoch) { "INVALID_SESSION_EPOCH_ADVANCE" }
            append(id,"runtime_events","SESSION_EPOCH_ADVANCED",StudyWire.payload("previous_epoch" to current.sessionEpoch,"session_epoch" to epoch),now)
            dao.updateSession(current.copy(sessionEpoch=epoch))
            dao.checkpoint(id)?.let {dao.checkpoint(it.copy(epoch=epoch,updatedAtEpochMs=now))}
        }
    }
    fun stop(id: String,now: Long) {
        database.runInTransaction {
            val session=requireNotNull(dao.session(id))
            if(session.endedAtEpochMs!=null) return@runInTransaction
            append(id,"runtime_events","SESSION_STOP",StudyWire.payload("session_id" to id,"stopped_at_epoch_ms" to now),now)
            dao.updateSession(session.copy(endedAtEpochMs=now))
            checkpoint(id,session.sessionEpoch,"STOPPED",dao.checkpoint(id)?.stateJson?.let { StudyWire.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap()),now)
        }
    }
    /** Called once at process startup, before new work. An unacknowledged service is never replayed. */
    fun recoverInterrupted(now: Long=System.currentTimeMillis()): Int {
        var recovered=0
        database.runInTransaction {
            for(session in dao.unfinishedSessions()) {
                val checkpoint=dao.checkpoint(session.sessionId)
                val pending=InterruptedOperations()
                readVerified(snapshot(session.sessionId),pending::record)
                val data=checkpoint?.stateJson?.let {StudyWire.json.parseToJsonElement(it).jsonObject} ?: pending.configuration
                for(operation in pending.cancelled(now)) append(session.sessionId,operation.stream,operation.type,operation.payload,now)
                append(session.sessionId,"runtime_events","SESSION_INTERRUPTED",StudyWire.payload("reason" to "PROCESS_RESTART",
                    "last_phase" to (checkpoint?.state ?: "PREPARING"),"last_checkpoint_at_epoch_ms" to checkpoint?.updatedAtEpochMs),now)
                checkpoint(session.sessionId,session.sessionEpoch,"INTERRUPTED",JsonObject(data+("recovery_reason" to JsonPrimitive("PROCESS_RESTART"))),now)
                stop(session.sessionId,now)
                recovered++
            }
        }
        return recovered
    }
    private data class Snapshot(val session: StudySessionEntity,val checkpoint: RuntimeCheckpointEntity?,val last: JournalEntity)
    private fun snapshot(id: String)=database.runInTransaction<Snapshot> {
        Snapshot(requireNotNull(dao.session(id)),dao.checkpoint(id),requireNotNull(dao.lastJournal(id)))
    }
    private fun readVerified(snapshot: Snapshot,consume: (JournalEntity)->Unit) {
        var after=-1L
        var previous="0".repeat(64)
        while(after<snapshot.last.sequence) {
            val page=dao.journalPage(snapshot.session.sessionId,after,snapshot.last.sequence)
            check(page.isNotEmpty()) { "AUDIT_CHAIN_TRUNCATED" }
            for(row in page) {
                val hash=StudyWire.sha256("${row.sequence}|${row.eventType}|${row.eventAtEpochMs}|${row.payloadSha256}|$previous".toByteArray())
                check(row.sequence==after+1 && row.previousHash==previous && row.chainHash==hash &&
                    row.stream in streams && StudyWire.sha256(row.canonicalJson.toByteArray())==row.payloadSha256) { "AUDIT_CHAIN_INVALID" }
                consume(row)
                after=row.sequence;previous=row.chainHash
            }
        }
        check(previous==snapshot.last.chainHash) { "AUDIT_HEAD_CHANGED" }
    }
    @Synchronized fun verify(id: String): Boolean = runCatching { readVerified(snapshot(id)) {} }.isSuccess

    /** A fixed journal high-water mark; bounded pages avoid loading cumulative replay requests into RAM. */
    @Synchronized fun export(id: String): File {
        val snapshot=snapshot(id)
        require(snapshot.checkpoint?.state in setOf("STOPPED","PAUSED")) { "PAUSE_OR_STOP_BEFORE_EXPORT" }
        val frozen=snapshot.checkpoint?.stateJson?.let { StudyWire.json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val contract=frozen["contract"]?.jsonObject ?: error("SESSION_CONTRACT_NOT_RECORDED")
        // A photograph the operator removed keeps its row in observations.jsonl - the record still says
        // the frame existed - but its bytes are deliberately not in the archive. The tombstone row in
        // runtime_events.jsonl is what says why the file is absent, and what the trace validator reads.
        val removed=deletedPhotoObservations(id)
        val directory=File(context.cacheDir,"exports/$id-${UUID.randomUUID()}").apply { mkdirs() }
        val archive=File(directory.parentFile,"${directory.name}.zip")
        val writers=streams.associateWith { File(directory,"$it.jsonl").bufferedWriter() }
        try {
            File(directory,"audit.jsonl").bufferedWriter().use { audit ->
                val seen=hashSetOf<String>()
                readVerified(snapshot) { row ->
                    writers.getValue(row.stream).apply { write(row.canonicalJson);newLine() }
                    audit.write(StudyWire.payload("sequence" to row.sequence,"event_type" to row.eventType,
                        "event_at_epoch_ms" to row.eventAtEpochMs,"stream" to row.stream,
                        "payload_sha256" to row.payloadSha256,"previous_chain_sha256" to row.previousHash,
                        "chain_sha256" to row.chainHash,"canonical_payload" to row.canonicalJson).toString())
                    audit.newLine()
                    if(row.stream=="observations") {
                        val data=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
                        val path=data["local_relative_path"]?.jsonPrimitive?.contentOrNull
                        if(path!=null && data["observation_id"]?.jsonPrimitive?.contentOrNull !in removed && seen.add(path)) {
                            val source=localImage(id,path)
                            check(source.isFile && hashFile(source)==data.getValue("sha256").jsonPrimitive.content) { "EXPORT_IMAGE_HASH_MISMATCH" }
                            val target=File(directory,path);target.parentFile!!.mkdirs();source.copyTo(target)
                        }
                    }
                }
            }
            writers.values.forEach { it.close() }
            val files=directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.toList()
            val session=snapshot.session
            val manifest=StudyWire.payload("schema_version" to "opstate_session_export_v2","session_id" to id,
                "session_epoch" to session.sessionEpoch,"participant_alias" to session.participantAlias,"scenario_id" to session.scenarioId,
                "condition_code" to session.conditionCode,"started_at_epoch_ms" to session.startedAtEpochMs,"ended_at_epoch_ms" to session.endedAtEpochMs,
                "journal_entries" to snapshot.last.sequence+1,"audit_head_sha256" to snapshot.last.chainHash,
                "phone_app_version" to session.appVersion,"glasses_app_version" to (frozen["glasses_app_version"]?.jsonPrimitive?.contentOrNull ?: "not_recorded"),
                "runtime_config_sha256" to session.configHash,"model_id" to contract.getValue("model_id"),
                "prompt_sha256" to contract.getValue("prompt_hash"),"preprocess_sha256" to contract.getValue("preprocess_hash"),
                "parser_sha256" to contract.getValue("parser_hash"),"frozen" to frozen,
                "files" to JsonArray(files.map { file -> StudyWire.payload("path" to file.relativeTo(directory).invariantSeparatorsPath,
                    "sha256" to hashFile(file),"bytes" to file.length()) }))
            File(directory,"session_manifest.json").writeText(canonical(manifest).toString())
            File(directory,"SHA256SUMS.txt").writeText((files+File(directory,"session_manifest.json")).joinToString("") {
                "${hashFile(it)}  ${it.relativeTo(directory).invariantSeparatorsPath}\n"
            })
            ZipOutputStream(archive.outputStream()).use { zip ->
                directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
                }
            }
            return archive
        } catch(error: Throwable) {
            archive.delete();throw error
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            directory.deleteRecursively()
        }
    }
    /**
     * Appends operator-authored context to a record, including a finished one.
     *
     * The live path refuses to add context outside a SERVICE session, but reviewing a record means
     * enriching history after the fact, so this writes the same two streams the runtime does: an
     * observation the timeline renders beside the photos, and a context event recording that an
     * operator supplied it. Appending keeps the chain valid and leaves the STOPPED checkpoint in
     * place, so the record stays exportable.
     */
    @Synchronized fun addSyntheticContext(id: String,type: String,title: String,body: String,atEpochMs: Long): String {
        require(type in setOf("EMAIL","CALENDAR","MESSAGE","NOTE")) { "CONTEXT_INPUT_INVALID" }
        val trimmedTitle=title.trim();val trimmedBody=body.trim()
        require(trimmedTitle.isNotEmpty() && trimmedTitle.length<=80 && trimmedBody.length<=4000) { "CONTEXT_INPUT_INVALID" }
        val session=requireNotNull(dao.session(id)) { "UNKNOWN_SESSION" }
        val text="[$type] $trimmedTitle\n$trimmedBody"
        val eventId="manual-${UUID.randomUUID()}"
        database.runInTransaction {
            // Same shape the runtime writes for every observation, so the readers that later rebuild a
            // service session from this record - LocalCollectionRepository.contextRows among them - find
            // it. Using a distinct event type here made the note invisible to all of them.
            append(id,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                "observation_id" to eventId,"kind" to "SYNTHETIC_CONTEXT",
                "occurred_at_epoch_ms" to atEpochMs,"available_at_epoch_ms" to atEpochMs,
                "content" to text,"asset_id" to null,"episode_handle" to null,"material_revision" to null,
                "public_text" to text,"source" to "OPERATOR_SYNTHETIC","synthetic_type" to type,
                "title" to trimmedTitle,"body" to trimmedBody,
                "added_after_session_end" to (session.endedAtEpochMs!=null)),atEpochMs)
            append(id,"context_events","OPERATOR_CONTEXT_ADDED",StudyWire.payload(
                "event_id" to eventId,"synthetic_type" to type,"title" to trimmedTitle,
                "public_text" to text,"occurred_at_epoch_ms" to atEpochMs,
                "source" to "OPERATOR_SYNTHETIC"),atEpochMs)
        }
        return eventId
    }

    /**
     * The photographs an operator has taken out of this record. The journal still holds them and the
     * chain still verifies; this is the set every reader subtracts, because their bytes are gone.
     */
    fun deletedPhotoObservations(id: String): Set<String> = dao.photoDeletions(id).mapNotNull {event->
        StudyWire.json.parseToJsonElement(event.canonicalJson).jsonObject["observation_id"]?.jsonPrimitive?.contentOrNull
    }.toSet()

    /**
     * Removes one photograph, without removing anything from the journal.
     *
     * The chain is append-only and covers every row including the observation that describes the
     * image, so a deletion is written as a new event naming the observation it retires instead of by
     * taking that row out. The record then still says the frame existed and that an operator took it
     * away, [verify] keeps passing and [export] keeps working. That is the honest shape for a study
     * record: what changed is not the history, it is which material is still on the phone.
     *
     * The bytes are unlinked second, so a failure between the two leaves an orphan file the readers
     * already ignore, rather than a journal promising a file the disk does not have.
     *
     * Returns the tombstone payload, or null when the photo is unknown or was already deleted, so a
     * second tap appends nothing.
     */
    @Synchronized fun deletePhoto(id: String,observationId: String,now: Long): JsonObject? {
        val session=requireNotNull(dao.session(id)) {"UNKNOWN_SESSION"}
        val removed=deletedPhotoObservations(id)
        if(observationId in removed) return null
        // The observation row is the authority on what this photograph was - its asset, its length and
        // where the bytes live all come from it, never from the caller, so a stale caller cannot point
        // the deletion at the wrong file. Read once: a long record's observations are not cheap to
        // parse, and this runs while the operator waits.
        val observations=dao.stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
        fun observationIdOf(row: JsonObject)=row["observation_id"]?.jsonPrimitive?.contentOrNull
        val row=observations.firstOrNull {observationIdOf(it)==observationId && it["kind"]==JsonPrimitive("IMAGE")} ?: return null
        val path=row["local_relative_path"]?.jsonPrimitive?.contentOrNull ?: return null
        val payload=StudyWire.payload(
            // `event` is redundant with the journal column, and it is here on purpose: an export writes
            // payloads only, so without it the archived trace could not say which rows are deletions.
            "event" to "PHOTO_DELETED","observation_id" to observationId,
            "asset_id" to row["asset_id"]?.jsonPrimitive?.contentOrNull,
            "local_relative_path" to path,"sha256" to row["sha256"]?.jsonPrimitive?.contentOrNull,
            "byte_length" to row["byte_length"]?.jsonPrimitive?.intOrNull,
            "quality_valid" to row["quality_valid"]?.jsonPrimitive?.booleanOrNull,
            "occurred_at_epoch_ms" to row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull,
            "available_at_epoch_ms" to row["available_at_epoch_ms"]?.jsonPrimitive?.longOrNull,
            "deleted_at_epoch_ms" to now,"deleted_during_session" to (session.endedAtEpochMs==null),
            "source" to "OPERATOR_DELETE")
        database.runInTransaction {append(id,"runtime_events","PHOTO_DELETED",payload,now)}
        // Only when nothing live still points at the same file. Two observations can share one capture
        // id - an imported history frame and the record it came from are the same bytes - and then one
        // file backs both of them.
        val shared=observations.any {
            it["local_relative_path"]?.jsonPrimitive?.contentOrNull==path &&
                observationIdOf(it)?.let {other->other!=observationId && other !in removed}==true
        }
        if(!shared) runCatching {localImage(id,path).delete()}
        return payload
    }

    /** The name a record is listed under is `participantAlias`; the audit chain stores no alias, so
     *  renaming cannot invalidate it. */
    @Synchronized fun rename(id: String,name: String) {
        val trimmed=name.trim()
        require(trimmed.isNotBlank() && trimmed.length<=80) { "INVALID_RECORD_NAME" }
        val session=requireNotNull(dao.session(id)) { "UNKNOWN_SESSION" }
        dao.updateSession(session.copy(participantAlias=trimmed))
    }
    @Synchronized fun deleteMany(ids: List<String>) { ids.forEach(::delete) }

    private data class MergeSource(val session: StudySessionEntity,val rows: List<JournalEntity>)
    private fun copyImages(sources: List<MergeSource>,target: String) {
        val directory=File(sessionDirectory(target),"images").apply { mkdirs() }
        for(source in sources) {
            for(file in File(sessionDirectory(source.session.sessionId),"images").listFiles()?.sortedBy { it.name } ?: emptyList()) {
                val destination=File(directory,file.name)
                if(destination.exists()) check(hashFile(destination)==hashFile(file)) { "MERGE_IMAGE_ID_COLLISION" }
                else file.copyTo(destination)
            }
        }
    }
    private fun copyMetadata(sources: List<String>,target: String) {
        for(id in sources) {
            dao.contextEvents(id).forEach {dao.contextEvent(it.copy(sessionId=target))}
            dao.observations(id).forEach {dao.observation(it.copy(sessionId=target))}
            dao.sensorWindows(id).forEach {dao.sensorWindow(it.copy(sessionId=target))}
            dao.episodes(id).forEach {dao.episode(it.copy(sessionId=target))}
            dao.anchors(id).forEach {dao.anchor(it.copy(sessionId=target))}
            dao.requests(id).forEach {dao.inference(it.copy(sessionId=target))}
            dao.decisions(id).forEach {dao.decision(it.copy(sessionId=target))}
            dao.deliveries(id).forEach {dao.delivery(it.copy(sessionId=target))}
            dao.receipts(id).forEach {dao.receipt(it.copy(sessionId=target))}
            dao.audits(id).forEach {dao.audit(it.copy(sessionId=target))}
        }
    }
    /**
     * Folds finished records into one. Journals are chained per session and seeded from 64 zeros, so two
     * chains cannot be spliced: the merged record gets a fresh chain opening with a provenance header
     * that names the sources and their chain heads, followed by every source event in time order,
     * re-chained. Payloads are copied verbatim, so each event still describes the session it came from
     * and the merged journal reads as the concatenation it is.
     *
     * Sources are removed only after the merged record is complete, so an interrupted merge leaves the
     * originals intact and the half-built record clearly unfinished (no STOPPED checkpoint), which is
     * the state [delete] and [export] both already refuse to operate on.
     */
    @Synchronized fun merge(ids: List<String>,name: String): String {
        require(ids.size>=2 && ids.toSet().size==ids.size) { "MERGE_REQUIRES_DISTINCT_RECORDS" }
        val trimmed=name.trim()
        require(trimmed.isNotBlank() && trimmed.length<=80) { "INVALID_RECORD_NAME" }
        val sources=ids.map { id ->
            val session=requireNotNull(dao.session(id)) { "UNKNOWN_SESSION" }
            check(session.endedAtEpochMs!=null && dao.checkpoint(id)?.state in setOf(null,"STOPPED")) { "STOP_SESSION_BEFORE_MERGE" }
            val rows=ArrayList<JournalEntity>()
            readVerified(snapshot(id)) { rows.add(it) }
            MergeSource(session,rows)
        }.sortedBy { it.session.startedAtEpochMs }
        val mergedId="session-${UUID.randomUUID()}"
        val directory=sessionDirectory(mergedId)
        check(directory.mkdirs()) { "SESSION_DIRECTORY_CREATE_FAILED" }
        try {
            copyImages(sources,mergedId)
            database.runInTransaction {
                val first=sources.first().session
                val started=first.startedAtEpochMs
                val ended=sources.maxOf {requireNotNull(it.session.endedAtEpochMs)}
                dao.insertSession(StudySessionEntity(mergedId,first.sessionEpoch,trimmed,first.scenarioId,
                    first.conditionCode,started,null,first.appVersion,first.configHash))
                var previous="0".repeat(64)
                var sequence=0L
                fun link(stream: String,type: String,payload: JsonObject,at: Long) {
                    val row=chained(mergedId,sequence,stream,type,payload,at,previous)
                    dao.insertJournal(row);previous=row.chainHash;sequence++
                }
                link("runtime_events","SESSION_MERGE",StudyWire.payload(
                    "merged_session_id" to mergedId,"record_name" to trimmed,
                    "merged_at_epoch_ms" to System.currentTimeMillis(),
                    "sources" to JsonArray(sources.map { source ->
                        StudyWire.payload("session_id" to source.session.sessionId,
                            "participant_alias" to source.session.participantAlias,
                            "started_at_epoch_ms" to source.session.startedAtEpochMs,
                            "ended_at_epoch_ms" to source.session.endedAtEpochMs,
                            "journal_entries" to source.rows.size,
                            "audit_head_sha256" to (source.rows.lastOrNull()?.chainHash ?: "0".repeat(64)))})),started)
                for(source in sources) for(row in source.rows)
                    link(row.stream,row.eventType,StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject,row.eventAtEpochMs)
                copyMetadata(ids,mergedId)
                dao.updateSession(requireNotNull(dao.session(mergedId)).copy(endedAtEpochMs=ended))
                // Inherit the earliest source's frozen configuration so the merged record stays
                // exportable; export refuses a session whose checkpoint records no contract.
                val inherited=dao.checkpoint(first.sessionId)?.stateJson
                    ?.let {runCatching {StudyWire.json.parseToJsonElement(it).jsonObject}.getOrNull()} ?: JsonObject(emptyMap())
                checkpoint(mergedId,first.sessionEpoch,"STOPPED",
                    JsonObject(inherited+("merged_from" to JsonArray(sources.map {JsonPrimitive(it.session.sessionId)}))),ended)
            }
        } catch(error: Throwable) {
            directory.deleteRecursively();throw error
        }
        deleteMany(ids)
        return mergedId
    }
    @Synchronized fun delete(id: String) {
        check(dao.checkpoint(id)?.state in setOf(null,"STOPPED")) { "STOP_SESSION_BEFORE_DELETE" }
        check(!sessionDirectory(id).exists() || sessionDirectory(id).deleteRecursively()) { "SESSION_FILE_DELETE_FAILED" }
        File(context.cacheDir,"exports").listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { check(it.deleteRecursively()) { "EXPORT_DELETE_FAILED" } }
        database.runInTransaction {
            dao.deleteObservations(id); dao.deleteContext(id); dao.deleteSensors(id); dao.deleteEpisodes(id)
            dao.deleteAnchors(id); dao.deleteRequests(id); dao.deleteDecisions(id); dao.deleteDeliveries(id)
            dao.deleteReceipts(id); dao.deleteAudits(id); dao.deleteJournal(id); dao.deleteCheckpoint(id); dao.deleteSession(id)
        }
    }
    companion object {
        fun hashFile(file: File): String {
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(64*1024)
                while(true) { val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun canonical(value: JsonElement): JsonElement=when(value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
    }
}
