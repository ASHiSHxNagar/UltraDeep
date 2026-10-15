package com.ultradeep.music.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Animated 4-bar audio equalizer visualizer for album art thumbnails.
 * Bounces dynamically when song is playing, smoothly rests at baseline when paused.
 */
class EqualizerBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paint for the animated equalizer bars
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F5D4") // Vibrant Neon Cyan
        style = Paint.Style.FILL
    }

    // Translucent dark vignette background for contrast over bright album art
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000") // 50% dark overlay
        style = Paint.Style.FILL
    }

    private val bgRect = RectF()
    private val barRect = RectF()

    private var isPlaying = false
    private var phase = 0f
    private var animator: ValueAnimator? = null

    // Target animation progress when stopping (decay to resting state)
    private var barHeights = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f)

    init {
        setupAnimator()
    }

    private fun setupAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 10000f).apply {
            duration = 100000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                phase = (va.animatedValue as Float) * 0.05f
                if (isPlaying) {
                    // 4 independent harmonic frequency oscillators for realistic audio equalizer bounce
                    barHeights[0] = (0.20f + 0.65f * (0.5f + 0.5f * sin(phase * 1.9f))).coerceIn(0.15f, 0.95f)
                    barHeights[1] = (0.25f + 0.70f * (0.5f + 0.5f * sin(phase * 2.7f + 1.2f))).coerceIn(0.15f, 0.95f)
                    barHeights[2] = (0.20f + 0.75f * (0.5f + 0.5f * sin(phase * 1.6f + 2.5f))).coerceIn(0.15f, 0.95f)
                    barHeights[3] = (0.22f + 0.68f * (0.5f + 0.5f * sin(phase * 2.3f + 3.8f))).coerceIn(0.15f, 0.95f)
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
            // Smoothly collapse bars to resting baseline
            barHeights[0] = 0.20f
            barHeights[1] = 0.20f
            barHeights[2] = 0.20f
            barHeights[3] = 0.20f
            invalidate()
        }
    }

    fun setBarColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw rounded dark backdrop
        bgRect.set(0f, 0f, w, h)
        val bgRadius = 8f * resources.displayMetrics.density
        canvas.drawRoundRect(bgRect, bgRadius, bgRadius, bgPaint)

        // 2. Draw 4 equalizer bars centered inside view
        val barCount = 4
        val paddingHorizontal = w * 0.16f
        val paddingBottom = h * 0.16f
        val usableWidth = w - (paddingHorizontal * 2f)
        val usableHeight = h - (paddingBottom * 2f)

        val totalSpacingRatio = 0.35f
        val barWidth = usableWidth / (barCount + (barCount - 1) * totalSpacingRatio)
        val barSpacing = barWidth * totalSpacingRatio
        val barCornerRadius = barWidth / 2f

        for (i in 0 until barCount) {
            val left = paddingHorizontal + i * (barWidth + barSpacing)
            val right = left + barWidth
            val currentBarHeight = usableHeight * barHeights[i]
            val bottom = h - paddingBottom
            val top = bottom - currentBarHeight

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint)
        }
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
