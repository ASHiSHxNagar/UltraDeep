package com.ultradeep.music.ui.options

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultradeep.music.R
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.databinding.DialogRenameSongBinding
import com.ultradeep.music.databinding.DialogSongDetailsBinding
import com.ultradeep.music.databinding.DialogSongOptionsBinding
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.ui.playlist.AddToPlaylistBottomSheet
import com.ultradeep.music.util.ArtworkManager
import com.ultradeep.music.util.FileUtil
import java.util.Locale

class SongOptionsBottomSheet : BottomSheetDialogFragment {

    private var song: Song? = null
    private var onSongUpdated: (() -> Unit)? = null

    constructor() : super()

    constructor(song: Song, onSongUpdated: () -> Unit) : super() {
        this.song = song
        this.onSongUpdated = onSongUpdated
    }

    private var _binding: DialogSongOptionsBinding? = null
    private val binding get() = _binding!!

    private val pickArtworkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val currentSong = song ?: return@registerForActivityResult
        if (uri != null) {
            val destFile = ArtworkManager.getCustomArtworkFile(requireContext(), currentSong.id)
            val success = FileUtil.copyUriToFile(requireContext(), uri, destFile)
            if (success) {
                Toast.makeText(requireContext(), "Custom artwork set for this song!", Toast.LENGTH_SHORT).show()
                onSongUpdated?.invoke()
                dismiss()
            } else {
                Toast.makeText(requireContext(), "Failed to save artwork", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSongOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSong = song ?: run {
            dismissAllowingStateLoss()
            return
        }

        binding.tvOptionSongTitle.text = currentSong.title
        binding.tvOptionSongArtist.text = "${currentSong.artist} • ${currentSong.album}"

        val art = ArtworkManager.getArtworkForSong(requireContext(), currentSong)
        binding.ivOptionThumbnail.load(art) {
            crossfade(true)
            placeholder(R.drawable.bg_artwork_placeholder)
        }

        binding.optAddToPlaylist.setOnClickListener {
            val addSheet = AddToPlaylistBottomSheet(currentSong)
            addSheet.show(parentFragmentManager, AddToPlaylistBottomSheet.TAG)
            dismiss()
        }

        binding.optSetCustomArt.setOnClickListener {
            pickArtworkLauncher.launch("image/*")
        }

        binding.optRenameSong.setOnClickListener {
            showRenameDialog()
        }

        binding.optSongDetails.setOnClickListener {
            showDetailsDialog()
        }

        binding.optDeleteSong.setOnClickListener {
            confirmDeleteTrack()
        }
    }

    private fun showRenameDialog() {
        val currentSong = song ?: return
        val renameBinding = DialogRenameSongBinding.inflate(layoutInflater)
        renameBinding.etRenameTitle.setText(currentSong.title)
        renameBinding.etRenameArtist.setText(currentSong.artist)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        dialog.setContentView(renameBinding.root)

        renameBinding.btnSaveRename.setOnClickListener {
            val newTitle = renameBinding.etRenameTitle.text?.toString()?.trim() ?: currentSong.title
            val newArtist = renameBinding.etRenameArtist.text?.toString()?.trim() ?: currentSong.artist

            val currentQueue = MusicController.queue.value.map {
                if (it.id == currentSong.id) it.copy(title = newTitle, artist = newArtist) else it
            }
            val allSongs = MusicController.allSongs.value.map {
                if (it.id == currentSong.id) it.copy(title = newTitle, artist = newArtist) else it
            }

            MusicController.updateLibrary(allSongs)
            if (MusicController.currentSong.value?.id == currentSong.id) {
                MusicController.playSong(currentSong.copy(title = newTitle, artist = newArtist), currentQueue)
            }
            Toast.makeText(requireContext(), "Song renamed", Toast.LENGTH_SHORT).show()
            onSongUpdated?.invoke()
            dialog.dismiss()
            dismiss()
        }

        dialog.show()
    }

    private fun showDetailsDialog() {
        val currentSong = song ?: return
        val detailsBinding = DialogSongDetailsBinding.inflate(layoutInflater)
        detailsBinding.tvPropTitle.text = currentSong.title
        detailsBinding.tvPropArtistAlbum.text = "${currentSong.artist} / ${currentSong.album}"
        val sizeMb = String.format(Locale.getDefault(), "%.2f MB", currentSong.size / (1024f * 1024f))
        detailsBinding.tvPropDurationSize.text = "${currentSong.formattedDuration}  •  $sizeMb"
        detailsBinding.tvPropPath.text = currentSong.contentUri

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        dialog.setContentView(detailsBinding.root)

        detailsBinding.btnDoneProperties.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDeleteTrack() {
        val currentSong = song ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Track")
            .setMessage("Are you sure you want to remove '${currentSong.title}' from your library?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    requireContext().contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, currentSong.id),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val updated = MusicController.allSongs.value.filter { it.id != currentSong.id }
                MusicController.updateLibrary(updated)
                Toast.makeText(requireContext(), "Track removed", Toast.LENGTH_SHORT).show()
                onSongUpdated?.invoke()
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SongOptionsBottomSheet"
    }
}
