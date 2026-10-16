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
    // Additive screen blend mode makes overlapping fluid colors ignite into brilliant bloom
    private val screenBlend = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    private val fluidPaints = Array(8) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = screenBlend
        }
    }

    // 6 Curling fluid smoke tendrils weaving along edges and corners
    private val tendrilPaints = Array(6) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            xfermode = screenBlend
        }
    }
    private val tendrilPaths = Array(6) { Path() }

    private val touchSplatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = screenBlend
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
    }

    // ── Rendering Engine ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // One stable colour family at a time, with broad moving bodies and
        // short-lived touch splats layered underneath the child content.
        drawFluidField(canvas, w, h)
        super.onDraw(canvas)
    }

    @Suppress("UNUSED_CODE")
    private fun drawLegacyFluidField(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Solid deep background base
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Time axis - fast paced and turbulent
        val t = animTime * 0.0055f
        val hueCycle = (animTime * 0.09f) % 360f

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
        val density = resources.displayMetrics.density

        // ── 2. Fluid Curling Smoke Tendrils ───────────────────────────────────
        // Ribbon paths undulating along left, right, and diagonals
        val tendrilConfigs = arrayOf(
            // 0: Left-side curling wisp
            Triple(0f, h * 0.90f, c[0]),
            // 1: Right-side curling wisp
            Triple(w, h * 0.85f, c[2]),
            // 2: Top-left plasma curl
            Triple(0f, h * 0.15f, c[4]),
            // 3: Top-right plasma curl
            Triple(w, h * 0.20f, c[1]),
            // 4: Bottom-center sweeping tendril
            Triple(w * 0.40f, h, c[6]),
            // 5: Upper-center swirling ribbon
            Triple(w * 0.60f, 0f, c[3])
        )

        val strokeBase = 72f * density
        tendrilConfigs.forEachIndexed { i, (startX, startY, color) ->
            val path = tendrilPaths[i]
            path.reset()
            path.moveTo(startX, startY)

            val p1x: Float
            val p1y: Float
            val p2x: Float
            val p2y: Float
            val endX: Float
            val endY: Float

            when (i) {
                0 -> { // Left edge smoke curling up and inward
                    p1x = w * (0.28f + 0.16f * sin(t * 1.6f + 0.2f))
                    p1y = h * (0.65f + 0.12f * cos(t * 1.3f))
                    p2x = w * (0.08f + 0.18f * cos(t * 1.8f + 1.2f))
                    p2y = h * (0.40f + 0.15f * sin(t * 1.4f))
                    endX = w * (0.22f + 0.14f * sin(t * 1.2f + 2.5f))
                    endY = h * 0.10f
                }
                1 -> { // Right edge smoke curling up and inward
                    p1x = w * (0.72f - 0.16f * cos(t * 1.5f + 0.8f))
                    p1y = h * (0.60f + 0.14f * sin(t * 1.4f))
                    p2x = w * (0.92f - 0.18f * sin(t * 1.7f + 1.6f))
                    p2y = h * (0.35f + 0.12f * cos(t * 1.3f))
                    endX = w * (0.78f - 0.14f * cos(t * 1.1f + 3.1f))
                    endY = h * 0.08f
                }
                2 -> { // Top-left curl across toward center
                    p1x = w * (0.25f + 0.15f * sin(t * 1.4f + 1.8f))
                    p1y = h * (0.18f + 0.10f * cos(t * 1.6f))
                    p2x = w * (0.45f + 0.12f * cos(t * 1.3f + 0.4f))
                    p2y = h * (0.32f + 0.12f * sin(t * 1.5f))
                    endX = w * 0.65f
                    endY = h * 0.22f
                }
                3 -> { // Top-right curl across toward center
                    p1x = w * (0.75f - 0.15f * cos(t * 1.5f + 2.2f))
                    p1y = h * (0.20f + 0.10f * sin(t * 1.7f))
                    p2x = w * (0.55f - 0.12f * sin(t * 1.2f + 1.0f))
                    p2y = h * (0.36f + 0.12f * cos(t * 1.4f))
                    endX = w * 0.35f
                    endY = h * 0.28f
                }
                4 -> { // Bottom center billowing upward
                    p1x = w * (0.35f + 0.20f * sin(t * 1.3f + 3.5f))
                    p1y = h * (0.80f - 0.10f * cos(t * 1.5f))
                    p2x = w * (0.65f - 0.20f * cos(t * 1.4f + 2.0f))
                    p2y = h * (0.65f - 0.12f * sin(t * 1.6f))
                    endX = w * 0.50f
                    endY = h * 0.48f
                }
                else -> { // Center diagonal vortex ribbon
                    p1x = w * (0.55f + 0.22f * cos(t * 1.2f + 4.2f))
                    p1y = h * (0.30f + 0.14f * sin(t * 1.5f))
                    p2x = w * (0.45f - 0.22f * sin(t * 1.4f + 1.5f))
                    p2y = h * (0.60f + 0.14f * cos(t * 1.3f))
                    endX = w * 0.70f
                    endY = h * 0.85f
                }
            }

            path.cubicTo(p1x, p1y, p2x, p2y, endX, endY)

            val paint = tendrilPaints[i]
            paint.strokeWidth = strokeBase * (0.85f + 0.30f * sin(t * 1.2f + i.toFloat()))
            paint.shader = LinearGradient(
                startX, startY, endX, endY,
                intArrayOf(
                    colorWithAlpha(color, if (isLightTheme) 0.24f else 0.65f),
                    colorWithAlpha(shiftHue(color, 45f), if (isLightTheme) 0.32f else 0.80f),
                    colorWithAlpha(shiftHue(color, 90f), if (isLightTheme) 0.12f else 0.40f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, paint)
        }

        // ── 3. Rotating & Stretching Elliptical Vortices ───────────────────────
        data class Vortex(
            val cx: Float,
            val cy: Float,
            val r: Float,
            val angle: Float,
            val scaleX: Float,
            val scaleY: Float,
            val alpha: Float
        )

        val vortices = arrayOf(
            // 0: Figure-eight high-velocity plasma stream
            Vortex(
                cx = w * (0.50f + 0.50f * sin(t * 1.55f)),
                cy = h * (0.50f + 0.48f * sin(t * 3.10f + 0.4f)),
                r = (minDim * 0.95f).coerceAtLeast(460f),
                angle = (t * 45f) % 360f,
                scaleX = 1.35f + 0.25f * sin(t * 1.8f),
                scaleY = 0.75f + 0.20f * cos(t * 1.5f),
                alpha = if (isLightTheme) 0.32f else 0.85f
            ),
            // 1: Top-right sweeping vortex with orbital curl
            Vortex(
                cx = w * (0.52f + 0.52f * cos(t * 1.35f + 1.2f)),
                cy = h * (0.48f + 0.52f * sin(t * 1.45f + 0.8f)),
                r = (minDim * 0.90f).coerceAtLeast(440f),
                angle = (-t * 38f + 45f) % 360f,
                scaleX = 1.25f + 0.20f * cos(t * 1.4f),
                scaleY = 0.80f + 0.20f * sin(t * 1.6f),
                alpha = if (isLightTheme) 0.30f else 0.80f
            ),
            // 2: Golden-ratio whirlpool
            Vortex(
                cx = w * (0.50f + 0.48f * sin(t * 1.85f + 2.5f) * cos(t * 0.7f)),
                cy = h * (0.50f + 0.48f * cos(t * 1.85f + 2.5f)),
                r = (minDim * 1.05f).coerceAtLeast(500f),
                angle = (t * 52f + 120f) % 360f,
                scaleX = 1.40f + 0.30f * sin(t * 2.1f),
                scaleY = 0.70f + 0.15f * cos(t * 1.9f),
                alpha = if (isLightTheme) 0.28f else 0.78f
            ),
            // 3: Bottom-left energetic surging jet
            Vortex(
                cx = w * (0.48f + 0.52f * cos(t * 1.20f + 3.2f)),
                cy = h * (0.52f + 0.52f * sin(t * 1.60f + 3.8f)),
                r = (minDim * 0.88f).coerceAtLeast(420f),
                angle = (-t * 42f + 80f) % 360f,
                scaleX = 1.30f + 0.25f * cos(t * 1.7f),
                scaleY = 0.75f + 0.18f * sin(t * 1.3f),
                alpha = if (isLightTheme) 0.30f else 0.82f
            ),
            // 4: Bottom-right fluid eddy
            Vortex(
                cx = w * (0.50f + 0.52f * sin(t * 1.40f + 4.1f)),
                cy = h * (0.50f + 0.52f * cos(t * 1.15f + 2.1f)),
                r = (minDim * 0.92f).coerceAtLeast(450f),
                angle = (t * 36f + 200f) % 360f,
                scaleX = 1.20f + 0.20f * sin(t * 1.5f),
                scaleY = 0.82f + 0.20f * cos(t * 1.7f),
                alpha = if (isLightTheme) 0.28f else 0.76f
            ),
            // 5: Rapid center harmonic pulse
            Vortex(
                cx = w * (0.50f + 0.42f * sin(t * 2.40f + 0.6f)),
                cy = h * (0.50f + 0.42f * cos(t * 2.10f + 1.1f)),
                r = (minDim * 0.75f).coerceAtLeast(380f),
                angle = (-t * 60f) % 360f,
                scaleX = 1.15f + 0.25f * cos(t * 2.2f),
                scaleY = 0.85f + 0.15f * sin(t * 2.5f),
                alpha = if (isLightTheme) 0.34f else 0.90f
            ),
            // 6: Perimeter sweep river
            Vortex(
                cx = w * (0.50f + 0.55f * cos(t * 0.95f + 5.2f)),
                cy = h * (0.50f + 0.55f * sin(t * 0.85f + 1.4f)),
                r = (minDim * 1.10f).coerceAtLeast(520f),
                angle = (t * 28f + 310f) % 360f,
                scaleX = 1.45f + 0.30f * sin(t * 1.1f),
                scaleY = 0.65f + 0.18f * cos(t * 0.9f),
                alpha = if (isLightTheme) 0.24f else 0.70f
            ),
            // 7: Counter-rotating plasma turbulence
            Vortex(
                cx = w * (0.50f + 0.46f * sin(t * 2.80f + 1.8f)),
                cy = h * (0.50f + 0.46f * sin(t * 1.90f + 4.2f)),
                r = (minDim * 0.80f).coerceAtLeast(400f),
                angle = (-t * 48f + 150f) % 360f,
                scaleX = 1.28f + 0.22f * cos(t * 1.8f),
                scaleY = 0.78f + 0.18f * sin(t * 2.0f),
                alpha = if (isLightTheme) 0.26f else 0.75f
            )
        )

        // Draw autonomous fluid vortices with additive glowing screen blend and elliptical rotation
        vortices.forEachIndexed { i, vortex ->
            val color = c[i]
            fluidPaints[i].shader = RadialGradient(
                0f, 0f, vortex.r,
                intArrayOf(
                    colorWithAlpha(Color.WHITE, vortex.alpha * (if (isLightTheme) 0.20f else 0.42f)),
                    colorWithAlpha(color, vortex.alpha * 0.95f),
                    colorWithAlpha(color, vortex.alpha * 0.30f),
                    Color.TRANSPARENT
                ),
                gradientStops,
                Shader.TileMode.CLAMP
            )

            canvas.save()
            canvas.translate(vortex.cx, vortex.cy)
            canvas.rotate(vortex.angle)
            canvas.scale(vortex.scaleX, vortex.scaleY)
            canvas.drawCircle(0f, 0f, vortex.r, fluidPaints[i])
            canvas.restore()
        }

        // ── 4. Interactive Touch & Cursor Fluid Splats ────────────────────────
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

        // ── 5. Frosted Dark Scrim ─────────────────────────────────────────────
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        super.onDraw(canvas)
    }

    private fun drawFluidField(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val t = animTime * 0.00155f
        val colourBand = kotlin.math.floor(animTime / 2_400f).toInt()
        val activeColor = shiftHue(baseColor1, (colourBand * 47f) % 360f)
        val alpha = if (isLightTheme) 0.19f else 0.52f
        val radius = (w.coerceAtLeast(h) * 0.54f).coerceAtLeast(420f)

        // Slow, broad blobs make the whole screen feel like a liquid surface while
        // staying in one colour family at any moment.
        drawFluidBlob(canvas, w * (0.18f + 0.16f * sin(t * 0.72f)), h * (0.22f + 0.14f * cos(t * 0.58f)), radius, 1.25f, 0.72f, t * 18f, activeColor, alpha, 0)
        drawFluidBlob(canvas, w * (0.82f + 0.18f * cos(t * 0.61f + 1.5f)), h * (0.36f + 0.20f * sin(t * 0.74f)), radius * 0.92f, 0.95f, 1.20f, -t * 15f, activeColor, alpha * 0.86f, 1)
        drawFluidBlob(canvas, w * (0.44f + 0.28f * sin(t * 0.45f + 2.1f)), h * (0.82f + 0.16f * cos(t * 0.52f)), radius * 0.84f, 1.40f, 0.62f, t * 12f, activeColor, alpha * 0.72f, 2)
        drawFluidBlob(canvas, w * (0.64f + 0.24f * cos(t * 0.38f + 3.8f)), h * (0.12f + 0.18f * sin(t * 0.63f)), radius * 0.72f, 0.80f, 1.30f, -t * 20f, activeColor, alpha * 0.64f, 3)

        for (i in 0 until maxSplats) {
            val splat = splats[i]
            if (!splat.active || splat.alpha <= 0.01f) continue

            touchSplatPaint.shader = RadialGradient(
                splat.x,
                splat.y,
                splat.radius,
                intArrayOf(
                    colorWithAlpha(splat.color, splat.alpha * if (isLightTheme) 0.55f else 0.82f),
                    colorWithAlpha(splat.color, splat.alpha * if (isLightTheme) 0.28f else 0.52f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(splat.x, splat.y, splat.radius, touchSplatPaint)
        }

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
        val paint = fluidPaints[paintIndex]
        paint.shader = RadialGradient(
            0f,
            0f,
            radius,
            intArrayOf(
                colorWithAlpha(color, alpha),
                colorWithAlpha(color, alpha * 0.68f),
                colorWithAlpha(color, alpha * 0.24f),
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
        hsvBuffer[1] = if (isLightTheme) hsvBuffer[1].coerceIn(0.55f, 0.85f)
                       else              hsvBuffer[1].coerceAtLeast(0.85f)
        hsvBuffer[2] = if (isLightTheme) hsvBuffer[2].coerceIn(0.60f, 0.90f)
                       else              hsvBuffer[2].coerceAtLeast(0.95f)
        return Color.HSVToColor(hsvBuffer)
    }

    private fun currentFluidColor(): Int {
        val colourBand = kotlin.math.floor(animTime / 2_400f).toInt()
        return shiftHue(baseColor1, (colourBand * 47f) % 360f)
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
