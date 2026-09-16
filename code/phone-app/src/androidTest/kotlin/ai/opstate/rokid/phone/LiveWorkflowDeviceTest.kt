package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.StudyRuntimeService
import ai.opstate.rokid.phone.transport.RokidDevice
import android.content.Intent
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
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

/** Explicit opt-in: real SDK, real camera and radio, saved API Key, and the actual operator UI. */
@RunWith(AndroidJUnit4::class)
class LiveWorkflowDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun operatorLoadsHistoryAddsCalendarAndContinuesWithARealObservation()=runBlocking {
        assumeTrue("Explicit real workflow only",InstrumentationRegistry.getArguments().getString("live_workflow")=="true")
        val app=compose.activity.application as PhoneApplication
        val dir=File(app.filesDir,"validation").apply {mkdirs()}
        fun stage(value: String) {File(dir,"live-workflow-progress.json").writeText(StudyWire.payload("stage" to value,
            "phase" to app.studyRuntime.state.value.phase,"error" to app.studyRuntime.state.value.error).toString())}
        fun screenshot(name: String) {File(dir,"workflow-$name.png").outputStream().use {InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}}
        fun click(text: String) {
            if(compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
                val container=listOf("synthetic-context-form","session-setup","operator-content-0").first {compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag(container).performScrollToNode(hasText(text))
            }
            val node=compose.onNodeWithText(text)
            if(!node.isDisplayed()) node.performScrollTo()
            node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) {it()}
        }
        app.storageReady.await()
        val device=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        stage("CONNECTING")
        app.transport.connect(RokidDevice(device.address,device.name,true)).getOrThrow()
        withTimeout(45000) {app.link.state.first {it.p2pConnected || it.p2pPhase=="FAILED"}}
        var id: String?=null
        try {
            compose.waitUntil(5000) {compose.onAllNodesWithText("开始现场验证").fetchSemanticsNodes().isNotEmpty()}
            click("开始现场验证")
            compose.onNodeWithTag("history-progress").performScrollTo().performTouchInput {swipe(centerLeft,centerRight,durationMillis=600)}
            compose.waitForIdle()
            // Return to a small public prefix for this bounded paid-provider smoke.
            compose.onNodeWithTag("history-progress").performSemanticsAction(SemanticsActions.SetProgress) {it(1f)}
            compose.onNode(hasSetTextAction() and hasText("参与者代号")).performScrollTo().performTextReplacement("TEST_LIVE_WORKFLOW")
            compose.onNode(hasSetTextAction() and hasText("参与者代号")).performImeAction()
            compose.waitForIdle();delay(300)
            screenshot("history-setup")
            click("载入历史并连接眼镜")
            withTimeout(15000) {while(app.studyRuntime.state.value.phase!="HISTORY_READY") delay(30)}
            id=app.studyRuntime.state.value.sessionId!!
            assertEquals(1,app.studyRuntime.state.value.historyPreview!!.index)
            withTimeout(8000) {while(app.studyRuntime.state.value.startupDisplay!="VISIBLE") delay(30)}
            assertTrue(app.journal.dao.stream(id,"inference_requests").isEmpty())
            stage("HISTORY_VISIBLE");screenshot("history-ready");delay(2000)
            app.studyRuntime.setAutomaticCapture(false)
            click("添加合成情境")
            click("Calendar")
            compose.onNodeWithTag("context-title").performScrollTo().performTextInput("演示日程")
            compose.onNodeWithTag("context-body").performScrollTo().performTextInput("这是一条合成日程：请在出发前确认会议地点 B302，并带上电脑和笔记本。")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle();delay(300)
            screenshot("context-form")
            click("加入当前会话")
            compose.waitUntil(5000) {compose.onAllNodesWithTag("synthetic-context-form").fetchSemanticsNodes().isEmpty()}
            click("开始实时服务")
            withTimeout(10000) {while(app.studyRuntime.state.value.phase!="RUNNING") delay(30)}
            stage("LIVE_VISIBLE");delay(9000)
            click("拍照观察一次")
            stage("OBSERVING")
            withTimeout(90000) {
                while(app.journal.dao.stream(id,"decisions").isEmpty()) {
                    check(app.journal.dao.stream(id,"runtime_events").none {it.eventType=="TECHNICAL_FAILURE"}) {app.studyRuntime.state.value.error ?: "MODEL_FAILED"}
                    delay(100)
                }
            }
            val requests=app.journal.dao.stream(id,"inference_requests").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            assertEquals(1,requests.size)
            assertTrue(requests.single().getValue("observations").jsonArray.any {it.jsonObject["content"]?.jsonPrimitive?.content?.contains("[CALENDAR] 演示日程")==true})
            val raw=app.journal.dao.stream(id,"raw_responses")
            assertTrue(raw.any {it.eventType=="PROVIDER_RESPONSE"})
            assertTrue(raw.any {it.eventType=="RAW_DECISION"})
            stage("DECISION_READY");screenshot("live-observation");delay(2500)
            app.studyRuntime.pauseSession()
            app.journal.export(id).copyTo(File(dir,"live-workflow.zip"),overwrite=true)
            File(dir,"live-workflow-result.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "history_index" to 1,"history_observations" to app.studyRuntime.state.value.historyPreview!!.observationCount,
                "actual_captures" to app.studyRuntime.state.value.liveCaptures,"p2p_connected" to app.link.state.value.p2pConnected,
                "actual_model" to app.modelConfig().modelId,"startup_visible_ack" to true,"synthetic_calendar_in_request" to true,
                "actual_provider_responses" to raw.count {it.eventType=="PROVIDER_RESPONSE"},"decision" to app.studyRuntime.state.value.rawDecision).toString())
        } catch(error: Throwable) {
            id?.let {runCatching {app.journal.export(it).copyTo(File(dir,"live-workflow-failure.zip"),overwrite=true)}}
            stage("FAILED");runCatching {File(dir,"live-workflow-ui-failure.txt").writeText(compose.onAllNodes(isRoot()).printToString())};throw error
        } finally {
            app.studyRuntime.stopSession()
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
        }
    }
}
