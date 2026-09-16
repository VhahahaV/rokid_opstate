package ai.opstate.rokid.vision

import ai.opstate.core.scheduler.VisualSignature
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.sqrt

/** pHash plus 16-bin RGB histograms, matching the streaming HR visual front end. */
object VisualSignatureExtractor {
    fun usable(jpeg: ByteArray): Boolean {
        val bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size) ?: return false
        val thumbnail=Bitmap.createScaledBitmap(bitmap,32,24,true)
        return try {
            val pixels=IntArray(32*24);thumbnail.getPixels(pixels,0,32,0,0,32,24)
            ai.opstate.core.quality.ImageQualityStatistics.analyze(pixels,32,24).status==ai.opstate.core.model.ImageQualityStatus.VALID
        } finally {if(thumbnail!==bitmap) thumbnail.recycle();bitmap.recycle()}
    }
    fun fromJpeg(jpeg: ByteArray): VisualSignature {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("VISUAL_IMAGE_DECODE_FAILED")
        try {
            val small = Bitmap.createScaledBitmap(source, 32, 32, true)
            val rgb = Bitmap.createScaledBitmap(source, 128, 128, true)
            try {
                val values = DoubleArray(32 * 32)
                for (y in 0 until 32) for (x in 0 until 32) {
                    val color = small.getPixel(x, y)
                    values[y * 32 + x] = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
                }
                val basis = Array(8) { k -> DoubleArray(32) { n ->
                    (if (k == 0) sqrt(1.0 / 32) else sqrt(2.0 / 32)) * cos(Math.PI * (2 * n + 1) * k / 64)
                } }
                val low = DoubleArray(64)
                for (u in 0 until 8) for (v in 0 until 8) {
                    var total = 0.0
                    for (y in 0 until 32) for (x in 0 until 32) total += basis[u][y] * values[y * 32 + x] * basis[v][x]
                    low[u * 8 + v] = total
                }
                val median = low.drop(1).sorted()[31]
                var hash = 0L
                for (index in low.indices) if (low[index] >= median) hash = hash or (1L shl index)
                val bins = DoubleArray(48)
                for (y in 0 until rgb.height) for (x in 0 until rgb.width) {
                    val color = rgb.getPixel(x, y)
                    bins[Color.red(color) / 16]++
                    bins[16 + Color.green(color) / 16]++
                    bins[32 + Color.blue(color) / 16]++
                }
                val count = (rgb.width * rgb.height).toDouble()
                return VisualSignature(hash, bins.map { it / count })
            } finally { small.recycle(); rgb.recycle() }
        } finally { source.recycle() }
    }
}
