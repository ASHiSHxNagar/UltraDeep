package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.ultradeep.music.util.AmbientGradientColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Epic full-screen fluid ambient background with:
 * - 7 fast-moving luminous color orbs sweeping across the entire screen
 * - Continuous rainbow hue cycling shifted per orb for variety
 * - Touch/pointer-follow: fluid blob trails your finger like a lava lamp
 * - Smooth color-transition when track changes
 */
class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val isLightTheme = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    // Background paint – AMOLED true black / light cream
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#F0F2F8" else "#04040A")
    }

    // Translucent scrim to guarantee text readability on top
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#20FFFFFF" else "#4004040A")
    }

    // ── 7 independent orb paints ──────────────────────────────────────────
    private val orbPaints = Array(7) { Paint(Paint.ANTI_ALIAS_FLAG) }

    // Touch-follow reactive blob
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var touchX = -1f
    private var touchY = -1f
    // Smoothed (lerped) touch position for fluid trailing
    private var smoothTouchX = -1f
    private var smoothTouchY = -1f

    private val hsvBuffer = FloatArray(3)
    private val gradientStops = floatArrayOf(0f, 0.30f, 0.65f, 1f)

    // Base palette – album-art driven
    private var baseColor1 = Color.parseColor("#00F5D4")
    private var baseColor2 = Color.parseColor("#7B2CBF")
    private var baseColor3 = Color.parseColor("#FF007F")

    private var targetColor1 = baseColor1
    private var targetColor2 = baseColor2
    private var targetColor3 = baseColor3

    private val argbEvaluator = ArgbEvaluator()
    private var colorAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    private var animTime = 0f

    init {
        setWillNotDraw(false)
        isClickable = false
        startMotionAnimation()
    }

    private fun startMotionAnimation() {
        motionAnimator?.cancel()
        motionAnimator = ValueAnimator.ofFloat(0f, 1_000_000f).apply {
            duration = 8_000_000L          // effectively infinite; repeats anyway
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                animTime = anim.animatedValue as Float
                // Lerp smoothed touch towards real touch
                if (touchX >= 0f) {
                    smoothTouchX = if (smoothTouchX < 0f) touchX else lerp(smoothTouchX, touchX, 0.12f)
                    smoothTouchY = if (smoothTouchY < 0f) touchY else lerp(smoothTouchY, touchY, 0.12f)
                } else {
                    smoothTouchX = -1f
                    smoothTouchY = -1f
                }
                invalidate()
            }
            start()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun setAmbientColors(colors: AmbientGradientColors, animate: Boolean = true) {
        targetColor1 = colors.topColor
        targetColor2 = colors.centerColor
        targetColor3 = blendColors(colors.topColor, colors.centerColor)

        if (!animate) {
            baseColor1 = targetColor1; baseColor2 = targetColor2; baseColor3 = targetColor3
            invalidate(); return
        }
        colorAnimator?.cancel()
        val s1 = baseColor1; val s2 = baseColor2; val s3 = baseColor3
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                baseColor1 = argbEvaluator.evaluate(f, s1, targetColor1) as Int
                baseColor2 = argbEvaluator.evaluate(f, s2, targetColor2) as Int
                baseColor3 = argbEvaluator.evaluate(f, s3, targetColor3) as Int
            }
            start()
        }
    }

    // ── Touch passthrough – draws the reactive blob, doesn't consume events ──

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                touchX = event.x
                touchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                touchX = -1f; touchY = -1f
            }
        }
        // Do NOT return true – let child views receive the event
        return false
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. True black base
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Fast time axis – drives all orb motion at high speed
        val t = animTime * 0.0038f
        // Hue cycle shifts the entire palette continuously around the wheel
        val hueCycle = (animTime * 0.055f) % 360f

        // Derive 7 vivid hue-shifted colors from the 3 base album colors
        val c = arrayOf(
            shiftHue(baseColor1, hueCycle),
            shiftHue(baseColor2, hueCycle + 51f),
            shiftHue(baseColor3, hueCycle + 102f),
            shiftHue(baseColor1, hueCycle + 153f),
            shiftHue(baseColor2, hueCycle + 204f),
            shiftHue(baseColor3, hueCycle + 255f),
            shiftHue(baseColor1, hueCycle + 306f)
        )

        // Orb parameters: [cx_formula, cy_formula, radius_factor, alpha]
        // All positions cover the FULL screen, not just a corner quarter
        data class OrbParams(val cx: Float, val cy: Float, val r: Float, val alpha: Float)

        val orbs = arrayOf(
            OrbParams(                                                   // 0 – diagonal sweeper
                w * (0.50f + 0.52f * sin(t * 1.15f)),
                h * (0.50f + 0.52f * cos(t * 0.93f)),
                (w.coerceAtLeast(h) * 1.05f).coerceAtLeast(520f),
                if (isLightTheme) 0.28f else 0.72f
            ),
            OrbParams(                                                   // 1 – top-right sweeper
                w * (0.52f + 0.52f * cos(t * 1.08f + 1.1f)),
                h * (0.48f + 0.52f * sin(t * 1.02f + 0.7f)),
                (w.coerceAtLeast(h) * 1.00f).coerceAtLeast(500f),
                if (isLightTheme) 0.26f else 0.68f
            ),
            OrbParams(                                                   // 2 – figure-eight plasma
                w * (0.50f + 0.48f * sin(t * 1.32f + 2.0f)),
                h * (0.50f + 0.48f * sin(t * 2.64f + 1.5f)),
                (w.coerceAtLeast(h) * 1.15f).coerceAtLeast(540f),
                if (isLightTheme) 0.24f else 0.65f
            ),
            OrbParams(                                                   // 3 – bottom-left rider
                w * (0.48f + 0.52f * cos(t * 0.93f + 2.7f)),
                h * (0.52f + 0.52f * sin(t * 1.08f + 3.1f)),
                (w.coerceAtLeast(h) * 1.00f).coerceAtLeast(500f),
                if (isLightTheme) 0.27f else 0.70f
            ),
            OrbParams(                                                   // 4 – bottom-right drifter
                w * (0.50f + 0.52f * sin(t * 1.02f + 3.9f)),
                h * (0.50f + 0.52f * cos(t * 0.88f + 2.3f)),
                (w.coerceAtLeast(h) * 1.00f).coerceAtLeast(500f),
                if (isLightTheme) 0.25f else 0.66f
            ),
            OrbParams(                                                   // 5 – slow macro sweep
                w * (0.50f + 0.55f * cos(t * 0.72f + 5.0f)),
                h * (0.50f + 0.55f * sin(t * 0.65f + 1.0f)),
                (w.coerceAtLeast(h) * 1.20f).coerceAtLeast(560f),
                if (isLightTheme) 0.18f else 0.52f
            ),
            OrbParams(                                                   // 6 – rapid center blinker
                w * (0.50f + 0.40f * sin(t * 2.10f + 0.4f)),
                h * (0.50f + 0.40f * cos(t * 1.75f + 0.9f)),
                (w.coerceAtLeast(h) * 0.75f).coerceAtLeast(380f),
                if (isLightTheme) 0.20f else 0.58f
            )
        )

        orbs.forEachIndexed { i, orb ->
            orbPaints[i].shader = RadialGradient(
                orb.cx, orb.cy, orb.r,
                intArrayOf(
                    colorWithAlpha(c[i], orb.alpha * 1.00f),
                    colorWithAlpha(c[i], orb.alpha * 0.52f),
                    colorWithAlpha(c[i], orb.alpha * 0.18f),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orb.cx, orb.cy, orb.r, orbPaints[i])
        }

        // ── Touch-follow reactive fluid blob ──────────────────────────────
        if (smoothTouchX >= 0f && smoothTouchY >= 0f) {
            val blobColor = shiftHue(baseColor1, hueCycle + 180f)
            val blobR = w.coerceAtLeast(h) * 0.75f
            touchPaint.shader = RadialGradient(
                smoothTouchX, smoothTouchY, blobR,
                intArrayOf(
                    colorWithAlpha(blobColor, if (isLightTheme) 0.50f else 1.00f),
                    colorWithAlpha(blobColor, if (isLightTheme) 0.22f else 0.55f),
                    colorWithAlpha(blobColor, if (isLightTheme) 0.06f else 0.18f),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(smoothTouchX, smoothTouchY, blobR, touchPaint)

            // Second smaller, hue-shifted inner glow for depth
            val innerColor = shiftHue(baseColor2, hueCycle + 90f)
            val innerR = blobR * 0.42f
            touchPaint.shader = RadialGradient(
                smoothTouchX, smoothTouchY, innerR,
                intArrayOf(
                    colorWithAlpha(innerColor, if (isLightTheme) 0.55f else 1.00f),
                    colorWithAlpha(innerColor, if (isLightTheme) 0.20f else 0.50f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(smoothTouchX, smoothTouchY, innerR, touchPaint)
        }

        // 3. Subtle scrim for text legibility
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        super.onDraw(canvas)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun blendColors(c1: Int, c2: Int): Int {
        return Color.rgb(
            ((Color.red(c1) + Color.red(c2)) / 2).coerceIn(0, 255),
            ((Color.green(c1) + Color.green(c2)) / 2).coerceIn(0, 255),
            ((Color.blue(c1) + Color.blue(c2)) / 2).coerceIn(0, 255)
        )
    }

    private fun shiftHue(color: Int, degreeOffset: Float): Int {
        Color.colorToHSV(color, hsvBuffer)
        var newHue = (hsvBuffer[0] + degreeOffset) % 360f
        if (newHue < 0f) newHue += 360f
        hsvBuffer[0] = newHue
        hsvBuffer[1] = if (isLightTheme) hsvBuffer[1].coerceIn(0.45f, 0.75f)
                       else              hsvBuffer[1].coerceAtLeast(0.80f)
        hsvBuffer[2] = if (isLightTheme) hsvBuffer[2].coerceIn(0.55f, 0.82f)
                       else              hsvBuffer[2].coerceAtLeast(0.90f)
        return Color.HSVToColor(hsvBuffer)
    }

    private fun colorWithAlpha(color: Int, alphaFloat: Float): Int {
        val alpha = (alphaFloat * (if (isLightTheme) 0.35f else 1f) * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (motionAnimator?.isRunning != true) startMotionAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionAnimator?.cancel()
        colorAnimator?.cancel()
    }
}
