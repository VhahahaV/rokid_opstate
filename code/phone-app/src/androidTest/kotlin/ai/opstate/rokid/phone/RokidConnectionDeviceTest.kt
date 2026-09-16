package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.transport.RokidDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule

/** Real licensed CXR connection, explicitly invoked on the paired study glasses; credential values are never logged. */
@RunWith(AndroidJUnit4::class)
class RokidConnectionDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun authenticatesAndCompletesTheRealGlassesHandshake()=runBlocking {
        assumeTrue("Explicit device connection test only",InstrumentationRegistry.getArguments().getString("real_sdk_connect")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhoneApplication
        val requireP2p=InstrumentationRegistry.getArguments().getString("require_p2p")=="true"
        app.storageReady.await()
        check(!app.credentials.clientSecret().isNullOrBlank() && app.credentials.licenseBytes()?.isNotEmpty()==true) {"ROKID_CREDENTIALS_REQUIRED"}
        val device=app.link.savedDevice() ?: app.link.pairedDevices().singleOrNull() ?: error("SELECT_STUDY_GLASSES_FIRST")
        val directory=File(app.filesDir,"validation").apply {mkdirs()}
        val phases=CopyOnWriteArrayList<JsonObject>()
        val observer=launch(Dispatchers.IO) {app.link.state.collect {state->
            val event=StudyWire.payload("phase" to state.phase,"bluetooth_connected" to state.bluetoothConnected,"p2p_connected" to state.p2pConnected,
                "p2p_phase" to state.p2pPhase,"p2p_init_status" to state.p2pInitStatus,"error_code" to state.errorCode,"at_epoch_ms" to System.currentTimeMillis())
            phases+=event;File(directory,"sdk-connect-progress.json").writeText(JsonArray(phases).toString())
        }}
        var failure: Throwable?=null
        try {
            withTimeout(65000) {app.transport.connect(RokidDevice(device.address,device.name,true)).getOrThrow()}
            if(requireP2p) {
                val p2p=withTimeout(45000) {app.link.state.first {it.p2pConnected || it.p2pPhase=="FAILED"}}
                check(p2p.p2pConnected) {"P2P_NOT_READY"}
            }
        } catch(error: Exception) {failure=error}
        observer.cancelAndJoin()
        val state=app.link.state.value
        val report=StudyWire.payload("status" to if(failure==null && app.transport.ready.value) "PASS" else "FAIL",
            "credentials_present" to true,"license_bytes" to app.credentials.licenseBytes()!!.size,"identity_cached" to app.link.hasDeviceIdentity(),
            "sdk_phase" to state.phase,"sdk_bluetooth_connected" to state.bluetoothConnected,"peer_ready" to app.transport.ready.value,
            "p2p_connected" to state.p2pConnected,"error_code" to state.errorCode,"error_type" to failure?.javaClass?.simpleName,
            "p2p_required" to requireP2p,"p2p_phase" to state.p2pPhase,"p2p_init_status" to state.p2pInitStatus,
            "handshake" to app.transport.handshakeDiagnostics.value,"phases" to JsonArray(phases))
        File(directory,if(requireP2p) "sdk-p2p-result.json" else "sdk-connect-result.json").writeText(report.toString())
        assertTrue(report.toString(),failure==null && app.transport.ready.value)
    }
}
