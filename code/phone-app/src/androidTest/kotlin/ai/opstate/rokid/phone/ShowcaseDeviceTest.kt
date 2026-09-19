package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.review.*
import android.content.Context
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
 * The 展示 tab's data side: which cards count, what pairing one with another photograph means, and
 * what an export of the result contains.
 *
 * The rule under test is the one that decides whether a card is something anyone saw. A service that
 * was read by the model but never reached the wearer is not one, and the difference between the two is
 * a delivery row - which is exactly the kind of thing that silently stops being true.
 */
@RunWith(AndroidJUnit4::class)
class ShowcaseDeviceTest {
    private fun checkerboardJpeg(shift: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10+shift)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);bitmap.recycle();it.toByteArray()}
    }

    /** A record holding two photographs and two services reading the first: one seen, one not. */
    private class Fixture(context: Context) {
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val sessionId="test-showcase-${System.currentTimeMillis()}"
        lateinit var scene: File
        lateinit var other: File
        fun close() {runCatching {journal.delete(sessionId)};database.close()}

        fun build(sceneJpeg: ByteArray,otherJpeg: ByteArray) {
            val now=System.currentTimeMillis()
            journal.start(sessionId,100,"展示测试","live-service-v1","BENCHMARK_ALIGNED","0".repeat(64),now,
                StudyWire.payload("session_mode" to "SERVICE"))
            val sha=StudyWire.sha256(sceneJpeg)
            val asset="asset-${sha.take(16)}"
            val path=journal.image(sessionId,"capture-shown",sceneJpeg,sha)
            scene=journal.localImage(sessionId,path)
            journal.append(sessionId,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                "observation_id" to "obs-shown","kind" to "IMAGE","occurred_at_epoch_ms" to now,
                "available_at_epoch_ms" to now,"content" to "Current egocentric image observation.",
                "asset_id" to asset,"local_relative_path" to path,"sha256" to sha,
                "width" to 320,"height" to 240,"rotation_degrees" to 0,"quality_valid" to true,
                "quality_status" to "VALID","byte_length" to sceneJpeg.size),now)
            // A second photograph that no service ever read: the one a card can be paired with.
            val otherSha=StudyWire.sha256(otherJpeg)
            val otherPath=journal.image(sessionId,"capture-other",otherJpeg,otherSha)
            other=journal.localImage(sessionId,otherPath)
            journal.append(sessionId,"observations","OBSERVATION_PERSISTED",StudyWire.payload(
                "observation_id" to "obs-other","kind" to "IMAGE","occurred_at_epoch_ms" to now+1000,
                "available_at_epoch_ms" to now+1000,"content" to "Current egocentric image observation.",
                "asset_id" to "asset-${otherSha.take(16)}","local_relative_path" to otherPath,"sha256" to otherSha,
                "width" to 320,"height" to 240,"rotation_degrees" to 0,"quality_valid" to true,
                "quality_status" to "VALID","byte_length" to otherJpeg.size),now+1000)
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

    private fun scenes(journal: StudyJournal): (String,String)->File = {sessionId,path->journal.localImage(sessionId,path)}

    private fun cards(fixture: Fixture)=ShowcaseRepository.scan(fixture.journal,
        fixture.database.dao().sessions().filter {it.sessionId==fixture.sessionId})

    @Test fun onlyAServiceThatReachedTheGlassesIsAMoment() {
        val fixture=Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            fixture.build(checkerboardJpeg(0),checkerboardJpeg(1))
            val card=cards(fixture).single()
            assertEquals("decision-SHOWN",card.decisionId)
            assertEquals("edge-shown",card.anchorId)
            assertEquals("桌面物品整理SHOWN",card.title)
            assertEquals("AWARENESS",card.mode)
            assertEquals("展示测试",card.recordName)
            assertEquals(fixture.scene.canonicalPath,card.scene.canonicalPath)
        } finally {fixture.close()}
    }

    @Test fun pairingACardWithAnotherPictureIsItsOwnItem() {
        val fixture=Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            fixture.build(checkerboardJpeg(0),checkerboardJpeg(1))
            val card=cards(fixture).single()
            val other=ShowcaseRepository.allPhotos(fixture.journal,
                fixture.database.dao().sessions().filter {it.sessionId==fixture.sessionId})
                .single {it.photo.file.canonicalPath==fixture.other.canonicalPath}
            val own=ShowcaseRepository.allPhotos(fixture.journal,
                fixture.database.dao().sessions().filter {it.sessionId==fixture.sessionId})
                .single {it.photo.file.canonicalPath==fixture.scene.canonicalPath}

            // Drawn on the picture it actually read, it is the plain moment and keeps one identity.
            assertEquals(ShowcaseRepository.refOf(card),ShowcaseRepository.refOf(card,own))
            // Drawn on another picture it is its own item, and carries which picture it is.
            val paired=ShowcaseRepository.refOf(card,other)
            assertNotEquals(ShowcaseRepository.refOf(card),paired)
            assertEquals(fixture.sessionId,paired.sceneSessionId)
            assertEquals(other.relativePath,paired.scenePath)

            val resolved=ShowcaseRepository.resolve(cards(fixture),listOf(paired),scenes(fixture.journal)).single()
            assertEquals(fixture.other.canonicalPath,resolved.scene.canonicalPath)
            assertEquals("桌面物品整理SHOWN",resolved.card.title)
        } finally {fixture.close()}
    }

    @Test fun aSelectionDropsOutWhenItsPictureIsGone() {
        val fixture=Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            fixture.build(checkerboardJpeg(0),checkerboardJpeg(1))
            val card=cards(fixture).single()
            val stale=ShowcaseRef(card.sessionId,card.anchorId,card.decisionId,card.sessionId,"images/capture-removed.jpg")
            // A pairing whose photograph has been deleted resolves to nothing at all, rather than
            // silently falling back to the card's own picture.
            assertEquals(emptyList<ShowcaseItem>(),ShowcaseRepository.resolve(cards(fixture),listOf(stale),scenes(fixture.journal)))
            assertEquals(1,ShowcaseRepository.resolve(cards(fixture),listOf(ShowcaseRepository.refOf(card)),scenes(fixture.journal)).size)
        } finally {fixture.close()}
    }

    @Test fun aRecordWithNoDecisionIsNeverScannedForMoments() {
        val fixture=Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val now=System.currentTimeMillis()
            fixture.journal.start(fixture.sessionId,100,"空记录","local-collection-v1","BENCHMARK_ALIGNED","0".repeat(64),now,
                StudyWire.payload("session_mode" to "COLLECTION"))
            assertEquals(emptyList<ShowcaseCard>(),cards(fixture))
            fixture.journal.stop(fixture.sessionId,now)
        } finally {fixture.close()}
    }

    @Test fun exportWritesTheCompositeAndTheCardInTheOrderChosen() {
        val fixture=Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            fixture.build(checkerboardJpeg(0),checkerboardJpeg(1))
            val card=cards(fixture).single()
            val root=File(context.cacheDir,"showcase-test-${System.currentTimeMillis()}")
            try {
                val files=ShowcaseExporter.write(root,listOf(ShowcaseItem(card,fixture.scene)))
                // No original: the picture underneath is not shipped beside the composite any more.
                assertEquals(listOf("01_composite.jpg","01_card.txt","index.jsonl"),files.map {it.name})
                assertTrue(files.all {it.isFile && it.length()>0})
                val text=File(root,"01_card.txt").readText()
                assertTrue(text.contains("桌面物品整理SHOWN"))
                assertTrue(text.contains("提醒"))          // AWARENESS, in the words the glasses used
                assertTrue(text.contains(card.sessionId))
                val index=StudyWire.json.parseToJsonElement(File(root,"index.jsonl").readLines().single()).jsonObject
                assertEquals("decision-SHOWN",index.getValue("decision_id").jsonPrimitive.content)
                assertEquals(1,index.getValue("position").jsonPrimitive.int)
                // What it was drawn on is still recorded, so a composite can be traced back to its picture.
                assertEquals(StudyJournal.hashFile(fixture.scene),index.getValue("scene_sha256").jsonPrimitive.content)
            } finally {root.deleteRecursively()}
        } finally {fixture.close()}
    }

    @Test fun theShortlistSurvivesARestart() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("showcase-test-${System.currentTimeMillis()}",Context.MODE_PRIVATE)
        val refs=listOf(ShowcaseRef("session-a","edge-1","decision-1"),
            ShowcaseRef("session-a","edge-2","decision-2","session-a","images/capture-x.jpg"))
        ShowcaseStore.write(prefs,refs,"论文配图")
        val (read,group)=ShowcaseStore.read(prefs)
        assertEquals(refs,read)
        assertEquals("论文配图",group)
        // Order is part of the selection: it is the order the moments will be shown in.
        assertEquals(listOf("edge-1","edge-2"),read.map {it.anchorId})
        // And so is which picture each one was paired with.
        assertNull(read[0].scenePath)
        assertEquals("images/capture-x.jpg",read[1].scenePath)
    }

    @Test fun anUnreadableStoredSelectionReadsAsEmpty() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("showcase-empty-${System.currentTimeMillis()}",Context.MODE_PRIVATE)
        assertEquals(emptyList<ShowcaseRef>(),ShowcaseStore.read(prefs).first)
        prefs.edit().putString("showcase_selection","not json").apply()
        assertEquals(emptyList<ShowcaseRef>(),ShowcaseStore.read(prefs).first)
        assertEquals(ShowcaseState.DEFAULT_GROUP,ShowcaseStore.read(prefs).second)
        // A selection saved before pairing existed has no scene fields, and must keep meaning the
        // moment it meant: the card's own picture.
        prefs.edit().putString("showcase_selection",
            """[{"session_id":"s","anchor_id":"a","decision_id":"d"}]""").apply()
        val (legacy,_)=ShowcaseStore.read(prefs)
        assertEquals(listOf(ShowcaseRef("s","a","d",null,null)),legacy)
    }
}
