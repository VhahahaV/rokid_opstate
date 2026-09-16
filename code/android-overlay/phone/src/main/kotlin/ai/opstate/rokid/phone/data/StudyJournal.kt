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
    fun append(id: String,stream: String,type: String,payload: JsonObject,atEpochMs: Long): JournalEntity {
        require(stream in streams) { "UNKNOWN_EXPORT_STREAM" }
        val canonical=canonical(payload).toString()
        val payloadHash=StudyWire.sha256(canonical.toByteArray())
        return database.runInTransaction<JournalEntity> {
            check(dao.session(id)!=null) { "UNKNOWN_SESSION" }
            val previous=dao.lastJournal(id)
            val sequence=(previous?.sequence ?: -1)+1
            val previousHash=previous?.chainHash ?: "0".repeat(64)
            val hash=StudyWire.sha256("$sequence|$type|$atEpochMs|$payloadHash|$previousHash".toByteArray())
            JournalEntity(id,sequence,stream,type,atEpochMs,canonical,payloadHash,previousHash,hash,
                (payload["packet_id"] ?: payload["anchor_id"])?.jsonPrimitive?.contentOrNull,payload["asset_id"]?.jsonPrimitive?.contentOrNull).also(dao::insertJournal)
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
                        if(path!=null && seen.add(path)) {
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
