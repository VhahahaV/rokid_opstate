package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.runtime.*
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Explicit one-request provider smoke, with a generated checkerboard and synthetic history; no benchmark comparison. */
@RunWith(AndroidJUnit4::class)
class FrozenHarnessDeviceTest {
    @Test fun nativeClientUsesTheFrozenModelAndDuplicateRequestReusesItsDecision()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("harness_endpoint")
        assumeTrue("Run explicitly with harness_endpoint and expected_model",endpoint!=null && args.getString("expected_model")!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"validation").apply {mkdirs()}
        val tokenFile=File(directory,"frozen-harness-access-token")
        val client=RemoteHarnessClient(endpoint!!,{tokenFile.readText().trim()},StudyWire.json,routeClient=InternetRouting(context)::client)
        val contract=client.contract(args.getString("expected_model"))
        assertFalse(contract.mock);assertTrue(contract.strictContract);assertNotNull(contract.modelConfig)
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/20+y/20)%2==0) Color.WHITE else Color.BLACK)
        val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it);it.toByteArray()};bitmap.recycle()
        val now=System.currentTimeMillis();val suffix=UUID.randomUUID().toString()
        val request=InferenceRequest(contract.protocolVersion,"model-smoke-$suffix","anchor-smoke-$suffix",now,"asset-checkerboard",StudyWire.sha256(bytes),
            ModelCandidate("obj-smoke","ep-smoke"),listOf(
                ModelObservation("obs-context",ObservationKind.SYNTHETIC_CONTEXT,now-1000,now-1000,
                    "Engineering integration fixture: there is no pending task or requested action. The attached checkerboard is generated test data.",null,"ep-smoke",0),
                ModelObservation("obs-image",ObservationKind.IMAGE,now,now,"Generated current test image.","asset-checkerboard",null,null)),
            emptyList(),contract.promptHash,contract.preprocessHash,contract.modelId,contract.parserHash)
        File(directory,"frozen-smoke-request.json").writeText(StudyWire.json.encodeToJsonElement(request.toWire()).toString())
        File(directory,"frozen-smoke-image.jpg").writeBytes(bytes)
        var rawSaved=false
        val response=client.evaluate(request,bytes) {raw->
            File(directory,"frozen-smoke-raw.json").writeText(StudyWire.payload("http_status" to raw.httpStatus,"body" to raw.body,"sha256" to raw.bodySha256,"latency_ms" to raw.latencyMs).toString())
            rawSaved=true
        }
        assertTrue(rawSaved);assertTrue(InferenceContract.validate(response,request).isEmpty())
        assertEquals(response,client.evaluate(request,bytes))
        File(directory,"frozen-smoke-result.json").writeText(StudyWire.payload("status" to "PASS","request_id" to request.requestId,
            "contract" to StudyWire.json.encodeToJsonElement(contract),"decision_id" to response.decisionId,"decision" to response.decision.name,
            "duplicate_result_identical" to true,"image_source" to "GENERATED_CHECKERBOARD","history_source" to "SYNTHETIC_ENGINEERING_FIXTURE",
            "physical_radio_test" to false,"track_a_comparison" to false).toString())
    }
}
