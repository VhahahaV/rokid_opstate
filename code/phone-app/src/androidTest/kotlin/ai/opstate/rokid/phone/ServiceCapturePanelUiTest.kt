package ai.opstate.rokid.phone

import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.SessionMode
import ai.opstate.rokid.phone.runtime.SessionSetup
import ai.opstate.rokid.phone.runtime.StudyRuntimeService
import ai.opstate.rokid.phone.transport.RokidDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Intent

/**
 * What a running service session says about the capture on the phone.
 *
 * It used to say nothing: the panel reported observations and receipts, the photograph strip was fed
 * every image in the session including the hundreds of history frames it had been seeded with, and the
 * operator's report was that the glasses did not look like they were capturing at all. The numbers a
 * collection shows - what the glasses shot, what is still on them, what arrived - belong to both modes,
 * because the capture does.
 */
@RunWith(AndroidJUnit4::class)
class ServiceCapturePanelUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun aServiceSessionShowsTheCaptureAndUploadCounts()=runBlocking<Unit> {
        assumeTrue("needs the glasses connected",InstrumentationRegistry.getArguments().getString("service_panel")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        if(!app.transport.ready.value) {
            val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
        }
        withTimeout(60_000) {app.transport.ready.first {it}}
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            val setup=SessionSetup("TEST_SERVICE_PANEL","local://scripted",expectedModelId="mock-on-device-v5",scenarioAsset="",
                mode=SessionMode.SERVICE,captureIntervalMs=5_000,glassesCapture=true,syncBatchSize=2,frontEndEnabled=false)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java)
                .setAction(StudyRuntimeService.ACTION_START).putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(30_000) {while(app.studyRuntime.state.value.sessionId==null) delay(100)}
            id=requireNotNull(app.studyRuntime.state.value.sessionId)
            // The glasses report their own counts as the session runs; the page has to be showing them.
            withTimeout(60_000) {
                while(compose.onAllNodesWithText("眼镜已拍",substring=true).fetchSemanticsNodes().isEmpty()) delay(200)
            }
            compose.onAllNodesWithText("眼镜已拍",substring=true).onFirst().assertExists()
            compose.onAllNodesWithText("待同步",substring=true).onFirst().assertExists()
            compose.onAllNodesWithText("本次已收",substring=true).onFirst().assertExists()
            // The upload is counted in images in this mode too, and the operator can pull a batch early.
            compose.onAllNodesWithText("整批上传",substring=true).onFirst().assertExists()
            compose.onAllNodesWithText("立即同步").onFirst().assertExists()
            compose.onAllNodesWithText("拍摄与上传").onFirst().assertExists()
        } finally {
            app.studyRuntime.stopSession();app.validationInferenceFactory=null
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
            // A hardware run writes a record per session, and the operator's own list is not the place
            // for the test's. The evidence keeps the numbers; the record goes.
            withContext(Dispatchers.IO) {id?.let {runCatching {app.journal.delete(it)}}}
        }
    }

    /**
     * One-off sweep of the records earlier hardware runs of this suite left behind.
     *
     * Gated, because deleting records is not something a suite does on its own; kept in the tree
     * because it is the recipe for clearing what a capture run leaves on the phone.
     */
    @Test fun sweepTestRecordsLeftByTheCaptureSuite()=runBlocking<Unit> {
        assumeTrue("one-off sweep",InstrumentationRegistry.getArguments().getString("sweep")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        val doomed=withContext(Dispatchers.IO) {
            app.database.dao().sessions().filter {it.participantAlias.startsWith("TEST_SERVICE_")}.map {it.sessionId}
        }
        val refused=withContext(Dispatchers.IO) {doomed.mapNotNull {id->
            runCatching {app.journal.delete(id)}.exceptionOrNull()?.let {"$id: ${it.message}"}
        }}
        val report=buildJsonObject {
            put("found",JsonPrimitive(doomed.size))
            put("refused",JsonArray(refused.map {JsonPrimitive(it)}))
        }
        java.io.File(requireNotNull(compose.activity.getExternalFilesDir(null)),"sweep.json")
            .writeText(report.toString())
    }
}
