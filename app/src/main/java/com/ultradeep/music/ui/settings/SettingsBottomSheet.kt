package com.ultradeep.music.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.databinding.DialogSettingsBinding
import com.ultradeep.music.scanner.AudioScanner
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.util.ArtworkManager
import com.ultradeep.music.util.BackupManager
import com.ultradeep.music.util.FileUtil
import kotlinx.coroutines.launch
import java.util.Calendar

class SettingsBottomSheet : BottomSheetDialogFragment {

    private var onLibraryRescanned: (() -> Unit)? = null

    constructor() : super()

    constructor(onLibraryRescanned: () -> Unit) : super() {
        this.onLibraryRescanned = onLibraryRescanned
    }

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickDefaultArtLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val dest = ArtworkManager.getGlobalDefaultArtworkFile(requireContext())
            val success = FileUtil.copyUriToFile(requireContext(), uri, dest)
            if (success) {
                updateDefaultArtUI()
                onLibraryRescanned?.invoke()
                Toast.makeText(requireContext(), "Default artwork updated for all songs!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val jsonText = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (jsonText.isNotBlank()) {
                        val result = BackupManager.restoreBackup(requireContext(), jsonText)
                        updateDefaultArtUI()
                        onLibraryRescanned?.invoke()
                        Toast.makeText(
                            requireContext(),
                            "Backup restored! ${result.lyricsCount} lyrics, ${result.favoritesCount} favs, ${result.playlistsCount} playlists, ${result.artworksCount} arts.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(requireContext(), "Empty backup file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "Failed to restore backup: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)

        // Setup Theme selection
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.rbThemeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> binding.rbThemeSystem.isChecked = true
            else -> binding.rbThemeDark.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.rbThemeSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
            val currentMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
            if (currentMode != mode) {
                prefs.edit().putInt("theme_mode", mode).apply()
                dismissAllowingStateLoss()
                view.post {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }

        // Setup Default Track Artwork
        updateDefaultArtUI()

        binding.btnPickDefaultArt.setOnClickListener {
            pickDefaultArtLauncher.launch("image/*")
        }

        binding.btnResetDefaultArt.setOnClickListener {
            val file = ArtworkManager.getGlobalDefaultArtworkFile(requireContext())
            if (file.exists()) file.delete()
            updateDefaultArtUI()
            onLibraryRescanned?.invoke()
            Toast.makeText(requireContext(), "Reverted to Car Default Photo", Toast.LENGTH_SHORT).show()
        }

        // Setup Backup & Restore
        binding.btnExportBackup.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val shareIntent = BackupManager.exportBackupAndShare(requireContext())
                    startActivity(Intent.createChooser(shareIntent, "Save or Share UltraDeep Backup"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "Backup export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnImportBackup.setOnClickListener {
            importBackupLauncher.launch("*/*")
        }

        binding.btnSyncMissingLyrics.setOnClickListener {
            syncMissingLyrics()
        }

        binding.btnSyncLyricsAfterDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    syncMissingLyrics(selected.timeInMillis / 1000L)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Setup Rescan Storage
        binding.btnRescanStorage.setOnClickListener {
            binding.tvRescanStatus.text = getString(R.string.rescanning)
            binding.btnRescanStorage.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val songs = AudioScanner.scanAudioFiles(requireContext())
                MusicController.updateLibrary(songs)
                binding.btnRescanStorage.isEnabled = true
                binding.tvRescanStatus.text = getString(R.string.rescan_complete)
                Toast.makeText(requireContext(), "${songs.size} tracks found", Toast.LENGTH_SHORT).show()
                onLibraryRescanned?.invoke()
            }
        }
    }

    private fun updateDefaultArtUI() {
        val hasCustom = ArtworkManager.hasCustomGlobalDefault(requireContext())
        val defaultArt = ArtworkManager.getGlobalDefaultArtwork(requireContext())

        binding.ivDefaultArtPreview.load(defaultArt) {
            crossfade(true)
            placeholder(R.drawable.bg_artwork_placeholder)
        }

        if (hasCustom) {
            binding.tvDefaultArtStatus.text = "Active: Custom Default Photo"
            binding.tvDefaultArtStatus.setTextColor(requireContext().getColor(R.color.neon_cyan))
        } else {
            binding.tvDefaultArtStatus.text = "Active: Car Default Photo"
            binding.tvDefaultArtStatus.setTextColor(requireContext().getColor(R.color.text_primary))
        }
    }

    /**
     * Intentionally skips every track with a Room entry, whether it was downloaded,
     * marked instrumental, or authored by the user. This makes the bulk action safe
     * to re-run without wasting requests or overwriting work.
     */
    private fun syncMissingLyrics(afterEpochSeconds: Long? = null) {
        val allSongs = MusicController.allSongs.value
        val candidates = allSongs.filter { afterEpochSeconds == null || it.dateAdded >= afterEpochSeconds }
        if (candidates.isEmpty()) {
            binding.tvLyricsSyncStatus.text = "No tracks match this date."
            return
        }

        binding.btnSyncMissingLyrics.isEnabled = false
        binding.btnSyncLyricsAfterDate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val repository = UltraDeepApplication.instance.lyricsRepository
            var checked = 0
            var downloaded = 0
            var skipped = 0
            candidates.forEach { song ->
                checked++
                if (repository.hasSavedLyrics(song.id)) {
                    skipped++
                } else {
                    val result = repository.getLyrics(song, forceRefresh = true)
                    if (result.lines.isNotEmpty() || result.isInstrumental) downloaded++
                }
                binding.tvLyricsSyncStatus.text = "Checking $checked/${candidates.size} • saved $downloaded • skipped $skipped"
            }
            binding.btnSyncMissingLyrics.isEnabled = true
            binding.btnSyncLyricsAfterDate.isEnabled = true
            binding.tvLyricsSyncStatus.text = "Finished: $downloaded saved, $skipped already saved."
            Toast.makeText(requireContext(), "Lyrics sync complete", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
