package ai.opstate.rokid.phone

import ai.opstate.core.context.CausalContextStore
import ai.opstate.core.hr.*
import ai.opstate.core.inference.*
import ai.opstate.core.model.ObservationKind
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.ImageFrame
import ai.opstate.rokid.phone.transport.RokidEvent
import android.graphics.Bitmap
import android.graphics.Color
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The running-session slider changes the same HR path used by operator frames and automatic packets. */
@RunWith(AndroidJUnit4::class)
class ProactivityEffortDeviceTest {
    private class SilentHr(private val effort: ProactivityEffort,private val calls: AtomicInteger,
                           private val delayMs: Long=0L,private val noteCurrentImage: Boolean=false) : HrInferenceClient {
        override val hrEnabled=true
        override suspend fun contract(expectedModelId: String?)=FrozenHarnessContract(
            "opstate-online-hr-mobile-v1","hr-effort-fixture",StudyWire.sha256("prompt-${effort.name}".toByteArray()),
            StudyWire.sha256("preprocess-${effort.name}".toByteArray()),"c".repeat(64),"d".repeat(64),
            "e".repeat(64),mock=true,mockMode="effort_scripted",notForPaper=true,strictContract=true)
        override suspend fun evaluate(request: InferenceRequest,jpeg: ByteArray,onRaw: (RawHarnessResponse)->Unit): InferenceResponse =
            error("HR path required")
        override suspend fun evaluateHrTurn(request: InferenceRequest,currentJpeg: ByteArray,
            historicalJpegs: Map<String,ByteArray>,history: CausalContextStore,
            agentHistory: List<HrAgentOutput>,onRaw: (RawHarnessResponse)->Unit): HrTurnOutcome {
            calls.incrementAndGet()
            if(delayMs>0) delay(delayMs)
            val provider=object: HrStageProvider {
                override suspend fun step(envelope: JsonObject): HrStep {
                    val current=request.observations.firstOrNull {it.kind==ObservationKind.IMAGE &&
                        it.assetId==request.currentAssetId && it.observationId in request.packetNewEventIds}
                    val note=if(noteCurrentImage && current!=null) HrVisualNote("测试图像有可见内容",listOf(current.observationId))
                        else HrVisualNote("",emptyList())
                    return HrStep(note,HrCandidate(null,null,emptyList(),emptyList(),HrRoute.SILENT),HrRoute.SILENT,null,null)
                }
                override suspend fun final(envelope: JsonObject): HrFinal {
                    check(envelope["stage"]==JsonPrimitive("GUARANTEED_SERVICE"))
                    val row=envelope.getValue("new_records").jsonArray.map {it.jsonObject}.last()
                    val id=row.getValue("record_id").jsonPrimitive.content
                    val image=row["kind"]==JsonPrimitive("IMAGE")
                    return HrFinal(HrRoute.SERVICE,HrService("AWARENESS",
                        if(image) "模型现场判断" else "模型情境判断",
                        if(image) "当前图像显示测试设备，请核对可见状态。" else "这条新情境需要核对记录中的具体事项。",
                        listOf(id)))
                }
            }
            return HrTurnRunner(history,provider,effort=effort).run(request,agentHistory,20_000)
        }
    }

    private fun jpeg(): ByteArray {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,
            if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);bitmap.recycle();it.toByteArray()}
    }

    @Test fun manualEvaluationsUseTheActiveEffortWithoutAForceSwitch()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val calls=AtomicInteger()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},
            inferenceOverride={setup->SilentHr(setup.proactivityEffort,calls,noteCurrentImage=true)})
        var sessionId: String?=null
        var frameNumber=0
        suspend fun frame() {
            frameNumber++
            val bytes=jpeg();val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("effort-$frameNumber",now-50,now,bytes,
                320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))))
        }
        suspend fun waitFor(condition: ()->Boolean)=withTimeout(6_000) {while(!condition()) delay(20)}
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("EFFORT_COMPONENT","unused://effort",expectedModelId="hr-effort-fixture",
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            sessionId=requireNotNull(engine.state.value.sessionId)
            assertEquals(ProactivityEffort.RESTRAINED,engine.state.value.proactivityEffort)
            frame();waitFor {calls.get()==1 && engine.state.value.decisions==1}
            assertEquals(0,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})
            engine.setProactivityEffort(ProactivityEffort.INCLINED).getOrThrow()
            assertEquals(ProactivityEffort.INCLINED,engine.state.value.proactivityEffort)
            frame();waitFor {calls.get()==2 && engine.state.value.decisions==2}
            assertEquals(0,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})
            engine.setProactivityEffort(ProactivityEffort.GUARANTEED).getOrThrow()
            assertEquals(ProactivityEffort.GUARANTEED,engine.state.value.proactivityEffort)
            frame();waitFor {calls.get()==3 && transport.sent.count {it.type=="SHOW_SERVICE_CARD"}==1}
            val events=database.dao().stream(requireNotNull(sessionId),"runtime_events")
            assertEquals(2,events.count {it.eventType=="PROACTIVITY_EFFORT_CHANGED"})
            assertEquals(1,events.count {it.eventType=="HR_GUARANTEED_MODEL_SERVICE"})
            val card=transport.sent.single {it.type=="SHOW_SERVICE_CARD"}
            assertEquals("模型现场判断",card.payload.getValue("title").jsonPrimitive.content)
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            sessionId?.let {if(database.dao().session(it)!=null) {
                assertTrue(journal.verify(it));journal.delete(it)
            }}
            database.close()
        }
    }

    @Test fun manualCaptureSkipsTheAutomaticFrontEndAndStillUsesEffort()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val calls=AtomicInteger()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},
            inferenceOverride={setup->SilentHr(setup.proactivityEffort,calls,noteCurrentImage=true)})
        var sessionId: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(6_000) {while(!check()) delay(20)}
        suspend fun captureAndUpload() {
            engine.captureNow()
            val command=withTimeout(3_000) {transport.captures.receive()}
            assertTrue("MANUAL_DEBUG" in command.reasonCodes)
            val bytes=jpeg();val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(command.captureId,now-50,now,bytes,
                320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))))
        }
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("MANUAL_BYPASS","unused://effort",expectedModelId="hr-effort-fixture",
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=true)).getOrThrow()
            sessionId=requireNotNull(engine.state.value.sessionId)
            captureAndUpload();await {calls.get()==1 && engine.state.value.decisions==1}
            val first=database.dao().stream(requireNotNull(sessionId),"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .last {it["kind"]==JsonPrimitive("IMAGE")}
            assertEquals("BYPASSED_MANUAL",first["front_end"]?.jsonPrimitive?.content)
            assertEquals(listOf("MANUAL"),first.getValue("trigger_types").jsonArray.map {it.jsonPrimitive.content})
            assertFalse(first.containsKey("front_end_score"))
            assertEquals(0,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})
            engine.setProactivityEffort(ProactivityEffort.GUARANTEED).getOrThrow()
            captureAndUpload();await {calls.get()==2 && transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            assertEquals(2,database.dao().stream(requireNotNull(sessionId),"inference_requests").count {it.eventType=="INFERENCE_SENT"})
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            sessionId?.let {if(database.dao().session(it)!=null) {assertTrue(journal.verify(it));journal.delete(it)}}
            database.close()
        }
    }

    @Test fun chosenPhotoAndContextSubmitManualPacketsWithoutAutomaticFrontEnd()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val calls=AtomicInteger()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},
            inferenceOverride={setup->SilentHr(setup.proactivityEffort,calls)})
        val file=File.createTempFile("manual-source-",".jpg",context.cacheDir)
        var sessionId: String?=null
        suspend fun await(check: ()->Boolean)=withTimeout(6_000) {while(!check()) delay(20)}
        try {
            val bytes=jpeg();file.writeBytes(bytes)
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("MANUAL_SOURCES","unused://effort",expectedModelId="hr-effort-fixture",
                mode=SessionMode.SERVICE,glassesCapture=false,frontEndEnabled=true)).getOrThrow()
            engine.setAutomaticCapture(false)
            sessionId=requireNotNull(engine.state.value.sessionId)
            engine.evaluateChosenPhoto(ChosenPhoto(file.path,320,240,0,StudyWire.sha256(bytes))).getOrThrow()
            await {calls.get()==1 && engine.state.value.decisions==1}
            val photo=database.dao().stream(requireNotNull(sessionId),"observations")
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
                .last {it["kind"]==JsonPrimitive("IMAGE")}
            assertEquals("BYPASSED_MANUAL",photo["front_end"]?.jsonPrimitive?.content)
            engine.setProactivityEffort(ProactivityEffort.GUARANTEED).getOrThrow()
            engine.evaluateChosenContext(SyntheticContextInput(SyntheticContextType.NOTE,"手动情境","请模型判断这条情境是否值得服务。")).getOrThrow()
            await {calls.get()==2 && engine.state.value.decisions==2 && transport.sent.any {it.type=="SHOW_SERVICE_CARD"}}
            val requests=database.dao().stream(requireNotNull(sessionId),"inference_requests")
                .filter {it.eventType=="INFERENCE_SENT"}
                .map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject}
            assertEquals(2,requests.size)
            assertEquals("NO_IMAGE",requests.last()["current_asset_id"]?.jsonPrimitive?.content)
            assertEquals(listOf("MANUAL"),requests.last().getValue("trigger_types").jsonArray.map {it.jsonPrimitive.content})
            assertFalse("choosing a context must not show it before the model decides",
                transport.sent.any {it.type=="SHOW_CONTEXT_CARD"})
            assertEquals("模型情境判断",transport.sent.single {it.type=="SHOW_SERVICE_CARD"}.payload.getValue("title").jsonPrimitive.content)
            assertEquals(2,database.dao().stream(requireNotNull(sessionId),"packets").size)
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            sessionId?.let {if(database.dao().session(it)!=null) {assertTrue(journal.verify(it));journal.delete(it)}}
            database.close();file.delete()
        }
    }

    @Test fun changingEffortCancelsOldPromptAndRebindsTheFreshPacket()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val calls=AtomicInteger()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},
            inferenceOverride={setup->SilentHr(setup.proactivityEffort,calls,
                if(setup.proactivityEffort==ProactivityEffort.RESTRAINED) 2_000L else 0L)})
        var sessionId: String?=null
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("EFFORT_REBIND","unused://effort",expectedModelId="hr-effort-fixture",
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            sessionId=requireNotNull(engine.state.value.sessionId)
            val bytes=jpeg();val now=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame("effort-rebind",now-50,now,bytes,
                320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE",buffered=true,
                sourceMetadata=StudyWire.payload("trigger_types" to JsonArray(listOf(JsonPrimitive("MANUAL")))))))
            withTimeout(6_000) {while(calls.get()<1) delay(20)}
            engine.setProactivityEffort(ProactivityEffort.GUARANTEED).getOrThrow()
            withTimeout(6_000) {while(transport.sent.none {it.type=="SHOW_SERVICE_CARD"}) delay(20)}
            val rows=database.dao().stream(requireNotNull(sessionId),"inference_requests")
                .filter {it.eventType=="INFERENCE_SENT"}
            assertEquals(2,rows.size)
            val requestIds=rows.map {StudyWire.json.parseToJsonElement(it.canonicalJson).jsonObject
                .getValue("request_id").jsonPrimitive.content}
            assertEquals(2,requestIds.distinct().size)
            assertTrue(database.dao().stream(requireNotNull(sessionId),"runtime_events")
                .any {it.eventType=="INFERENCE_CANCELLED"})
            assertEquals(ProactivityEffort.GUARANTEED,engine.state.value.proactivityEffort)
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            sessionId?.let {if(database.dao().session(it)!=null) {
                assertTrue(journal.verify(it));journal.delete(it)
            }}
            database.close()
        }
    }

    @Test fun automaticDigitalPacketsUseTheSameEffortAsManualService()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val calls=AtomicInteger()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},
            inferenceOverride={setup->SilentHr(setup.proactivityEffort,calls)})
        var sessionId: String?=null
        try {
            transport.events.subscriptionCount.first {it>0}
            engine.start(SessionSetup("EFFORT_AUTOMATIC","unused://effort",expectedModelId="hr-effort-fixture",
                mode=SessionMode.SERVICE,glassesCapture=true,frontEndEnabled=false)).getOrThrow()
            sessionId=requireNotNull(engine.state.value.sessionId)
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"第一条自动情境",
                "记录一次无需拍照的数字变化。")).getOrThrow()
            withTimeout(22_000) {while(calls.get()<1 || engine.state.value.decisions<1) delay(50)}
            assertEquals(0,transport.sent.count {it.type=="SHOW_SERVICE_CARD"})
            engine.setProactivityEffort(ProactivityEffort.GUARANTEED).getOrThrow()
            engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"第二条自动情境",
                "再次记录无需拍照的数字变化。")).getOrThrow()
            withTimeout(22_000) {while(transport.sent.none {it.type=="SHOW_SERVICE_CARD"}) delay(50)}
            val modelService=database.dao().stream(requireNotNull(sessionId),"runtime_events")
                .single {it.eventType=="HR_GUARANTEED_MODEL_SERVICE"}
            val payload=StudyWire.json.parseToJsonElement(modelService.canonicalJson).jsonObject
            assertEquals(JsonPrimitive("GUARANTEED"),payload["effort"])
            assertEquals("模型情境判断",transport.sent.last {it.type=="SHOW_SERVICE_CARD"}.payload
                .getValue("title").jsonPrimitive.content)
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin()
            sessionId?.let {if(database.dao().session(it)!=null) {
                assertTrue(journal.verify(it));journal.delete(it)
            }}
            database.close()
        }
    }
}
