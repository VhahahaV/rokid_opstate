package ai.opstate.core.quality

import ai.opstate.core.model.ImageQualityStatus
import kotlin.math.abs

data class PixelQuality(val status: ImageQualityStatus,val meanLuma: Double,val blurScore: Double,
    val darkPixelRatio: Double,val brightPixelRatio: Double,val differenceHash: Long)

/** Deterministic measurements on a small decoded RGB thumbnail. Ratios are measured, not new pilot thresholds. */
object ImageQualityStatistics {
    const val MIN_LUMA=18.0
    const val MAX_LUMA=238.0
    const val MIN_EDGE_ENERGY=25.0
    fun analyze(pixels: IntArray,width: Int,height: Int): PixelQuality {
        require(width>=3 && height>=3 && pixels.size==width*height)
        val luma=IntArray(pixels.size);var sum=0.0;var dark=0;var bright=0
        pixels.forEachIndexed {index,pixel->
            val gray=0.2126*((pixel shr 16) and 255)+0.7152*((pixel shr 8) and 255)+0.0722*(pixel and 255)
            luma[index]=gray.toInt();sum+=gray
            if(gray<MIN_LUMA) dark++
            if(gray>MAX_LUMA) bright++
        }
        val mean=sum/pixels.size
        var energy=0.0
        for(y in 1 until height-1) for(x in 1 until width-1) {
            val value=luma[y*width+x]
            energy+=abs(value-luma[y*width+x-1])+abs(value-luma[(y-1)*width+x])
        }
        val blur=energy/((width-2).toDouble()*(height-2))
        var hash=0L
        for(row in 0 until 8) for(column in 0 until 8) {
            val y=(row*(height-1)/8.0).toInt().coerceIn(0,height-1)
            val x=(column*(width-2)/8.0).toInt().coerceIn(0,width-2)
            if(luma[y*width+x]>luma[y*width+x+1]) hash=hash or (1L shl (row*8+column))
        }
        val status=when {mean<MIN_LUMA->ImageQualityStatus.TOO_DARK;mean>MAX_LUMA->ImageQualityStatus.TOO_BRIGHT
            blur<MIN_EDGE_ENERGY->ImageQualityStatus.BLURRY;else->ImageQualityStatus.VALID}
        return PixelQuality(status,mean,blur,dark.toDouble()/pixels.size,bright.toDouble()/pixels.size,hash)
    }
}
