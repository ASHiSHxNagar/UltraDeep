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

    // Base background paint
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06060A") // Deep AMOLED dark base
    }

    // Gentle scrim overlay to ensure 100% text contrast & sharp readability
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3305050A")
    }

    // 5 Corner & Center luminous emitters covering all 4 corners, center, and edges
    private val orbPaintTopLeft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaintTopRight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaintCenter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaintBottomLeft = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaintBottomRight = Paint(Paint.ANTI_ALIAS_FLAG)

    // Reusable buffers to avoid allocations in onDraw
    private val hsvBuffer = FloatArray(3)
    private val gradientStops = floatArrayOf(0f, 0.38f, 0.72f, 1f)

    // Base seed colors
    private var baseColor1 = Color.parseColor("#00F5D4") // Cyan
    private var baseColor2 = Color.parseColor("#7B2CBF") // Violet / Purple
    private var baseColor3 = Color.parseColor("#FF007F") // Magenta / Pink

    private var targetColor1 = baseColor1
    private var targetColor2 = baseColor2
    private var targetColor3 = baseColor3

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
        motionAnimator = ValueAnimator.ofFloat(0f, 1000000f).apply {
            duration = 10000000L
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
        targetColor3 = blendColors(colors.topColor, colors.centerColor)

        if (!animate) {
            baseColor1 = targetColor1
            baseColor2 = targetColor2
            baseColor3 = targetColor3
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val start1 = baseColor1
        val start2 = baseColor2
        val start3 = baseColor3

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                baseColor1 = argbEvaluator.evaluate(fraction, start1, targetColor1) as Int
                baseColor2 = argbEvaluator.evaluate(fraction, start2, targetColor2) as Int
                baseColor3 = argbEvaluator.evaluate(fraction, start3, targetColor3) as Int
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

    private fun shiftHue(color: Int, degreeOffset: Float): Int {
        Color.colorToHSV(color, hsvBuffer)
        var newHue = (hsvBuffer[0] + degreeOffset) % 360f
        if (newHue < 0f) newHue += 360f
        hsvBuffer[0] = newHue
        // Ensure rich neon saturation and brightness for vivid glowing ambiance
        hsvBuffer[1] = hsvBuffer[1].coerceAtLeast(0.75f)
        hsvBuffer[2] = hsvBuffer[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsvBuffer)
    }

    private fun colorWithAlpha(color: Int, alphaFloat: Float): Int {
        val alpha = (alphaFloat * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw base AMOLED true black background
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val t = animTime * 0.001f
        // Dynamic continuous color shifting across the color wheel (~22s full cycle)
        val hueCycle = (animTime * 0.016f) % 360f

        // Calculate 5 distinct, vibrant, harmoniously shifted colors
        val cTopLeft = shiftHue(baseColor1, hueCycle)
        val cTopRight = shiftHue(baseColor2, hueCycle + 72f)
        val cCenter = shiftHue(baseColor3, hueCycle + 144f)
        val cBottomLeft = shiftHue(baseColor1, hueCycle + 216f)
        val cBottomRight = shiftHue(baseColor2, hueCycle + 288f)

        // --- 1. Top-Left Corner Emitter ---
        val cxTL = w * 0.12f + (w * 0.18f) * sin(t * 0.65f)
        val cyTL = h * 0.12f + (h * 0.12f) * cos(t * 0.55f)
        val rTL = (w * 1.15f).coerceAtLeast(420f)
        orbPaintTopLeft.shader = RadialGradient(
            cxTL, cyTL, rTL,
            intArrayOf(
                colorWithAlpha(cTopLeft, 0.58f),
                colorWithAlpha(cTopLeft, 0.28f),
                colorWithAlpha(cTopLeft, 0.08f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxTL, cyTL, rTL, orbPaintTopLeft)

        // --- 2. Top-Right Corner Emitter ---
        val cxTR = w * 0.88f + (w * 0.18f) * cos(t * 0.70f + 1.2f)
        val cyTR = h * 0.14f + (h * 0.12f) * sin(t * 0.60f + 0.8f)
        val rTR = (w * 1.15f).coerceAtLeast(420f)
        orbPaintTopRight.shader = RadialGradient(
            cxTR, cyTR, rTR,
            intArrayOf(
                colorWithAlpha(cTopRight, 0.56f),
                colorWithAlpha(cTopRight, 0.26f),
                colorWithAlpha(cTopRight, 0.07f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxTR, cyTR, rTR, orbPaintTopRight)

        // --- 3. Fluid Center Pulsing Emitter ---
        val cxC = w * 0.50f + (w * 0.20f) * sin(t * 0.85f + 2.1f)
        val cyC = h * 0.50f + (h * 0.16f) * cos(t * 0.75f + 1.6f)
        val rC = (w * 1.20f).coerceAtLeast(450f)
        orbPaintCenter.shader = RadialGradient(
            cxC, cyC, rC,
            intArrayOf(
                colorWithAlpha(cCenter, 0.52f),
                colorWithAlpha(cCenter, 0.24f),
                colorWithAlpha(cCenter, 0.06f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxC, cyC, rC, orbPaintCenter)

        // --- 4. Bottom-Left Corner Emitter ---
        val cxBL = w * 0.14f + (w * 0.18f) * cos(t * 0.55f + 2.7f)
        val cyBL = h * 0.86f + (h * 0.12f) * sin(t * 0.70f + 3.1f)
        val rBL = (w * 1.15f).coerceAtLeast(420f)
        orbPaintBottomLeft.shader = RadialGradient(
            cxBL, cyBL, rBL,
            intArrayOf(
                colorWithAlpha(cBottomLeft, 0.55f),
                colorWithAlpha(cBottomLeft, 0.25f),
                colorWithAlpha(cBottomLeft, 0.07f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxBL, cyBL, rBL, orbPaintBottomLeft)

        // --- 5. Bottom-Right Corner Emitter ---
        val cxBR = w * 0.86f + (w * 0.18f) * sin(t * 0.65f + 3.8f)
        val cyBR = h * 0.86f + (h * 0.14f) * cos(t * 0.50f + 2.3f)
        val rBR = (w * 1.15f).coerceAtLeast(420f)
        orbPaintBottomRight.shader = RadialGradient(
            cxBR, cyBR, rBR,
            intArrayOf(
                colorWithAlpha(cBottomRight, 0.58f),
                colorWithAlpha(cBottomRight, 0.28f),
                colorWithAlpha(cBottomRight, 0.08f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxBR, cyBR, rBR, orbPaintBottomRight)

        // 3. Soft translucent scrim overlay for crisp text readability
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
