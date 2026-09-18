package ai.opstate.rokid.glasses

import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.glasses.capture.AndroidCameraSession
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import ai.opstate.rokid.vision.VisualSignatureExtractor
import kotlinx.serialization.json.JsonArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlassesHardwareTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun activity(): MainActivity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    /**
     * The page must never write screenBrightness, at any value.
     *
     * Writing it makes this device composite the whole frame over a #101010 veil - measured: 0% of the
     * frame comes back true black with an override set, against ~98% without one. The panel is
     * single-wavelength, so that veil is a uniform green field across the entire display, which is
     * exactly what a wearer reports as a green background behind the text. No card redesign can remove
     * it, because the card was never what was lighting up.
     */
    private fun assertNeverVeils(activity: MainActivity) = assertEquals(
        "the window must not set a brightness override",
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,activity.window.attributes.screenBrightness,0f)
    @Test fun measuresColdAndWarmCameraCaptureOnDevice() = runBlocking {
        val activity=activity()
        val app=context.applicationContext as GlassesApplication
        withTimeout(5000) {while(!app.ui.value.running) delay(20)}
        context.stopService(Intent(context,GlassesStudyService::class.java))
        withTimeout(5000) {while(app.ui.value.running) delay(20)}
        val camera=AndroidCameraSession(context)
        val rows=mutableListOf<kotlinx.serialization.json.JsonObject>()
        try {
            repeat(3) {cycle->
                camera.close();delay(300)
                val started=SystemClock.elapsedRealtime()
                val cold=camera.captureJpeg(1280,720,80)
                val coldMs=SystemClock.elapsedRealtime()-started
                delay(1200)
                val warmStarted=SystemClock.elapsedRealtime()
                val warm=camera.captureJpeg(1280,720,80)
                val warmMs=SystemClock.elapsedRealtime()-warmStarted
                assertTrue(cold.jpeg.size>1000 && warm.jpeg.size>1000)
                rows+=StudyWire.payload("cycle" to cycle,"cold_capture_ms" to coldMs,"warm_capture_ms" to warmMs,
                    "cold_bytes" to cold.jpeg.size,"warm_bytes" to warm.jpeg.size,
                    "cold_usable" to VisualSignatureExtractor.usable(cold.jpeg),"warm_usable" to VisualSignatureExtractor.usable(warm.jpeg))
            }
            File(context.filesDir,"diagnostics").apply {mkdirs()}.resolve("camera-startup-probe.json").writeText(
                StudyWire.payload("scope" to "REAL_CAMERA_STARTUP_LATENCY_NOT_POWER_MEASUREMENT","cycles" to JsonArray(rows)).toString())
        } finally {camera.close();instrumentation.runOnMainSync {activity.finish()}}
    }
    @Test fun collapsedCollectionReleasesDisplayAndBackExitsWithoutStoppingService() = runBlocking {
        val activity=activity()
        val app=context.applicationContext as GlassesApplication
        withTimeout(5000) {while(!app.ui.value.running) delay(20)}
        try {
            app.mutableUi.value=GlassesUiState(running=true,cameraReady=true,phoneConnected=true,sessionId="display-fixture",
                demoStatus=GlassesDemoStatus("正在采集","",0,0,true,collection=true))
            withTimeout(5000) {while(!app.displayVisible.value) delay(20)}
            instrumentation.runOnMainSync {
                activity.onKeyUp(KeyEvent.KEYCODE_ENTER,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
                assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertNeverVeils(activity)
                // Collapsing releases the panel, not the page. The page is still up and focused, so it
                // can still be handed a card - which is the point of holding one for a sleeping panel
                // rather than refusing it. Gating this on drawn content instead deadlocked the two:
                // the page stayed "not ready" until a card arrived, and the card was held until the
                // page was ready.
                assertTrue(app.displayVisible.value)
            }
            app.mutableUi.value=app.ui.value.copy(bufferedPhotos=3,phoneConnected=false)
            delay(100)
            instrumentation.runOnMainSync {
                assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertTrue(app.displayVisible.value)
                activity.onKeyUp(KeyEvent.KEYCODE_ENTER,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
                // Re-expanding shows the collection status again, and that view stays passive: only a
                // card holds the panel lit, because only a card needs an interactive screen for the
                // two-frame visible ACK.
                assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertNeverVeils(activity)
            }
            // A card is the exception to that passivity, and the reason is the ACK: the two-frame
            // visible acknowledgement cannot be produced on a panel that has already gone dark.
            app.mutableUi.value=app.ui.value.copy(card=GlassesCard("display-fixture-card",1,"SERVICE","PREPARATION",
                "现在可以出发","组会地点已改到 B302。",SystemClock.elapsedRealtime()+10000))
            delay(100)
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                // Even lit, a card must not reach for brightness to make itself readable.
                assertNeverVeils(activity)
                activity.onKeyUp(KeyEvent.KEYCODE_BACK,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK))
                assertTrue(activity.isFinishing)
                assertFalse(app.displayVisible.value)
            }
            assertTrue("Leaving the UI must not stop the foreground service",app.ui.value.running)
        } finally {
            app.mutableUi.value=GlassesUiState(running=true,cameraReady=true)
            instrumentation.runOnMainSync {if(!activity.isFinishing) activity.finish()}
        }
    }
    @Test fun camera2ProducesActualJpegWithOrientationAndCaptureTime() = runBlocking {
        context.stopService(Intent(context,GlassesStudyService::class.java))
        val activity=activity()
        val camera=AndroidCameraSession(context)
        try {
            camera.openLowResolutionPreview(); delay(1200)
            val before=System.currentTimeMillis()
            val frame=camera.captureJpeg(1280,720,80)
            assertTrue(frame.jpeg.size>1000)
            assertTrue(frame.width>0 && frame.height>0)
            assertTrue(frame.rotationDegrees in setOf(0,90,180,270))
            assertTrue(requireNotNull(frame.capturedAtEpochMs) in before..System.currentTimeMillis())
            val dir=File(context.filesDir,"diagnostics").apply { mkdirs() }
            File(dir,"camera-probe.jpg").writeBytes(frame.jpeg)
            File(dir,"camera-probe.json").writeText(StudyWire.payload("width" to frame.width,"height" to frame.height,
                "rotation_degrees" to frame.rotationDegrees,"bytes" to frame.jpeg.size,"sha256" to StudyWire.sha256(frame.jpeg),
                "captured_at_epoch_ms" to frame.capturedAtEpochMs).toString())
        } finally { camera.close(); instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun nativeCxrBridgeSubscribesToStudyChannel() {
        val app=context.applicationContext as GlassesApplication
        app.link.start()
        assertNull(app.link.error.value)
    }
    @Test fun serviceCardProducesTwoDrawVisibleAckOnRealDisplay() = runBlocking {
        val activity=activity()
        val app=context.applicationContext as GlassesApplication
        assertNotNull("activity did not become visible: ui=${app.ui.value}, display=${app.displayVisible.value}",
            withTimeoutOrNull(5000) {while(!app.ui.value.running || !app.displayVisible.value) delay(20);true})
        while(app.visibleEvents.tryReceive().isSuccess) { }
        val id="hardware-visible-${SystemClock.elapsedRealtime()}"
        try {
            app.mutableUi.value=GlassesUiState(running=true,cameraReady=true,sessionId="hardware-test",
                card=GlassesCard(id,1,"SERVICE","PREPARATION","现在可以出发","组会地点已改到 B302。请查看更新后的安排。",SystemClock.elapsedRealtime()+10000))
            val ack=withTimeoutOrNull(5000) { app.visibleEvents.receive() }
                ?: throw AssertionError("no two-draw ACK: ui=${app.ui.value}, display=${app.displayVisible.value}")
            assertEquals(id,ack.decisionId)
            assertEquals(1L,ack.epoch)
            assertTrue(ack.frames>=2)
            delay(200)
            assertFalse(app.visibleEvents.tryReceive().isSuccess)
        } finally {
            app.mutableUi.value=GlassesUiState()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
