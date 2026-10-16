package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
 * - Autonomous multi-harmonic fluid vortices sweeping, rotating, and billowing across the entire canvas
 * - Multi-bezier curling smoke tendrils and plasma filaments flowing along sides, corners, and behind the glass card
 * - Real-time touch & cursor follow: dragging or touching injects dynamic fluid splats with
 *   momentum, velocity dissipation, curl rotation, and vibrant neon bloom trailing
 * - Additive / Screen blending for luminous plasma cores and saturated electric edges
 * - Continuous 360° neon hue evolution (cyan, magenta, electric purple, neon blue) blended with album artwork palette
 * - Deep midnight obsidian base for crystal-clear readability of lyrics and player controls
 */
class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val isLightTheme = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    // Deep midnight obsidian base in dark mode; soft sleek porcelain in light mode
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#E8EAF2" else "#060714")
        style = Paint.Style.FILL
    }

    // Frosted dark scrim overlay for crisp lyrics & control readability without killing fluid bloom
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isLightTheme) "#14FFFFFF" else "#1A050714")
        style = Paint.Style.FILL
    }

    // ── Fluid Simulation Paints ──────────────────────────────────────────────
    // Additive screen blend mode makes overlapping fluid colors ignite into brilliant bloom in dark mode.
    // In light mode, normal SRC_OVER (null xfermode) ensures rich, vibrant colors blend smoothly without bleaching to white.
    private val screenBlend = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    private val fluidPaints = Array(8) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = if (isLightTheme) null else screenBlend
        }
    }

    // 6 Curling fluid smoke tendrils weaving along edges and corners
    private val tendrilPaints = Array(6) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            xfermode = if (isLightTheme) null else screenBlend
        }
    }
    private val tendrilPaths = Array(6) { Path() }

    private val touchSplatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = if (isLightTheme) null else screenBlend
    }

    private val gradientStops = floatArrayOf(0f, 0.25f, 0.65f, 1f)
    private val hsvBuffer = FloatArray(3)

    // Base album-art palette (defaults to electric cyan, violet, and neon magenta)
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

    // ── Spreading Ambient Particle Sparks System ─────────────────────────────
    private class AmbientParticle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var alpha = 0f
        var phase = 0f
        var hueOffset = 0f
        var active = false
    }

    private val maxParticles = 36
    private val particles = Array(maxParticles) { AmbientParticle() }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = if (isLightTheme) null else screenBlend
    }

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
        if (event.pointerCount <= 0) return
        val action = event.actionMasked
        val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
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
        // A touch injects energy into the same hue that is currently filling the
        // background. This keeps the scene intentionally monochromatic until the
        // slow colour cycle advances.
        val splatColor = currentFluidColor()

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

        // Update spreading ambient particles
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val density = resources.displayMetrics.density

        for (i in 0 until maxParticles) {
            val p = particles[i]
            if (!p.active || p.x < -80f || p.x > w + 80f || p.y < -80f || p.y > h + 80f) {
                p.x = (Math.random() * w).toFloat()
                p.y = (Math.random() * h).toFloat()
                val speed = (1.2f + Math.random().toFloat() * 2.2f) * density
                val angle = (Math.random() * Math.PI * 2).toFloat()
                p.vx = cos(angle) * speed
                p.vy = sin(angle) * speed
                p.radius = (14f + Math.random().toFloat() * 32f) * density
                p.alpha = 0.25f + Math.random().toFloat() * 0.50f
                p.phase = (Math.random() * Math.PI * 2).toFloat()
                p.hueOffset = (Math.random().toFloat() * 60f - 30f)
                p.active = true
            }

            p.x += p.vx
            p.y += p.vy
            p.vx += (sin(animTime * 0.0025f + p.phase) * 0.08f).toFloat()
            p.vy += (cos(animTime * 0.0025f + p.phase) * 0.08f).toFloat()
        }
    }

    // ── Rendering Engine ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawFluidField(canvas, w, h)
        super.onDraw(canvas)
    }

    private fun drawFluidField(canvas: Canvas, w: Float, h: Float) {
        // 1. Solid deep background base
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // 2. Fast-paced, turbulent motion time axis
        val t = animTime * 0.0038f

        // 3. Smooth color transition cycle: strictly every 3 seconds (3000ms)
        val cycleMs = 3000f
        val currentCycle = animTime / cycleMs
        val currentBand = kotlin.math.floor(currentCycle).toInt()
        val nextBand = currentBand + 1
        val fraction = (currentCycle - currentBand).coerceIn(0f, 1f)

        val colorFrom = shiftHue(baseColor1, (currentBand * 47f) % 360f)
        val colorTo = shiftHue(baseColor1, (nextBand * 47f) % 360f)
        val activeColor = argbEvaluator.evaluate(fraction, colorFrom, colorTo) as Int

        val secondaryColorFrom = shiftHue(baseColor2, (currentBand * 47f + 60f) % 360f)
        val secondaryColorTo = shiftHue(baseColor2, (nextBand * 47f + 60f) % 360f)
        val secondaryColor = argbEvaluator.evaluate(fraction, secondaryColorFrom, secondaryColorTo) as Int

        val tertiaryColorFrom = shiftHue(baseColor3, (currentBand * 47f + 120f) % 360f)
        val tertiaryColorTo = shiftHue(baseColor3, (nextBand * 47f + 120f) % 360f)
        val tertiaryColor = argbEvaluator.evaluate(fraction, tertiaryColorFrom, tertiaryColorTo) as Int

        val alpha = if (isLightTheme) 0.44f else 0.76f
        val radius = (w.coerceAtLeast(h) * 0.85f).coerceAtLeast(540f)

        // ── 4. Broad, Fast-Paced Liquid Lightning Blobs (Sweeping Motion) ─────
        drawFluidBlob(canvas, w * (0.22f + 0.32f * sin(t * 1.5f)), h * (0.24f + 0.28f * cos(t * 1.3f)), radius * 1.05f, 1.35f, 0.75f, t * 35f, activeColor, alpha, 0)
        drawFluidBlob(canvas, w * (0.78f + 0.30f * cos(t * 1.4f + 1.2f)), h * (0.38f + 0.28f * sin(t * 1.6f)), radius * 0.95f, 0.85f, 1.35f, -t * 30f, secondaryColor, alpha * 0.90f, 1)
        drawFluidBlob(canvas, w * (0.42f + 0.35f * sin(t * 1.2f + 2.5f)), h * (0.80f + 0.24f * cos(t * 1.4f)), radius * 1.10f, 1.40f, 0.72f, t * 25f, tertiaryColor, alpha * 0.85f, 2)
        drawFluidBlob(canvas, w * (0.62f + 0.34f * cos(t * 1.6f + 3.8f)), h * (0.16f + 0.26f * sin(t * 1.5f)), radius * 0.88f, 0.90f, 1.25f, -t * 40f, activeColor, alpha * 0.80f, 3)
        drawFluidBlob(canvas, w * (0.16f + 0.26f * cos(t * 1.8f + 0.8f)), h * (0.68f + 0.30f * sin(t * 1.3f)), radius * 0.92f, 1.25f, 0.78f, t * 45f, secondaryColor, alpha * 0.78f, 4)
        drawFluidBlob(canvas, w * (0.50f + 0.26f * sin(t * 2.0f + 1.2f)), h * (0.50f + 0.26f * cos(t * 1.8f)), radius * 1.15f, 1.20f, 1.10f, t * 20f, tertiaryColor, alpha * 0.72f, 5)

        // ── 5. Spreading Particle Effects (Diffusing Bokeh Sparks) ────────────
        for (i in 0 until maxParticles) {
            val p = particles[i]
            if (!p.active) continue
            val pulse = 0.65f + 0.35f * sin(t * 2.5f + p.phase)
            val r = p.radius * pulse
            val pAlpha = p.alpha * pulse * (if (isLightTheme) 0.50f else 0.85f)
            if (pAlpha <= 0.01f || r <= 0f) continue

            val pColor = shiftHue(activeColor, p.hueOffset)
            val centerColor = if (isLightTheme) pColor else Color.WHITE

            particlePaint.shader = RadialGradient(
                p.x, p.y, r,
                intArrayOf(
                    colorWithAlpha(centerColor, pAlpha),
                    colorWithAlpha(pColor, pAlpha * 0.60f),
                    colorWithAlpha(pColor, pAlpha * 0.20f),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(p.x, p.y, r, particlePaint)
        }

        // ── 6. Interactive Touch Splats ───────────────────────────────────────
        for (i in 0 until maxSplats) {
            val splat = splats[i]
            if (!splat.active || splat.alpha <= 0.01f) continue

            val splatR = splat.radius
            val splatCenter = if (isLightTheme) splat.color else Color.WHITE
            touchSplatPaint.shader = RadialGradient(
                splat.x, splat.y, splatR,
                intArrayOf(
                    colorWithAlpha(splatCenter, splat.alpha * (if (isLightTheme) 0.70f else 0.95f)),
                    colorWithAlpha(splat.color, splat.alpha * (if (isLightTheme) 0.55f else 0.85f)),
                    colorWithAlpha(splat.color, splat.alpha * (if (isLightTheme) 0.22f else 0.40f)),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(splat.x, splat.y, splatR, touchSplatPaint)
        }

        // ── 7. Scrim Overlay ──────────────────────────────────────────────────
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
    }

    private fun drawFluidBlob(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        scaleX: Float,
        scaleY: Float,
        angle: Float,
        color: Int,
        alpha: Float,
        paintIndex: Int
    ) {
        if (radius <= 0f) return
        val paint = fluidPaints[paintIndex % fluidPaints.size]
        val centerColor = if (isLightTheme) color else Color.WHITE
        val centerAlpha = if (isLightTheme) alpha * 0.80f else alpha * 0.45f
        val bodyAlpha = if (isLightTheme) alpha * 0.58f else alpha * 0.90f
        val edgeAlpha = if (isLightTheme) alpha * 0.22f else alpha * 0.28f

        paint.shader = RadialGradient(
            0f,
            0f,
            radius,
            intArrayOf(
                colorWithAlpha(centerColor, centerAlpha),
                colorWithAlpha(color, bodyAlpha),
                colorWithAlpha(color, edgeAlpha),
                Color.TRANSPARENT
            ),
            gradientStops,
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        canvas.scale(scaleX, scaleY)
        canvas.drawCircle(0f, 0f, radius, paint)
        canvas.restore()
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
        hsvBuffer[1] = if (isLightTheme) hsvBuffer[1].coerceIn(0.65f, 0.95f)
                       else              hsvBuffer[1].coerceAtLeast(0.85f)
        hsvBuffer[2] = if (isLightTheme) hsvBuffer[2].coerceIn(0.70f, 0.95f)
                       else              hsvBuffer[2].coerceAtLeast(0.95f)
        return Color.HSVToColor(hsvBuffer)
    }

    private fun currentFluidColor(): Int {
        val cycleMs = 3000f
        val currentCycle = animTime / cycleMs
        val currentBand = kotlin.math.floor(currentCycle).toInt()
        val nextBand = currentBand + 1
        val fraction = (currentCycle - currentBand).coerceIn(0f, 1f)

        val colorFrom = shiftHue(baseColor1, (currentBand * 47f) % 360f)
        val colorTo = shiftHue(baseColor1, (nextBand * 47f) % 360f)
        return argbEvaluator.evaluate(fraction, colorFrom, colorTo) as Int
    }

    private fun colorWithAlpha(color: Int, alphaFloat: Float): Int {
        val alpha = (alphaFloat * 255).toInt().coerceIn(0, 255)
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
