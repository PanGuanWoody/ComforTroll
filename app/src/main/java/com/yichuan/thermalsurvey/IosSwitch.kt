package com.yichuan.thermalsurvey

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class IosSwitch(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f
    private var checked = false
    private var listener: ((Boolean) -> Unit)? = null

    var isChecked: Boolean
        get() = checked
        set(value) {
            checked = value
            position = if (value) 1f else 0f
            invalidate()
        }

    init {
        isClickable = true
        setOnClickListener { setCheckedAnimated(!isChecked, true) }
    }

    fun setOnCheckedChangeListener(value: (Boolean) -> Unit) {
        listener = value
    }

    fun setChecked(value: Boolean, animated: Boolean) {
        if (animated) setCheckedAnimated(value, false) else isChecked = value
    }

    private fun setCheckedAnimated(value: Boolean, notify: Boolean) {
        if (checked == value) return
        checked = value
        val target = if (value) 1f else 0f
        ValueAnimator.ofFloat(position, target).apply {
            duration = 180L
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        if (notify) listener?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dpValue(52), context.dpValue(32))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val radius = h / 2f
        paint.color = blend(0xFFD5DADD.toInt(), AppUi.PRIMARY, position)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, radius, radius, paint)
        val thumbRadius = context.dpValue(13).toFloat()
        val start = radius
        val end = width - radius
        paint.color = 0xFFFFFFFF.toInt()
        paint.setShadowLayer(context.dpValue(2).toFloat(), 0f, context.dpValue(1).toFloat(), 0x33000000)
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawCircle(start + (end - start) * position, radius, thumbRadius, paint)
        paint.clearShadowLayer()
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val inverse = 1f - amount
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(from) * inverse + android.graphics.Color.red(to) * amount).toInt(),
            (android.graphics.Color.green(from) * inverse + android.graphics.Color.green(to) * amount).toInt(),
            (android.graphics.Color.blue(from) * inverse + android.graphics.Color.blue(to) * amount).toInt()
        )
    }
}

private fun Context.dpValue(value: Int): Int = (value * resources.displayMetrics.density).toInt()
