package com.ultradeep.music.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.view.MotionEvent
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

    private val isLightTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    // The mini player follows the selected theme instead of forcing an AMOLED slab.
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#F4F6FA" else "#12111A")
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
    private val fluidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private var touchX = -1f
    private var touchY = -1f

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360000f).apply {
            duration = 60000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                if (isPlaying) {
                    animTime = (va.animatedValue as Float) * 0.04f
                    sweepAngle = (sweepAngle + 3.2f) % 360f
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
        val auraPulse = if (isLightTheme) 0.035f + 0.025f * sin(animTime * 0.8f) else 0.15f + 0.10f * sin(animTime * 0.8f)
        val alphaInt = (auraPulse * 255).toInt().coerceIn(20, 80)
        auraPaint.color = Color.argb(
            alphaInt,
            Color.red(primaryColor),
            Color.green(primaryColor),
            Color.blue(primaryColor)
        )
        canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, auraPaint)

        // Fast-moving, overlapping color pools create the fluid ambient motion.
        val phase = animTime
        val points = listOf(
            Triple(
                w * (0.5f + 0.42f * sin(phase * 1.7f)),
                h * (0.5f + 0.42f * sin(phase * 1.3f + 1.8f)),
                primaryColor
            ),
            Triple(
                w * (0.5f + 0.42f * sin(phase * 1.1f + 3.1f)),
                h * (0.5f + 0.42f * sin(phase * 1.9f)),
                secondaryColor
            ),
            Triple(
                w * (0.5f + 0.45f * sin(phase * 1.5f + 4.4f)),
                h * (0.5f + 0.40f * sin(phase * 1.2f + 0.6f)),
                Color.parseColor("#F72585")
            )
        )
        points.forEach { point ->
            val radius = w.coerceAtLeast(h) * 0.72f
            fluidPaint.shader = RadialGradient(
                point.first,
                point.second,
                radius,
                Color.argb(if (isLightTheme) 35 else 70, Color.red(point.third), Color.green(point.third), Color.blue(point.third)),
                Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, fluidPaint)
        }
        if (touchX >= 0f && touchY >= 0f) {
            fluidPaint.shader = RadialGradient(
                touchX,
                touchY,
                w.coerceAtLeast(h) * 0.8f,
                Color.argb(if (isLightTheme) 48 else 92, 0, 245, 212),
                Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(borderRect, cornerRadius, cornerRadius, fluidPaint)
        }

        // Rotating neon edge keeps the card lively even when the fluid pools overlap.
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = -1f
                touchY = -1f
                invalidate()
                return true
            }
        }
        return true
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
