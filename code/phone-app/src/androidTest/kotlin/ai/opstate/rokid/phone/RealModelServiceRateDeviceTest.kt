package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.JournalEntity
import ai.opstate.rokid.phone.data.StudyDatabase
import ai.opstate.rokid.phone.data.StudyJournal
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the real model does with the real prompt, on frames the glasses actually took.
 *
 * The delivery gate decides whether a card *may* be shown; this measures whether the model *offers* one.
 * That is the number the harness is tuned by, and it cannot be read off the gate or off a scripted
 * fixture - a fixture answers whatever it was written to answer. Every call here is a real call to the
 * configured provider, through the real runtime, on photographs pulled from the phone's own sessions.
 *
 * Three arms, the same eight frames in each:
 *
 * - [EMPTY_RECORD] the session holds an ordinary record that has nothing to do with these frames. This is
 *   the reported failure: a trigger arrived, so a card came back.
 * - [EMPTY_BARE] no record at all. The record floor is off, so the prompt is the only thing standing
 *   between a trigger and a card.
 * - [GROUNDED] two records establishing one concrete open item and when it is due. A run that never
 *   serves is as useless as one that always does, and this arm is what says whether the prompt still
 *   speaks when there is something to speak about.
 *
 * Frames and results are staged under `files/validation/` on the device; nothing here is committed.
 */
@RunWith(AndroidJUnit4::class)
class RealModelServiceRateDeviceTest {
    private enum class Arm(val label: String) { EMPTY_RECORD("empty-record"), EMPTY_BARE("empty-bare"), GROUNDED("grounded") }

    private data class Sample(val arm: Arm,val frame: String,val decision: String,val title: String?,val body: String?,
        val delivery: String?,val latencyMs: Long,val quality: String?,val triggers: String?,val error: String?)

    @Test fun realModelOnRealFramesAcrossThreeArms()=runBlocking {
        assumeTrue("Explicit live-model measurement only",
            InstrumentationRegistry.getArguments().getString("service_rate_probe")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val config=DirectModelConfig.qwen()
        val validation=File(context.filesDir,"validation").apply {mkdirs()}
        val frames=File(validation,"probe-frames").listFiles {file->file.extension=="jpg"}?.sortedBy {it.name}.orEmpty()
        assertTrue("no frames staged in files/validation/probe-frames",frames.size>=6)
        check(app.credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}

        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},{app.credentials.modelApiKey(it.credentialScope())})
        val samples=mutableListOf<Sample>()
        var id: String?=null
        suspend fun await(check: ()->Boolean,timeoutMs: Long)=withTimeout(timeoutMs) {while(!check()) delay(20)}
        fun rows(stream: String)=database.dao().stream(requireNotNull(id),stream)
        fun payload(row: JournalEntity)=StudyWire.json.parseToJsonElement(row.canonicalJson).jsonObject

        suspend fun one(arm: Arm,frame: File) {
            // The real online-service shape: the glasses capture and upload, and the phone's front end
            // decides which frames are worth a model call. A session without it would be a different
            // measurement, and its quality gate re-takes a frame instead of evaluating it.
            engine.start(SessionSetup("TEST_SERVICE_RATE",config.resolvedEndpoint(),expectedModelId=config.modelId,
                directModel=config,mode=SessionMode.SERVICE,glassesCapture=true,captureIntervalMs=60_000)).getOrThrow()
            id=engine.state.value.sessionId!!
            try {
                when(arm) {
                    // A record the operator really might be carrying, about a day these frames are not part of.
                    Arm.EMPTY_RECORD -> {
                        engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"周会纪要","周四下午组会改到 B302，带了电脑和转接头。")).getOrThrow()
                        engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.EMAIL,"报销","上周的差旅报销单还没提交，截止到月底。")).getOrThrow()
                    }
                    Arm.EMPTY_BARE -> Unit
                    // One concrete open item, and the time that makes it due.
                    Arm.GROUNDED -> {
                        engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.CALENDAR,"出发","今天 15:30 从住处出发去高铁站，车次 G1234。")).getOrThrow()
                        engine.addSyntheticContext(SyntheticContextInput(SyntheticContextType.NOTE,"出发清单","行李清单里充电器和常用药还没打勾确认。")).getOrThrow()
                    }
                }
                val now=SystemClock.elapsedRealtime()
                transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(now-4000,now,80,0.1,0.2,0.01,0.02,1.0,true,false,0.0,false,10000,false,false)))
                await({rows("sensor_windows").isNotEmpty()},3000)
                engine.captureNow()
                val capture=withTimeout(3000) {transport.captures.receive()}
                val bytes=frame.readBytes()
                // The real dimensions, not a declared size: the quality analysis compares the decoded
                // bounds against the frame's, so a guessed pair marks every photograph CORRUPTED and the
                // session re-takes the shot instead of evaluating it.
                val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                val at=System.currentTimeMillis()
                transport.events.emit(RokidEvent.ImageReceived(ImageFrame(capture.captureId,at,at,bytes,
                    bounds.outWidth,bounds.outHeight,0,StudyWire.sha256(bytes),"TEST_FIXTURE")))
                val before=rows("decisions").size
                val failed=runCatching {await({rows("decisions").size>before},60_000)}
                val observed=rows("observations").lastOrNull {payload(it)["observation_id"]?.jsonPrimitive?.contentOrNull=="obs-${capture.captureId}"}
                    ?.let(::payload)
                val quality=observed?.get("quality_status")?.jsonPrimitive?.contentOrNull
                val triggers=observed?.get("trigger_types")?.jsonArray?.joinToString(",") {it.jsonPrimitive.content}
                if(failed.isFailure) {
                    samples+=Sample(arm,frame.name,"NO_DECISION",null,null,null,0,quality,triggers,
                        engine.state.value.error ?: "TIMEOUT")
                } else {
                    val decision=rows("decisions").last().let(::payload)
                    val delivery=rows("deliveries").lastOrNull()?.let {payload(it)["status"]?.jsonPrimitive?.contentOrNull}
                    samples+=Sample(arm,frame.name,decision.getValue("decision").jsonPrimitive.content,
                        decision["title"]?.jsonPrimitive?.contentOrNull,decision["body"]?.jsonPrimitive?.contentOrNull,
                        delivery,decision["model_latency_ms"]?.jsonPrimitive?.longOrNull ?: 0L,quality,triggers,null)
                }
            } finally {runCatching {engine.stopSession()}}
        }

        try {
            for(arm in Arm.entries) for(frame in frames) one(arm,frame)
            val report=StudyWire.payload("probe" to "real_model_service_rate_v1","endpoint" to config.resolvedEndpoint(),
                "model_id" to config.modelId,"frames" to JsonArray(frames.map {JsonPrimitive(it.name)}),
                "samples" to JsonArray(samples.map {sample->StudyWire.payload("arm" to sample.arm.label,"frame" to sample.frame,
                    "decision" to sample.decision,"title" to sample.title,"body" to sample.body,"delivery" to sample.delivery,
                    "latency_ms" to sample.latencyMs,"quality" to sample.quality,"triggers" to sample.triggers,"error" to sample.error)}),
                "summary" to StudyWire.payload(*Arm.entries.map {arm->
                    val of=samples.filter {it.arm==arm}
                    arm.label to StudyWire.payload("n" to of.size,"service" to of.count {it.decision=="SERVICE"},
                        "no_service" to of.count {it.decision=="NO_SERVICE"},
                        "service_rate" to if(of.isEmpty()) null else of.count {it.decision=="SERVICE"}.toDouble()/of.size)
                }.toTypedArray()))
            File(validation,"service-rate-probe.json").writeText(report.toString())
            samples.forEach {println("SERVICE_RATE ${it.arm.label} ${it.frame} ${it.decision} ${it.delivery ?: "-"} q=${it.quality ?: "-"} t=${it.triggers ?: "-"} ${it.title ?: ""}")}
        } finally {
            runCatching {engine.stopSession()}
            scope.coroutineContext.job.cancelAndJoin();database.close()
        }
    }
}
