package com.ultradeep.music.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
                        val rvHeight = recyclerView.height
                        val centerOffset = (rvHeight / 2) - 80
                        layoutManager.scrollToPositionWithOffset(activeIndex, centerOffset)
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
        holder.bind(line, position == activeIndex)
    }

    override fun getItemCount(): Int = lines.size

    inner class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)

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

        fun bind(line: LyricLine, isActive: Boolean) {
            tvLine.text = line.text
            if (isActive) {
                // Spotify Active Lyric: High-contrast pure white, bold sans-serif, increased font (+2sp)
                tvLine.setTextColor(Color.WHITE)
                tvLine.textSize = 26f
                tvLine.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
                tvLine.alpha = 1.0f
            } else {
                // Spotify Inactive Lyric: Muted slate-cyan, semi-bold (+2sp)
                tvLine.setTextColor(Color.parseColor("#7D9B9B"))
                tvLine.textSize = 20f
                tvLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                tvLine.alpha = 0.65f
            }
        }
    }
}
