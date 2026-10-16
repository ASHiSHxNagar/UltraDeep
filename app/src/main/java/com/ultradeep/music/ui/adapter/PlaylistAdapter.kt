package com.ultradeep.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ultradeep.music.R
import com.ultradeep.music.data.db.entity.PlaylistEntity
import com.ultradeep.music.data.model.SystemPlaylist

class PlaylistAdapter(
    private val onPlaylistClicked: (PlaylistEntity) -> Unit,
    private val onDeleteClicked: (PlaylistEntity) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    private val playlists = mutableListOf<PlaylistEntity>()
    private var systemPlaylistTrackCount = 0

    fun submitList(newPlaylists: List<PlaylistEntity>) {
        playlists.clear()
        playlists.addAll(newPlaylists)
        notifyDataSetChanged()
    }

    fun setSystemPlaylistTrackCount(count: Int) {
        systemPlaylistTrackCount = count
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvPlaylistTrackCount)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeletePlaylist)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in playlists.indices) {
                    onPlaylistClicked(playlists[pos])
                }
            }
            btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in playlists.indices) {
                    onDeleteClicked(playlists[pos])
                }
            }
        }

        fun bind(playlist: PlaylistEntity) {
            tvName.text = playlist.name
            val isLastPlayed = playlist.id == SystemPlaylist.LAST_PLAYED_ID
            tvCount.text = if (isLastPlayed) {
                "$systemPlaylistTrackCount of ${SystemPlaylist.MAX_LAST_PLAYED_TRACKS} recently played"
            } else {
                itemView.context.getString(R.string.tab_playlists)
            }
            btnDelete.visibility = if (isLastPlayed) View.INVISIBLE else View.VISIBLE
        }
    }
}
