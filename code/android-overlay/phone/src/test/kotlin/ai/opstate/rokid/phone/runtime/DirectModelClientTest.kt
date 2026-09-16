package ai.opstate.rokid.phone.runtime

import ai.opstate.core.inference.*
import ai.opstate.core.model.*
import ai.opstate.protocol.StudyWire
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class DirectModelClientTest {
    private val key="fixture-model-secret-321"
    private val image="explicit-unit-image-fixture".toByteArray()
    private fun client(server: MockWebServer,wire: String="responses")=DirectModelClient(
        DirectModelConfig("fixture-model",server.url("/v1").toString(),wire,512,reasoningEffort=if(wire=="responses") "high" else null),
        {key},"fixture prompt",StudyWire.payload("type" to "object"),"a".repeat(64),"b".repeat(64),"c".repeat(64),allowTestLoopback=true)
    private suspend fun request(client: DirectModelClient): InferenceRequest {
        val frozen=client.contract();val now=System.currentTimeMillis()
        return InferenceRequest(frozen.protocolVersion,"request-fixture","anchor-fixture",now,"asset-fixture",StudyWire.sha256(image),
            ModelCandidate("obj-fixture","ep-fixture"),listOf(ModelObservation("obs-fixture",ObservationKind.IMAGE,now,now,"test", "asset-fixture",null,null)),emptyList(),
            frozen.promptHash,frozen.preprocessHash,frozen.modelId,frozen.parserHash,
            setOf("VISUAL"),listOf("obs-fixture"),0,false)
    }
    private fun reply(wire: String="responses",label: String="NO_SERVICE",evidence: String="obs-fixture",incomplete: Boolean=false,serviceBody: String="Prepare equipment."): MockResponse {
        val output=StudyWire.payload("decision" to label,"service" to if(label=="SERVICE") StudyWire.payload("mode" to "PREPARATION","title" to "Ready","body" to serviceBody) else JsonNull,
            "evidence_refs" to JsonArray(listOf(JsonPrimitive(evidence))))
        val raw=if(wire=="responses") StudyWire.payload("model" to "fixture-model","status" to if(incomplete) "incomplete" else "completed",
            "output" to JsonArray(listOf(StudyWire.payload("type" to "message","content" to JsonArray(listOf(StudyWire.payload("type" to "output_text","text" to output.toString())))))))
        else StudyWire.payload("model" to "fixture-model","choices" to JsonArray(listOf(StudyWire.payload("finish_reason" to if(incomplete) "length" else "stop","message" to StudyWire.payload("content" to output.toString())))))
        return MockResponse().setHeader("Content-Type","application/json").setBody(raw.toString())
    }
    @Test fun responsesPreservesRawThenNormalizesAndDoesNotCallAgainForCompletedRequest()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(reply(label="SERVICE"));val client=client(server);val request=request(client)
            val raw=mutableListOf<RawHarnessResponse>()
            val result=client.evaluate(request,image) {raw+=it}
            assertEquals(result,client.evaluate(request,image))
            assertEquals(1,server.requestCount)
            assertEquals(listOf("PROVIDER_RESPONSES","OPSTATE_V2_NORMALIZED"),raw.map {it.format})
            assertEquals(DecisionLabel.SERVICE,result.decision)
            val sent=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/v1/responses",sent.path);assertEquals("Bearer $key",sent.getHeader("Authorization"))
            val body=StudyWire.json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(false),body["store"]);assertFalse("temperature" in body)
            assertEquals(JsonPrimitive("high"),body["reasoning"]!!.jsonObject["effort"])
            assertFalse(body.toString().contains(key))
            val inputText=body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
            assertTrue(inputText.contains("\"packet_id\":\"anchor-fixture\""))
            assertTrue(inputText.contains("\"trigger_types\":[\"VISUAL\"]"))
            assertFalse(inputText.contains("anchor_id"));assertFalse(inputText.contains("candidate"))
            assertTrue(runCatching {client.evaluate(request.copy(anchorId="changed"),image)}.isFailure)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun chatUsesChatParametersAndNoServiceHasNullHow()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(reply("chat_completions"));val client=client(server,"chat_completions")
            val result=client.evaluate(request(client),image)
            assertEquals(DecisionLabel.NO_SERVICE,result.decision);assertNull(result.body)
            val sent=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions",sent.path)
            val body=StudyWire.json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(512),body["max_tokens"]);assertEquals(JsonPrimitive(false),body["enable_thinking"])
            assertFalse("reasoning" in body)
        }
    }
    @Test fun directRequestsAttachSelectedHistoricalImagesBeforeTheCurrentFrame()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(reply());val client=client(server);val base=request(client)
            val historical="historical-image".toByteArray()
            val request=base.copy(observations=listOf(
                ModelObservation("history-obs",ObservationKind.IMAGE,base.anchorTimeEpochMs-1,base.anchorTimeEpochMs-1,"collected","history-asset",null,null)
            )+base.observations)
            client.evaluateWithHistory(request,image,mapOf("history-asset" to historical))
            val body=StudyWire.json.parseToJsonElement(server.takeRequest(1,TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            val content=body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.map {it.jsonObject}
            assertEquals(listOf("input_text","input_text","input_image","input_text","input_image"),content.map {it.getValue("type").jsonPrimitive.content})
            assertTrue(content[1].getValue("text").jsonPrimitive.content.contains("history-asset"))
            assertTrue(content[2].getValue("image_url").jsonPrimitive.content.endsWith(Base64.getEncoder().encodeToString(historical)))
        }
    }
    @Test fun futureInputsAndContractChangesDoNotReachProvider()=runBlocking {
        MockWebServer().use {server->
            val client=client(server);val request=request(client)
            val future=request.copy(observations=request.observations.map {it.copy(availableAtEpochMs=request.anchorTimeEpochMs+1)})
            assertTrue(runCatching {client.evaluate(future,image)}.isFailure)
            assertTrue(runCatching {client.evaluate(request.copy(parserHash="wrong"),image)}.isFailure)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun invalidEvidenceAndIncompleteOutputRemainTechnicalFailuresAfterRawPersistence()=runBlocking {
        MockWebServer().use {server->
            for(response in listOf(reply(evidence="future-evidence"),reply(incomplete=true))) {
                server.enqueue(response);val client=client(server);val raw=mutableListOf<RawHarnessResponse>()
                assertTrue(runCatching {client.evaluate(request(client),image) {raw+=it}}.isFailure)
                assertEquals(listOf("PROVIDER_RESPONSES"),raw.map {it.format})
            }
        }
    }
    @Test fun providerErrorsCannotLeakTheKeyIntoAuditOrBecomeNoService()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid credential: $key"))
            val client=client(server);val raw=mutableListOf<RawHarnessResponse>()
            val error=runCatching {client.evaluate(request(client),image) {raw+=it}}.exceptionOrNull()!!
            assertEquals("MODEL_API_HTTP_401",error.message)
            assertTrue(raw.single().redacted);assertFalse(raw.single().body.contains(key))
            assertEquals(StudyWire.sha256(raw.single().body.toByteArray()),raw.single().bodySha256)
        }
    }
    @Test fun overlongServiceIsPreservedButNeverTruncatedOrNormalizedIntoADisplay()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(reply(label="SERVICE",serviceBody="长".repeat(141)))
            val client=client(server);val raw=mutableListOf<RawHarnessResponse>()
            val failure=runCatching {client.evaluate(request(client),image) {raw+=it}}.exceptionOrNull()
            assertEquals("MODEL_OUTPUT_TOO_LONG",failure?.message)
            assertEquals(listOf("PROVIDER_RESPONSES"),raw.map {it.format})
            assertTrue(raw.single().body.contains("长".repeat(141)))
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellationClosesAnOutstandingHttpCall()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val client=client(server);val request=request(client)
            val call=launch {client.evaluate(request,image)}
            withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            withTimeout(1000) {call.cancelAndJoin()}
            assertTrue(call.isCancelled)
        }
    }
    @Test fun endpointNormalizationAndCredentialScopePreventAccidentalProviderKeyReuse() {
        val chat=DirectModelConfig(endpoint="https://api.example/v1/")
        val responses=chat.copy(wireApi="responses")
        assertEquals("https://api.example/v1/chat/completions",chat.resolvedEndpoint())
        assertEquals(chat.credentialScope(),responses.credentialScope())
        assertNotEquals(chat.credentialScope(),chat.copy(endpoint="https://another.example/v1").credentialScope())
        assertTrue(runCatching {chat.copy(endpoint="http://api.example/v1").validate()}.isFailure)
        assertTrue(runCatching {chat.copy(endpoint="https://api.example/v1?api_key=secret").validate()}.isFailure)
    }
}
