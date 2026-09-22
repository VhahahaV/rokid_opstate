package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.RokidDevice
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaves a live service session running so the link can be watched with the screen off.
 *
 * It starts a session over the real radio and holds it, writing what the engine believes to the app's
 * own files so the watch can be read while it is still running - `am instrument -w` buffers stdout
 * until it returns, and a log that can only be read afterwards cannot be lined up against the screen
 * going off. Turn the screen off from the shell and read both that file and `dumpsys power`:
 *
 *   adb shell run-as ai.opstate.rokid.phone cat files/validation/stability.log
 *
 *   adb shell am instrument -w -e stability_probe true \
 *     -e class ai.opstate.rokid.phone.ServiceStabilityProbeDeviceTest \
 *     ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 *   adb shell input keyevent KEYCODE_POWER
 *   adb shell dumpsys power | grep -i "OPSTATE:collection"
 *
 * Stop it from the console's 结束, or with the instrumentation below.
 */
@RunWith(AndroidJUnit4::class)
class ServiceStabilityProbeDeviceTest {
    @Test fun aLiveServiceSessionIsLeftRunningForTheScreenOffWatch()=runBlocking {
        assumeTrue("explicit screen-off stability watch",InstrumentationRegistry.getArguments().getString("stability_probe")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        app.validationInferenceFactory=null
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        runCatching {withTimeout(45_000) {app.transport.ready.first {it}}}.onFailure {
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        // Five seconds and a batch of two keep the link busy without the front end in the way: what is
        // being watched is the radio, not the detector.
        val setup=SessionSetup("TEST_STABILITY","local://scripted",expectedModelId="mock-on-device-v5",scenarioAsset="",
            mode=SessionMode.SERVICE,captureIntervalMs=5_000,glassesCapture=true,syncBatchSize=2,frontEndEnabled=false)
        app.validationInferenceFactory={model->if(model.expectedModelId=="mock-on-device-v5") RealHardwareLoopDeviceTest.LocalModel(ai.opstate.core.model.DecisionLabel.NO_SERVICE) else null}
        app.startForegroundService(Intent(app,StudyRuntimeService::class.java).setAction(StudyRuntimeService.ACTION_START)
            .putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))
        withTimeout(30_000) {while(app.studyRuntime.state.value.sessionId==null) delay(100)}
        val holdMs=InstrumentationRegistry.getArguments().getString("hold_ms")?.toLongOrNull() ?: 480_000L
        println("STABILITY_PROBE started session=${app.studyRuntime.state.value.sessionId} holding ${holdMs/1000}s")
        // Watch from the shell while this holds: turn the screen off and read the same numbers out of
        // `dumpsys power` and logcat. Printing here puts the app's own view of the link on the timeline,
        // so a drop can be lined up against the screen going off.
        val startedAt=System.currentTimeMillis()
        // Written to a file on the device rather than printed: `am instrument -w` buffers stdout until
        // it returns, and a watch that can only be read after it ends cannot be lined up against the
        // screen going off. `holdsCpu` is included because it is the thing the wake lock follows.
        val log=File(context.filesDir,"validation/stability.log").apply {parentFile?.mkdirs();writeText("")}
        var lastCaptures=0
        while(System.currentTimeMillis()-startedAt<holdMs) {
            delay(10_000)
            val state=app.studyRuntime.state.value
            val captures=state.liveCaptures
            log.appendText("t=${(System.currentTimeMillis()-startedAt)/1000}s phase=${state.phase} "+
                "holdsCpu=${state.holdsCpu()} awaitingLink=${state.awaitingLink} captures=$captures (+${captures-lastCaptures}) "+
                "buffered=${state.bufferedPhotos} error=${state.error ?: "-"}\n")
            lastCaptures=captures
        }
        log.appendText("t=${(System.currentTimeMillis()-startedAt)/1000}s WATCH COMPLETE\n")
        println("STABILITY_PROBE complete after ${(System.currentTimeMillis()-startedAt)/1000}s")
    }
}
