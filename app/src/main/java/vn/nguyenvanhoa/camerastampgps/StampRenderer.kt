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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Các lựa chọn hiển thị trên dấu. */
data class StampOptions(
    val showAddress: Boolean = true,
    val showCoords: Boolean = true,
    val showAltitude: Boolean = true,
    val showGpsTime: Boolean = true,
    val showNote: Boolean = true,
    val showLogo: Boolean = true,
    val sizePct: Int = 100,      // cỡ dấu: 50–150 %
    val bgOpacityPct: Int = 65   // độ đậm nền: 20–95 %
)

data class StampData(
    val title: String,
    val note: String,
    val time: Date,
    val location: Location?,
    val address: String,
    val mslAltitude: Double?,    // độ cao so với mực nước biển (null = không có)
    val gpsTime: Date?,          // giờ từ vệ tinh GPS (null = chưa có)
    val options: StampOptions
)

/** Vẽ khung dấu lên Canvas. Dùng chung cho xem trước và ảnh thật. */
object StampRenderer {
    private val YELLOW = Color.parseColor("#FFC400")
    private const val BASE_SCALE = 2f / 3f   // dấu = 2/3 so với bản 1.0
    private val WEEKDAYS = arrayOf("Chủ nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy")

    fun drawOnBitmap(src: Bitmap, d: StampData, logo: Bitmap?): Bitmap {
        val bmp = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), d, logo)
        return bmp
    }

    fun draw(c: Canvas, w: Float, h: Float, d: StampData, logo: Bitmap?) {
        if (w <= 0f || h <= 0f) return
        val o = d.options
        val u = min(w, h) / 1080f * BASE_SCALE * (o.sizePct / 100f)
        val margin = min(w, h) * 0.026f
        val pad = 24f * u
        val bar = 8f * u
        val gap = 8f * u
        val textX = margin + pad + bar + 18f * u
        val maxTextW = min(w - textX - margin - pad, 950f * u).toInt().coerceAtLeast(50)

        val timePaint = paint(76f * u, YELLOW, true, u)
        val datePaint = paint(32f * u, Color.WHITE, true, u)
        val titlePaint = paint(38f * u, Color.WHITE, true, u)
        val bodyPaint = paint(31f * u, Color.WHITE, false, u)
        val subPaint = paint(28f * u, Color.parseColor("#DDDDDD"), false, u)

        val cal = Calendar.getInstance().apply { time = d.time }
        val hms = SimpleDateFormat("HH:mm:ss", Locale.US)
        val timeStr = hms.format(d.time)
        val weekday = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d.time)

        // ---- Các dòng nội dung ----
        val layouts = mutableListOf<StaticLayout>()
        if (d.title.isNotBlank()) layouts += layout(d.title, titlePaint, maxTextW)
        if (o.showAddress && d.address.isNotBlank()) layouts += layout("📍 " + d.address, bodyPaint, maxTextW)

        val loc = d.location
        val geo = mutableListOf<String>()
        if (loc == null) {
            if (o.showCoords || o.showAltitude) geo += "Đang chờ tín hiệu GPS…"
        } else {
            if (o.showCoords) {
                geo += String.format(Locale.US, "Vĩ độ %.6f  •  Kinh độ %.6f", loc.latitude, loc.longitude)
            }
            val extra = mutableListOf<String>()
            if (o.showCoords) extra += "Sai số ±${loc.accuracy.roundToInt()} m"
            if (o.showAltitude && d.mslAltitude != null) extra += "Độ cao ${d.mslAltitude.roundToInt()} m"
            if (extra.isNotEmpty()) geo += extra.joinToString("  •  ")
        }
        if (o.showGpsTime && d.gpsTime != null) {
            val diff = ((d.time.time - d.gpsTime.time) / 1000.0).roundToInt()
            var s = "🛰 Giờ GPS ${hms.format(d.gpsTime)}"
            if (abs(diff) >= 30) s += "  (máy lệch ${if (diff > 0) "+" else ""}$diff giây)"
            geo += s
        }
        if (geo.isNotEmpty()) layouts += layout(geo.joinToString("\n"), subPaint, maxTextW)
        if (o.showNote && d.note.isNotBlank()) layouts += layout("📝 " + d.note, bodyPaint, maxTextW)

        // ---- Kích thước khung theo nội dung ----
        val fm = timePaint.fontMetrics
        val headerH = fm.descent - fm.ascent
        val timeW = timePaint.measureText(timeStr)
        val dateBlockW = max(datePaint.measureText(weekday), datePaint.measureText(dateStr))
        val headerW = timeW + 22f * u + 3f * u + 20f * u + dateBlockW
        var contentW = headerW
        for (l in layouts) for (i in 0 until l.lineCount) contentW = max(contentW, l.getLineWidth(i))

        val bodyH = layouts.sumOf { it.height }.toFloat() + gap * layouts.size
        val panelH = pad * 2 + headerH + bodyH
        val panelW = (textX - margin) + contentW + pad
        val bottom = h - margin
        val top = bottom - panelH

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((o.bgOpacityPct.coerceIn(0, 100) * 255) / 100, 0, 0, 0)
        }
        c.drawRoundRect(RectF(margin, top, margin + panelW, bottom), 18f * u, 18f * u, bg)
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
        c.drawRect(margin + pad, top + pad, margin + pad + bar, bottom - pad, accent)

        // ---- Giờ lớn + thứ/ngày ----
        var y = top + pad
        c.drawText(timeStr, textX, y - fm.ascent, timePaint)
        val dx = textX + timeW + 22f * u
        val divider = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        c.drawRect(dx, y + headerH * 0.18f, dx + 3f * u, y + headerH * 0.86f, divider)
        val sx = dx + 23f * u
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

        // ---- Logo góc trên phải ----
        if (o.showLogo && logo != null && logo.width > 0 && logo.height > 0) {
            val maxS = min(w, h) * 0.18f * BASE_SCALE * (o.sizePct / 100f)
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
