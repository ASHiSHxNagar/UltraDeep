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

    fun updatePosition(currentPositionMs: Long, recyclerView: RecyclerView, offsetMs: Long = 0L) {
        if (lines.isEmpty()) return

        val effectivePos = (currentPositionMs + offsetMs).coerceAtLeast(0L)
        var newIndex = -1
        for (i in lines.indices) {
            val line = lines[i]
            val nextLine = lines.getOrNull(i + 1)
            if (line.timestampMs <= effectivePos && (nextLine == null || nextLine.timestampMs > effectivePos)) {
                newIndex = i
                break
            }
        }

        if (newIndex != activeIndex) {
            val oldIndex = activeIndex
            activeIndex = newIndex
            if (oldIndex in lines.indices) notifyItemChanged(oldIndex)
            if (activeIndex in lines.indices) notifyItemChanged(activeIndex)

            if (activeIndex in lines.indices && !isUserScrolling) {
                smoothScrollToActiveLine(recyclerView, activeIndex)
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
                // Center around 70% of screen height
                val targetCenter = (boxEnd - boxStart) * 0.35f
                val viewCenter = (viewStart + viewEnd) / 2f
                return (targetCenter - viewCenter).toInt()
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return 40f / displayMetrics.densityDpi
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
                    onLyricClicked(lines[pos].timestampMs)
                }
            }
        }

        fun bind(line: LyricLine, isActive: Boolean) {
            tvLine.text = line.text
            if (isActive) {
                // Spotify Active Lyric: High-contrast pure white, bold sans-serif, larger font
                tvLine.setTextColor(Color.WHITE)
                tvLine.textSize = 24f
                tvLine.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
                tvLine.alpha = 1.0f
            } else {
                // Spotify Inactive Lyric: Muted slate-cyan, semi-bold
                tvLine.setTextColor(Color.parseColor("#7D9B9B"))
                tvLine.textSize = 18f
                tvLine.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                tvLine.alpha = 0.65f
            }
        }
    }
}
