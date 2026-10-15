package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var currentCenterColor = Color.parseColor("#4400F5D4")
    private var currentMidColor = Color.parseColor("#227B2CBF")
    private var currentOuterColor = Color.TRANSPARENT

    private var targetCenterColor = currentCenterColor
    private var targetMidColor = currentMidColor

    private var pulseFraction = 0.7f
    private val argbEvaluator = ArgbEvaluator()

    private var pulseAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    init {
        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.55f, 0.95f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseFraction = animator.animatedValue as Float
                updateShader()
                invalidate()
            }
            start()
        }
    }

    fun setGlowColors(primaryColor: Int, secondaryColor: Int, animate: Boolean = true) {
        targetCenterColor = adjustAlpha(primaryColor, 0.65f)
        targetMidColor = adjustAlpha(secondaryColor, 0.35f)

        if (!animate) {
            currentCenterColor = targetCenterColor
            currentMidColor = targetMidColor
            updateShader()
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val startCenter = currentCenterColor
        val startMid = currentMidColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            addUpdateListener { animator ->
                val f = animator.animatedFraction
                currentCenterColor = argbEvaluator.evaluate(f, startCenter, targetCenterColor) as Int
                currentMidColor = argbEvaluator.evaluate(f, startMid, targetMidColor) as Int
                updateShader()
                invalidate()
            }
            start()
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    private fun updateShader() {
        if (width <= 0 || height <= 0) return
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = (Math.min(width, height) / 2f) * 1.35f * pulseFraction

        if (maxRadius <= 0) return

        glowPaint.shader = RadialGradient(
            centerX, centerY, maxRadius,
            intArrayOf(currentCenterColor, currentMidColor, currentOuterColor),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (Math.max(width, height) / 2f) * 1.5f
        canvas.drawCircle(centerX, centerY, radius, glowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        colorAnimator?.cancel()
    }
}
