package com.ultradeep.music.ui.playlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.db.entity.PlaylistSongCrossRef
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.databinding.DialogAddToPlaylistBinding
import com.ultradeep.music.ui.adapter.PlaylistAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AddToPlaylistBottomSheet(
    private val song: Song
) : BottomSheetDialogFragment() {

    private var _binding: DialogAddToPlaylistBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddToPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val playlistDao = UltraDeepApplication.instance.database.playlistDao()
        val adapter = PlaylistAdapter(
            onPlaylistClicked = { playlist ->
                viewLifecycleOwner.lifecycleScope.launch {
                    playlistDao.addSongToPlaylist(
                        PlaylistSongCrossRef(playlistId = playlist.id, songId = song.id)
                    )
                    Toast.makeText(requireContext(), R.string.added_to_playlist, Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            },
            onDeleteClicked = {}
        )

        binding.rvPlaylistsToAdd.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaylistsToAdd.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            playlistDao.getAllPlaylists().collectLatest { list ->
                adapter.submitList(list)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddToPlaylistBottomSheet"
    }
}
