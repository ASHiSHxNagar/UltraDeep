package com.ultradeep.music.ui.adapter

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.ultradeep.music.R
import com.ultradeep.music.data.model.LyricLine

class LyricsAdapter(
    private val onLyricClicked: (Long) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    private val lines = mutableListOf<LyricLine>()
    private var activeIndex = -1
    private var isUserScrolling = false
    private var cachedTypeface: Typeface? = null

    private fun getTypeface(context: Context): Typeface {
        if (cachedTypeface == null) {
            cachedTypeface = try {
                ResourcesCompat.getFont(context, R.font.spotify_circular_bold)
            } catch (e: Exception) {
                try {
                    ResourcesCompat.getFont(context, R.font.manrope_variable)
                } catch (e2: Exception) {
                    Typeface.DEFAULT_BOLD
                }
            }
        }
        return cachedTypeface ?: Typeface.DEFAULT_BOLD
    }

    fun submitLines(newLines: List<LyricLine>, recyclerView: RecyclerView? = null) {
        val updateBlock = {
            lines.clear()
            lines.addAll(newLines)
            activeIndex = -1
            notifyDataSetChanged()
        }
        if (recyclerView != null && (recyclerView.isComputingLayout || recyclerView.isAnimating)) {
            recyclerView.post { updateBlock() }
        } else {
            updateBlock()
        }
    }

    fun getFirstLineTimestamp(): Long = lines.firstOrNull { it.timestampMs >= 0 }?.timestampMs ?: 0L

    fun updatePosition(
        currentPositionMs: Long,
        recyclerView: RecyclerView,
        offsetMs: Long = 0L,
        forceScroll: Boolean = false,
        immediate: Boolean = false
    ) {
        if (lines.isEmpty()) return
        // Do not auto-scroll if lines have no timestamps (unsynced plain lyrics)
        if (lines.firstOrNull()?.timestampMs == -1L) return
        // Position updates arrive every 100ms. A seek or a fast track change can
        // land exactly during RecyclerView layout; skip that frame and let the
        // next position update apply safely instead of touching holders mid-layout.
        if (recyclerView.isComputingLayout) return

        val effectivePos = (currentPositionMs - offsetMs).coerceAtLeast(0L)
        var newIndex = -1
        for (i in lines.indices) {
            val line = lines[i]
            val nextLine = lines.getOrNull(i + 1)
            if (line.timestampMs <= effectivePos && (nextLine == null || nextLine.timestampMs > effectivePos)) {
                newIndex = i
                break
            }
        }

        if (newIndex != activeIndex || forceScroll) {
            val oldIndex = activeIndex
            activeIndex = newIndex

            val minIdx = if (oldIndex >= 0) minOf(oldIndex, activeIndex) else activeIndex
            val maxIdx = if (oldIndex >= 0) maxOf(oldIndex, activeIndex) else activeIndex
            val range = ((minIdx - 2).coerceAtLeast(0)..(maxIdx + 2).coerceAtMost(lines.size - 1))
            for (idx in range) {
                val shouldAnimate = (idx == oldIndex || idx == activeIndex)
                updateHolderVisually(recyclerView, idx, animate = shouldAnimate)
            }

            if (activeIndex in lines.indices && (!isUserScrolling || forceScroll)) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null && !recyclerView.isComputingLayout) {
                    val density = recyclerView.resources.displayMetrics.density
                    val centerOffset = (recyclerView.height / 2) - (36 * density).toInt()

                    if (immediate || kotlin.math.abs(newIndex - oldIndex) > 2) {
                        recyclerView.post {
                            if (!recyclerView.isAttachedToWindow || recyclerView.isComputingLayout) return@post
                            val currentLayoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return@post
                            if (activeIndex in lines.indices && activeIndex < itemCount) {
                                try {
                                    currentLayoutManager.scrollToPositionWithOffset(activeIndex, centerOffset)
                                } catch (_: IllegalStateException) {
                                    // The sheet may have been dismissed between post and execution.
                                }
                            }
                        }
                    } else {
                        smoothScrollToActiveLine(recyclerView, activeIndex)
                    }
                }
            }
        }
    }

    private fun updateHolderVisually(recyclerView: RecyclerView, index: Int, animate: Boolean = true) {
        if (index !in lines.indices) return
        if (!recyclerView.isAttachedToWindow || recyclerView.isComputingLayout) return
        val holder = recyclerView.findViewHolderForAdapterPosition(index) as? LyricViewHolder
        if (holder != null) {
            val line = lines[index]
            val distance = if (activeIndex >= 0) kotlin.math.abs(index - activeIndex) else Int.MAX_VALUE
            holder.bind(line, isActive = (index == activeIndex), distanceToActive = distance, animate = animate)
        }
    }

    private fun smoothScrollToActiveLine(recyclerView: RecyclerView, index: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (index !in 0 until itemCount || recyclerView.isComputingLayout) return

        try {
            val smoothScroller = object : LinearSmoothScroller(recyclerView.context) {
                override fun calculateDtToFit(
                    viewStart: Int,
                    viewEnd: Int,
                    boxStart: Int,
                    boxEnd: Int,
                    snapPreference: Int
                ): Int {
                    val boxCenter = (boxStart + boxEnd) / 2
                    val viewCenter = (viewStart + viewEnd) / 2
                    return boxCenter - viewCenter
                }

                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                    return 32f / displayMetrics.densityDpi
                }
            }
            smoothScroller.targetPosition = index
            layoutManager.startSmoothScroll(smoothScroller)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setUserScrolling(isScrolling: Boolean) {
        this.isUserScrolling = isScrolling
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        if (position !in lines.indices) return
        val line = lines[position]
        val distance = if (activeIndex >= 0) kotlin.math.abs(position - activeIndex) else Int.MAX_VALUE
        holder.bind(line, isActive = (position == activeIndex), distanceToActive = distance, animate = false)
    }

    override fun getItemCount(): Int = lines.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)
        private var wasActive = false

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in lines.indices) {
                    val ts = lines[pos].timestampMs
                    if (ts >= 0) {
                        onLyricClicked(ts)
                    }
                }
            }
        }

        private fun formatWithWordGap(text: String): CharSequence {
            if (!text.contains(' ')) return text
            val spannable = android.text.SpannableString(text)
            for (i in text.indices) {
                if (text[i] == ' ') {
                    spannable.setSpan(
                        android.text.style.ScaleXSpan(1.45f),
                        i, i + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            return spannable
        }

        fun bind(line: LyricLine, isActive: Boolean, distanceToActive: Int, animate: Boolean) {
            val ctx = itemView.context
            val isDarkTheme = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val primaryTextColor = if (isDarkTheme) Color.WHITE else ContextCompat.getColor(ctx, R.color.text_primary)

            tvLine.text = formatWithWordGap(line.text)
            tvLine.typeface = getTypeface(ctx)
            tvLine.setTextColor(primaryTextColor)
            tvLine.letterSpacing = 0.01f
            val isUnsynced = lines.firstOrNull()?.timestampMs == -1L
            val targetTextSize = when {
                isUnsynced -> 19f
                isActive   -> 24f
                else       -> 18f
            }

            val currentSp = tvLine.textSize / ctx.resources.displayMetrics.scaledDensity
            if (kotlin.math.abs(currentSp - targetTextSize) > 0.5f) {
                tvLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetTextSize)
            }

            tvLine.pivotX = 0f
            tvLine.pivotY = 0f

            if (isActive) {
                // ── Spotify Active Line: Scale UP to 1.0f (24sp) with bright full opacity ─────────────
                if (animate && !wasActive) {
                    tvLine.animate().cancel()
                    tvLine.scaleX = 0.82f
                    tvLine.scaleY = 0.82f
                    tvLine.alpha = 0.70f
                    tvLine.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .setDuration(260)
                        .start()
                } else {
                    tvLine.scaleX = 1.0f
                    tvLine.scaleY = 1.0f
                    tvLine.alpha = 1.0f
                }
            } else {
                // ── Spotify Inactive Lines (Past & Future): Default small 18sp text with proximity fade ────
                val targetAlpha = if (isUnsynced) {
                    0.85f
                } else {
                    when (distanceToActive) {
                        1    -> if (isDarkTheme) 0.58f else 0.60f
                        2    -> if (isDarkTheme) 0.40f else 0.42f
                        else -> if (isDarkTheme) 0.24f else 0.26f
                    }
                }

                if (animate && wasActive) {
                    // Smoothly shrink down from large active size to small 18sp past text
                    tvLine.animate().cancel()
                    tvLine.scaleX = 1.25f
                    tvLine.scaleY = 1.25f
                    tvLine.alpha = 0.90f
                    tvLine.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(targetAlpha)
                        .setInterpolator(DecelerateInterpolator(1.2f))
                        .setDuration(240)
                        .start()
                } else {
                    tvLine.scaleX = 1.0f
                    tvLine.scaleY = 1.0f
                    tvLine.alpha = targetAlpha
                }
            }
            wasActive = isActive
        }
    }
}
