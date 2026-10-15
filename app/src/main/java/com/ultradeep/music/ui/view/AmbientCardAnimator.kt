package com.ultradeep.music.ui.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator

/** Animated, artwork-coloured glow for a small, currently-playing surface. */
class AmbientCardAnimator(private val view: View) {
    private val evaluator = ArgbEvaluator()
    private var animator: ValueAnimator? = null

    fun setColors(primary: Int, secondary: Int) {
        animator?.cancel()
        val first = withAlpha(primary, 92)
        val second = withAlpha(secondary, 72)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val start = evaluator.evaluate(progress, first, second) as Int
                val end = evaluator.evaluate(progress, second, first) as Int
                view.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(start, Color.rgb(16, 16, 22), end)
                ).apply { cornerRadius = 24f * view.resources.displayMetrics.density }
            }
            start()
        }
    }

    fun release() { animator?.cancel() }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
