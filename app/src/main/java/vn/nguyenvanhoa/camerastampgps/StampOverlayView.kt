package vn.nguyenvanhoa.camerastampgps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Lớp phủ xem trước dấu, vẽ đúng trong vùng ảnh 3:4 (PreviewView fitCenter). */
class StampOverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var data: StampData? = null
    private var logo: Bitmap? = null

    fun update(d: StampData, l: Bitmap?) {
        data = d
        logo = l
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val d = data ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        var iw = vw
        var ih = vw * 4f / 3f
        if (ih > vh) {
            ih = vh
            iw = vh * 3f / 4f
        }
        c.save()
        c.translate((vw - iw) / 2f, (vh - ih) / 2f)
        c.clipRect(0f, 0f, iw, ih)
        StampRenderer.draw(c, iw, ih, d, logo)
        c.restore()
    }
}
