package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import com.ultradeep.music.R
import com.ultradeep.music.util.AmbientGradientColors

class DynamicAmbientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9908080C")
    }

    private var currentTopColor = Color.parseColor("#3D1263")
    private var currentCenterColor = Color.parseColor("#140826")
    private var currentBottomColor = Color.parseColor("#08080C")

    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    private val customBackgroundImageView: ImageView

    init {
        setWillNotDraw(false)

        customBackgroundImageView = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.35f
        }
        addView(customBackgroundImageView)
    }

    fun setAmbientColors(colors: AmbientGradientColors, animate: Boolean = true) {
        if (!animate) {
            currentTopColor = colors.topColor
            currentCenterColor = colors.centerColor
            currentBottomColor = colors.bottomColor
            updateShader()
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val startTop = currentTopColor
        val startCenter = currentCenterColor
        val startBottom = currentBottomColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                currentTopColor = argbEvaluator.evaluate(fraction, startTop, colors.topColor) as Int
                currentCenterColor = argbEvaluator.evaluate(fraction, startCenter, colors.centerColor) as Int
                currentBottomColor = argbEvaluator.evaluate(fraction, startBottom, colors.bottomColor) as Int
                updateShader()
                invalidate()
            }
            start()
        }
    }

    fun setCustomBackgroundImage(uri: Uri?) {
        if (uri != null) {
            customBackgroundImageView.visibility = VISIBLE
            customBackgroundImageView.setImageURI(uri)
        } else {
            customBackgroundImageView.visibility = GONE
            customBackgroundImageView.setImageDrawable(null)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    private fun updateShader() {
        if (width <= 0 || height <= 0) return
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(currentTopColor, currentCenterColor, currentBottomColor),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
        super.onDraw(canvas)
        // Scrim overlay to ensure high readability of text and controls
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }
}
