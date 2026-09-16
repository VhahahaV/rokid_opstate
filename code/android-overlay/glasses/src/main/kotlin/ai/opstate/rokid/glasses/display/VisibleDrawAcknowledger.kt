package ai.opstate.rokid.glasses.display

import android.content.Context
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import java.io.Closeable

/** Counts actual View draw passes; a hidden, paused or powered-off window cannot acknowledge. */
class VisibleDrawAcknowledger(
    private val view: View,
    private val stillCurrent: ()->Boolean,
    private val onVisible: (Int)->Unit,
) : Closeable {
    private var count=0
    private var closed=false
    private var scheduled=false
    private val power=view.context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private fun visible()=view.isShown && view.hasWindowFocus() && view.windowVisibility==View.VISIBLE && power.isInteractive && stillCurrent()
    private val listener=ViewTreeObserver.OnDrawListener {
        if(!closed && !scheduled && visible()) {
            count++
            if(count>=2) {
                scheduled=true
                view.post {
                    if(!closed && visible()) { close(); onVisible(count) }
                    else if(!closed) { count=0; scheduled=false; view.postInvalidateOnAnimation() }
                }
            } else view.postInvalidateOnAnimation()
        }
    }
    init { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
    override fun close() {
        if(closed) return
        closed=true
        if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
    }
}
