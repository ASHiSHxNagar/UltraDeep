package com.ultradeep.music.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Animated wavy dynamic colorful ambient border & background view for the mini player.
 * Draws a flowing neon gradient border with smooth rotating motion when playing.
 */
class WavyAmbientBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Solid dark surface interior
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12111A")
        style = Paint.Style.FILL
    }

    // Dynamic sweeping colorful border
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    // Soft ambient aura fill inside border
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cardRect = RectF()
    private val borderRect = RectF()
    private val matrix = Matrix()

    private var primaryColor = Color.parseColor("#00F5D4") // Cyan
    private var secondaryColor = Color.parseColor("#7B2CBF") // Violet

    private var isPlaying = false
    private var sweepAngle = 0f
    private var animTime = 0f
    private var animator: ValueAnimator? = null

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360000f).apply {
            duration = 120000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                if (isPlaying) {
                    animTime = (va.animatedValue as Float) * 0.05f
                    sweepAngle = (sweepAngle + 2.2f) % 360f
                    invalidate()
                }
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing

        if (isPlaying) {
            if (animator?.isRunning != true) {
                animator?.start()
            }
        } else {
            invalidate()
        }
    }

    fun setColors(primary: Int, secondary: Int) {
        this.primaryColor = primary
        this.secondaryColor = secondary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cornerRadius = 16f * resources.displayMetrics.density
        val strokeHalf = borderPaint.strokeWidth / 2f

        cardRect.set(0f, 0f, w, h)
        borderRect.set(strokeHalf, strokeHalf, w - strokeHalf, h - strokeHalf)

        // 1. Draw base dark surface background
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, surfacePaint)

        // 2. Soft pulsing ambient glow fill
        val auraPulse = 0.15f + 0.10f * sin(animTime * 0.8f)
        val alphaInt = (auraPulse * 255).toInt().coerceIn(20, 80)
        auraPaint.color = Color.argb(
            alphaInt,
            Color.red(primaryColor),
            Color.green(primaryColor),
            Color.blue(primaryColor)
        )
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, auraPaint)

        // 3. Wavy rotating sweeping neon border gradient
        val cx = w / 2f
        val cy = h / 2f

        val colors = intArrayOf(
            primaryColor,
            Color.parseColor("#00F5D4"), // Neon Cyan
            secondaryColor,
            Color.parseColor("#FF007F"), // Hot Pink
            primaryColor
        )
        val positions = floatArrayOf(0f, 0.28f, 0.55f, 0.82f, 1f)

        val sweepGradient = SweepGradient(cx, cy, colors, positions)
        matrix.setRotate(sweepAngle, cx, cy)
        sweepGradient.setLocalMatrix(matrix)

        borderPaint.shader = sweepGradient
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, borderPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying && animator?.isRunning != true) {
            animator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
