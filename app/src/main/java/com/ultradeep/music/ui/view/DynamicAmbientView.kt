package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import com.ultradeep.music.util.AmbientGradientColors

class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6050508") // Pure deep AMOLED black scrim
    }

    private var currentTopColor = Color.parseColor("#2A0845")
    private var currentCenterColor = Color.parseColor("#12061D")
    private var currentBottomColor = Color.parseColor("#060609")

    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#060609"))
    }

    fun setAmbientColors(colors: AmbientGradientColors, animate: Boolean = true) {
        if (!animate) {
            currentTopColor = colors.topColor
            currentCenterColor = colors.centerColor
            currentBottomColor = colors.bottomColor
            updateShader()
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val startTop = currentTopColor
        val startCenter = currentCenterColor
        val startBottom = currentBottomColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                currentTopColor = argbEvaluator.evaluate(fraction, startTop, colors.topColor) as Int
                currentCenterColor = argbEvaluator.evaluate(fraction, startCenter, colors.centerColor) as Int
                currentBottomColor = argbEvaluator.evaluate(fraction, startBottom, colors.bottomColor) as Int
                updateShader()
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    private fun updateShader() {
        if (width <= 0 || height <= 0) return
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(currentTopColor, currentCenterColor, currentBottomColor),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }
}
