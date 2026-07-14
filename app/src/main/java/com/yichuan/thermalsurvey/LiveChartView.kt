package com.yichuan.thermalsurvey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs

class LiveChartView(
    context: Context,
    private val labels: List<String>,
    private val colors: List<Int>,
    private val unit: String
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val series = labels.map { mutableListOf<Float>() }
    private val density = resources.displayMetrics.density
    private val maxPoints = 60

    fun addSample(values: FloatArray?) {
        labels.indices.forEach { index ->
            val list = series[index]
            list += values?.getOrNull(index) ?: Float.NaN
            if (list.size > maxPoints) list.removeAt(0)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (178 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 42f * density
        val right = width - 10f * density
        val top = 28f * density
        val bottom = height - 28f * density
        val allValues = series.flatten().filter { it.isFinite() }

        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 10f * density
        if (allValues.isEmpty()) {
            paint.color = AppUi.MUTED
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("等待实时数据", width / 2f, height / 2f, paint)
            return
        }

        var minimum = allValues.minOrNull() ?: 0f
        var maximum = allValues.maxOrNull() ?: 1f
        if (abs(maximum - minimum) < 0.001f) {
            minimum -= 1f
            maximum += 1f
        } else {
            val padding = (maximum - minimum) * 0.1f
            minimum -= padding
            maximum += padding
        }

        paint.strokeWidth = density
        paint.color = 0x1F627278
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            canvas.drawLine(left, y, right, y, paint)
        }

        paint.color = AppUi.MUTED
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(format(maximum), left - 5f * density, top + 4f * density, paint)
        canvas.drawText(format(minimum), left - 5f * density, bottom, paint)

        series.forEachIndexed { seriesIndex, values ->
            val path = Path()
            var started = false
            values.forEachIndexed { index, value ->
                if (!value.isFinite()) {
                    started = false
                    return@forEachIndexed
                }
                val x = if (maxPoints <= 1) left else left + (right - left) * index / (maxPoints - 1f)
                val y = bottom - (value - minimum) / (maximum - minimum) * (bottom - top)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * density
            paint.color = colors.getOrElse(seriesIndex) { AppUi.PRIMARY }
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL

        var legendX = left
        labels.forEachIndexed { index, label ->
            paint.color = colors.getOrElse(index) { AppUi.PRIMARY }
            paint.textAlign = Paint.Align.LEFT
            val latest = series[index].lastOrNull { it.isFinite() }
            val text = "$label ${latest?.let(::format) ?: "—"}$unit"
            canvas.drawText(text, legendX, height - 8f * density, paint)
            legendX += paint.measureText(text) + 14f * density
        }
    }

    private fun format(value: Float): String = when {
        abs(value) >= 1000f -> "%.0f".format(value)
        abs(value) >= 100f -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}
