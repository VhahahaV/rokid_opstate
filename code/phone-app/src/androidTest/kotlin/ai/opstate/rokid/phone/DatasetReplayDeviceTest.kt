package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.replay.PublicReplayDataset
import ai.opstate.rokid.phone.runtime.FrozenHarnessContract
import ai.opstate.rokid.phone.runtime.toWire
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DatasetReplayDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val graph get()=instrumentation.targetContext.applicationContext as PhoneApplication
    @Test fun publicProjectionMatchesTwentyFrozenPythonRequests() {
        val dataset=PublicReplayDataset { path->graph.assets.open(PublicReplayDataset.ASSET_ROOT+path).use {it.readBytes()} }
        val fixture=instrumentation.context.assets.open("projection-parity.json").bufferedReader().use {StudyWire.json.parseToJsonElement(it.readText()).jsonObject}
        val contract=StudyWire.json.decodeFromJsonElement<FrozenHarnessContract>(fixture.getValue("contract"))
        for(case in fixture.getValue("cases").jsonArray.map {it.jsonObject}) {
            val index=case.getValue("source_index").jsonPrimitive.int
            val request=dataset.request(dataset.anchors[index],contract,"projection-parity")
            val canonical=StudyJournal.canonical(StudyWire.json.encodeToJsonElement(request.toWire())).toString()
            assertEquals("public request $index",case.getValue("sha256").jsonPrimitive.content,StudyWire.sha256(canonical.toByteArray()))
            assertEquals(case.getValue("history_count").jsonPrimitive.int,request.observations.size)
            assertTrue(request.receipts.isEmpty())
            assertEquals(request.currentImageSha256,StudyWire.sha256(dataset.image(request.currentAssetId)))
        }
    }
    @Test fun replayUsesPhoneHttpParserJournalAndExport()=runBlocking {
        val limit=InstrumentationRegistry.getArguments().getString("replay_limit","266")!!.toInt()
        val id=withTimeout(300_000) {graph.datasetReplay.run("http://127.0.0.1:8765",limit)}
        val state=graph.datasetReplay.state.value
        assertEquals("COMPLETE",state.phase);assertEquals(limit,state.completed);assertEquals(0,state.failures)
        assertTrue(graph.journal.verify(id))
        assertEquals("STOPPED",graph.journal.dao.checkpoint(id)!!.state)
        assertTrue(graph.journal.dao.stream(id,"receipts").isEmpty())
        val archive=graph.journal.export(id)
        ZipFile(archive).use { zip ->
            val manifest=zip.getInputStream(zip.getEntry("session_manifest.json")).bufferedReader().use {StudyWire.json.parseToJsonElement(it.readText()).jsonObject}
            assertEquals("CONTRACT_REPLAY",manifest.getValue("condition_code").jsonPrimitive.content)
            assertEquals("mock-january-v10-r6",manifest.getValue("model_id").jsonPrimitive.content)
            assertTrue(manifest.getValue("files").jsonArray.size>14)
            assertEquals(0L,zip.getEntry("receipts.jsonl").size)
        }
        val output=File(graph.filesDir,"validation").apply {mkdirs()}
        archive.copyTo(File(output,"phone-dataset-replay.zip"),overwrite=true)
        File(output,"phone-dataset-replay.json").writeText(StudyWire.payload("session_id" to id,"completed" to state.completed,
            "failures" to state.failures,"service" to state.services,"no_service" to state.silent,
            "kind" to "ANDROID_HTTP_MOCK_REPLAY","real_radio_validated" to false,"real_display_validated" to false).toString())
        archive.delete()
        Unit
    }
}
