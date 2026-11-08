package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dark PPI-style scope: own position at center, 3 range rings, contacts
 * placed by bearing+distance with a short heading tick when known.
 * Range is either a fixed radius or "Auto" (scales to the furthest contact).
 */
class RadarView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var contacts: List<RadarContact> = emptyList()
    private var fixedRangeMeters: Float? = null // null = Auto

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(60, 255, 255, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun setContacts(list: List<RadarContact>) {
        contacts = list
        invalidate()
    }

    fun setRange(meters: Float?) {
        fixedRangeMeters = meters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f - 40f
        if (maxRadius <= 0f) return

        val range = fixedRangeMeters
            ?: (contacts.maxOfOrNull { it.distanceMeters }?.coerceAtLeast(50f) ?: 100f)

        // Rings at 1/3, 2/3, full range.
        for (i in 1..3) {
            val r = maxRadius * i / 3f
            canvas.drawCircle(cx, cy, r, ringPaint)
            val label = formatMeters(range * i / 3f)
            canvas.drawText(label, cx, cy - r - 6f, labelPaint)
        }
        canvas.drawText("N", cx, cy - maxRadius - 36f, labelPaint)

        canvas.drawCircle(cx, cy, 8f, centerPaint)

        for (c in contacts) {
            val r = (c.distanceMeters / range).coerceIn(0f, 1f) * maxRadius
            val rad = Math.toRadians((c.bearingDeg - 90).toDouble()) // 0°=up
            val x = cx + r * cos(rad).toFloat()
            val y = cy + r * sin(rad).toFloat()

            contactPaint.color = c.type.color
            canvas.drawCircle(x, y, 10f, contactPaint)

            c.headingDeg?.let { hdg ->
                val hRad = Math.toRadians((hdg - 90).toDouble())
                val tx = x + 18f * cos(hRad).toFloat()
                val ty = y + 18f * sin(hRad).toFloat()
                tickPaint.color = c.type.color
                canvas.drawLine(x, y, tx, ty, tickPaint)
            }
        }
    }

    private fun formatMeters(m: Float): String =
        if (m >= 1000f) "%.1f km".format(m / 1000f) else "%.0f m".format(m)
}
