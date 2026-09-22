package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.JournalEntity
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.RokidDevice
import ai.opstate.rokid.phone.transport.RokidEvent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long the operator's own click takes, stage by stage, on the real link and the real model.
 *
 * The reported symptom is a gap of ten-odd seconds between pressing the manual service control and a
 * card appearing on the glasses. That number is the sum of stages that fail for different reasons -
 * a camera that may have been released, an upload over a Bluetooth link, an ONNX judgement on the
 * phone, a provider call, and the render - so it is measured here rather than attributed. Each round
 * is one press, and every stage boundary is read back out of the journal, which is where the engine
 * already records them.
 *
 * Forced service is on: a round has to end in a card for the last stage to exist at all, and what is
 * being measured is the path, not the decision.
 *
 *   adb shell am instrument -w -e manual_latency true [-e rounds 6] \
 *     -e class ai.opstate.rokid.phone.ManualServiceLatencyDeviceTest \
 *     ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ManualServiceLatencyDeviceTest {
    private companion object { const val CARD_VISIBLE_MS=10_000L }
    @Test fun howLongOnePressTakesToReachTheGlasses()=runBlocking<Unit> {
        assumeTrue("explicit manual-path latency measurement",InstrumentationRegistry.getArguments().getString("manual_latency")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val rounds=InstrumentationRegistry.getArguments().getString("rounds")?.toIntOrNull() ?: 6
        val frontEndOn=InstrumentationRegistry.getArguments().getString("front_end")!="false"
        // Pressing into a live card and pressing into a clear screen are different measurements, and the
        // difference is the whole question: `-e settle_ms 12000` waits the card out before pressing.
        val settleMs=InstrumentationRegistry.getArguments().getString("settle_ms")?.toLongOrNull() ?: 3_000L
        val config=DirectModelConfig.qwen()
        check(app.credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
        val saved=app.link.savedDevice() ?: error("SELECT_STUDY_GLASSES_FIRST")
        runCatching {withTimeout(30_000) {app.transport.ready.first {it}}}.onFailure {
            app.transport.connect(RokidDevice(saved.address,saved.name,true)).getOrThrow()
            withTimeout(30_000) {app.transport.ready.first {it}}
        }
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val engine=StudyRuntimeEngine(context,scope,app.transport,journal,{""},{app.credentials.modelApiKey(it.credentialScope())})
        var id: String?=null
        fun rows(stream: String)=database.dao().stream(requireNotNull(id),stream)
        fun payload(row: JournalEntity)=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject
        suspend fun await(check: ()->Boolean,ms: Long)=withTimeout(ms) {while(!check()) delay(10)}
        val table=mutableListOf<JsonObject>()
        fun lastOf(stream: String,type: String)=rows(stream).lastOrNull {it.eventType==type}?.let(::payload)
        try {
            engine.start(SessionSetup("MANUAL_LATENCY",config.resolvedEndpoint(),expectedModelId=config.modelId,
                directModel=config,mode=SessionMode.SERVICE,glassesCapture=true,captureIntervalMs=60_000,frontEndEnabled=frontEndOn)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            engine.setForceService(true).getOrThrow()
            // One settling round, so the first measurement is not a cold camera and cold front end.
            delay(4_000)
            repeat(rounds) {round->
                val beforeVisible=rows("deliveries").count {it.eventType=="DELIVERY_DECISION" &&
                    payload(it)["status"]?.jsonPrimitive?.contentOrNull=="DISPLAY_VISIBLE"}
                val beforeCompleted=rows("captures").count {it.eventType=="CAPTURE_COMPLETED"}
                val beforeRequests=rows("runtime_events").count {it.eventType=="CAPTURE_COMMAND_ACK"}
                // How much of the previous card is still on the glasses when this press happens. The
                // engine will not start a model call while a card is displayed, so whatever is left of it
                // is added to this round in full - which is worth knowing before blaming any other stage.
                val shown=rows("deliveries").filter {it.eventType=="DELIVERY_DECISION" &&
                    payload(it)["status"]?.jsonPrimitive?.contentOrNull=="DISPLAY_VISIBLE"}
                val pressedAt=System.currentTimeMillis()
                val pressedIntoCard=shown.lastOrNull()?.let {(it.eventAtEpochMs+CARD_VISIBLE_MS-pressedAt).coerceAtLeast(0L)} ?: 0L
                engine.captureNow()
                val ok=runCatching {await({rows("deliveries").count {it.eventType=="DELIVERY_DECISION" &&
                    payload(it)["status"]?.jsonPrimitive?.contentOrNull=="DISPLAY_VISIBLE"}>beforeVisible},45_000)}.isSuccess
                val visibleAt=System.currentTimeMillis()
                if(!ok) {table+=StudyWire.payload("round" to round,"status" to "NO_CARD");delay(2_000);return@repeat}
                // This round's own capture, not whatever completed last: an interval shot may be in flight,
                // and attributing its transfer to this press is how a stage gets blamed for the wrong wait.
                val ack=rows("runtime_events").filter {it.eventType=="CAPTURE_COMMAND_ACK"}.getOrNull(beforeRequests)
                val captureId=ack?.let {payload(it)["capture_id"]?.jsonPrimitive?.contentOrNull}
                val completed=rows("captures").drop(beforeCompleted).firstOrNull {
                    it.eventType=="CAPTURE_COMPLETED" && payload(it)["capture_id"]?.jsonPrimitive?.contentOrNull==captureId}
                fun at(row: JournalEntity?)=row?.eventAtEpochMs ?: 0L
                val ackAt=at(ack); val receivedAt=at(completed)
                // The request this press produced: the first one after its frame, never the last in the
                // session, which belongs to an earlier round once the cadence is faster than the round.
                val sent=rows("inference_requests").firstOrNull {it.eventType=="INFERENCE_SENT" && receivedAt>0 && at(it)>=receivedAt}
                val sentAt=at(sent)
                val decision=if(sent==null) null else rows("decisions").firstOrNull {it.eventType=="MODEL_DECISION" && at(it)>=sentAt}
                val timing=lastOf("runtime_events","VISIBLE_TIMING")
                val decidedAt=at(decision)
                val framesThisRound=rows("captures").count {it.eventType=="CAPTURE_COMPLETED"}-beforeCompleted
                table+=StudyWire.payload("round" to round,"status" to "CARD",
                    "press_to_visible_ms" to (visibleAt-pressedAt),
                    // Where the press actually went, in order, so a gap cannot hide between two stages.
                    "press_to_ack_ms" to (ackAt-pressedAt),
                    "ack_to_frame_ms" to (receivedAt-ackAt),
                    "frame_to_request_ms" to (sentAt-receivedAt),
                    "model_ms" to (decidedAt-sentAt),
                    "model_reported_ms" to (decision?.let {payload(it)["model_latency_ms"]?.jsonPrimitive?.longOrNull} ?: 0L),
                    "decision_to_visible_ms" to (visibleAt-decidedAt),
                    "reported_decision_to_visible_ms" to (timing?.get("decision_to_visible_ms")?.jsonPrimitive?.longOrNull ?: 0L),
                    "camera_ms" to (completed?.let {payload(it)["camera_duration_ms"]?.jsonPrimitive?.longOrNull} ?: 0L),
                    "transfer_ms" to (completed?.let {payload(it)["transfer_duration_ms"]?.jsonPrimitive?.longOrNull} ?: 0L),
                    "frames_this_round" to framesThisRound,
                    "pressed_into_card_ms" to pressedIntoCard,
                    "front_end_ms" to framesThisRound*(if(frontEndOn) 218L else 0L))
                delay(settleMs)
            }
            val done=table.filter {it["status"]?.jsonPrimitive?.contentOrNull=="CARD"}
            assertTrue("no round produced a card; the path could not be measured",done.isNotEmpty())
            fun col(name: String)=done.mapNotNull {it[name]?.jsonPrimitive?.longOrNull}
            fun med(name: String)=col(name).sorted().let {if(it.isEmpty()) 0L else it[it.size/2]}
            val report=StudyWire.payload("probe" to "manual_service_latency_v1","model_id" to config.modelId,
                "rounds" to table.size,"cards" to done.size,
                "median" to StudyWire.payload(*listOf("press_to_visible_ms","press_to_ack_ms","ack_to_frame_ms",
                    "frame_to_request_ms","model_ms","decision_to_visible_ms","camera_ms","transfer_ms").map {it to med(it)}.toTypedArray()),
                "rounds_detail" to JsonArray(table))
            File(requireNotNull(context.getExternalFilesDir(null)),"manual_service_latency.json").writeText(report.toString())
            println("MANUAL_LATENCY $report")
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin();id?.let {runCatching {journal.delete(it)}};database.close()
        }
    }
}
