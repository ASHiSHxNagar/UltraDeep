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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.ultradeep.music.util.AmbientGradientColors
import kotlin.math.cos
import kotlin.math.sin

class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Base paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#09090E") // Deep AMOLED dark base
    }

    // Scrim overlay: subtle 30% alpha so fluid glowing orbs shine through vibrantly while text stays sharp
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D07070B")
    }

    private val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint3 = Paint(Paint.ANTI_ALIAS_FLAG)

    // Current animated colors
    private var color1 = Color.parseColor("#00F5D4") // Cyan
    private var color2 = Color.parseColor("#7B2CBF") // Violet / Purple
    private var color3 = Color.parseColor("#FF007F") // Magenta / Pink

    private var targetColor1 = color1
    private var targetColor2 = color2
    private var targetColor3 = color3

    private val argbEvaluator = ArgbEvaluator()
    private var colorAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    private var animTime = 0f

    init {
        setWillNotDraw(false)
        startMotionAnimation()
    }

    private fun startMotionAnimation() {
        motionAnimator?.cancel()
        motionAnimator = ValueAnimator.ofFloat(0f, 100000f).apply {
            duration = 1000000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                animTime = (animator.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    fun setAmbientColors(colors: AmbientGradientColors, animate: Boolean = true) {
        targetColor1 = colors.topColor
        targetColor2 = colors.centerColor
        // Derive a third complementary vibrant glow
        targetColor3 = if (colors.accentColor != Color.TRANSPARENT && colors.accentColor != 0) {
            colors.accentColor
        } else {
            blendColors(colors.topColor, colors.centerColor)
        }

        if (!animate) {
            color1 = targetColor1
            color2 = targetColor2
            color3 = targetColor3
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val start1 = color1
        val start2 = color2
        val start3 = color3

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                color1 = argbEvaluator.evaluate(fraction, start1, targetColor1) as Int
                color2 = argbEvaluator.evaluate(fraction, start2, targetColor2) as Int
                color3 = argbEvaluator.evaluate(fraction, start3, targetColor3) as Int
                invalidate()
            }
            start()
        }
    }

    private fun blendColors(c1: Int, c2: Int): Int {
        val r = ((Color.red(c1) + Color.red(c2)) / 2).coerceIn(0, 255)
        val g = ((Color.green(c1) + Color.green(c2)) / 2).coerceIn(0, 255)
        val b = ((Color.blue(c1) + Color.blue(c2)) / 2).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun colorWithAlpha(color: Int, alphaFloat: Float): Int {
        val alpha = (alphaFloat * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw base AMOLED black canvas
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val t = animTime * 0.001f

        // Orb 1 (Top-Left / Center drifting motion)
        val cx1 = w * 0.35f + (w * 0.22f) * sin(t * 0.7f)
        val cy1 = h * 0.30f + (h * 0.16f) * cos(t * 0.5f)
        val r1 = (w * 0.75f).coerceAtLeast(300f)

        orbPaint1.shader = RadialGradient(
            cx1, cy1, r1,
            intArrayOf(
                colorWithAlpha(color1, 0.65f),
                colorWithAlpha(color1, 0.30f),
                colorWithAlpha(color1, 0.08f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx1, cy1, r1, orbPaint1)

        // Orb 2 (Center-Right / Bottom drifting motion)
        val cx2 = w * 0.68f + (w * 0.20f) * cos(t * 0.6f + 1.2f)
        val cy2 = h * 0.55f + (h * 0.18f) * sin(t * 0.8f + 0.8f)
        val r2 = (w * 0.82f).coerceAtLeast(340f)

        orbPaint2.shader = RadialGradient(
            cx2, cy2, r2,
            intArrayOf(
                colorWithAlpha(color2, 0.60f),
                colorWithAlpha(color2, 0.28f),
                colorWithAlpha(color2, 0.07f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx2, cy2, r2, orbPaint2)

        // Orb 3 (Bottom-Center glowing harmonic aura)
        val cx3 = w * 0.45f + (w * 0.25f) * sin(t * 0.5f + 2.5f)
        val cy3 = h * 0.78f + (h * 0.12f) * cos(t * 0.6f + 2.0f)
        val r3 = (w * 0.70f).coerceAtLeast(280f)

        orbPaint3.shader = RadialGradient(
            cx3, cy3, r3,
            intArrayOf(
                colorWithAlpha(color3, 0.55f),
                colorWithAlpha(color3, 0.25f),
                colorWithAlpha(color3, 0.06f),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx3, cy3, r3, orbPaint3)

        // Soft translucent scrim overlay to ensure 100% contrast & readability
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (motionAnimator?.isRunning != true) {
            startMotionAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionAnimator?.cancel()
        colorAnimator?.cancel()
    }
}
