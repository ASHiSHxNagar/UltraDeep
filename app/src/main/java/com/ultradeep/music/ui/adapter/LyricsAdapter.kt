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
                        // Post until RecyclerView has measured its padded lyric viewport.
                        // This preserves the centre position even after a large seek near
                        // the final line, where the old immediate jump could land at end.
                        recyclerView.post {
                            val centerOffset = (recyclerView.height / 2) - (28 * recyclerView.resources.displayMetrics.density).toInt()
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
                return 35f / displayMetrics.densityDpi
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
                // The active line rises into focus; the former current line shrinks away
                // on its next bind, echoing the compact Spotify-style lyric motion.
                tvLine.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                tvLine.textSize = 28f
                tvLine.typeface = Typeface.create(ResourcesCompat.getFont(itemView.context, R.font.manrope_variable), Typeface.BOLD)
                tvLine.alpha = 1.0f
                tvLine.scaleX = 0.86f
                tvLine.scaleY = 0.86f
                itemView.scaleX = 0.94f
                itemView.scaleY = 0.94f
                itemView.translationY = 14f
                tvLine.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280).start()
                itemView.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(240).start()
            } else {
                tvLine.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_inactive_lyric))
                tvLine.textSize = 22f
                tvLine.typeface = Typeface.create(ResourcesCompat.getFont(itemView.context, R.font.manrope_variable), Typeface.NORMAL)
                // The lines immediately around the active lyric provide context, so they
                // stay legible rather than fading into a dark artwork background.
                val inactiveAlpha = when (distanceToActive) {
                    1 -> 1.0f
                    2 -> 0.93f
                    else -> 0.84f
                }
                tvLine.alpha = inactiveAlpha
                tvLine.scaleX = if (wasActive) 1f else 0.90f
                tvLine.scaleY = if (wasActive) 1f else 0.90f
                if (wasActive) {
                    tvLine.animate().scaleX(0.90f).scaleY(0.90f).alpha(inactiveAlpha).setDuration(200).start()
                }
                itemView.animate().translationY(0f).scaleX(0.96f).scaleY(0.96f).setDuration(180).start()
            }
            wasActive = isActive
        }
    }
}
