package org.opentrafficmap.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Simple line sparkline over a fixed-size list of bucket counts. No axes/labels — those live outside as TextViews. */
class RateChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var data: List<Int> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ContextCompat.getColor(context, android.R.color.holo_blue_dark)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = linePaint.color
        alpha = 40
    }

    fun setData(buckets: List<Int>) {
        data = buckets
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return
        val max = (data.maxOrNull() ?: 0).coerceAtLeast(1)
        val w = width.toFloat()
        val h = height.toFloat()
        val stepX = w / (data.size - 1)

        val line = Path()
        val fill = Path()
        data.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - (v.toFloat() / max) * h
            if (i == 0) { line.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) }
            else { line.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h)
        fill.close()

        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}
