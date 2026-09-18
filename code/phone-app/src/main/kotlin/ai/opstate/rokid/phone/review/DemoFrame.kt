package ai.opstate.rokid.phone.review

import ai.opstate.protocol.StudyWire
import android.graphics.*
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * What a delivered service looked like to the wearer: the frame the model was reading, with the card
 * laid over it the way the waveguide lays it over the world.
 *
 * Rendered on demand rather than captured live. The parts are already in the journal - the exact JPEG
 * the model saw, the decision's title/body/mode, the receipt that says it really became visible - so
 * rendering later means the look can be improved and a set re-rendered without re-running a session,
 * and a session costs nothing extra while it is running.
 *
 * The composite follows the same rules the HUD itself does, because it is imitating a display that has
 * one emitter hue: everything is drawn in that green at some alpha, and the layer is blended with
 * SCREEN so the black around the text adds no light. Painting it normally would put a black rectangle
 * over the scene, which is not what the wearer sees.
 */
object DemoFrameRenderer {
    /** The emitter hue the panel actually produces; the same one HudView draws with. */
    private const val R=76
    private const val G=255
    private const val B=151

    /** The glasses display, and the density its HUD is laid out against (1dp = 1.5px). */
    private const val DISPLAY_W=480f
    private const val DISPLAY_H=640f
    private const val HUD_DENSITY=1.5f

    /** One card, as it was delivered. */
    data class Card(val title: String,val body: String,val modeLabel: String?,val marker: Boolean)

    private fun ink(alpha: Int)=Color.argb(alpha,R,G,B)
    private val strong=ink(225)
    private val body=ink(170)
    private val meta=ink(118)
    private val faint=ink(80)

    /**
     * Draws [card] over [scene]. The scene keeps its own size; the HUD is laid into the middle band of
     * the frame, since the waveguide covers less of the view than the camera does and its 3:4 area
     * fitted to the frame's width is the closest honest approximation of where the card sits.
     */
    fun render(scene: Bitmap,card: Card): Bitmap {
        val output=scene.copy(Bitmap.Config.ARGB_8888,true)
        val hud=renderHud(DISPLAY_W.toInt(),DISPLAY_H.toInt(),card)
        val hudHeight=output.width*DISPLAY_H/DISPLAY_W
        val hudTop=(output.height-hudHeight)/2f
        // A black-preserving gain on the way in, so the card reads over mid-tones the way a 1500-nit
        // emitter does over the scene. It scales, so black stays black and adds no light of its own.
        val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            colorFilter=ColorMatrixColorFilter(ColorMatrix().apply {setScale(1.35f,1.35f,1.35f,1f)})
        }
        Canvas(output).drawBitmap(hud,Rect(0,0,hud.width,hud.height),RectF(0f,hudTop,output.width.toFloat(),hudTop+hudHeight),paint)
        hud.recycle()
        return output
    }

    /** The HUD on its own, in the display's own 480x640 space, on transparent black. */
    private fun renderHud(width: Int,height: Int,card: Card): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val margin=24f*HUD_DENSITY
        val usable=width-margin*2
        var y=28f*HUD_DENSITY

        // Eyebrow: the kind of thing this is, with the service marker in front of it.
        y+=16.5f*1.35f
        if(card.marker) {
            paint.color=strong; paint.textSize=16.5f; paint.typeface=Typeface.DEFAULT
            canvas.drawText("◆",margin,y,paint)
        }
        paint.color=body; paint.textSize=16.5f; paint.typeface=Typeface.DEFAULT
        canvas.drawText(eyebrow(card),margin+(if(card.marker) 30f else 0f),y,paint)
        y+=7f

        // Title: the one line read first, and the brightest on screen.
        paint.color=strong; paint.textSize=27f; paint.typeface=Typeface.DEFAULT_BOLD
        y+=27f*1.25f
        for(line in wrap(card.title,usable,paint,2)) { canvas.drawText(line,margin,y,paint);y+=27f*1.25f }
        y+=3f

        // Body: the substance.
        paint.color=body; paint.textSize=19.5f; paint.typeface=Typeface.DEFAULT
        y+=19.5f*1.5f
        for(line in wrap(card.body,usable,paint,8)) { canvas.drawText(line,margin,y,paint);y+=19.5f*1.5f }

        // Footer: how to dismiss it, faintest thing on screen.
        paint.color=faint; paint.textSize=15f
        y=height.toFloat()-20f*HUD_DENSITY
        canvas.drawText("单击收起 · 双击返回桌面",margin,y,paint)
        return bitmap
    }

    private fun eyebrow(card: Card)=buildString {
        append("OPSTATE · 主动服务")
        card.modeLabel?.let {append(" · ");append(it)}
    }

    /** Character wrapping, as on the glasses: the copy is mostly Chinese, where a word break never fires. */
    private fun wrap(value: String,available: Float,paint: Paint,maxLines: Int): List<String> {
        val out=mutableListOf<String>()
        for(paragraph in value.split('\n')) {
            val builder=StringBuilder()
            for(character in paragraph) {
                if(builder.isNotEmpty() && paint.measureText(builder.toString()+character)>available) {
                    if(out.size==maxLines) return out
                    out.add(builder.toString());builder.setLength(0)
                }
                builder.append(character)
            }
            if(out.size==maxLines) break
            out.add(builder.toString())
        }
        return out
    }

    /** The mode label the glasses prints, so the composite says what the wearer's card said. */
    fun modeLabel(mode: String?)=when(mode) {
        "AWARENESS"->"提醒";"DECISION"->"建议";"PREPARATION"->"准备";"EXECUTION"->"执行";else->null
    }

    /** A candidate: a service that was actually delivered and seen. */
    data class Candidate(val decisionId: String,val anchorId: String,val at: Long,val title: String,val body: String,
        val mode: String?,val latencyMs: Long?,val delivery: String?,val scene: java.io.File)

    /**
     * Every service in [trials] that was really shown on the glasses, newest first. A decision that was
     * suppressed, expired or never acknowledged is not a moment anyone saw, so it is not a candidate.
     */
    /**
     * Renders every candidate into `root/<sessionId>/` and writes an index beside them.
     *
     * The image is for looking at and the index is for judging: it carries the mode, the model latency
     * and the time, so a set can be shortlisted without opening each frame.
     */
    fun write(root: File,sessionId: String,candidates: List<Candidate>): Pair<File,Int> {
        val directory=File(root,sessionId).apply {mkdirs()}
        val index=StringBuilder()
        var written=0
        for(candidate in candidates) {
            val scene=BitmapFactory.decodeFile(candidate.scene.path) ?: continue
            val frame=render(scene,Card(candidate.title,candidate.body,modeLabel(candidate.mode),true))
            scene.recycle()
            val name=candidate.decisionId.replace(Regex("[^A-Za-z0-9_-]"),"_")
            val target=File(directory,"$name.jpg")
            target.outputStream().use {frame.compress(Bitmap.CompressFormat.JPEG,92,it)}
            frame.recycle()
            index.append(StudyWire.json.encodeToString(JsonObject.serializer(),StudyWire.payload(
                "decision_id" to candidate.decisionId,"at_epoch_ms" to candidate.at,"title" to candidate.title,
                "body" to candidate.body,"service_mode" to candidate.mode,"model_latency_ms" to candidate.latencyMs,
                "file" to target.name))).append('\n')
            written++
        }
        File(directory,"index.jsonl").writeText(index.toString())
        return directory to written
    }

    fun candidates(repository: SessionReviewRepository,sessionId: String,trials: List<TrialSummary>): List<Candidate> =
        trials.filter {it.decision=="SERVICE" && it.delivery=="DISPLAY_VISIBLE" && it.title!=null}
            .sortedByDescending {it.at}
            .mapNotNull {summary->
                val review=repository.trial(sessionId,summary)
                val decision: JsonObject=review.decision ?: return@mapNotNull null
                val scene=review.image ?: return@mapNotNull null
                Candidate(decision.string("decision_id") ?: summary.anchorId,summary.anchorId,summary.at,
                    decision.string("title") ?: return@mapNotNull null,
                    decision.string("body").orEmpty(),
                    decision.string("service_mode"),summary.latencyMs,summary.delivery,scene)
            }
}
