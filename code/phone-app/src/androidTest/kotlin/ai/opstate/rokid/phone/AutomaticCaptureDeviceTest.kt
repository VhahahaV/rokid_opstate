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
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            val setup=SessionSetup("TEST_EDGE_TRIGGER","local://scripted",expectedModelId="mock-on-device-v5",scenarioAsset="",glassesCapture=true)
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
            assertEquals(2,photos.size)
            assertTrue(photos.all {it["buffered_sync"]==JsonPrimitive(true) && it["asset_source"]==JsonPrimitive("LIVE_CAMERA")})
            assertTrue(JsonPrimitive("VISUAL") in photos.first().getValue("trigger_types").jsonArray)
            assertTrue(JsonPrimitive("DIGITAL") in photos.last().getValue("trigger_types").jsonArray)
            assertTrue(app.journal.dao.stream(id,"captures").none {it.eventType=="CAPTURE_REQUEST"})
            assertTrue(app.journal.dao.stream(id,"receipts").isEmpty())
            val directory=File(app.filesDir,"validation").apply {mkdirs()}
            File(directory,"edge-trigger-real-device.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "physical_camera_and_transport" to true,"trigger_location" to "GLASSES","model" to "LOCAL_SCRIPTED_MOCK",
                "photos_uploaded" to photos.size,"triggers" to JsonArray(photos.map {it.getValue("trigger_types")}),
                "phone_capture_requests" to 0,"receipts" to 0).toString())
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
