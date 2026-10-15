package com.ultradeep.music.ui.playlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.db.entity.PlaylistEntity
import com.ultradeep.music.databinding.DialogCreatePlaylistBinding
import kotlinx.coroutines.launch

class CreatePlaylistDialog : BottomSheetDialogFragment {

    private var onPlaylistCreated: (() -> Unit)? = null

    constructor() : super()

    constructor(onPlaylistCreated: () -> Unit) : super() {
        this.onPlaylistCreated = onPlaylistCreated
    }

    private var _binding: DialogCreatePlaylistBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCreatePlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCreatePlaylistSubmit.setOnClickListener {
            val name = binding.etPlaylistName.text?.toString()?.trim() ?: ""
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a playlist name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val playlistDao = UltraDeepApplication.instance.database.playlistDao()
                playlistDao.insertPlaylist(PlaylistEntity(name = name))
                onPlaylistCreated?.invoke()
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CreatePlaylistDialog"
    }
}
