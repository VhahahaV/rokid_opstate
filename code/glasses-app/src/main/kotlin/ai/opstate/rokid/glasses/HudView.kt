package ai.opstate.rokid.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The glasses page: one View that paints the whole display with a Canvas.
 *
 * This is not a style preference, it is the fix for the green background. A page built from a
 * LinearLayout of TextViews comes back from this device's compositor with a grey veil over every
 * background pixel - measured, 0% of the frame was true black and every background pixel sat at
 * (16,16,16) instead of (0,0,0). The panel is single-wavelength, so that veil is a uniform green field
 * across the whole display. Painting the same content from one View measures 94-98% true black, which
 * is what the reference RealTopia HUD on this hardware does, and why the wearer does not see it there.
 *
 * The rest follows from the panel having one emitter hue: alpha is the only variable, so every colour
 * here is that green at some alpha, and nothing is ever filled in. A large bright area scatters across
 * the combiner and reads as a wash over the room rather than as something drawn.
 */
class HudView(context: Context): View(context) {
    /**
     * One line of the page. [gap] is the air above it in dp, [maxLines] the point at which it is
     * ellipsized rather than allowed to run off the display, and [action] makes it a tap target.
     */
    class Line(val text: String,val size: Float,val color: Int,val gap: Float=0f,
        val bold: Boolean=false,val maxLines: Int=1,val leading: Float=1.35f,
        val marker: String?=null,val action: (()->Unit)?=null)

    private class Hit(val top: Float,val bottom: Float,val action: ()->Unit)

    private val lines=mutableListOf<Line>()
    private val hits=mutableListOf<Hit>()
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private var pendingGap=0f
    /** What a tap that misses every row means. The page names the owner; see MainActivity. */
    var onEmptyTap:(()->Unit)?=null
    private val density get()=resources.displayMetrics.density
    private fun dp(value: Float)=value*density

    companion object {
        /** The emitter hue this display actually emits; every colour on the page is this at an alpha. */
        private const val R=76
        private const val G=255
        private const val B=151
        fun ink(alpha: Int)=Color.argb(alpha,R,G,B)
    }
    private val strong=ink(225)
    private val body=ink(170)
    private val meta=ink(118)
    private val faint=ink(80)

    init { setBackgroundColor(Color.BLACK) }

    /** Whether the page has anything to show. This is what "the display is in use" means here. */
    val hasContent get()=lines.isNotEmpty()

    fun clear() { lines.clear(); hits.clear(); pendingGap=0f; invalidate() }

    private fun add(line: Line) {
        val gap=pendingGap; pendingGap=0f
        lines.add(if(gap==0f) line else Line(line.text,line.size,line.color,gap,line.bold,line.maxLines,
            line.leading,line.marker,line.action))
        invalidate()
    }

    // The page's vocabulary: each helper fixes a size and an ink, so a caller never picks a colour.
    /** What kind of thing this is - the smallest, quietest line. A marker carries the accent. */
    fun eyebrow(value: String,marker: String?=null)=add(Line(value,11f,if(marker!=null) body else meta,marker=marker))
    /** The one line the wearer reads first, and the brightest thing on screen. */
    fun hero(value: String,lines: Int=2)=add(Line(value,18f,strong,bold=true,maxLines=lines,leading=1.25f))
    /** The substance. Wraps, and is ellipsized at the cap. */
    fun support(value: String,lines: Int=9)=add(Line(value,13f,body,maxLines=lines,leading=1.5f))
    fun meta(value: String,lines: Int=3)=add(Line(value,11f,meta,maxLines=lines,leading=1.4f))
    fun footer(value: String)=add(Line(value,10f,faint,maxLines=2,leading=1.4f))
    /** Vertical air, in dp, before the next line. */
    fun gap(value: Float) { pendingGap=value }
    /** A tappable control line. */
    fun button(label: String,action: ()->Unit)=add(Line(label,14f,body,leading=1.3f,action=action))
    /** A tappable list row; the cursor and the brighter ink both carry the selection. */
    fun row(label: String,selected: Boolean,action: ()->Unit)=
        add(Line(label,14f,if(selected) strong else meta,maxLines=2,leading=1.3f,action=action))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        hits.clear()
        val margin=dp(24f)
        val usable=width-margin*2
        var y=dp(28f)
        for(line in lines) {
            y+=dp(line.gap)
            ink.color=line.color
            ink.textSize=dp(line.size)
            ink.typeface=if(line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val markerWidth=line.marker?.let { ink.measureText(it)+dp(8f) } ?: 0f
            val top=y
            var drawn=0
            for(segment in wrap(line.text,usable-markerWidth)) {
                if(drawn>=line.maxLines) break
                y+=ink.textSize*line.leading
                if(drawn==0 && line.marker!=null) {
                    ink.color=strong
                    canvas.drawText(line.marker,margin,y,ink)
                    ink.color=line.color
                }
                canvas.drawText(segment,margin+markerWidth,y,ink)
                drawn++
            }
            if(drawn==0) y+=ink.textSize*line.leading
            line.action?.let { hits.add(Hit(top,y+dp(4f),it)) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.action!=MotionEvent.ACTION_UP) return true
        hits.lastOrNull { event.y in it.top..it.bottom }?.let { it.action();return true }
        onEmptyTap?.invoke()
        return true
    }

    /**
     * Breaks a line to the width available, character by character: the page is mostly Chinese, where
     * a word break would rarely fire.
     */
    private fun wrap(value: String,available: Float): List<String> {
        if(available<=0f) return listOf(value)
        val out=mutableListOf<String>()
        for(paragraph in value.split('\n')) {
            val builder=StringBuilder()
            for(character in paragraph) {
                if(builder.isNotEmpty() && ink.measureText(builder.toString()+character)>available) {
                    out.add(builder.toString()); builder.setLength(0)
                }
                builder.append(character)
            }
            out.add(builder.toString())
        }
        return out
    }
}
