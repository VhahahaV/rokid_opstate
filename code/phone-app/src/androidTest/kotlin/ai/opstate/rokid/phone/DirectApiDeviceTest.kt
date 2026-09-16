package ai.opstate.rokid.phone

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.data.*
import ai.opstate.rokid.phone.runtime.*
import ai.opstate.rokid.phone.transport.*
import android.graphics.Bitmap
import android.graphics.Color
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
import java.io.ByteArrayOutputStream
import java.io.File

/** Real phone -> model API -> actor/parser/Room/export; sensors and transport are explicitly simulated. */
@RunWith(AndroidJUnit4::class)
class DirectApiDeviceTest {
    @Test fun directKeyWorksThroughTheActualRuntimeAndPreservesBothResponseFormats()=runBlocking {
        assumeTrue("Explicit direct API smoke only",InstrumentationRegistry.getArguments().getString("direct_api_smoke")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as PhoneApplication
        app.storageReady.await()
        val config=DirectModelConfig.qwen()
        val validation=File(context.filesDir,"validation").apply {mkdirs()}
        val input=File(validation,"model-api-key-input")
        if(input.isFile) {
            try {app.credentials.putModelApiKey(config.credentialScope(),input.readText().trim())} finally {input.delete()}
        }
        check(app.credentials.modelApiKey(config.credentialScope()).isNotBlank()) {"MODEL_API_KEY_REQUIRED"}
        check(app.settings.edit().putString("model_api_config",StudyWire.json.encodeToString(DirectModelConfig.serializer(),config)).commit())
        assertTrue("The model Key must remain scoped to its configured API",app.credentials.modelApiKey("https://another-provider.invalid/v1").isEmpty())
        val database=Room.inMemoryDatabaseBuilder(context,StudyDatabase::class.java).build()
        val journal=StudyJournal(context,database)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val transport=RuntimeLifecycleDeviceTest.FixtureTransport()
        val engine=StudyRuntimeEngine(context,scope,transport,journal,{""},{app.credentials.modelApiKey(it.credentialScope())})
        var id: String?=null
        try {
            engine.start(SessionSetup("TEST_DIRECT_API",config.resolvedEndpoint(),expectedModelId=config.modelId,directModel=config)).getOrThrow()
            id=engine.state.value.sessionId!!
            val now=SystemClock.elapsedRealtime()
            transport.events.emit(RokidEvent.SensorWindowReceived(SensorWindowPacket(now-4000,now,80,0.1,0.2,0.01,0.02,1.0,true,false,0.0,false,10000,false,false)))
            withTimeout(3000) {while(database.dao().stream(id,"sensor_windows").isEmpty()) delay(10)}
            engine.captureNow();val capture=withTimeout(3000) {transport.captures.receive()}
            val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
            for(y in 0 until 240) for(x in 0 until 320) bitmap.setPixel(x,y,if((x/10+y/10)%2==0) Color.WHITE else Color.BLACK)
            val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};bitmap.recycle()
            val at=System.currentTimeMillis()
            transport.events.emit(RokidEvent.ImageReceived(ImageFrame(capture.captureId,at,at,bytes,320,240,0,StudyWire.sha256(bytes),"TEST_FIXTURE")))
            withTimeout(25000) {
                while(database.dao().stream(id,"decisions").isEmpty()) {
                    check(database.dao().stream(id,"runtime_events").none {it.eventType=="TECHNICAL_FAILURE"}) {engine.state.value.error ?: "DIRECT_API_FAILED"}
                    delay(20)
                }
            }
            engine.stopSession()
            val rows=database.dao().stream(id,"raw_responses")
            assertEquals(listOf("PROVIDER_RESPONSE","RAW_DECISION"),rows.map {it.eventType})
            assertTrue(database.dao().stream(id,"receipts").isEmpty())
            val apiKey=app.credentials.modelApiKey(config.credentialScope())
            assertTrue(database.dao().journal(id).none {it.canonicalJson.contains(apiKey)})
            val archive=journal.export(id)
            archive.copyTo(File(validation,"direct-api-runtime.zip"),overwrite=true)
            File(validation,"direct-api-runtime-result.json").writeText(StudyWire.payload("status" to "PASS","session_id" to id,
                "endpoint" to config.resolvedEndpoint(),"model_id" to config.modelId,"connection_mode" to "DIRECT_API_KEY",
                "provider_responses" to rows.count {it.eventType=="PROVIDER_RESPONSE"},"normalized_responses" to rows.count {it.eventType=="RAW_DECISION"},
                "decisions" to database.dao().stream(id,"decisions").size,"receipts" to 0,"key_in_journal" to false,
                "transport_source" to "TEST_FIXTURE","image_source" to "GENERATED_CHECKERBOARD","physical_radio_test" to false,"track_a_comparison" to false).toString())
        } finally {
            engine.stopSession();scope.coroutineContext.job.cancelAndJoin()
            id?.let {journal.delete(it)};database.close()
        }
    }
}
