package com.ultradeep.music.ui.adapter

import android.content.Context
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

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun setActiveSong(songId: Long) {
        if (activeSongId == songId) return
        val oldIndex = songs.indexOfFirst { it.id == activeSongId }
        val newIndex = songs.indexOfFirst { it.id == songId }
        activeSongId = songId
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
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
            } else {
                tvTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                vActiveIndicator.visibility = View.GONE
            }

            val art = com.ultradeep.music.util.ArtworkManager.getArtworkForSong(context, song)
            ivThumbnail.load(art) {
                crossfade(true)
                placeholder(R.drawable.bg_artwork_placeholder)
                error(R.drawable.bg_artwork_placeholder)
            }
        }
    }
}
