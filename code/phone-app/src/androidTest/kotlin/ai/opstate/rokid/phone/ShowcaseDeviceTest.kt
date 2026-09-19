package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.review.*
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The 展示 tab's data side: which moments count, and what an export of them contains.
 *
 * The rule under test is the one that decides whether a card is a moment anyone saw. A service that was
 * read by the model but never reached the wearer is not one, and the difference between the two is a
 * delivery row - which is exactly the kind of thing that silently stops being true.
 */
@RunWith(AndroidJUnit4::class)
class ShowcaseDeviceTest {
    private fun checkerboardJpeg(shift: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10+shift)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);bitmap.recycle();it.toByteArray()}
    }

    /** A record holding one photograph and two services reading it: one that reached the glasses, one that did not. */
    private class Fixture(context: android.content.Context) {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val sessionId="test-showcase-${System.currentTimeMillis()}"
        lateinit var scene: File
        fun close() {runCatching {journal.delete(sessionId)};database.close()}

        fun build(jpeg: ByteArray) {
            val now=System.currentTimeMillis()
            journal.start(sessionId,100,"展示测试","live-service-v1","BENCHMARK_ALIGNED","0".repeat(64),now,
                StudyWire.payload("session_mode" to "SERVICE"))
            val sha=StudyWire.sha256(jpeg)
            val asset="asset-${sha.take(16)}"
            val path=journal.image(sessionId,"capture-shown",jpeg,sha)
            scene=journal.localImage(sessionId,path)
            journal.append(sessionId,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                "observation_id" to "obs-shown","kind" to "IMAGE","occurred_at_epoch_ms" to now,
                "available_at_epoch_ms" to now,"content" to "Current egocentric image observation.",
                "asset_id" to asset,"local_relative_path" to path,"sha256" to sha,
                "width" to 320,"height" to 240,"rotation_degrees" to 0,"quality_valid" to true,
                "quality_status" to "VALID","byte_length" to jpeg.size),now)
            for(tag in listOf("SHOWN","FAILED")) {
                val anchor="edge-${tag.lowercase()}"
                journal.append(sessionId,"inference_requests","INFERENCE_REQUESTED",StudyWire.payload(
                    "request_id" to "request-$tag","packet_id" to anchor,"current_asset_id" to asset,
                    "current_image_sha256" to sha),now)
                journal.append(sessionId,"decisions","MODEL_DECISION",StudyWire.payload(
                    "request_id" to "request-$tag","packet_id" to anchor,"decision_id" to "decision-$tag",
                    "decision" to "SERVICE","evidence_observation_ids" to listOf("obs-shown"),
                    "service_mode" to "AWARENESS","title" to "桌面物品整理$tag",
                    "body" to "桌面上有笔记本电脑、钥匙和扩展坞。","produced_at_epoch_ms" to now,
                    "expires_at_epoch_ms" to now+30_000),now)
                journal.append(sessionId,"deliveries","DELIVERY_DECISION",StudyWire.payload(
                    "delivery_id" to "delivery-$tag","decision_id" to "decision-$tag",
                    "status" to if(tag=="SHOWN") "DISPLAY_VISIBLE" else "DISPLAY_FAILED",
                    "reason" to "TEST"),now)
            }
            journal.stop(sessionId,now)
        }
    }

    @Test fun onlyAServiceThatReachedTheGlassesIsAMoment() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=Fixture(context)
        try {
            fixture.build(checkerboardJpeg(0))
            val sessions=fixture.database.dao().sessions().filter {it.sessionId==fixture.sessionId}
            val frames=ShowcaseRepository.scan(fixture.journal,sessions)
            assertEquals(1,frames.size)
            val frame=frames.single()
            assertEquals("decision-SHOWN",frame.decisionId)
            assertEquals("edge-shown",frame.anchorId)
            assertEquals("桌面物品整理SHOWN",frame.title)
            assertEquals("AWARENESS",frame.mode)
            assertEquals("展示测试",frame.recordName)
            assertTrue(frame.scene.isFile)
            assertEquals(fixture.scene.canonicalPath,frame.scene.canonicalPath)
        } finally {fixture.close()}
    }

    @Test fun aRecordWithNoDecisionIsNeverParsedForMoments() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=Fixture(context)
        try {
            val now=System.currentTimeMillis()
            fixture.journal.start(fixture.sessionId,100,"空记录","local-collection-v1","BENCHMARK_ALIGNED","0".repeat(64),now,
                StudyWire.payload("session_mode" to "COLLECTION"))
            assertEquals(emptyList<ShowcaseFrame>(),ShowcaseRepository.scan(fixture.journal,fixture.database.dao().sessions()
                .filter {it.sessionId==fixture.sessionId}))
            fixture.journal.stop(fixture.sessionId,now)
        } finally {fixture.close()}
    }

    @Test fun exportWritesTheThreePiecesInTheOrderTheyWereChosen() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=Fixture(context)
        try {
            fixture.build(checkerboardJpeg(0))
            val frame=ShowcaseRepository.scan(fixture.journal,fixture.database.dao().sessions()
                .filter {it.sessionId==fixture.sessionId}).single()
            val root=File(context.cacheDir,"showcase-test-${System.currentTimeMillis()}")
            try {
                val files=ShowcaseExporter.write(root,listOf(frame))
                assertEquals(listOf("01_composite.jpg","01_original.jpg","01_card.txt","index.jsonl"),files.map {it.name})
                assertTrue(files.all {it.isFile && it.length()>0})
                // The original is the picture itself, not a re-encode of it: that is what makes the pair
                // worth putting side by side.
                assertEquals(StudyJournal.hashFile(frame.scene),StudyJournal.hashFile(File(root,"01_original.jpg")))
                val card=File(root,"01_card.txt").readText()
                assertTrue(card.contains("桌面物品整理SHOWN"))
                assertTrue(card.contains("提醒"))          // AWARENESS, in the words the glasses used
                assertTrue(card.contains(frame.sessionId))
                val index=StudyWire.json.parseToJsonElement(File(root,"index.jsonl").readLines().single()).jsonObject
                assertEquals("decision-SHOWN",index.getValue("decision_id").jsonPrimitive.content)
                assertEquals(1,index.getValue("position").jsonPrimitive.int)
            } finally {root.deleteRecursively()}
        } finally {fixture.close()}
    }

    @Test fun aSelectionOutlivesItsRecordByDroppingOut() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=Fixture(context)
        try {
            fixture.build(checkerboardJpeg(0))
            val sessions=fixture.database.dao().sessions().filter {it.sessionId==fixture.sessionId}
            val frame=ShowcaseRepository.scan(fixture.journal,sessions).single()
            val ref=ShowcaseRepository.refOf(frame)
            val gone=ShowcaseRef("session-does-not-exist","edge-gone","decision-gone")
            // The stale one is dropped rather than resolved to something else, so a shortlist can never
            // quietly point at a moment the operator never chose.
            assertEquals(listOf(frame),ShowcaseRepository.resolve(listOf(frame),listOf(gone,ref)))
            assertEquals(emptyList<ShowcaseFrame>(),ShowcaseRepository.resolve(listOf(frame),listOf(gone)))
        } finally {fixture.close()}
    }

    @Test fun theShortlistSurvivesARestart() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("showcase-test-${System.currentTimeMillis()}",android.content.Context.MODE_PRIVATE)
        val refs=listOf(ShowcaseRef("session-a","edge-1","decision-1"),ShowcaseRef("session-a","edge-2","decision-2"))
        ShowcaseStore.write(prefs,refs,"论文配图")
        val (read,group)=ShowcaseStore.read(prefs)
        assertEquals(refs,read)
        assertEquals("论文配图",group)
        // Order is part of the selection: it is the order the moments will be shown in.
        assertEquals(listOf("edge-1","edge-2"),read.map {it.anchorId})
    }

    @Test fun anEmptyStoredSelectionReadsAsEmptyRatherThanFailing() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("showcase-empty-${System.currentTimeMillis()}",android.content.Context.MODE_PRIVATE)
        assertEquals(emptyList<ShowcaseRef>(),ShowcaseStore.read(prefs).first)
        prefs.edit().putString("showcase_selection","not json").apply()
        assertEquals(emptyList<ShowcaseRef>(),ShowcaseStore.read(prefs).first)
        assertEquals(ShowcaseState.DEFAULT_GROUP,ShowcaseStore.read(prefs).second)
    }
}
