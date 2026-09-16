package ai.opstate.rokid.phone

import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.RokidDevice
import android.content.Intent
import android.os.SystemClock
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
import java.security.MessageDigest

/** Real radio/camera/session soak. No transport restart or synthetic image replacement can hide a failure. */
@RunWith(AndroidJUnit4::class)
class DeviceSoakTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun thirtyMinutesAndFiftyPrimaryCapturesWithoutManualRecovery()=runBlocking {
        assumeTrue("Explicit full hardware soak only",InstrumentationRegistry.getArguments().getString("device_soak")=="true")
        val app=compose.activity.application as PhoneApplication
        val dir=File(app.filesDir,"validation").apply {mkdirs()}
        val digest=MessageDigest.getInstance("SHA-256")
        File(app.applicationInfo.sourceDir).inputStream().use {input->
            val buffer=ByteArray(65536);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
        }
        val apkHash=digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        val runStarted=SystemClock.elapsedRealtime()
        var connectedStarted: Long?=null;var id: String?=null;var failure: Throwable?=null
        var outageStarted: Long?=null;var maxOutageMs=0L
        fun write(name: String,value: JsonObject) {
            val temporary=File(dir,"$name.tmp");temporary.writeText(value.toString())
            check(temporary.renameTo(File(dir,name)))
        }
        fun progress(stage: String) {write("device-soak-progress.json",StudyWire.payload("stage" to stage,"session_id" to id,
            "elapsed_ms" to (SystemClock.elapsedRealtime()-(connectedStarted ?: runStarted)),"target_ms" to 1800000,
            "phase" to app.studyRuntime.state.value.phase,"images" to app.studyRuntime.state.value.liveCaptures,
            "peer_ready" to app.transport.ready.value,"p2p_connected" to app.link.state.value.p2pConnected,
            "error" to app.studyRuntime.state.value.error,"apk_sha256" to apkHash))}
        try {
            app.storageReady.await();progress("CONNECTING")
            val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(DecisionLabel.NO_SERVICE) else null}
            val setup=SessionSetup("TEST_DEVICE_SOAK_30_MIN","local://scripted",expectedModelId="mock-on-device-v5",historyAnchorIndex=1)
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
                .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(15000) {while(app.studyRuntime.state.value.phase!="HISTORY_READY") delay(50)}
            id=app.studyRuntime.state.value.sessionId!!
            app.studyRuntime.resumeSession()
            connectedStarted=SystemClock.elapsedRealtime()
            var nextManual=connectedStarted+30000;var nextProgress=SystemClock.elapsedRealtime()
            while(SystemClock.elapsedRealtime()-connectedStarted<1800000) {
                val now=SystemClock.elapsedRealtime()
                if(app.transport.ready.value) {
                    outageStarted?.let {maxOutageMs=maxOf(maxOutageMs,now-it)};outageStarted=null
                } else {
                    if(outageStarted==null) outageStarted=now
                    maxOutageMs=maxOf(maxOutageMs,now-outageStarted!!)
                    check(maxOutageMs<60000) {"UNRECOVERED_CONNECTION_LOSS"}
                }
                check(app.studyRuntime.state.value.sessionId==id) {"SESSION_ENDED_DURING_SOAK"}
                if(now>=nextManual && app.studyRuntime.state.value.phase=="RUNNING") {
                    app.studyRuntime.captureNow();nextManual+=60000
                }
                if(now>=nextProgress) {progress("RUNNING");nextProgress=now+5000}
                delay(250)
            }
            app.studyRuntime.pauseSession()
            progress("EXPORTING")
        } catch(error: Throwable) {failure=error}
        finally {
            withContext(NonCancellable) {
                try {
                    app.studyRuntime.stopSession()
                    id?.let {sessionId->
                        val captures=app.journal.dao.stream(sessionId,"captures").map {it.eventType to StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                        val primary=captures.filter {(type,p)->type=="CAPTURE_REQUEST" && p["quality_retry"]==JsonPrimitive(0)}.map {it.second}
                        val completed=captures.filter {it.first=="CAPTURE_COMPLETED"}.associate {it.second.getValue("capture_id").jsonPrimitive.content to it.second}
                        val successes=primary.count {it.getValue("capture_id").jsonPrimitive.content in completed}
                        val photos=app.journal.dao.stream(sessionId,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                            .filter {it["asset_source"]==JsonPrimitive("LIVE_CAMERA")}
                        val elapsed=SystemClock.elapsedRealtime()-(connectedStarted ?: runStarted)
                        val pass=failure==null && elapsed>=1800000 && primary.size>=50 && successes.toDouble()/primary.size>=0.95
                        val report=StudyWire.payload("status" to if(pass) "PASS" else "FAIL","session_id" to sessionId,"elapsed_ms" to elapsed,
                            "scope" to "PHYSICAL_RADIO_CAMERA_SOAK","primary_capture_requests" to primary.size,"primary_capture_successes" to successes,
                            "max_peer_not_ready_ms" to maxOutageMs,"actual_photos" to photos.size,
                            "quality_valid_photos" to photos.count {it["quality_valid"]==JsonPrimitive(true)},
                            "channels" to JsonArray(photos.map {it.getValue("transfer_channel")}.distinct()),"model" to "LOCAL_SCRIPTED_MOCK",
                            "model_decisions" to app.journal.dao.stream(sessionId,"decisions").size,"apk_sha256" to apkHash,
                            "failure_type" to failure?.javaClass?.simpleName,"failure_code" to failure?.message)
                        app.journal.export(sessionId).copyTo(File(dir,"device-soak.zip"),overwrite=true)
                        write("device-soak-result.json",report)
                        if(!pass && failure==null) failure=AssertionError(report.toString())
                    }
                } finally {
                    app.validationInferenceFactory=null
                    compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
                    progress(if(failure==null) "COMPLETE" else "FAILED")
                }
            }
        }
        failure?.let {throw it}
        Unit
    }
}
