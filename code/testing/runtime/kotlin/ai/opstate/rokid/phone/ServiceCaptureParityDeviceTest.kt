package ai.opstate.rokid.phone

import ai.opstate.core.inference.*
import ai.opstate.core.model.DecisionLabel
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.ImageFrame
import ai.opstate.rokid.phone.transport.RokidEvent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A service session captures the way a collection does.
 *
 * A service session uploads every frame it takes, and the phone decides what to do with each one: the
 * photograph is kept and counted - it is the record of what the wearer passed in front of, and the page
 * draws it - while only a frame worth evaluating becomes a packet and reaches the model. The front end
 * that decides that runs here rather than on the glasses; what this exercises is the rule around it,
 * with the front end switched off and a context arriving, which is the other way a frame is made worth
 * a model call. A service session that kept only its triggers is what made the phone look as if the
 * glasses were not capturing at all.
 */
@RunWith(AndroidJUnit4::class)
class ServiceCaptureParityDeviceTest {
    private class RecordingModel : InferenceClient {
        val requests=CopyOnWriteArrayList<InferenceRequest>()
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract("opstate-online-v2","service-capture-fixture",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),mock=true,mockMode="local_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit)=
            error("evaluateWithHistory is required")
        override suspend fun evaluateWithHistory(request: InferenceRequest,currentJpeg: ByteArray,historicalJpegs: Map<String,ByteArray>,
            onRaw: (RawHarnessResponse)->Unit): InferenceResponse {
            requests+=request
            val now=System.currentTimeMillis()
            return InferenceResponse(request.requestId,"decision-${request.requestId}",DecisionLabel.NO_SERVICE,null,null,null,
                setOf(request.observations.last().observationId),now,now+30_000)
        }
    }

    @Test fun everySyncedFrameIsKeptAndOnlyATriggeredOneReachesTheModel()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=RecordingModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(5_000) {while(!check()) delay(20)}
        // A service session may carry scenario context observations of its own; what is being counted
        // here is the photographs the glasses synced.
        fun imageRows()=database.dao().stream(requireNotNull(id),"observations").map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            .count {it["kind"]?.jsonPrimitive?.contentOrNull=="IMAGE"}
        fun imageMetadata(captureId: String)=database.dao().stream(requireNotNull(id),"observations")
            .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            .single {it["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-$captureId"}
        fun frame(captureId: String): ImageFrame {
            val bytes=checkerboardJpeg(captureId.length)
            return ImageFrame(captureId,System.currentTimeMillis()-1_000,System.currentTimeMillis(),bytes,320,240,0,
                StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(emptyList())))
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("SERVICE_PARITY","unused://service",expectedModelId="service-capture-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=30_000,glassesCapture=true,syncBatchSize=6,
                frontEndEnabled=false)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}
            // The batch the operator set is what the glasses are told to upload in, in this mode too.
            assertEquals(6,transport.sent.last {it.type=="CONFIGURE_CAPTURE"}.payload
                .getValue("batch_size").jsonPrimitive.int)

            // A frame with nothing to say about it: kept, committed and counted, but not evaluated.
            transport.events.emit(RokidEvent.ImageReceived(frame("plain-frame")))
            await {transport.sent.any {it.type=="IMAGE_COMMITTED"}}
            await {imageRows()>0}
            assertEquals(1,imageRows())
            assertTrue("a frame nobody asked about must not become a packet",database.dao().stream(id,"packets").isEmpty())
            assertEquals(0,model.requests.size)
            assertEquals(1,engine.state.value.liveCaptures)
            // The frame says which front end looked at it, even when that is none: the page's mark is
            // read from the record, months later, and it may not be a guess about what happened.
            assertEquals("DISABLED",imageMetadata("plain-frame").getValue("front_end").jsonPrimitive.content)

            // A context arrives, and the next frame is worth a model call because of it.
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.MESSAGE,"触发验证","新消息需要结合画面判断。"))
                .getOrThrow()
            transport.events.emit(RokidEvent.ImageReceived(frame("triggered-frame")))
            await {model.requests.isNotEmpty()}
            assertEquals(2,imageRows())
            assertEquals(1,database.dao().stream(id,"packets").size)
            assertEquals(2,engine.state.value.liveCaptures)
            assertTrue("the context is what made it worth evaluating",
                imageMetadata("triggered-frame").toString().contains("local_relative_path"))
            assertTrue(journal.verify(id))
        } finally {runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin();id?.let {runCatching {journal.delete(it)}};database.close()}
    }

    /**
     * With the front end on, the verdict it reaches travels with the frame.
     *
     * The page marks every photograph with what the front end made of it, and it reads that mark from
     * the record rather than keeping it in memory: this is the wiring that has to hold for a review
     * months later to still say whether the glasses' frame fired. The front end runs here, on the
     * phone, over the same frozen graphs the release shipped.
     */
    @Test fun thePhonesFrontEndWritesItsVerdictOntoTheFrameItJudged()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=RecordingModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(120_000) {while(!check()) delay(50)}
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("SERVICE_FRONT_END","unused://service",expectedModelId="service-capture-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=30_000,glassesCapture=true,frontEndEnabled=true)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}
            val bytes=checkerboardJpeg(0)
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("judged-frame",System.currentTimeMillis()-1_000,System.currentTimeMillis(),
                bytes,320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(emptyList())))))
            await {database.dao().stream(id,"observations").any {row->
                StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-judged-frame"}}
            val row=database.dao().stream(id,"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .single {it["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-judged-frame"}
            assertEquals("cvtt_frontend_v1_calibrated",row["front_end"]?.jsonPrimitive?.contentOrNull)
            assertNotNull("the front end reports a score for every frame it judges",row["front_end_score"]?.jsonPrimitive?.contentOrNull)
            // Whether it fired or not, the mark is there and the photograph is kept either way.
            assertTrue(row.containsKey("trigger_types"))
            assertEquals(1,engine.state.value.liveCaptures)
        } finally {runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin();id?.let {runCatching {journal.delete(it)}};database.close()}
    }

    /**
     * The front end judges every frame, including the ones the pixel metrics dislike.
     *
     * The release is the frozen model and a hysteresis, and nothing else: a quality gate in front of it
     * would be a second opinion about which pictures existed, and it would change the measured trigger
     * rate. A frame the metrics call blurry is a frame the front end is allowed to look at and decline -
     * and a service session does not ask the wearer to hold still and take it again, because it is not
     * collecting material, it is serving.
     */
    @Test fun aFrameTheMetricsDislikeIsStillJudgedAndNotReTaken()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database);val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport();val model=RecordingModel()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},inferenceOverride={model})
        var id: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(120_000) {while(!check()) delay(50)}
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("SERVICE_NO_GATE","unused://service",expectedModelId="service-capture-fixture",
                mode=SessionMode.SERVICE,captureIntervalMs=30_000,glassesCapture=true,frontEndEnabled=true)).getOrThrow()
            id=requireNotNull(engine.state.value.sessionId)
            await {transport.sent.any {it.type=="CONFIGURE_CAPTURE"}}
            // A flat photograph: the pixel metrics call it blurry, and the front end is asked about it anyway.
            val bytes=flatJpeg()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("flat-frame",System.currentTimeMillis()-1_000,System.currentTimeMillis(),
                bytes,320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(emptyList())))))
            await {database.dao().stream(id,"observations").any {row->
                StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-flat-frame"}}
            val row=database.dao().stream(id,"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .single {it["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-flat-frame"}
            assertEquals("the pixel metrics are recorded as they always were","BLURRY",
                row["quality_status"]?.jsonPrimitive?.contentOrNull)
            assertEquals("and the front end judged the frame regardless","cvtt_frontend_v1_calibrated",
                row["front_end"]?.jsonPrimitive?.contentOrNull)
            assertNotNull(row["front_end_score"]?.jsonPrimitive?.contentOrNull)
            assertTrue("the frame is kept",row.containsKey("local_relative_path"))
            assertTrue("a service session does not ask for the shot to be taken again",
                transport.sent.none {it.type=="CAPTURE_REQUEST"})
        } finally {runCatching {engine.stopSession()};scope.coroutineContext.job.cancelAndJoin();id?.let {runCatching {journal.delete(it)}};database.close()}
    }

    /** A picture with no detail at all: the quality analysis scores it as blurry. */
    private fun flatJpeg(): ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(120,120,120))
        val out=ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out)
        return out.toByteArray()
    }

    private fun checkerboardJpeg(seed: Int): ByteArray {
        val bitmap=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        for(x in 0 until 320) for(y in 0 until 240)
            bitmap.setPixel(x,y,if((x/16+y/16+seed)%2==0) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
        val out=ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out)
        return out.toByteArray()
    }
}
