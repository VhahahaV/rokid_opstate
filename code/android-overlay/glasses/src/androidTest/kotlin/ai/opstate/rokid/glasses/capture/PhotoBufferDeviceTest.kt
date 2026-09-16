package ai.opstate.rokid.glasses.capture

import ai.opstate.protocol.*
import ai.opstate.core.scheduler.StreamingPacketizer
import ai.opstate.rokid.vision.VisualSignatureExtractor
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoBufferDeviceTest {
    @Test fun hundredOriginalsSurviveReopenAndOnlyVerifiedCommitsRemoveThem() {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"buffer-test-${UUID.randomUUID()}")
        try {
            val buffer=PhotoBuffer(directory);val bytes=jpeg()
            val hash=StudyWire.sha256(bytes)
            repeat(100) {n->buffer.put(ImageDescriptor("frame-$n","collection",1,1000L+n,128,96,0,bytes.size,hash),bytes,StudyWire.payload("capture_mode" to "COLLECTION"))}
            val reopened=PhotoBuffer(directory)
            assertEquals(100,reopened.entries().size)
            assertEquals("frame-0",reopened.entries().first().descriptor.capture_id)
            assertThrows(IllegalArgumentException::class.java) {reopened.commit("frame-0","0".repeat(64),bytes.size)}
            assertEquals(100,PhotoBuffer(directory).entries().size)
            assertTrue(reopened.commit("frame-0",hash,bytes.size))
            assertFalse(reopened.commit("frame-0",hash,bytes.size))
            assertEquals(99,PhotoBuffer(directory).entries().size)
            reopened.entries().forEach {assertEquals(hash,StudyWire.sha256(it.file.readBytes()))}
        } finally {directory.deleteRecursively()}
    }
    @Test fun localVisualDetectorSuppressesUnchangedFramesAndKeepsDigitalTrigger() {
        val bytes=jpeg();assertTrue(VisualSignatureExtractor.usable(bytes))
        val signature=VisualSignatureExtractor.fromJpeg(bytes)
        val trigger=StreamingPacketizer()
        assertEquals(setOf("VISUAL"),trigger.onImage("one","image1",1_000,signature).single().triggerTypes)
        assertTrue(trigger.onImage("two","image2",61_000,signature).isEmpty())
        assertTrue(trigger.onImage("three","image3",181_000,signature).isEmpty())
        assertTrue(trigger.onDigital("context",182_000).isEmpty())
        assertEquals(setOf("DIGITAL"),trigger.advance(197_000).single().triggerTypes)
    }
    private fun jpeg(): ByteArray {
        val bitmap=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until 96) for(x in 0 until 128) bitmap.setPixel(x,y,if((x/4+y/4)%2==0) Color.WHITE else Color.BLACK)
        return ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);bitmap.recycle();it.toByteArray()}
    }
}
