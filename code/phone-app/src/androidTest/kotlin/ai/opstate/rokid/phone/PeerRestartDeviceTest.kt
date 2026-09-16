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

/** Host restarts only the glasses application after WAITING_FOR_PEER_RESTART; radio pairing stays intact. */
@RunWith(AndroidJUnit4::class)
class PeerRestartDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun pausedSessionResynchronizesARestartedGlassesAppAndCapturesAgain()=runBlocking<Unit> {
        assumeTrue("Explicit real glasses restart test only",InstrumentationRegistry.getArguments().getString("peer_restart")=="true")
        val app=compose.activity.application as PhoneApplication
        val dir=File(app.filesDir,"validation").apply {mkdirs()}
        val signal=File(dir,"peer-restarted.signal").apply {delete()}
        fun stage(value: String) {File(dir,"peer-restart-progress.json").writeText(StudyWire.payload("stage" to value,"phase" to app.studyRuntime.state.value.phase,
            "error" to app.studyRuntime.state.value.error).toString())}
        app.storageReady.await()
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
        var id: String?=null
        try {
            val setup=SessionSetup("TEST_PEER_PROCESS_RESTART","local://scripted",expectedModelId="mock-on-device-v5",historyAnchorIndex=1)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(15000) {while(app.studyRuntime.state.value.phase!="HISTORY_READY") delay(50)}
            id=app.studyRuntime.state.value.sessionId!!
            val before=app.journal.dao.session(id)!!.sessionEpoch
            val build=app.transport.peerBuildInfo()
            app.studyRuntime.setAutomaticCapture(false)
            stage("WAITING_FOR_PEER_RESTART")
            withTimeout(60000) {while(!signal.isFile) delay(100)}
            withTimeout(15000) {while(!app.transport.ready.value) delay(100)}
            withTimeout(35000) {app.studyRuntime.resumeSession()}
            assertEquals(id,app.studyRuntime.state.value.sessionId)
            assertTrue(app.journal.dao.session(id)!!.sessionEpoch>before)
            assertEquals(build,app.transport.peerBuildInfo())
            stage("RESUMED")
            app.studyRuntime.captureNow()
            withTimeout(35000) {while(app.studyRuntime.state.value.liveCaptures==0) delay(50)}
            app.studyRuntime.pauseSession()
            assertNotNull(app.studyRuntime.state.value.commandAckMs)
            assertNotNull(app.studyRuntime.state.value.cameraDurationMs)
            assertNotNull(app.studyRuntime.state.value.transferDurationMs)
            app.journal.export(id).copyTo(File(dir,"peer-restart.zip"),overwrite=true)
            File(dir,"peer-restart-result.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "scope" to "PHYSICAL_GLASSES_PROCESS_RESTART","session_preserved" to true,"epoch_advanced" to true,"build_unchanged" to true,
                "command_ack_rtt_ms" to app.studyRuntime.state.value.commandAckMs,"camera_duration_ms" to app.studyRuntime.state.value.cameraDurationMs,
                "transfer_duration_ms" to app.studyRuntime.state.value.transferDurationMs,"capture_round_trip_ms" to app.studyRuntime.state.value.captureRoundTripMs,
                "model" to "LOCAL_SCRIPTED_MOCK").toString())
            stage("COMPLETE")
        } catch(error: Throwable) {
            stage("FAILED");File(dir,"peer-restart-failure.json").writeText(StudyWire.payload("error_type" to error.javaClass.simpleName,"code" to error.message).toString());throw error
        } finally {
            app.studyRuntime.stopSession();app.validationInferenceFactory=null;signal.delete()
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
        }
    }
}
