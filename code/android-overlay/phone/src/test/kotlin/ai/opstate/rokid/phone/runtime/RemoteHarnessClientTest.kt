package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.InferenceRequest
import ai.opstate.core.inference.ModelCandidate
import ai.opstate.core.model.DecisionLabel
import ai.opstate.rokid.phone.protocol.DeviceEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteHarnessClientTest {
    @Test fun deviceEnvelopeIncludesRequiredProtocolVersionWithDefaultJson() {
        val envelope = DeviceEnvelope(
            messageId = "m", sessionId = "s", sessionEpoch = 1, idempotencyKey = "k",
            type = "SESSION_START", sentAtEpochMs = 100,
        )
        assertTrue(Json.encodeToString(envelope).contains("\"protocol_version\":\"rokid-study-v4\""))
    }

    private fun request() = InferenceRequest(
        requestId = "request-test", anchorId = "anchor-test", anchorTimeEpochMs = 100,
        currentAssetId = "image-test", currentImageSha256 = "a".repeat(64),
        candidate = ModelCandidate("object-test", "episode-test"),
        observations = emptyList(), receipts = emptyList(), promptHash = "p", preprocessHash = "x",
    )

    @Test fun requestIsAFormFieldAndResponseDecodes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"request_id":"request-test","decision_id":"d","decision":"NO_SERVICE",
                 "evidence_observation_ids":[],"produced_at_epoch_ms":100,"expires_at_epoch_ms":200}
            """.trimIndent()))
            val client = RemoteHarnessClient(server.url("/").toString(), { "test-token" }, Json)
            val response = client.evaluate(request(), "image bytes".toByteArray())
            assertEquals(DecisionLabel.NO_SERVICE, response.decision)
            val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/v1/evaluate", sent.path)
            assertEquals("request-test", sent.getHeader("Idempotency-Key"))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("name=\"request\"\r\n"))
            assertFalse(body.contains("filename=\"request.json\""))
            assertTrue(body.contains("name=\"image\"; filename=\"current.jpg\""))
            assertTrue(body.contains("\"request_id\":\"request-test\""))
        }
    }

    @Test fun httpFailureIsReportedToTheRuntime() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val client = RemoteHarnessClient(server.url("/").toString(), { "" }, Json)
            try {
                client.evaluate(request(), byteArrayOf(1))
                fail("HTTP 503 should fail")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("503"))
            }
        }
    }
    @Test fun configuredModelIsCheckedAgainstTheAuthenticatedServerContract()=runBlocking {
        MockWebServer().use { server ->
            val frozen=FrozenHarnessContract("opstate-online-v2","model-a","a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64))
            repeat(2) {server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(Json.encodeToString(frozen)))}
            val client=RemoteHarnessClient(server.url("/").toString(),{"fixture-access-token"},Json)
            try { client.contract("model-b");fail("Wrong model must prevent session preparation") }
            catch(error: IllegalArgumentException) {assertEquals("CONFIGURED_MODEL_MISMATCH",error.message)}
            assertEquals("model-a",client.contract("model-a").modelId)
            repeat(2) {
                val sent=requireNotNull(server.takeRequest(1,TimeUnit.SECONDS))
                assertEquals("/v1/contract",sent.path)
                assertEquals("Bearer fixture-access-token",sent.getHeader("Authorization"))
            }
            assertEquals(2,server.requestCount)
        }
    }

}
