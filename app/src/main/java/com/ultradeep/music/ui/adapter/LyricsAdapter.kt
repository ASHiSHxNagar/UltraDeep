package com.ultradeep.music.ui.adapter

import android.content.Context
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    // Must live at adapter scope – Kotlin forbids data classes inside inner classes
    private data class Quad(val size: Float, val alpha: Float, val scaleX: Float, val scaleY: Float)

    fun submitLines(newLines: List<LyricLine>) {
        lines.clear()
        lines.addAll(newLines)
        activeIndex = -1
        notifyDataSetChanged()
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

        // Delay timer formula: positive offsetMs delays lyrics (lyrics appear later)
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
            if (oldIndex in lines.indices) notifyItemChanged(oldIndex)
            if (activeIndex in lines.indices) notifyItemChanged(activeIndex)

            if (activeIndex in lines.indices && (!isUserScrolling || forceScroll)) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (immediate || kotlin.math.abs(newIndex - oldIndex) > 2) {
                    // Instant accurate jump to exact vertical center during seeking
                    if (layoutManager != null) {
                        recyclerView.post {
                            val centerOffset = (recyclerView.height / 2) - (32 * recyclerView.resources.displayMetrics.density).toInt()
                            layoutManager.scrollToPositionWithOffset(activeIndex, centerOffset)
                        }
                    }
                } else {
                    smoothScrollToActiveLine(recyclerView, activeIndex)
                }
            }
        }
    }

    private fun smoothScrollToActiveLine(recyclerView: RecyclerView, index: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val context = recyclerView.context

        val smoothScroller = object : LinearSmoothScroller(context) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                // Exact vertical centering of active line in the visible area
                val boxCenter = (boxStart + boxEnd) / 2
                val viewCenter = (viewStart + viewEnd) / 2
                return boxCenter - viewCenter
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 30f / displayMetrics.densityDpi
            }
        }

        smoothScroller.targetPosition = index
        layoutManager.startSmoothScroll(smoothScroller)
    }

    fun setUserScrolling(isScrolling: Boolean) {
        this.isUserScrolling = isScrolling
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lines[position]
        holder.bind(
            line = line,
            isActive = position == activeIndex,
            distanceToActive = if (activeIndex >= 0) kotlin.math.abs(position - activeIndex) else Int.MAX_VALUE
        )
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

        fun bind(line: LyricLine, isActive: Boolean, distanceToActive: Int) {
            tvLine.text = line.text
            tvLine.animate().cancel()
            itemView.animate().cancel()

            if (isActive) {
                // ── Active line: Spotify-style large bold white ──────────────
                tvLine.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                tvLine.textSize = 30f
                tvLine.typeface = Typeface.create(
                    ResourcesCompat.getFont(itemView.context, R.font.manrope_variable),
                    Typeface.BOLD
                )
                tvLine.alpha = 1.0f
                // Pop-in scale: start slightly small & grow to full
                if (!wasActive) {
                    tvLine.scaleX = 0.88f
                    tvLine.scaleY = 0.88f
                    itemView.translationY = 10f
                }
                tvLine.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(260)
                    .start()
                itemView.animate()
                    .translationY(0f)
                    .setDuration(220)
                    .start()

            } else {
                // ── Inactive lines: muted, smaller, italic on extreme distance ─
                tvLine.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_inactive_lyric))

                // Scale text and alpha progressively away from active line
                val (textSizeSp, targetAlpha, targetScaleX, targetScaleY) = when (distanceToActive) {
                    1    -> Quad(26f, 0.90f, 0.96f, 0.96f)
                    2    -> Quad(24f, 0.72f, 0.92f, 0.92f)
                    3    -> Quad(22f, 0.52f, 0.88f, 0.88f)
                    else -> Quad(20f, 0.36f, 0.84f, 0.84f)
                }

                tvLine.textSize = textSizeSp
                tvLine.typeface = Typeface.create(
                    ResourcesCompat.getFont(itemView.context, R.font.manrope_variable),
                    Typeface.NORMAL
                )

                if (wasActive) {
                    // Animate out from active state
                    tvLine.animate()
                        .scaleX(targetScaleX)
                        .scaleY(targetScaleY)
                        .alpha(targetAlpha)
                        .setDuration(220)
                        .start()
                } else {
                    tvLine.scaleX = targetScaleX
                    tvLine.scaleY = targetScaleY
                    tvLine.alpha = targetAlpha
                }
                itemView.animate().translationY(0f).setDuration(180).start()
            }
            wasActive = isActive
        }

    }
}
