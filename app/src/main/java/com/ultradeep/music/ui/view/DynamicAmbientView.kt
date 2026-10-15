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

        // Faster, fluid time progression for energetic cool visual motion
        val t = animTime * 0.0018f
        // Dynamic continuous color shifting across the color wheel
        val hueCycle = (animTime * 0.030f) % 360f

        // Calculate 5 distinct, vibrant, harmoniously shifted colors
        val c1 = shiftHue(baseColor1, hueCycle)
        val c2 = shiftHue(baseColor2, hueCycle + 72f)
        val c3 = shiftHue(baseColor3, hueCycle + 144f)
        val c4 = shiftHue(baseColor1, hueCycle + 216f)
        val c5 = shiftHue(baseColor2, hueCycle + 288f)

        // --- 1. Orb 1: Sweeps diagonally from Top-Left into Center-Right & back ---
        val cx1 = w * (0.35f + 0.35f * sin(t * 1.15f))
        val cy1 = h * (0.28f + 0.28f * cos(t * 0.95f))
        val r1 = (w * (1.10f + 0.25f * sin(t * 1.40f))).coerceAtLeast(440f)
        orbPaintTopLeft.shader = RadialGradient(
            cx1, cy1, r1,
            intArrayOf(
                colorWithAlpha(c1, 0.65f),
                colorWithAlpha(c1, 0.32f),
                colorWithAlpha(c1, 0.09f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx1, cy1, r1, orbPaintTopLeft)

        // --- 2. Orb 2: Sweeps diagonally from Top-Right into Bottom-Left & back ---
        val cx2 = w * (0.65f + 0.35f * cos(t * 1.10f + 1.2f))
        val cy2 = h * (0.32f + 0.30f * sin(t * 1.05f + 0.8f))
        val r2 = (w * (1.10f + 0.22f * cos(t * 1.30f))).coerceAtLeast(440f)
        orbPaintTopRight.shader = RadialGradient(
            cx2, cy2, r2,
            intArrayOf(
                colorWithAlpha(c2, 0.62f),
                colorWithAlpha(c2, 0.30f),
                colorWithAlpha(c2, 0.08f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx2, cy2, r2, orbPaintTopRight)

        // --- 3. Orb 3: Dynamic Figure-8 Plasma weaving across center and corners ---
        val cx3 = w * (0.50f + 0.40f * sin(t * 1.35f + 2.1f))
        val cy3 = h * (0.50f + 0.30f * sin(t * 2.70f + 1.6f))
        val r3 = (w * (1.25f + 0.28f * sin(t * 1.60f))).coerceAtLeast(470f)
        orbPaintCenter.shader = RadialGradient(
            cx3, cy3, r3,
            intArrayOf(
                colorWithAlpha(c3, 0.58f),
                colorWithAlpha(c3, 0.28f),
                colorWithAlpha(c3, 0.08f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx3, cy3, r3, orbPaintCenter)

        // --- 4. Orb 4: Sweeps from Bottom-Left up towards Top-Right ---
        val cx4 = w * (0.32f + 0.38f * cos(t * 0.95f + 2.8f))
        val cy4 = h * (0.72f + 0.28f * sin(t * 1.10f + 3.2f))
        val r4 = (w * (1.10f + 0.25f * cos(t * 1.50f))).coerceAtLeast(440f)
        orbPaintBottomLeft.shader = RadialGradient(
            cx4, cy4, r4,
            intArrayOf(
                colorWithAlpha(c4, 0.62f),
                colorWithAlpha(c4, 0.30f),
                colorWithAlpha(c4, 0.08f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx4, cy4, r4, orbPaintBottomLeft)

        // --- 5. Orb 5: Sweeps from Bottom-Right up towards Top-Left ---
        val cx5 = w * (0.68f + 0.38f * sin(t * 1.05f + 4.0f))
        val cy5 = h * (0.74f + 0.26f * cos(t * 0.90f + 2.4f))
        val r5 = (w * (1.10f + 0.24f * sin(t * 1.35f))).coerceAtLeast(440f)
        orbPaintBottomRight.shader = RadialGradient(
            cx5, cy5, r5,
            intArrayOf(
                colorWithAlpha(c5, 0.65f),
                colorWithAlpha(c5, 0.32f),
                colorWithAlpha(c5, 0.09f),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx5, cy5, r5, orbPaintBottomRight)

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
