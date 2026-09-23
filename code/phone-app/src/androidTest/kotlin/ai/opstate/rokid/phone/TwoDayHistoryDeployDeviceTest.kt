package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.replay.LocalCollectionRepository
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** One-time, guarded import of the user-authorized local two-day history into a collection record. */
@RunWith(AndroidJUnit4::class)
class TwoDayHistoryDeployDeviceTest {
    @Test fun deployReviewedTwoDayHistoryAsOneCollection()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("two_day_deploy")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val journal=app.journal
        val id="session-two-day-warmup-20260923"
        val manifest=File(context.filesDir,"import/two-day-history-deploy.jsonl")
        assertTrue("TWO_DAY_DEPLOY_MANIFEST_MISSING",manifest.isFile)
        val lines=manifest.readLines().filter {it.isNotBlank()}
        assertEquals("TWO_DAY_DEPLOY_MANIFEST_ROWS",1828,lines.size)
        val entries=lines.map {StudyWire.json.parseToJsonElement(it).jsonObject}
        val first=entries.first().getValue("at_epoch_ms").jsonPrimitive.long
        val last=entries.last().getValue("at_epoch_ms").jsonPrimitive.long
        assertTrue("TWO_DAY_DEPLOY_TIME_ORDER",entries.zipWithNext().all {(a,b)->
            a.getValue("at_epoch_ms").jsonPrimitive.long<=b.getValue("at_epoch_ms").jsonPrimitive.long})
        val existing=journal.dao.session(id)
        if(existing==null) {
            val config=StudyWire.payload(
                "session_mode" to "COLLECTION",
                "source" to "LOCAL_ADB_TWO_DAY_CURATED_HISTORY",
                "virtual_photo_times" to true,
                "real_source_photos" to 1019,
                "synthetic_context_count" to 56,
                "synthetic_review_status" to "USER_AUTHORIZED_DRAFT_IMPORT",
                "manifest_sha256" to StudyWire.sha256(manifest.readBytes()),
                "contract" to StudyWire.payload(
                    "model_id" to "not_applicable_collection",
                    "prompt_hash" to "not_applicable_collection",
                    "preprocess_hash" to "not_applicable_collection",
                    "parser_hash" to "not_applicable_collection"))
            journal.start(id,1,"在线服务预热｜两天采集历史（含56条合成）",
                "two-day-curated-history-v1","USER_AUTHORIZED_SYNTHETIC_DRAFT",
                StudyWire.sha256(config.toString().toByteArray()),first,config)
            val copied=hashMapOf<String,String>()
            var photoCount=0
            var contextCount=0
            for(entry in entries) {
                val at=entry.getValue("at_epoch_ms").jsonPrimitive.long
                when(entry.getValue("kind").jsonPrimitive.content) {
                    "IMAGE" -> {
                        val order=entry.getValue("order").jsonPrimitive.int
                        assertEquals("TWO_DAY_DEPLOY_IMAGE_ORDER",++photoCount,order)
                        val sha=entry.getValue("sha256").jsonPrimitive.content
                        val path=copied[sha] ?: run {
                            val sourceSession=entry.getValue("source_session_id").jsonPrimitive.content
                            val sourceRelative=entry.getValue("source_relative_path").jsonPrimitive.content
                            val source=journal.localImage(sourceSession,sourceRelative)
                            assertTrue("TWO_DAY_DEPLOY_SOURCE_MISSING_$order",source.isFile)
                            assertEquals("TWO_DAY_DEPLOY_SOURCE_HASH_$order",sha,
                                ai.opstate.rokid.phone.data.StudyJournal.hashFile(source))
                            val relative="images/warmup-${sha.take(32)}.jpg"
                            val destination=journal.localImage(id,relative)
                            destination.parentFile!!.mkdirs()
                            try {Files.createLink(destination.toPath(),source.toPath())}
                            catch(_: Exception) {source.copyTo(destination,overwrite=false)}
                            copied[sha]=relative
                            relative
                        }
                        val source=journal.localImage(id,path)
                        journal.append(id,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                            "observation_id" to "warmup-${order.toString().padStart(4,'0')}",
                            "kind" to "IMAGE","occurred_at_epoch_ms" to at,"available_at_epoch_ms" to at,
                            "content" to "Collected historical egocentric image.",
                            "asset_id" to "warmup-${sha.take(16)}",
                            "local_relative_path" to path,"sha256" to sha,
                            "width" to entry.getValue("width").jsonPrimitive.int,
                            "height" to entry.getValue("height").jsonPrimitive.int,
                            "rotation_degrees" to 0,"quality_valid" to true,"quality_status" to "VALID",
                            "byte_length" to source.length(),
                            "source" to "LOCAL_ADB_REAL_PHOTO_CURATED_TIME",
                            "source_session_id" to entry.getValue("source_session_id").jsonPrimitive.content,
                            "source_observation_id" to entry.getValue("source_observation_id").jsonPrimitive.content,
                            "source_occurred_at_epoch_ms" to entry.getValue("source_at_epoch_ms").jsonPrimitive.long,
                            "curated_day" to entry.getValue("day").jsonPrimitive.int,
                            "curated_scene_id" to entry.getValue("scene_id").jsonPrimitive.content),at)
                        if(photoCount%200==0) instrumentation.sendStatus(1,Bundle().apply {
                            putInt("two_day_images_deployed",photoCount)
                            putInt("two_day_contexts_deployed",contextCount)
                        })
                    }
                    "SYNTHETIC_CONTEXT" -> {
                        contextCount++
                        journal.addSyntheticContext(id,entry.getValue("type").jsonPrimitive.content,
                            entry.getValue("title").jsonPrimitive.content,
                            entry.getValue("body").jsonPrimitive.content,at)
                    }
                    else -> error("TWO_DAY_DEPLOY_UNKNOWN_KIND")
                }
            }
            assertEquals(1772,photoCount)
            assertEquals(56,contextCount)
            assertEquals(1018,copied.size)
            journal.stop(id,last+1000)
        } else {
            assertTrue("TWO_DAY_DEPLOY_EXISTING_RECORD_INCOMPLETE",existing.endedAtEpochMs!=null)
        }
        assertTrue("TWO_DAY_DEPLOY_AUDIT_CHAIN",journal.verify(id))
        val repository=LocalCollectionRepository(journal)
        val summary=repository.list().single {it.sessionId==id}
        assertEquals("TWO_DAY_DEPLOY_VISIBLE_IMAGE_COUNT",1772,summary.validImages)
        assertEquals("TWO_DAY_DEPLOY_VISIBLE_CONTEXT_COUNT",56,summary.contextCount)
        val seed=repository.load(id,0)
        assertEquals("TWO_DAY_DEPLOY_FULL_LOAD_IMAGES",1772,seed.images.size)
        assertEquals("TWO_DAY_DEPLOY_FULL_LOAD_CONTEXTS",56,seed.contexts.size)
        assertEquals(first,seed.images.first().record.occurredAtEpochMs)
        assertEquals(last,seed.images.last().record.occurredAtEpochMs)
        val result=StudyWire.payload(
            "session_id" to id,"participant_alias" to summary.participantAlias,
            "images" to summary.validImages,"contexts" to summary.contextCount,
            "first_at_epoch_ms" to first,"last_at_epoch_ms" to last,
            "manifest_sha256" to StudyWire.sha256(manifest.readBytes()),
            "audit_chain_verified" to true,"full_load_verified" to true)
        File(context.filesDir,"two-day-deploy-summary.json").writeText(result.toString())
        println("TWO_DAY_DEPLOY_COMPLETE $result")
    }
}
