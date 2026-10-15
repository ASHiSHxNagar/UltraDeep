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

class SongOptionsBottomSheet(
    private val song: Song,
    private val onSongUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSongOptionsBinding? = null
    private val binding get() = _binding!!

    private val pickArtworkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val destFile = ArtworkManager.getCustomArtworkFile(requireContext(), song.id)
            val success = FileUtil.copyUriToFile(requireContext(), uri, destFile)
            if (success) {
                Toast.makeText(requireContext(), "Custom artwork set for this song!", Toast.LENGTH_SHORT).show()
                onSongUpdated()
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

        binding.tvOptionSongTitle.text = song.title
        binding.tvOptionSongArtist.text = "${song.artist} • ${song.album}"

        val art = ArtworkManager.getArtworkForSong(requireContext(), song)
        binding.ivOptionThumbnail.load(art) {
            crossfade(true)
            placeholder(R.drawable.bg_artwork_placeholder)
        }

        binding.optAddToPlaylist.setOnClickListener {
            val addSheet = AddToPlaylistBottomSheet(song)
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
        val renameBinding = DialogRenameSongBinding.inflate(layoutInflater)
        renameBinding.etRenameTitle.setText(song.title)
        renameBinding.etRenameArtist.setText(song.artist)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        dialog.setContentView(renameBinding.root)

        renameBinding.btnSaveRename.setOnClickListener {
            val newTitle = renameBinding.etRenameTitle.text?.toString()?.trim() ?: song.title
            val newArtist = renameBinding.etRenameArtist.text?.toString()?.trim() ?: song.artist

            val currentQueue = MusicController.queue.value.map {
                if (it.id == song.id) it.copy(title = newTitle, artist = newArtist) else it
            }
            val allSongs = MusicController.allSongs.value.map {
                if (it.id == song.id) it.copy(title = newTitle, artist = newArtist) else it
            }

            MusicController.updateLibrary(allSongs)
            if (MusicController.currentSong.value?.id == song.id) {
                MusicController.playSong(song.copy(title = newTitle, artist = newArtist), currentQueue)
            }
            Toast.makeText(requireContext(), "Song renamed", Toast.LENGTH_SHORT).show()
            onSongUpdated()
            dialog.dismiss()
            dismiss()
        }

        dialog.show()
    }

    private fun showDetailsDialog() {
        val detailsBinding = DialogSongDetailsBinding.inflate(layoutInflater)
        detailsBinding.tvPropTitle.text = song.title
        detailsBinding.tvPropArtistAlbum.text = "${song.artist} / ${song.album}"
        val sizeMb = String.format(Locale.getDefault(), "%.2f MB", song.size / (1024f * 1024f))
        detailsBinding.tvPropDurationSize.text = "${song.formattedDuration}  •  $sizeMb"
        detailsBinding.tvPropPath.text = song.contentUri

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        dialog.setContentView(detailsBinding.root)

        detailsBinding.btnDoneProperties.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun confirmDeleteTrack() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Track")
            .setMessage("Are you sure you want to remove '${song.title}' from your library?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    requireContext().contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id),
                        null,
                        null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val updated = MusicController.allSongs.value.filter { it.id != song.id }
                MusicController.updateLibrary(updated)
                Toast.makeText(requireContext(), "Track removed", Toast.LENGTH_SHORT).show()
                onSongUpdated()
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
