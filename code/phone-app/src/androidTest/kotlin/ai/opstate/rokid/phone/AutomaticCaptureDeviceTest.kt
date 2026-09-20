package ai.opstate.rokid.phone

import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.RokidDevice
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Measures actual camera request cadence and transfers. Model decisions are explicitly local mock. */
@RunWith(AndroidJUnit4::class)
class AutomaticCaptureDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun glassesTriggerUploadsOnlyTriggeredFramesAndHandlesDigitalContext()=runBlocking<Unit> {
        assumeTrue("Explicit short edge-trigger hardware test only",InstrumentationRegistry.getArguments().getString("edge_trigger")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        // The app reopens the saved glasses as it starts, and the instrumentation restarts it, so the
        // handshake is given its time before this test opens a link of its own: a second connect while
        // the first is still coming up is what the SDK refuses.
        runCatching {withTimeout(45_000) {app.transport.ready.first {it}}}.onFailure {
            val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            // A short cadence for the same reason the batch test uses one, and now for a second reason:
            // the glasses used to take a shot the moment a trigger was pending (`pendingTrigger.isNotEmpty()`
            // in its capture condition), so a context produced its frame within seconds. The phone decides
            // what a frame is worth now, and the glasses capture on the cadence alone - so the frame that
            // carries the DIGITAL trigger is the next scheduled one, and waiting for it means waiting
            // for the interval rather than for an event.
            val setup=SessionSetup("TEST_EDGE_TRIGGER","local://scripted",expectedModelId="mock-on-device-v5",scenarioAsset="",
                glassesCapture=true,captureIntervalMs=10_000)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(15_000) {while(app.studyRuntime.state.value.liveCaptures<1) delay(100)}
            id=requireNotNull(app.studyRuntime.state.value.sessionId)
            withTimeout(10_000) {while(app.journal.dao.stream(id,"decisions").isEmpty()) delay(100)}
            app.studyRuntime.addSyntheticContext(SyntheticContextInput(SyntheticContextType.MESSAGE,"触发验证","新消息需要结合当前画面判断。",showOnGlasses=false)).getOrThrow()
            delay(2_000)
            assertEquals(1,app.studyRuntime.state.value.liveCaptures)
            withTimeout(25_000) {while(app.studyRuntime.state.value.liveCaptures<2 || app.journal.dao.stream(id,"decisions").size<2) delay(100)}
            val photos=app.journal.dao.stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}.filter {it["kind"]==JsonPrimitive("IMAGE")}
            // Two frames were triggered, and they are the ones that reach the model. A frame nobody
            // triggered may ride out in the same batch: the unit of an upload is the batch, not the
            // trigger, which is why this counts the triggered ones rather than every photograph.
            val triggered=photos.filter {(it["trigger_types"]?.jsonArray?.size ?: 0)>0}
            assertEquals(2,triggered.size)
            assertTrue(photos.all {it["buffered_sync"]==JsonPrimitive(true) && it["asset_source"]==JsonPrimitive("LIVE_CAMERA")})
            // The verdict is the phone's now: the visual one carries the front end's own reason, and the
            // one after a context carries the digital reason that context set.
            val reasons=triggered.map {row->row.getValue("trigger_types").jsonArray.map {it.jsonPrimitive.content}}
            assertTrue("a visual trigger should name the front end: $reasons",reasons.any {list->list.any {it.startsWith("CVTT_")}})
            assertTrue("the context should have triggered one too: $reasons",reasons.any {list->list.contains("DIGITAL")})
            assertTrue(app.journal.dao.stream(id,"captures").none {it.eventType=="CAPTURE_REQUEST"})
            assertTrue(app.journal.dao.stream(id,"receipts").isEmpty())
            val directory=File(app.filesDir,"validation").apply {mkdirs()}
            File(directory,"edge-trigger-real-device.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "physical_camera_and_transport" to true,"trigger_location" to "GLASSES","model" to "LOCAL_SCRIPTED_MOCK",
                "photos_uploaded" to photos.size,"triggered_photos" to triggered.size,
                "triggers" to JsonArray(triggered.map {it.getValue("trigger_types")}),
                "phone_capture_requests" to 0,"receipts" to 0).toString())
        } finally {
            app.studyRuntime.stopSession();app.validationInferenceFactory=null
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
            withContext(Dispatchers.IO) {id?.let {runCatching {app.journal.delete(it)}}}
        }
    }
    /**
     * A service session uploads the frames it captured, in batches counted in images.
     *
     * This is the wearer's side of the report that a running service looked as if the glasses were not
     * capturing at all: the glasses kept only the frames a trigger produced, dribbled them up one at a
     * time, and the phone's page showed a photograph count that was mostly the history it had been
     * seeded with. What is asserted here is the capture path a collection already had: every shot is
     * kept, a batch of [SessionSetup.syncBatchSize] goes up together, and a frame nobody triggered is
     * not evaluated - the front end is what decides that, and turning it off here is what proves the
     * frames still arrive without it.
     */
    @Test fun serviceSessionUploadsItsCapturesInBatchesOfTheConfiguredCount()=runBlocking<Unit> {
        assumeTrue("Explicit service-batch hardware test only",InstrumentationRegistry.getArguments().getString("service_batch")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        // The app reopens the saved glasses as it starts, and the instrumentation restarts it, so the
        // handshake is given its time before this test opens a link of its own: a second connect while
        // the first is still coming up is what the SDK refuses.
        runCatching {withTimeout(45_000) {app.transport.ready.first {it}}}.onFailure {
            val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            // 5 s and a batch of 2 keep the check short; the front end is off so the frames under test are
            // the plain captures and the cadence is one it was never asked to score.
            val setup=SessionSetup("TEST_SERVICE_BATCH","local://scripted",expectedModelId="mock-on-device-v5",scenarioAsset="",
                mode=SessionMode.SERVICE,captureIntervalMs=5_000,glassesCapture=true,syncBatchSize=2,frontEndEnabled=false)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(30_000) {while(app.studyRuntime.state.value.sessionId==null) delay(100)}
            id=requireNotNull(app.studyRuntime.state.value.sessionId)
            withTimeout(90_000) {while(app.journal.dao.stream(id,"observations")
                .count {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject["kind"]==JsonPrimitive("IMAGE")}<4) delay(200)}
            val photos=app.journal.dao.stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .filter {it["kind"]==JsonPrimitive("IMAGE")}
            val state=app.studyRuntime.state.value
            assertTrue("the glasses captured at least what arrived",state.glassesCaptures>=photos.size)
            assertTrue("a batch of two is what the phone asked for",state.syncBatchSize==2)
            assertTrue("every frame arrived through the buffer",photos.all {it["buffered_sync"]==JsonPrimitive(true)})
            assertTrue("nothing was triggered, so nothing became a packet",app.journal.dao.stream(id,"packets").isEmpty())
            assertTrue("and nothing reached the model",app.journal.dao.stream(id,"decisions").isEmpty())
            assertTrue("the phone never asked for a capture",app.journal.dao.stream(id,"captures").none {it.eventType=="CAPTURE_REQUEST"})
            assertTrue(app.journal.verify(id))
            val directory=File(app.filesDir,"validation").apply {mkdirs()}
            File(directory,"service_batch_upload.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "capture_interval_ms" to 5_000,"batch_size" to 2,"front_end_enabled" to false,
                "physical_camera_and_transport" to true,"model" to "LOCAL_SCRIPTED_MOCK",
                "photos_uploaded" to photos.size,"glasses_captured" to state.glassesCaptures,
                "packets" to 0,"decisions" to 0,"phone_capture_requests" to 0).toString())
        } finally {
            app.studyRuntime.stopSession();app.validationInferenceFactory=null
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
        }
    }
    @Test fun twoMinuteBaselineRequestsAndManualCaptureUseThePhysicalGlasses()=runBlocking {
        assumeTrue("Explicit physical cadence test only",InstrumentationRegistry.getArguments().getString("automatic_capture")=="true")
        val app=compose.activity.application as PhoneApplication
        app.storageReady.await()
        val dir=File(app.filesDir,"validation").apply {mkdirs()}
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            val setup=SessionSetup("TEST_AUTOMATIC_CAPTURE","local://scripted",expectedModelId="mock-on-device-v5",historyAnchorIndex=1,glassesDemoMode=true)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(10000) {while(app.studyRuntime.state.value.phase!="HISTORY_READY") delay(50)}
            id=app.studyRuntime.state.value.sessionId!!
            app.studyRuntime.resumeSession()
            fun captures()=app.journal.dao.stream(id,"captures").filter {it.eventType=="CAPTURE_REQUEST"}.map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            fun baselines()=captures().filter {JsonPrimitive("PERIODIC_BASELINE") in it.getValue("reason_codes").jsonArray}
            withTimeout(85000) {
                while(baselines().size<2) {
                    File(dir,"automatic-capture-progress.json").writeText(StudyWire.payload("phase" to app.studyRuntime.state.value.phase,
                        "baseline_requests" to baselines().size,"live_images" to app.studyRuntime.state.value.liveCaptures,
                        "next_in_ms" to app.studyRuntime.state.value.nextCaptureInMs,"error" to app.studyRuntime.state.value.error).toString())
                    delay(500)
                }
            }
            val baseline=baselines().take(2)
            val interval=baseline[1].getValue("requested_elapsed_ms").jsonPrimitive.long-baseline[0].getValue("requested_elapsed_ms").jsonPrimitive.long
            assertTrue("Actual interval $interval",interval in 59500..65000)
            withTimeout(15000) {while(app.studyRuntime.state.value.phase!="RUNNING") delay(100)}
            app.studyRuntime.setAutomaticCapture(false);app.studyRuntime.captureNow()
            withTimeout(10000) {while(captures().none {JsonPrimitive("MANUAL_DEBUG") in it.getValue("reason_codes").jsonArray}) delay(50)}
            withTimeout(15000) {while(app.studyRuntime.state.value.phase!="RUNNING") delay(100)}
            app.studyRuntime.pauseSession()
            val photos=app.journal.dao.stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}.filter {it["asset_source"]==JsonPrimitive("LIVE_CAMERA")}
            assertTrue(photos.size>=3)
            app.journal.export(id).copyTo(File(dir,"automatic-capture.zip"),overwrite=true)
            File(dir,"automatic-capture-result.json").writeText(StudyWire.payload("status" to "PASS","scope" to "ACTUAL_CAMERA_CADENCE_AND_MANUAL_CONTROL",
                "interval_ms" to interval,"baseline_requests" to 2,"manual_requests" to 1,"actual_photos" to photos.size,
                "valid_quality_photos" to photos.count {it["quality_valid"]==JsonPrimitive(true)},
                "channels" to JsonArray(photos.map {it.getValue("transfer_channel")}.distinct()),"model" to "LOCAL_MOCK").toString())
        } finally {
            app.studyRuntime.stopSession();app.validationInferenceFactory=null
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
        }
    }
}
