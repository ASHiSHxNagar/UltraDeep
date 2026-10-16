package com.ultradeep.music.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.DisplayMetrics
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

    private data class StyleConfig(val alpha: Float, val scale: Float)

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

            // Update visible view holders in-place without triggering full layout passes or crashing
            updateHolderVisually(recyclerView, oldIndex)
            updateHolderVisually(recyclerView, activeIndex)
            if (oldIndex in lines.indices) {
                updateHolderVisually(recyclerView, oldIndex - 1)
                updateHolderVisually(recyclerView, oldIndex + 1)
            }
            if (activeIndex in lines.indices) {
                updateHolderVisually(recyclerView, activeIndex - 1)
                updateHolderVisually(recyclerView, activeIndex + 1)
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

    private fun updateHolderVisually(recyclerView: RecyclerView, index: Int) {
        if (index !in lines.indices) return
        if (!recyclerView.isAttachedToWindow || recyclerView.isComputingLayout) return
        val holder = recyclerView.findViewHolderForAdapterPosition(index) as? LyricViewHolder
        if (holder != null) {
            val line = lines[index]
            val distance = if (activeIndex >= 0) kotlin.math.abs(index - activeIndex) else Int.MAX_VALUE
            holder.bind(line, isActive = (index == activeIndex), distanceToActive = distance, animate = true)
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

        fun bind(line: LyricLine, isActive: Boolean, distanceToActive: Int, animate: Boolean) {
            tvLine.text = line.text
            tvLine.typeface = getTypeface(itemView.context)
            tvLine.setTextColor(Color.WHITE)
            tvLine.letterSpacing = -0.02f
            tvLine.pivotX = 0f
            tvLine.pivotY = tvLine.height.toFloat() / 2f

            if (isActive) {
                // ── Spotify Active Line: Pure solid white, scale pop-in ─────────────
                if (animate && !wasActive) {
                    tvLine.animate().cancel()
                    tvLine.scaleX = 0.98f
                    tvLine.scaleY = 0.98f
                    tvLine.alpha = 0.85f
                    tvLine.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .alpha(1.0f)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .setDuration(240)
                        .start()
                } else {
                    tvLine.scaleX = 1.08f
                    tvLine.scaleY = 1.08f
                    tvLine.alpha = 1.0f
                }
            } else {
                // ── Spotify Inactive Lines: Dimmed bold white with proximity fade ────
                val style = when (distanceToActive) {
                    1    -> StyleConfig(alpha = 0.62f, scale = 0.98f)
                    2    -> StyleConfig(alpha = 0.45f, scale = 0.94f)
                    else -> StyleConfig(alpha = 0.28f, scale = 0.90f)
                }

                if (animate && wasActive) {
                    tvLine.animate().cancel()
                    tvLine.animate()
                        .scaleX(style.scale)
                        .scaleY(style.scale)
                        .alpha(style.alpha)
                        .setDuration(200)
                        .start()
                } else {
                    tvLine.scaleX = style.scale
                    tvLine.scaleY = style.scale
                    tvLine.alpha = style.alpha
                }
            }
            wasActive = isActive
        }
    }
}
