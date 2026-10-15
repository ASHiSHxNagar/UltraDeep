package com.ultradeep.music.ui.lyrics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.databinding.DialogEditLyricsBinding
import kotlinx.coroutines.launch

class EditLyricsBottomSheet(
    private val song: Song,
    private val initialContent: String,
    private val onLyricsUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogEditLyricsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEditLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSongSubtitle.text = "${song.title} • ${song.artist}"
        binding.etLyricsContent.setText(initialContent)

        val repository = UltraDeepApplication.instance.lyricsRepository

        binding.btnFetchUrl.setOnClickListener {
            val url = binding.etLrcUrl.text?.toString()?.trim()
            if (url.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please enter an LRC URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnFetchUrl.isEnabled = false
                val fetched = repository.fetchLrcFromUrl(url)
                binding.btnFetchUrl.isEnabled = true
                if (!fetched.isNullOrEmpty()) {
                    binding.etLyricsContent.setText(fetched)
                    Toast.makeText(requireContext(), "LRC downloaded successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch LRC from URL", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSaveLyrics.setOnClickListener {
            val content = binding.etLyricsContent.text?.toString()?.trim() ?: ""
            if (content.isEmpty()) {
                Toast.makeText(requireContext(), "Please paste some lyrics or timestamps", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                repository.saveCustomLyrics(song, content)
                Toast.makeText(requireContext(), R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
                onLyricsUpdated()
                dismiss()
            }
        }

        binding.btnResetLyrics.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.resetLyrics(song.id)
                Toast.makeText(requireContext(), "Reset to automatic lyrics", Toast.LENGTH_SHORT).show()
                onLyricsUpdated()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EditLyricsBottomSheet"
    }
}
