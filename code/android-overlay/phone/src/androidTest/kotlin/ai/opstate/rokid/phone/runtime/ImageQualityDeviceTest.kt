package ai.opstate.rokid.phone.runtime

import ai.opstate.core.model.ImageQualityStatus
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.transport.ImageFrame
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Isolated library-test process: no study app lifecycle, credentials, radio or camera commands. */
@RunWith(AndroidJUnit4::class)
class ImageQualityDeviceTest {
    private fun frame(pixel: (Int,Int)->Int): ImageFrame {
        val image=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888)
        for(y in 0 until 96) for(x in 0 until 128) image.setPixel(x,y,pixel(x,y))
        val bytes=ByteArrayOutputStream().use {image.compress(Bitmap.CompressFormat.JPEG,100,it);it.toByteArray()};image.recycle()
        return ImageFrame("quality-fixture",1,1,bytes,128,96,0,StudyWire.sha256(bytes),"GENERATED_TEST_FIXTURE")
    }
    @Test fun actualAndroidDecodeReportsQualityAndClippedPixelRatios() {
        val analyzer=LocalImageQualityAnalyzer()
        val dark=analyzer.analyze(frame {_,_->Color.BLACK})
        assertEquals(ImageQualityStatus.TOO_DARK,dark.status);assertEquals(1.0,dark.darkPixelRatio!!,0.0)
        val bright=analyzer.analyze(frame {_,_->Color.WHITE})
        assertEquals(ImageQualityStatus.TOO_BRIGHT,bright.status);assertEquals(1.0,bright.brightPixelRatio!!,0.0)
        assertEquals(ImageQualityStatus.BLURRY,analyzer.analyze(frame {_,_->Color.GRAY}).status)
        val detail=frame {x,y->if((x/4+y/4)%2==0) Color.WHITE else Color.BLACK}
        val valid=analyzer.analyze(detail)
        assertEquals(ImageQualityStatus.VALID,valid.status);assertTrue(valid.valid)
        assertEquals(0.0,analyzer.analyze(detail).noveltyScore,0.0)
    }
    @Test fun inconsistentMetadataHashAndTruncatedJpegAreCorruptionRatherThanDarkness() {
        val analyzer=LocalImageQualityAnalyzer();val image=frame {_,_->Color.BLACK}
        val truncated=image.jpegBytes.copyOf(image.jpegBytes.size-2)
        for(bad in listOf(image.copy(width=256),image.copy(rotationDegrees=1),image.copy(sha256="0".repeat(64)),
            image.copy(jpegBytes=truncated,sha256=StudyWire.sha256(truncated)))) {
            val quality=analyzer.analyze(bad)
            assertEquals(ImageQualityStatus.CORRUPTED,quality.status);assertFalse(quality.valid)
            assertNull(quality.darkPixelRatio)
        }
    }
}
