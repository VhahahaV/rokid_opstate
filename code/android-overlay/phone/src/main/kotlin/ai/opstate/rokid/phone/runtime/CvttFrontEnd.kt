package ai.opstate.rokid.phone.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * CVTT front end v1, ported from the frozen release in dist/cvtt_frontend_v1.zip.
 *
 * It runs on the phone, on the frames the glasses send it: a service session uploads every capture as
 * it is taken, and this decides which of them are worth a model call. A returned [Decision] is only
 * that decision - it neither calls the remote model nor decides the service card. The hidden GRU state
 * is reset on a frame gap over 120 seconds, while the latch is retained as required by the release
 * contract.
 */
class CvttFrontEnd(context: Context) : Closeable {
    data class Decision(val fired: Boolean,val reason: String?,val score: Float,val seedScores: FloatArray)

    private val environment=OrtEnvironment.getEnvironment()
    private val root=File(context.noBackupFilesDir,"cvtt-frontend-v1").apply {mkdirs()}
    private val encoder: OrtSession
    private val heads: List<OrtSession>
    private val hidden=Array(SEEDS.size) {FloatArray(HIDDEN)}
    private var lastFrameElapsed: Long?=null
    private var latched=false
    private var lastTriggerElapsed: Long?=null
    private var emittedInitialisation=false

    init {
        val encoderFile=materialize(context,"cvtt/encoder.onnx","encoder.onnx")
        encoder=environment.createSession(encoderFile.absolutePath,OrtSession.SessionOptions())
        heads=SEEDS.map {seed->environment.createSession(materialize(context,"cvtt/head_seed$seed.onnx","head_seed$seed.onnx").absolutePath,
            OrtSession.SessionOptions())}
    }

    /** The first frame always asks for one packet. It initializes the pipeline without closing the latch. */
    fun observe(jpeg: ByteArray,elapsedMs: Long): Decision {
        val previous=lastFrameElapsed
        val gapSeconds=if(previous==null) 0.0 else ((elapsedMs-previous).coerceAtLeast(0L)/1000.0)
        if(previous!=null && gapSeconds>SESSION_GAP_SECONDS) hidden.forEach {it.fill(0f)}
        lastFrameElapsed=elapsedMs
        val embedding=embedding(jpeg)
        val normalizedGap=ln(1.0+gapSeconds/GAP_TAU_SECONDS).toFloat()
        val scores=FloatArray(SEEDS.size) {index->step(heads[index],embedding,normalizedGap,hidden[index])}
        val score=scores.average().toFloat()
        if(!emittedInitialisation) {
            emittedInitialisation=true
            return Decision(true,"CVTT_COLD_START",score,scores)
        }
        if(latched) {
            if(score<=THETA_OFF) latched=false
            else if(lastTriggerElapsed!=null && elapsedMs-lastTriggerElapsed!!>=REFRACTORY_MS) latched=false
            return Decision(false,null,score,scores)
        }
        if(score>=THETA_ON) {
            latched=true;lastTriggerElapsed=elapsedMs
            return Decision(true,"CVTT_THETA_ON",score,scores)
        }
        return Decision(false,null,score,scores)
    }

    private fun embedding(jpeg: ByteArray): FloatArray {
        val input=preprocess(jpeg)
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,IMAGE_SIZE.toLong(),IMAGE_SIZE.toLong())).use {tensor->
            encoder.run(mapOf(encoder.inputNames.single() to tensor)).use {result->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>)[0].copyOf()
            }
        }
    }

    private fun step(session: OrtSession,embedding: FloatArray,gap: Float,state: FloatArray): Float {
        val names=session.inputNames.toList()
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(embedding),longArrayOf(1,EMBEDDING.toLong())).use {embeddingTensor->
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(floatArrayOf(gap)),longArrayOf(1)).use {gapTensor->
                OnnxTensor.createTensor(environment,FloatBuffer.wrap(state),longArrayOf(1,1,HIDDEN.toLong())).use {hiddenTensor->
                    session.run(linkedMapOf(names[0] to embeddingTensor,names[1] to gapTensor,names[2] to hiddenTensor)).use {result->
                        val score=when(val value=result[0].value) {
                            is FloatArray->value[0]
                            is Array<*>->((value[0] as FloatArray)[0])
                            else->error("CVTT_HEAD_SCORE_TYPE")
                        }
                        @Suppress("UNCHECKED_CAST")
                        val updated=result[1].value as Array<Array<FloatArray>>
                        updated[0][0].copyInto(state)
                        return score
                    }
                }
            }
        }
    }

    /** Exact separable antialiased bilinear resize from the supplied CVTT reference implementation. */
    private fun preprocess(jpeg: ByteArray): FloatArray {
        val bitmap=BitmapFactory.decodeByteArray(jpeg,0,jpeg.size) ?: error("CVTT_IMAGE_DECODE_FAILED")
        val source=if(bitmap.config==Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888,false).also {bitmap.recycle()}
        try {
            val width=source.width;val height=source.height
            val pixels=IntArray(width*height);source.getPixels(pixels,0,width,0,0,width,height)
            val wy=weights(height);val wx=weights(width)
            val vertical=FloatArray(IMAGE_SIZE*width*3)
            for(y in 0 until IMAGE_SIZE) for(x in 0 until width) for(tap in wy[y]) {
                val color=pixels[tap.index*width+x];val base=(y*width+x)*3
                vertical[base]+=tap.weight*Color.red(color)
                vertical[base+1]+=tap.weight*Color.green(color)
                vertical[base+2]+=tap.weight*Color.blue(color)
            }
            val out=FloatArray(3*IMAGE_SIZE*IMAGE_SIZE)
            for(y in 0 until IMAGE_SIZE) for(x in 0 until IMAGE_SIZE) {
                var red=0f;var green=0f;var blue=0f
                for(tap in wx[x]) {
                    val base=(y*width+tap.index)*3
                    red+=tap.weight*vertical[base];green+=tap.weight*vertical[base+1];blue+=tap.weight*vertical[base+2]
                }
                val position=y*IMAGE_SIZE+x
                out[position]=(red/255f-MEAN[0])/STD[0]
                out[IMAGE_SIZE*IMAGE_SIZE+position]=(green/255f-MEAN[1])/STD[1]
                out[2*IMAGE_SIZE*IMAGE_SIZE+position]=(blue/255f-MEAN[2])/STD[2]
            }
            return out
        } finally {source.recycle()}
    }

    private data class Tap(val index: Int,val weight: Float)
    private fun weights(source: Int): Array<List<Tap>> {
        val scale=source.toDouble()/IMAGE_SIZE
        val support=max(scale,1.0)
        return Array(IMAGE_SIZE) {target->
            val centre=(target+0.5)*scale
            val low=max(floor(centre-support).toInt(),0)
            val high=min(ceil(centre+support).toInt(),source)
            val raw=(low until high).map {index->
                val weight=max(1.0-kotlin.math.abs((index+0.5)-centre)/support,0.0)
                index to weight
            }
            val total=raw.sumOf {it.second}
            require(total>0.0) {"CVTT_RESIZE_WEIGHTS_INVALID"}
            raw.map {Tap(it.first,(it.second/total).toFloat())}
        }
    }

    private fun materialize(context: Context,asset: String,name: String): File {
        val output=File(root,name)
        val expected=context.assets.open(asset).use {it.available().toLong()}
        if(output.isFile && output.length()==expected) return output
        val temporary=File(root,"$name.part")
        context.assets.open(asset).use {input->FileOutputStream(temporary).use {outputStream->input.copyTo(outputStream)}}
        check(temporary.renameTo(output)) {"CVTT_MODEL_COPY_FAILED"}
        return output
    }

    override fun close() {heads.forEach(OrtSession::close);encoder.close()}

    companion object {
        const val VERSION="cvtt_frontend_v1"
        private const val IMAGE_SIZE=224
        private const val EMBEDDING=384
        private const val HIDDEN=128
        private val SEEDS=listOf(11,23,37)
        private const val GAP_TAU_SECONDS=32.0
        private const val SESSION_GAP_SECONDS=120.0
        private const val REFRACTORY_MS=120_000L
        private const val THETA_ON=1.926526f
        private const val THETA_OFF=0.930753f
        private val MEAN=floatArrayOf(0.485f,0.456f,0.406f)
        private val STD=floatArrayOf(0.229f,0.224f,0.225f)
    }
}
