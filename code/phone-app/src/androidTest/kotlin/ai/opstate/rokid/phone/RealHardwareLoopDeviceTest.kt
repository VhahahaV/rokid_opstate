package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Only WHEN/HOW is scripted. Transport, camera, IMU, display and visible ACK are the actual devices. */
@RunWith(AndroidJUnit4::class)
class RealHardwareLoopDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    internal class LocalModel(private val label: DecisionLabel): InferenceClient {
        private val frozen=FrozenHarnessContract("opstate-online-v2","mock-on-device-v5","a".repeat(64),"b".repeat(64),"c".repeat(64),
            "66ca2fd5145b54392cf0dff807cc8a2575704c7826363df493fcabbb144e4985","19be82d16972ca122468c00118a2447d1002a1ac19ba45a0f103cd282776429f",
            mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun contract(expectedModelId: String?)=frozen
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            val now=System.currentTimeMillis()
            val response=InferenceResponse(request.requestId,"decision-${UUID.randomUUID()}",label,
                if(label==DecisionLabel.SERVICE) ServiceMode.PREPARATION else null,if(label==DecisionLabel.SERVICE) "设备闭环验证" else null,
                if(label==DecisionLabel.SERVICE) "这是一条模拟研究服务，用于验证真实显示与可见回执。" else null,
                setOf(request.observations.last {it.assetId==request.currentAssetId}.observationId),now,now+20000)
            val body=StudyWire.payload("request_id" to response.requestId,"decision_id" to response.decisionId,"decision" to label.name,
                "service" to if(label==DecisionLabel.SERVICE) StudyWire.payload("mode" to response.serviceMode!!.name,"title" to response.title,"body" to response.body) else JsonNull,
                "evidence_refs" to JsonArray(response.evidenceObservationIds.map(::JsonPrimitive)),"produced_at_epoch_ms" to now,"expires_at_epoch_ms" to now+20000).toString()
            onRaw(RawHarnessResponse(request.requestId,200,body,StudyWire.sha256(body.toByteArray()),now,0))
            return response
        }
    }
    @Test fun actualCaptureTransferAndVisibleReceiptWithOnPhoneMock()=runBlocking {
        val argument=InstrumentationRegistry.getArguments().getString("hardware_loop")
        assumeTrue("Explicit real hardware loop only",argument in setOf("SERVICE","NO_SERVICE"))
        val label=DecisionLabel.valueOf(argument!!)
        val app=compose.activity.application as PhoneApplication
        val directory=File(app.filesDir,"validation").apply {mkdirs()}
        app.storageReady.await()
        val device=app.link.savedDevice() ?: app.link.pairedDevices().singleOrNull() ?: error("SELECT_STUDY_GLASSES_FIRST")
        app.transport.connect(RokidDevice(device.address,device.name,true)).getOrThrow()
        app.validationInferenceFactory={setup->if(setup.expectedModelId=="mock-on-device-v5") LocalModel(label) else null}
        val statuses=launch(Dispatchers.IO) {app.studyRuntime.state.collect {s->
            File(directory,"hardware-loop-progress.json").writeText(StudyWire.payload("phase" to s.phase,"session_id" to s.sessionId,
                "observations" to s.observations,"packets" to s.packets,"receipts" to s.receipts,"error" to s.error,
                "sdk_connected" to app.link.state.value.bluetoothConnected,"peer_ready" to app.transport.ready.value,"p2p_connected" to app.link.state.value.p2pConnected).toString())
        }}
        var id: String?=null
        try {
            val setup=SessionSetup("TEST_REAL_HARDWARE","local://scripted",expectedModelId="mock-on-device-v5")
            compose.runOnUiThread {compose.activity.startForegroundService(Intent(compose.activity,StudyRuntimeService::class.java)
                .setAction(StudyRuntimeService.ACTION_START).putExtra("setup",StudyWire.json.encodeToString(SessionSetup.serializer(),setup)))}
            withTimeout(10000) {while(app.studyRuntime.state.value.sessionId==null) delay(50)}
            id=app.studyRuntime.state.value.sessionId!!
            withTimeout(80000) {
                while(app.journal.dao.stream(id,"decisions").isEmpty()) delay(100)
                if(label==DecisionLabel.SERVICE) while(app.journal.dao.stream(id,"receipts").isEmpty()) delay(100)
            }
            app.studyRuntime.pauseSession()
            val images=app.journal.dao.stream(id,"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}.filter {it["local_relative_path"]!=null}
            val receipts=app.journal.dao.stream(id,"receipts")
            assertTrue(images.isNotEmpty());assertTrue(images.all {it["asset_source"]==JsonPrimitive("LIVE_CAMERA")})
            if(label==DecisionLabel.SERVICE) {
                assertEquals(1,receipts.size)
                assertTrue(StudyWire.json.parseToJsonElement(receipts.single().canonicalJson).jsonObject.getValue("visible_ack_frame_count").jsonPrimitive.int>=2)
            } else assertTrue(receipts.isEmpty())
            val archive=app.journal.export(id)
            archive.copyTo(File(directory,"hardware-loop-${label.name}.zip"),overwrite=true)
            File(directory,"hardware-loop-${label.name}.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "model" to "LOCAL_SCRIPTED_MOCK","expected_decision" to label.name,"captures" to images.size,"receipts" to receipts.size,
                "channels" to JsonArray(images.map {it.getValue("transfer_channel")}),"sdk_authenticated" to true,
                "physical_camera" to true,"physical_radio" to true,"physical_visible_ack" to (label==DecisionLabel.SERVICE)).toString())
        } finally {
            app.studyRuntime.stopSession()
            compose.runOnUiThread {compose.activity.stopService(Intent(compose.activity,StudyRuntimeService::class.java))}
            app.validationInferenceFactory=null;statuses.cancelAndJoin()
        }
    }
}
