package vn.nguyenvanhoa.camerastampgps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class StampData(
    val title: String,
    val note: String,
    val time: Date,
    val location: Location?,
    val address: String
)

/** Vẽ khung "dấu" (thời gian + vị trí + tên đơn vị + logo) lên Canvas. Dùng chung cho xem trước và ảnh thật. */
object StampRenderer {
    private val YELLOW = Color.parseColor("#FFC400")
    private val WEEKDAYS = arrayOf("Chủ nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")

    fun drawOnBitmap(src: Bitmap, d: StampData, logo: Bitmap?): Bitmap {
        val bmp = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), d, logo)
        return bmp
    }

    fun draw(c: Canvas, w: Float, h: Float, d: StampData, logo: Bitmap?) {
        if (w <= 0f || h <= 0f) return
        val u = min(w, h) / 1080f
        val margin = 28f * u
        val pad = 24f * u
        val bar = 8f * u
        val gap = 8f * u
        val panelW = if (w > h) w * 0.55f else w - 2 * margin
        val textX = margin + pad + bar + 18f * u
        val textW = (margin + panelW - pad - textX).toInt().coerceAtLeast(50)

        val timePaint = paint(76f * u, YELLOW, true, u)
        val datePaint = paint(32f * u, Color.WHITE, true, u)
        val titlePaint = paint(38f * u, Color.WHITE, true, u)
        val bodyPaint = paint(31f * u, Color.WHITE, false, u)
        val subPaint = paint(28f * u, Color.parseColor("#DDDDDD"), false, u)

        val cal = Calendar.getInstance().apply { time = d.time }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(d.time)
        val weekday = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d.time)

        val layouts = mutableListOf<StaticLayout>()
        if (d.title.isNotBlank()) layouts += layout(d.title, titlePaint, textW)
        if (d.address.isNotBlank()) layouts += layout("📍 " + d.address, bodyPaint, textW)
        val loc = d.location
        if (loc != null) {
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "Vĩ độ %.6f  •  Kinh độ %.6f", loc.latitude, loc.longitude))
            sb.append("\nSai số ±").append(loc.accuracy.roundToInt()).append(" m")
            if (loc.hasAltitude()) sb.append("  •  Độ cao ").append(loc.altitude.roundToInt()).append(" m")
            layouts += layout(sb.toString(), subPaint, textW)
        } else {
            layouts += layout("Đang chờ tín hiệu GPS…", subPaint, textW)
        }
        if (d.note.isNotBlank()) layouts += layout("📝 " + d.note, bodyPaint, textW)

        val fm = timePaint.fontMetrics
        val headerH = fm.descent - fm.ascent
        val bodyH = layouts.sumOf { it.height }.toFloat() + gap * layouts.size
        val panelH = pad * 2 + headerH + bodyH
        val bottom = h - margin
        val top = bottom - panelH

        // Nền khung
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
        c.drawRoundRect(RectF(margin, top, margin + panelW, bottom), 18f * u, 18f * u, bg)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
        c.drawRect(margin + pad, top + pad, margin + pad + bar, bottom - pad, accent)

        // Dòng giờ lớn + thứ/ngày
        var y = top + pad
        c.drawText(timeStr, textX, y - fm.ascent, timePaint)
        val dx = textX + timePaint.measureText(timeStr) + 22f * u
        val divider = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        c.drawRect(dx, y + headerH * 0.18f, dx + 3f * u, y + headerH * 0.86f, divider)
        val sx = dx + 20f * u
        c.drawText(weekday, sx, y + headerH * 0.47f, datePaint)
        c.drawText(dateStr, sx, y + headerH * 0.47f + datePaint.textSize * 1.15f, datePaint)
        y += headerH + gap

        for (l in layouts) {
            c.save()
            c.translate(textX, y)
            l.draw(c)
            c.restore()
            y += l.height + gap
        }

        // Logo góc trên phải
        if (logo != null && logo.width > 0 && logo.height > 0) {
            val maxS = min(w, h) * 0.18f
            val s = min(maxS / logo.width, maxS / logo.height)
            val lw = logo.width * s
            val lh = logo.height * s
            val dst = RectF(w - margin - lw, margin, w - margin, margin + lh)
            c.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }

    private fun paint(size: Float, color: Int, bold: Boolean, u: Float) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setShadowLayer(3f * u, 0f, 1f * u, Color.BLACK)
        }

    private fun layout(text: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setLineSpacing(0f, 1.05f)
            .build()
}
