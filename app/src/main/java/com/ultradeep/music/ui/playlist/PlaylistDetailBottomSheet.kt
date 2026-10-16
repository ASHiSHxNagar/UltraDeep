package com.ultradeep.music.ui.playlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.db.entity.PlaylistEntity
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.data.model.SystemPlaylist
import com.ultradeep.music.databinding.DialogPlaylistDetailBinding
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.ui.adapter.SongAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistDetailBottomSheet : BottomSheetDialogFragment {

    private var playlist: PlaylistEntity? = null

    constructor() : super()

    constructor(playlist: PlaylistEntity) : super() {
        this.playlist = playlist
    }

    private var _binding: DialogPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentPlaylist = playlist ?: run {
            dismissAllowingStateLoss()
            return
        }

        binding.tvPlaylistDetailName.text = currentPlaylist.name

        val playlistDao = UltraDeepApplication.instance.database.playlistDao()
        val allSongs = MusicController.allSongs.value
        val playlistSongs = mutableListOf<Song>()
        val isLastPlayed = currentPlaylist.id == SystemPlaylist.LAST_PLAYED_ID

        val adapter = SongAdapter(
            onSongClicked = { song, queue ->
                MusicController.playSong(song, queue)
                dismiss()
            },
            onSongMoreClicked = { song ->
                if (!isLastPlayed) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        playlistDao.removeSongFromPlaylist(currentPlaylist.id, song.id)
                    }
                }
            }
        )

        binding.rvPlaylistSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaylistSongs.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val songIdsFlow = if (isLastPlayed) MusicController.recentSongIds else playlistDao.getSongIdsForPlaylist(currentPlaylist.id)
            songIdsFlow.collectLatest { songIds ->
                playlistSongs.clear()
                // Preserve the order in which tracks were played, newest first.
                playlistSongs.addAll(songIds.mapNotNull { id -> allSongs.firstOrNull { it.id == id } })

                binding.tvPlaylistDetailCount.text = "${playlistSongs.size} tracks"
                adapter.submitList(playlistSongs)

                if (playlistSongs.isEmpty()) {
                    binding.tvPlaylistEmpty.visibility = View.VISIBLE
                    binding.rvPlaylistSongs.visibility = View.GONE
                    binding.btnPlayAllPlaylist.visibility = View.GONE
                } else {
                    binding.tvPlaylistEmpty.visibility = View.GONE
                    binding.rvPlaylistSongs.visibility = View.VISIBLE
                    binding.btnPlayAllPlaylist.visibility = View.VISIBLE
                }
            }
        }

        binding.btnPlayAllPlaylist.setOnClickListener {
            if (playlistSongs.isNotEmpty()) {
                MusicController.playSong(playlistSongs.first(), playlistSongs)
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PlaylistDetailBottomSheet"
    }
}
