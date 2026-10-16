package com.ultradeep.music.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.ultradeep.music.R
import com.ultradeep.music.data.model.Song

class SongAdapter(
    private val onSongClicked: (Song, List<Song>) -> Unit,
    private val onSongMoreClicked: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()
    private var activeSongId: Long = -1L
    private var activePrimaryColor: Int = Color.parseColor("#00D9FF")
    private var activeSecondaryColor: Int = Color.parseColor("#5A2D82")

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        val rv = attachedRecyclerView
        if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
            rv.post { notifyDataSetChanged() }
        } else {
            notifyDataSetChanged()
        }
    }

    fun setActiveSong(songId: Long, primaryColor: Int? = null, secondaryColor: Int? = null) {
        val paletteChanged = primaryColor != null && secondaryColor != null &&
            (primaryColor != activePrimaryColor || secondaryColor != activeSecondaryColor)
        if (activeSongId == songId && !paletteChanged) return
        val oldIndex = songs.indexOfFirst { it.id == activeSongId }
        val newIndex = songs.indexOfFirst { it.id == songId }
        activeSongId = songId
        if (primaryColor != null) activePrimaryColor = primaryColor
        if (secondaryColor != null) activeSecondaryColor = secondaryColor
        if (oldIndex != -1) safeNotifyItemChanged(oldIndex)
        if (newIndex != -1) safeNotifyItemChanged(newIndex)
    }

    private fun safeNotifyItemChanged(index: Int) {
        if (index !in songs.indices) return
        val rv = attachedRecyclerView
        if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
            rv.post {
                if (index in songs.indices) notifyItemChanged(index)
            }
        } else {
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position])
    }

    override fun getItemCount(): Int = songs.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ShapeableImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSongTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvSongArtist)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val vActiveIndicator: View = itemView.findViewById(R.id.vActiveIndicator)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in songs.indices) {
                    onSongClicked(songs[pos], songs)
                }
            }
            btnMore.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in songs.indices) {
                    onSongMoreClicked(songs[pos])
                }
            }
        }

        fun bind(song: Song) {
            tvTitle.text = song.title
            tvArtist.text = "${song.artist} • ${song.album}"
            tvDuration.text = song.formattedDuration

            val isCurrent = song.id == activeSongId
            val context = itemView.context

            if (isCurrent) {
                tvTitle.setTextColor(ContextCompat.getColor(context, R.color.neon_cyan))
                vActiveIndicator.visibility = View.VISIBLE
                itemView.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(withAlpha(activePrimaryColor, 54), Color.TRANSPARENT, withAlpha(activeSecondaryColor, 34))
                ).apply { cornerRadius = 18f * context.resources.displayMetrics.density }
            } else {
                tvTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                vActiveIndicator.visibility = View.GONE
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            val art = com.ultradeep.music.util.ArtworkManager.getArtworkForSong(context, song)
            ivThumbnail.load(art) {
                crossfade(true)
                placeholder(R.drawable.bg_artwork_placeholder)
                error(R.drawable.bg_artwork_placeholder)
            }
        }

        private fun withAlpha(color: Int, alpha: Int) =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
