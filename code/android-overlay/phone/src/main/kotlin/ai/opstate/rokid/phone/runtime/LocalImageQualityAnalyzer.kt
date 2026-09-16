package ai.opstate.rokid.phone.runtime

import ai.opstate.core.model.ImageQuality
import ai.opstate.core.model.ImageQualityStatus
import ai.opstate.core.quality.ImageQualityStatistics
import ai.opstate.protocol.StudyWire
import ai.opstate.rokid.phone.transport.ImageFrame
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decode/metadata integrity precedes the pilot quality thresholds; raw pixels never enter model metadata. */
class LocalImageQualityAnalyzer {
    private var previousHash: Long?=null
    fun analyze(frame: ImageFrame): ImageQuality {
        val bytes=frame.jpegBytes
        if(bytes.size !in 4..8*1024*1024 || bytes[0]!=0xff.toByte() || bytes[1]!=0xd8.toByte() ||
            bytes[bytes.size-2]!=0xff.toByte() || bytes.last()!=0xd9.toByte() || StudyWire.sha256(bytes)!=frame.sha256 ||
            frame.rotationDegrees !in setOf(0,90,180,270)) return invalid(frame)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outMimeType!="image/jpeg" || bounds.outWidth!=frame.width || bounds.outHeight!=frame.height ||
            bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 || bounds.outWidth.toLong()*bounds.outHeight>8_000_000) return invalid(frame)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return invalid(frame)
        var thumb: Bitmap?=null
        return try {
            thumb=Bitmap.createScaledBitmap(bitmap,32,24,true)
            val pixels=IntArray(32*24);thumb.getPixels(pixels,0,32,0,0,32,24)
            val metrics=ImageQualityStatistics.analyze(pixels,32,24)
            val novelty=previousHash?.let {java.lang.Long.bitCount(it xor metrics.differenceHash)/64.0} ?: 1.0
            previousHash=metrics.differenceHash
            ImageQuality(metrics.status==ImageQualityStatus.VALID,metrics.meanLuma,metrics.blurScore,novelty,
                frame.width,frame.height,frame.rotationDegrees,frame.sha256,metrics.status,metrics.darkPixelRatio,metrics.brightPixelRatio)
        } catch(_: RuntimeException) {invalid(frame)}
        finally {if(thumb!==bitmap) thumb?.recycle();bitmap.recycle()}
    }
    private fun invalid(frame: ImageFrame)=ImageQuality(false,0.0,0.0,0.0,frame.width,frame.height,frame.rotationDegrees,frame.sha256,ImageQualityStatus.CORRUPTED)
}
