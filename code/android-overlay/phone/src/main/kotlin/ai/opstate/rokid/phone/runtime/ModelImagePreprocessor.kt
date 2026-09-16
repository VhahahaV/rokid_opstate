package ai.opstate.rokid.phone.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** HR provider image policy: 768 px long edge, 32 px alignment, black centered padding, JPEG 90. */
internal object ModelImagePreprocessor {
    fun prepare(jpeg: ByteArray,longEdge: Int=768,alignment: Int=32): ByteArray {
        val source=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size) ?: error("MODEL_IMAGE_DECODE_FAILED")
        try {
            val scale=minOf(1.0,longEdge.toDouble()/maxOf(source.width,source.height))
            val width=maxOf(1,(source.width*scale).roundToInt());val height=maxOf(1,(source.height*scale).roundToInt())
            val resized=if(width==source.width && height==source.height) source else Bitmap.createScaledBitmap(source,width,height,true)
            try {
                val alignedWidth=(ceil(width.toDouble()/alignment)*alignment).toInt()
                val alignedHeight=(ceil(height.toDouble()/alignment)*alignment).toInt()
                val canvasBitmap=Bitmap.createBitmap(alignedWidth,alignedHeight,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(canvasBitmap);canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(resized,((alignedWidth-width)/2).toFloat(),((alignedHeight-height)/2).toFloat(),Paint(Paint.FILTER_BITMAP_FLAG))
                    return ByteArrayOutputStream().use {out->
                        check(canvasBitmap.compress(Bitmap.CompressFormat.JPEG,90,out)) {"MODEL_IMAGE_ENCODE_FAILED"};out.toByteArray()
                    }
                } finally {canvasBitmap.recycle()}
            } finally {if(resized!==source) resized.recycle()}
        } finally {source.recycle()}
    }
}
