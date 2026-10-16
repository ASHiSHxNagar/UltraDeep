package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.ultradeep.music.util.AmbientGradientColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * State-of-the-art WebGL-style Fluid Simulation & Dynamic Ambient Background:
 * - Autonomous multi-harmonic fast-paced fluid vortices sweeping across the entire screen
 * - Real-time touch & cursor follow: dragging or touching injects dynamic fluid splats with
 *   momentum, velocity dissipation, curl rotation, and vibrant neon rainbow trailing
 * - Additive / Screen blending for luminous white-hot bloom cores and saturated electric edges
 * - Continuous 360° rainbow hue evolution blended with album artwork palette
 * - Frosted AMOLED obsidian base for crystal-clear readability of lyrics and player controls
 */
class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val isLightTheme = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    // Deep AMOLED obsidian base in dark mode; soft sleek porcelain in light mode
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#E8EAF2" else "#05050A")
        style = Paint.Style.FILL
    }

    // Frosted dark scrim overlay for text & control readability
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#18FFFFFF" else "#3305050A")
        style = Paint.Style.FILL
    }

    // ── Fluid Simulation Paints ──────────────────────────────────────────────
    // Additive screen blend mode makes overlapping fluid colors ignite into brilliant bloom
    private val screenBlend = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    private val fluidPaints = Array(8) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = screenBlend
        }
    }

    private val touchSplatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = screenBlend
    }

    private val gradientStops = floatArrayOf(0f, 0.28f, 0.65f, 1f)
    private val hsvBuffer = FloatArray(3)

    // Base album-art palette
    private var baseColor1 = Color.parseColor("#00F5D4") // Cyan
    private var baseColor2 = Color.parseColor("#7928CA") // Electric Violet
    private var baseColor3 = Color.parseColor("#FF007F") // Neon Pink

    private var targetColor1 = baseColor1
    private var targetColor2 = baseColor2
    private var targetColor3 = baseColor3

    private val argbEvaluator = ArgbEvaluator()
    private var colorAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    private var animTime = 0f

    // ── Interactive Fluid Splat System ───────────────────────────────────────
    private class FluidSplat {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var maxRadius = 0f
        var alpha = 0f
        var color = Color.WHITE
        var curlAngle = 0f
        var curlSpeed = 0f
        var active = false
    }

    private val maxSplats = 36
    private val splats = Array(maxSplats) { FluidSplat() }
    private var nextSplatIndex = 0

    // Multi-touch tracking
    private val lastTouchX = FloatArray(10) { -1f }
    private val lastTouchY = FloatArray(10) { -1f }
    private var touchColorHue = 0f

    init {
        setWillNotDraw(false)
        isClickable = false
        startMotionAnimation()
    }

    private fun startMotionAnimation() {
        motionAnimator?.cancel()
        motionAnimator = ValueAnimator.ofFloat(0f, 1_000_000f).apply {
            duration = 8_000_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val prev = animTime
                animTime = va.animatedValue as Float
                val dt = (animTime - prev).coerceIn(0f, 60f)
                updateFluidSimulation(dt)
                invalidate()
            }
            start()
        }
    }

    // ── Public Color Control ─────────────────────────────────────────────────

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
        val s1 = baseColor1
        val s2 = baseColor2
        val s3 = baseColor3
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                baseColor1 = argbEvaluator.evaluate(f, s1, targetColor1) as Int
                baseColor2 = argbEvaluator.evaluate(f, s2, targetColor2) as Int
                baseColor3 = argbEvaluator.evaluate(f, s3, targetColor3) as Int
            }
            start()
        }
    }

    // ── Touch Interception: Full-Screen Fluid Trail Interaction ──────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleFluidTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleFluidTouch(event: MotionEvent) {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (pointerId in lastTouchX.indices) {
                    lastTouchX[pointerId] = x
                    lastTouchY[pointerId] = y
                }
                // Inject an explosive fluid splat at touch origin
                spawnFluidSplat(x, y, 0f, 0f, initialRadius = 50f, targetRadius = 260f, initialAlpha = 0.95f)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid !in lastTouchX.indices) continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val prevX = lastTouchX[pid]
                    val prevY = lastTouchY[pid]

                    if (prevX >= 0f && prevY >= 0f) {
                        val dx = x - prevX
                        val dy = y - prevY
                        val dist = sqrt(dx * dx + dy * dy)

                        if (dist > 6f) {
                            val vx = dx * 0.45f
                            val vy = dy * 0.45f
                            spawnFluidSplat(
                                x = x,
                                y = y,
                                vx = vx,
                                vy = vy,
                                initialRadius = 60f + (dist * 0.35f).coerceAtMost(100f),
                                targetRadius = 220f + (dist * 0.8f).coerceAtMost(160f),
                                initialAlpha = 0.90f
                            )
                        }
                    }
                    lastTouchX[pid] = x
                    lastTouchY[pid] = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId in lastTouchX.indices) {
                    lastTouchX[pointerId] = -1f
                    lastTouchY[pointerId] = -1f
                }
            }
        }
    }

    private fun spawnFluidSplat(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        initialRadius: Float,
        targetRadius: Float,
        initialAlpha: Float
    ) {
        touchColorHue = (touchColorHue + 22f) % 360f
        val splatColor = shiftHue(baseColor1, touchColorHue)

        val splat = splats[nextSplatIndex]
        splat.x = x
        splat.y = y
        splat.vx = vx
        splat.vy = vy
        splat.radius = initialRadius
        splat.maxRadius = targetRadius
        splat.alpha = initialAlpha
        splat.color = splatColor
        splat.curlAngle = (nextSplatIndex * 45f)
        splat.curlSpeed = if (nextSplatIndex % 2 == 0) 0.08f else -0.08f
        splat.active = true

        nextSplatIndex = (nextSplatIndex + 1) % maxSplats
    }

    private fun updateFluidSimulation(dt: Float) {
        // Update all active interactive fluid splats
        for (i in 0 until maxSplats) {
            val splat = splats[i]
            if (!splat.active) continue

            // Momentum & fluid drag damping
            splat.x += splat.vx
            splat.y += splat.vy
            splat.vx *= 0.93f
            splat.vy *= 0.93f

            // Fluid expansion & diffusion
            splat.radius += (splat.maxRadius - splat.radius) * 0.07f
            splat.curlAngle += splat.curlSpeed
            splat.alpha *= 0.945f

            if (splat.alpha < 0.02f) {
                splat.active = false
            }
        }
    }

    // ── Rendering Engine ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Solid deep background base
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Time axis - fast paced and turbulent
        val t = animTime * 0.0055f
        val hueCycle = (animTime * 0.08f) % 360f

        // 8 Vivid evolving fluid colors derived from track palette & high-energy spectrum
        val c = arrayOf(
            shiftHue(baseColor1, hueCycle),
            shiftHue(baseColor2, hueCycle + 45f),
            shiftHue(baseColor3, hueCycle + 90f),
            shiftHue(baseColor1, hueCycle + 135f),
            shiftHue(baseColor2, hueCycle + 180f),
            shiftHue(baseColor3, hueCycle + 225f),
            shiftHue(baseColor1, hueCycle + 270f),
            shiftHue(baseColor2, hueCycle + 315f)
        )

        val minDim = w.coerceAtLeast(h)

        // 8 Fast, multi-harmonic fluid vortices swirling across the screen
        data class Vortex(val cx: Float, val cy: Float, val r: Float, val alpha: Float)

        val vortices = arrayOf(
            // 0: Figure-eight high-velocity plasma stream
            Vortex(
                w * (0.50f + 0.50f * sin(t * 1.55f)),
                h * (0.50f + 0.48f * sin(t * 3.10f + 0.4f)),
                (minDim * 1.10f).coerceAtLeast(520f),
                if (isLightTheme) 0.32f else 0.85f
            ),
            // 1: Top-right sweeping vortex with orbital curl
            Vortex(
                w * (0.52f + 0.52f * cos(t * 1.35f + 1.2f)),
                h * (0.48f + 0.52f * sin(t * 1.45f + 0.8f)),
                (minDim * 1.05f).coerceAtLeast(500f),
                if (isLightTheme) 0.30f else 0.80f
            ),
            // 2: Golden-ratio whirlpool
            Vortex(
                w * (0.50f + 0.48f * sin(t * 1.85f + 2.5f) * cos(t * 0.7f)),
                h * (0.50f + 0.48f * cos(t * 1.85f + 2.5f)),
                (minDim * 1.20f).coerceAtLeast(560f),
                if (isLightTheme) 0.28f else 0.78f
            ),
            // 3: Bottom-left energetic surging jet
            Vortex(
                w * (0.48f + 0.52f * cos(t * 1.20f + 3.2f)),
                h * (0.52f + 0.52f * sin(t * 1.60f + 3.8f)),
                (minDim * 1.00f).coerceAtLeast(480f),
                if (isLightTheme) 0.30f else 0.82f
            ),
            // 4: Bottom-right fluid eddy
            Vortex(
                w * (0.50f + 0.52f * sin(t * 1.40f + 4.1f)),
                h * (0.50f + 0.52f * cos(t * 1.15f + 2.1f)),
                (minDim * 1.05f).coerceAtLeast(500f),
                if (isLightTheme) 0.28f else 0.76f
            ),
            // 5: Rapid center harmonic pulse
            Vortex(
                w * (0.50f + 0.42f * sin(t * 2.40f + 0.6f)),
                h * (0.50f + 0.42f * cos(t * 2.10f + 1.1f)),
                (minDim * 0.85f).coerceAtLeast(420f),
                if (isLightTheme) 0.34f else 0.90f
            ),
            // 6: Perimeter sweep river
            Vortex(
                w * (0.50f + 0.55f * cos(t * 0.95f + 5.2f)),
                h * (0.50f + 0.55f * sin(t * 0.85f + 1.4f)),
                (minDim * 1.25f).coerceAtLeast(580f),
                if (isLightTheme) 0.24f else 0.70f
            ),
            // 7: Counter-rotating plasma turbulence
            Vortex(
                w * (0.50f + 0.46f * sin(t * 2.80f + 1.8f)),
                h * (0.50f + 0.46f * sin(t * 1.90f + 4.2f)),
                (minDim * 0.90f).coerceAtLeast(440f),
                if (isLightTheme) 0.26f else 0.75f
            )
        )

        // Draw autonomous fluid vortices with additive glowing screen blend
        vortices.forEachIndexed { i, vortex ->
            val color = c[i]
            fluidPaints[i].shader = RadialGradient(
                vortex.cx, vortex.cy, vortex.r,
                intArrayOf(
                    colorWithAlpha(color, vortex.alpha * 1.00f),
                    colorWithAlpha(color, vortex.alpha * 0.60f),
                    colorWithAlpha(color, vortex.alpha * 0.20f),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(vortex.cx, vortex.cy, vortex.r, fluidPaints[i])
        }

        // 2. Draw Interactive Touch & Cursor Fluid Splats
        for (i in 0 until maxSplats) {
            val splat = splats[i]
            if (!splat.active || splat.alpha <= 0.01f) continue

            val splatR = splat.radius
            touchSplatPaint.shader = RadialGradient(
                splat.x, splat.y, splatR,
                intArrayOf(
                    colorWithAlpha(Color.WHITE, splat.alpha * (if (isLightTheme) 0.6f else 0.95f)),
                    colorWithAlpha(splat.color, splat.alpha * (if (isLightTheme) 0.5f else 0.85f)),
                    colorWithAlpha(splat.color, splat.alpha * (if (isLightTheme) 0.2f else 0.40f)),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(splat.x, splat.y, splatR, touchSplatPaint)
        }

        // 3. Subtle frosted dark scrim overlay for crisp text readability
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        super.onDraw(canvas)
    }

    // ── Color Utilities ──────────────────────────────────────────────────────

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
        hsvBuffer[1] = if (isLightTheme) hsvBuffer[1].coerceIn(0.55f, 0.85f)
                       else              hsvBuffer[1].coerceAtLeast(0.85f)
        hsvBuffer[2] = if (isLightTheme) hsvBuffer[2].coerceIn(0.60f, 0.90f)
                       else              hsvBuffer[2].coerceAtLeast(0.95f)
        return Color.HSVToColor(hsvBuffer)
    }

    private fun colorWithAlpha(color: Int, alphaFloat: Float): Int {
        val alpha = (alphaFloat * (if (isLightTheme) 0.45f else 1.0f) * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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
