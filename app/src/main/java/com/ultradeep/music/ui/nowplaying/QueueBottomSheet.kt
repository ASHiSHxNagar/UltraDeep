package com.ultradeep.music.ui.nowplaying

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.databinding.DialogQueueBinding
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.ui.adapter.SongAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QueueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogQueueBinding? = null
    private val binding get() = _binding!!

    private lateinit var queueAdapter: SongAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        queueAdapter = SongAdapter(
            onSongClicked = { song, queue ->
                MusicController.playSong(song, queue)
                dismiss()
            },
            onSongMoreClicked = {}
        )

        binding.rvQueueTracks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = queueAdapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.queue.collectLatest { queueSongs ->
                binding.tvQueueCount.text = "${queueSongs.size} tracks"
                queueAdapter.submitList(queueSongs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.currentSong.collectLatest { song ->
                if (song != null) {
                    queueAdapter.setActiveSong(song.id)
                }
            }
        }

        binding.btnReshuffleQueue.setOnClickListener {
            val current = MusicController.queue.value
            if (current.isNotEmpty()) {
                val shuffled = current.shuffled()
                val currentSong = MusicController.currentSong.value
                if (currentSong != null) {
                    MusicController.playSong(currentSong, listOf(currentSong) + shuffled.filter { it.id != currentSong.id })
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "QueueBottomSheet"
    }
}
