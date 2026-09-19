package ai.opstate.rokid.phone.review

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.data.StudySessionEntity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A moment a proactive service was really on the glasses, together with the picture it was reading.
 *
 * This is the whole point of the study materialised: `scene` is the frame the model saw, `title` and
 * `body` are what the wearer read, and the two together are what the 展示 tab assembles.
 */
data class ShowcaseFrame(val sessionId: String,val recordName: String,val anchorId: String,val decisionId: String,
    val at: Long,val title: String,val body: String,val mode: String?,val latencyMs: Long?,val scene: File)

/**
 * What a selection remembers. Deliberately not the [File] and not the text: a record can be renamed,
 * a photo deleted, and the selection has to survive all of that by being re-resolved against a fresh
 * scan rather than by holding on to a path that may no longer mean anything.
 */
data class ShowcaseRef(val sessionId: String,val anchorId: String,val decisionId: String)

data class ShowcaseState(val loaded: Boolean=false,val loading: Boolean=false,
    val frames: List<ShowcaseFrame> = emptyList(),val selected: List<ShowcaseRef> = emptyList(),
    val group: String = DEFAULT_GROUP) {
    /** The chosen moments, in the order they were chosen, minus any whose record has gone. */
    val chosen get()=ShowcaseRepository.resolve(frames,selected)
    companion object {const val DEFAULT_GROUP="展示集"}
}

object ShowcaseRepository {
    /**
     * Every service that was really shown, across every record, newest first.
     *
     * The rule is the one the demo frames have always used and it is worth restating: a service that
     * was suppressed, superseded, or never acknowledged is not a moment anyone saw.
     *
     * It is deliberately keyed by decision rather than by photograph. `CapturedPhoto.served` looks like
     * the same question and is not: it is built by joining each *asset* to the service reading it, so a
     * frame that two services read is described by whichever came last - a service that failed to
     * display can hide one that the wearer actually saw. Walking the decisions is also the cheap
     * direction: nothing here parses a record's observations, which on a collection record is several
     * hundred rows that cannot contain a service at all.
     */
    fun scan(journal: StudyJournal,sessions: List<StudySessionEntity>): List<ShowcaseFrame> =
        sessions.sortedByDescending {it.startedAtEpochMs}.flatMap {session->
            val visible=lastDeliveryByDecision(journal,session.sessionId)
            val removed=journal.deletedPhotoObservations(session.sessionId)
            journal.dao.stream(session.sessionId,"decisions").mapNotNull {event->
                val decision=json(event.canonicalJson)
                if(decision.string("decision")!="SERVICE") return@mapNotNull null
                val decisionId=decision.string("decision_id") ?: return@mapNotNull null
                if(visible[decisionId]!="DISPLAY_VISIBLE") return@mapNotNull null
                val title=decision.string("title") ?: return@mapNotNull null
                val anchor=decision.string("packet_id") ?: decision.string("anchor_id") ?: return@mapNotNull null
                // The picture this service was reading, resolved the same indexed way the record view
                // does it: the request names the asset, the asset names the observation and its file.
                val asset=journal.dao.eventForAnchor(session.sessionId,"inference_requests",anchor)?.canonicalJson
                    ?.let {json(it).string("current_asset_id")} ?: return@mapNotNull null
                val row=journal.dao.imageForAsset(session.sessionId,asset)?.canonicalJson?.let(::json) ?: return@mapNotNull null
                val path=row.string("local_relative_path") ?: return@mapNotNull null
                if(row.string("observation_id")?.let {it in removed}==true) return@mapNotNull null
                ShowcaseFrame(session.sessionId,session.participantAlias,anchor,decisionId,
                    row["source_occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull
                        ?: row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    title,decision.string("body").orEmpty(),decision.string("service_mode"),
                    decision["model_latency_ms"]?.jsonPrimitive?.longOrNull,
                    journal.localImage(session.sessionId,path))
            }
        }.sortedByDescending {it.at}

    private fun json(canonical: String)=StudyWire.json.parseToJsonElement(canonical).jsonObject

    /** The chosen frames in the order they were chosen. A selection whose record is gone drops out. */
    fun resolve(frames: List<ShowcaseFrame>,refs: List<ShowcaseRef>): List<ShowcaseFrame> {
        val byRef=frames.associateBy(::refOf)
        return refs.mapNotNull {byRef[it]}
    }

    fun refOf(frame: ShowcaseFrame)=ShowcaseRef(frame.sessionId,frame.anchorId,frame.decisionId)

    // `stream` is ordered by sequence, so the last row for a decision is its final status - which for a
    // service that was attempted and then confirmed is the one that says whether anyone saw it.
    private fun lastDeliveryByDecision(journal: StudyJournal,sessionId: String): Map<String,String> =
        journal.dao.stream(sessionId,"deliveries").mapNotNull {event->
            val row=json(event.canonicalJson)
            val id=row.string("decision_id") ?: return@mapNotNull null
            val status=row.string("status") ?: return@mapNotNull null
            id to status
        }.toMap()
}

/** The selection as it is kept between launches. A list of refs and a name, and nothing else. */
object ShowcaseStore {
    private const val SELECTION="showcase_selection"
    private const val GROUP="showcase_group"

    fun read(prefs: SharedPreferences): Pair<List<ShowcaseRef>,String> {
        val refs=prefs.getString(SELECTION,null)?.let {stored->
            runCatching {
                StudyWire.json.parseToJsonElement(stored).jsonArray.mapNotNull {element->
                    val row=element.jsonObject
                    val sessionId=row.string("session_id") ?: return@mapNotNull null
                    val anchorId=row.string("anchor_id") ?: return@mapNotNull null
                    val decisionId=row.string("decision_id") ?: return@mapNotNull null
                    ShowcaseRef(sessionId,anchorId,decisionId)
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return refs to (prefs.getString(GROUP,null)?.takeIf {it.isNotBlank()} ?: ShowcaseState.DEFAULT_GROUP)
    }

    fun write(prefs: SharedPreferences,selected: List<ShowcaseRef>,group: String) {
        val rows=selected.map {StudyWire.payload("session_id" to it.sessionId,"anchor_id" to it.anchorId,"decision_id" to it.decisionId)}
        prefs.edit().putString(SELECTION,JsonArray(rows).toString()).putString(GROUP,group).apply()
    }
}

/**
 * Turning a selection into files somebody can look at.
 *
 * Two steps on purpose. [write] is ordinary file work and can be tested anywhere; [publish] is the
 * only part that knows about the phone's gallery. The set is assembled in the app's own directory
 * first, so the index that says where each frame came from stays with the app even though the pictures
 * go somewhere a person can browse.
 */
object ShowcaseExporter {
    const val ALBUM="OPSTATE"

    /**
     * Three files per moment, numbered in the order they were chosen:
     * the composite, the original it was drawn over, and the card's own words.
     *
     * The pair is the point. The composite is what the wearer saw and is the thing worth showing; the
     * original is what lets someone check that the composite is not a picture of a picture.
     */
    fun write(root: File,frames: List<ShowcaseFrame>): List<File> {
        root.mkdirs()
        val written=mutableListOf<File>()
        val index=StringBuilder()
        frames.forEachIndexed { position,frame->
            val number=(position+1).toString().padStart(2,'0')
            val scene=BitmapFactory.decodeFile(frame.scene.path) ?: return@forEachIndexed
            val composite=DemoFrameRenderer.render(scene,DemoFrameRenderer.Card(frame.title,frame.body,
                DemoFrameRenderer.modeLabel(frame.mode),true))
            scene.recycle()
            val compositeFile=File(root,"${number}_composite.jpg")
            compositeFile.outputStream().use {composite.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,it)}
            composite.recycle()
            val original=File(root,"${number}_original.jpg")
            frame.scene.copyTo(original,overwrite=true)
            val card=File(root,"${number}_card.txt")
            card.writeText(cardText(frame))
            written+=listOf(compositeFile,original,card)
            index.append(StudyWire.json.encodeToString(JsonObject.serializer(),StudyWire.payload(
                "position" to position+1,"session_id" to frame.sessionId,"anchor_id" to frame.anchorId,
                "decision_id" to frame.decisionId,"at_epoch_ms" to frame.at,"title" to frame.title,
                "body" to frame.body,"service_mode" to frame.mode,"model_latency_ms" to frame.latencyMs,
                "record_name" to frame.recordName,"scene_sha256" to StudyJournal.hashFile(frame.scene),
                "files" to listOf(compositeFile.name,original.name,card.name)))).append('\n')
        }
        val indexFile=File(root,"index.jsonl")
        indexFile.writeText(index.toString())
        return written+indexFile
    }

    /**
     * Copies the set out of [root] to where a person can find it, and returns how many files landed.
     *
     * The pictures go to `Pictures/OPSTATE/<group>` - that is what the gallery shows - and the card
     * text goes to `Download/OPSTATE/<group>`, because a gallery directory takes images and nothing
     * else: `MediaStore.Files` refuses a text file under `Pictures/` outright.
     *
     * The text is not also attached to the picture. `MediaStore`'s `description` column is re-derived
     * from the file's own XMP on every scan, so a plain insert does not keep what it is given, and
     * writing XMP into the JPEG to carry one paragraph is not worth what it would cost.
     *
     * Whatever this app previously left in those two folders is removed first. Without that a second
     * export does not replace the first - MediaStore renames the collision to `01_composite (1).jpg` -
     * and a shortlist that grows a duplicate copy on every export is worse than no shortlist.
     */
    fun publish(context: Context,group: String,root: File): Int {
        check(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {"SHOWCASE_REQUIRES_ANDROID_10"}
        val pictures="${Environment.DIRECTORY_PICTURES}/$ALBUM/${sanitize(group)}"
        val documents="${Environment.DIRECTORY_DOWNLOADS}/$ALBUM/${sanitize(group)}"
        clearFolder(context,pictures)
        clearFolder(context,documents)
        var published=0
        for(file in root.listFiles()?.sortedBy {it.name} ?: emptyList()) {
            when {
                file.extension.equals("jpg",true) -> published+=insert(context,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    pictures,file,"image/jpeg")
                file.extension.equals("txt",true) -> published+=insert(context,MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    documents,file,"text/plain")
                // index.jsonl stays where it was written: the provenance record is the app's, not the album's.
            }
        }
        return published
    }

    private fun insert(context: Context,collection: android.net.Uri,relative: String,file: File,mime: String): Int {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,file.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH,relative)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(collection,values) ?: return 0
        return try {
            context.contentResolver.openOutputStream(uri,"w")!!.use {output->file.inputStream().use {it.copyTo(output)} }
            context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            1
        } catch(error: Exception) {
            runCatching {context.contentResolver.delete(uri,null,null)}
            throw error
        }
    }

    // RELATIVE_PATH is stored with a trailing separator, but it is not worth betting the deletion on
    // that: the rows this app owns are few, so read them and compare the path directly.
    private fun clearFolder(context: Context,relative: String) {
        val target=relative.trimEnd('/')
        for(collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",arrayOf(context.packageName),null)?.use {rows->
                while(rows.moveToNext()) {
                    if(rows.getString(1)?.trimEnd('/')==target)
                        runCatching {context.contentResolver.delete(ContentUris.withAppendedId(collection,rows.getLong(0)),null,null)}
                }
            }
        }
    }

    /** A folder name, not a path: MediaStore takes a directory and a separator means something else. */
    private fun sanitize(group: String)=group.trim().replace(Regex("[/\\\\:*?\"<>|]"),"_").ifBlank {ShowcaseState.DEFAULT_GROUP}

    fun cardText(frame: ShowcaseFrame)=buildString {
        append("主动服务")
        DemoFrameRenderer.modeLabel(frame.mode)?.let {append(" · ");append(it)}
        append('\n');append('\n')
        append(frame.title);append('\n')
        append(frame.body);append('\n');append('\n')
        append("模式：").append(frame.mode ?: "未记录").append('\n')
        append("时间：").append(timestamp(frame.at)).append('\n')
        append("模型时延：").append(frame.latencyMs?.let {"$it ms"} ?: "未记录").append('\n')
        append("记录：").append(frame.recordName).append('\n')
        append("会话：").append(frame.sessionId).append('\n')
        append("决策：").append(frame.decisionId).append('\n')
    }

    private fun timestamp(at: Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(at))
}
