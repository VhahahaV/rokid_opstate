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
 * A proactive-service card that was really on the glasses, with the picture it was reading.
 *
 * The card and its `scene` are the two halves of a moment. What the 展示 tab does is let them be
 * chosen apart: the card is the words the wearer read, the scene is any photograph on the phone, and
 * the same card is worth showing over a better picture than the one it happened to be drawn on.
 */
data class ShowcaseCard(val sessionId: String,val recordName: String,val anchorId: String,val decisionId: String,
    val at: Long,val title: String,val body: String,val mode: String?,val latencyMs: Long?,val scene: File)

/** A card and the picture it will be drawn on. The pair is what gets rendered and exported. */
data class ShowcaseItem(val card: ShowcaseCard,val scene: File)

/** A photograph offered for pairing. The record it lives in is part of it: the journal names a picture
 *  by session and relative path, never by a path alone. */
data class ShowcasePhoto(val sessionId: String,val photo: CapturedPhoto) {
    val relativePath get()=photo.metadata.string("local_relative_path").orEmpty()
}

/**
 * What a selection remembers.
 *
 * Deliberately ids and a relative path rather than [File]s: a record can be renamed or deleted and a
 * photograph removed, and the selection has to survive all of that by being re-resolved against a
 * fresh scan rather than by holding a path that may no longer mean anything. [scenePath] absent means
 * the frame the card itself was reading - the real moment - which is what it was before pairing
 * existed, so an older saved selection still means what it meant.
 */
data class ShowcaseRef(val sessionId: String,val anchorId: String,val decisionId: String,
    val sceneSessionId: String?=null,val scenePath: String?=null)

/** What the composer is currently showing. A null [scene] is "the card's own picture". */
data class ShowcaseDraft(val card: ShowcaseCard?=null,val scene: ShowcasePhoto?=null)

data class ShowcaseState(val loaded: Boolean=false,val loading: Boolean=false,
    val cards: List<ShowcaseCard> = emptyList(),
    val photos: List<ShowcasePhoto> = emptyList(),val photosLoaded: Boolean=false,val photosLoading: Boolean=false,
    val draft: ShowcaseDraft=ShowcaseDraft(),
    val selected: List<ShowcaseRef> = emptyList(),val chosen: List<ShowcaseItem> = emptyList(),
    val group: String = DEFAULT_GROUP) {
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
    fun scan(journal: StudyJournal,sessions: List<StudySessionEntity>): List<ShowcaseCard> =
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
                ShowcaseCard(session.sessionId,session.participantAlias,anchor,decisionId,
                    row["source_occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull
                        ?: row["occurred_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    title,decision.string("body").orEmpty(),decision.string("service_mode"),
                    decision["model_latency_ms"]?.jsonPrimitive?.longOrNull,
                    journal.localImage(session.sessionId,path))
            }
        }.sortedByDescending {it.at}

    private fun json(canonical: String)=StudyWire.json.parseToJsonElement(canonical).jsonObject

    /**
     * Every photograph on the phone, once each, newest first, for choosing a picture to draw on.
     *
     * Deduplicated by hash, because the same frame is copied into several records when one is seeded
     * from another - the imported history is the same bytes under the same name in each of them - and
     * a picker that offers one photograph five times is worse than one that offers it once. A record
     * with nothing in it costs a single query, and one with several hundred rows is parsed once.
     */
    fun allPhotos(journal: StudyJournal,sessions: List<StudySessionEntity>): List<ShowcasePhoto> =
        sessions.sortedByDescending {it.startedAtEpochMs}
            .flatMap {session->SessionReviewRepository(journal).photos(session.sessionId).map {ShowcasePhoto(session.sessionId,it)}}
            .filter {it.relativePath.isNotEmpty()}
            .distinctBy {it.photo.metadata.string("sha256") ?: it.photo.file.path}
            .sortedByDescending {it.photo.capturedAt}

    /**
     * The chosen items in the order they were chosen. Anything that can no longer be produced drops
     * out: a card whose record is gone, or a picture that has since been removed from the phone.
     */
    fun resolve(cards: List<ShowcaseCard>,refs: List<ShowcaseRef>,sceneFile: (String,String)->File): List<ShowcaseItem> {
        val byCard=cards.associateBy {it.decisionId}
        return refs.mapNotNull {ref->
            val card=byCard[ref.decisionId] ?: return@mapNotNull null
            val scene=(if(ref.scenePath!=null && ref.sceneSessionId!=null) sceneFile(ref.sceneSessionId,ref.scenePath) else card.scene)
                .takeIf {it.isFile} ?: return@mapNotNull null
            ShowcaseItem(card,scene)
        }
    }

    /**
     * What the current selection resolves to right now.
     *
     * Recomputed on every change rather than stored once, so the set that is rendered and exported can
     * never drift from the ids it was made from when a record is deleted or a picture removed.
     */
    fun withChosen(showcase: ShowcaseState,sceneFile: (String,String)->File): ShowcaseState =
        showcase.copy(chosen=resolve(showcase.cards,showcase.selected,sceneFile))

    fun refOf(card: ShowcaseCard)=ShowcaseRef(card.sessionId,card.anchorId,card.decisionId)

    /**
     * The ref for a composed pair: this card drawn on that photograph.
     *
     * A card drawn on the picture it actually read is not a composition and is stored as the plain
     * card, so that a moment which was only ever looked at keeps one identity and one thumbnail.
     */
    fun refOf(card: ShowcaseCard,scene: ShowcasePhoto?): ShowcaseRef =
        if(scene==null || scene.photo.file.canonicalPath==card.scene.canonicalPath) refOf(card)
        else ShowcaseRef(card.sessionId,card.anchorId,card.decisionId,scene.sessionId,scene.relativePath)

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
                    // A selection saved before a card could be paired with another picture has no
                    // scene, and that is what "the card's own picture" is stored as - so it still
                    // means exactly what it meant when it was made.
                    ShowcaseRef(sessionId,anchorId,decisionId,row.string("scene_session_id"),row.string("scene_path"))
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return refs to (prefs.getString(GROUP,null)?.takeIf {it.isNotBlank()} ?: ShowcaseState.DEFAULT_GROUP)
    }

    fun write(prefs: SharedPreferences,selected: List<ShowcaseRef>,group: String) {
        val rows=selected.map {StudyWire.payload("session_id" to it.sessionId,"anchor_id" to it.anchorId,
            "decision_id" to it.decisionId,"scene_session_id" to it.sceneSessionId,"scene_path" to it.scenePath)}
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
     * Two files per item, numbered in the order they were chosen: the composite, and the card's words.
     *
     * The picture underneath is not exported beside it. It was there so the composite could be
     * checked against it, and in practice nobody did: the composite is the thing worth looking at,
     * and a folder where every moment is two half-size pictures and a text file is harder to read
     * than one where it is the picture and the words.
     */
    fun write(root: File,items: List<ShowcaseItem>): List<File> {
        root.mkdirs()
        val written=mutableListOf<File>()
        val index=StringBuilder()
        items.forEachIndexed { position,item->
            val number=(position+1).toString().padStart(2,'0')
            val card=item.card
            val scene=BitmapFactory.decodeFile(item.scene.path) ?: return@forEachIndexed
            val composite=DemoFrameRenderer.render(scene,DemoFrameRenderer.Card(card.title,card.body,
                DemoFrameRenderer.modeLabel(card.mode),true))
            scene.recycle()
            val compositeFile=File(root,"${number}_composite.jpg")
            compositeFile.outputStream().use {composite.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,it)}
            composite.recycle()
            val cardFile=File(root,"${number}_card.txt")
            cardFile.writeText(cardText(card))
            written+=listOf(compositeFile,cardFile)
            index.append(StudyWire.json.encodeToString(JsonObject.serializer(),StudyWire.payload(
                "position" to position+1,"session_id" to card.sessionId,"anchor_id" to card.anchorId,
                "decision_id" to card.decisionId,"at_epoch_ms" to card.at,"title" to card.title,
                "body" to card.body,"service_mode" to card.mode,"model_latency_ms" to card.latencyMs,
                "record_name" to card.recordName,"scene_sha256" to StudyJournal.hashFile(item.scene),
                "files" to listOf(compositeFile.name,cardFile.name)))).append('\n')
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

    fun cardText(card: ShowcaseCard)=buildString {
        append("主动服务")
        DemoFrameRenderer.modeLabel(card.mode)?.let {append(" · ");append(it)}
        append('\n');append('\n')
        append(card.title);append('\n')
        append(card.body);append('\n');append('\n')
        append("模式：").append(card.mode ?: "未记录").append('\n')
        append("时间：").append(timestamp(card.at)).append('\n')
        append("模型时延：").append(card.latencyMs?.let {"$it ms"} ?: "未记录").append('\n')
        append("记录：").append(card.recordName).append('\n')
        append("会话：").append(card.sessionId).append('\n')
        append("决策：").append(card.decisionId).append('\n')
    }

    private fun timestamp(at: Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(at))
}
