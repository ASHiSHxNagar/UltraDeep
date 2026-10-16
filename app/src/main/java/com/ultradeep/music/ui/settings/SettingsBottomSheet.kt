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
import kotlinx.coroutines.CancellationException
import java.util.Calendar

class SettingsBottomSheet : BottomSheetDialogFragment {

    private var onLibraryRescanned: (() -> Unit)? = null
    private var openLyricsSyncSection = false

    constructor() : super()

    constructor(onLibraryRescanned: () -> Unit) : super() {
        this.onLibraryRescanned = onLibraryRescanned
    }

    constructor(onLibraryRescanned: () -> Unit, openLyricsSyncSection: Boolean) : super() {
        this.onLibraryRescanned = onLibraryRescanned
        this.openLyricsSyncSection = openLyricsSyncSection
    }

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.90f).toInt()
        )
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.layoutParams = sheet.layoutParams.apply { height = (resources.displayMetrics.heightPixels * 0.90f).toInt() }
        val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.90f).toInt()
        if (openLyricsSyncSection) binding.scrollSettings.post { binding.scrollSettings.smoothScrollTo(0, binding.lyricsSyncSection.top) }
    }

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

    private val chooseAutoBackupFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnChooseAutoBackupFolder.isEnabled = false
                try {
                    val result = BackupManager.selectAutoBackupFolder(requireContext(), uri)
                    updateAutoBackupStatus()
                    val message = result.restored?.let {
                        "Auto-recovered ${it.lyricsCount} lyrics, ${it.favoritesCount} favorites, and ${it.playlistsCount} playlists from backup!"
                    } ?: if (result.written) {
                        "Backup folder saved! Created: ${result.backupFolderName}"
                    } else "Automatic backup folder saved."
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    if (result.restored != null) {
                        onLibraryRescanned?.invoke()
                    }
                } catch (error: Exception) {
                    binding.tvAutoBackupStatus.text = "Could not use this folder: ${error.localizedMessage ?: "permission denied"}"
                    Toast.makeText(requireContext(), "Could not access the selected folder", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnChooseAutoBackupFolder.isEnabled = true
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
        updateAutoBackupStatus()

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
                // AppCompat recreates the active Activity as soon as the mode changes.
                // Posting through a view after dismissing this sheet could lose the
                // callback, leaving the selected theme unapplied until the next launch.
                AppCompatDelegate.setDefaultNightMode(mode)
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

        binding.btnChooseAutoBackupFolder.setOnClickListener {
            chooseAutoBackupFolderLauncher.launch(null)
        }

        binding.btnBackupNow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnBackupNow.isEnabled = false
                val saved = BackupManager.updateAutoBackup(requireContext())
                binding.btnBackupNow.isEnabled = true
                updateAutoBackupStatus()
                Toast.makeText(
                    requireContext(),
                    if (saved) "New backup folder created!" else "No local data to back up yet",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnRestoreAutoBackup.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Restore automatic backup?")
                .setMessage("Saved lyrics, favorites, playlists and artwork will be merged into this installation.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Restore") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        binding.btnRestoreAutoBackup.isEnabled = false
                        try {
                            val restored = BackupManager.restoreSelectedAutoBackup(requireContext())
                            if (restored == null) {
                                Toast.makeText(requireContext(), "No automatic backup found in that folder", Toast.LENGTH_SHORT).show()
                            } else {
                                onLibraryRescanned?.invoke()
                                Toast.makeText(
                                    requireContext(),
                                    "Restored ${restored.lyricsCount} lyrics, ${restored.favoritesCount} favorites, ${restored.playlistsCount} playlists.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (error: Exception) {
                            Toast.makeText(requireContext(), "Backup restore failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                        } finally {
                            binding.btnRestoreAutoBackup.isEnabled = true
                        }
                    }
                }
                .show()
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

    private fun updateAutoBackupStatus() {
        binding.tvAutoBackupStatus.text = if (BackupManager.hasAutoBackupFolder(requireContext())) {
            "Automatic backup is active. Every time you open UltraDeep, a new folder 'UltraDeep - DD-MM-YY - HH.mm.ss' is created with zero overwrite! If you reinstall the app, select this same folder to automatically recover your data."
        } else {
            "Choose a folder. Every time you open UltraDeep, a new timestamped folder is created with your complete data."
        }
    }

    /**
     * Intentionally skips every track with a Room entry, whether it was downloaded,
     * marked instrumental, or authored by the user. This makes the bulk action safe
     * to re-run without wasting requests or overwriting work.
     */
    private fun syncMissingLyrics(afterEpochSeconds: Long? = null) {
        val allSongs = MusicController.allSongs.value
        if (allSongs.isEmpty()) {
            showLyricsSyncStatus("Your music library is still loading. Rescan storage and try again.")
            return
        }

        val candidates = allSongs.filter { afterEpochSeconds == null || it.dateAdded >= afterEpochSeconds }
        if (candidates.isEmpty()) {
            showLyricsSyncStatus("No tracks were added on or after the selected date.")
            return
        }

        binding.btnSyncMissingLyrics.isEnabled = false
        binding.btnSyncLyricsAfterDate.isEnabled = false
        binding.tvLyricsSyncStatus.text = "Checking ${candidates.size} tracks for saved lyrics…"
        viewLifecycleOwner.lifecycleScope.launch {
            val repository = UltraDeepApplication.instance.lyricsRepository
            var checked = 0
            var downloaded = 0
            var skipped = 0
            var notFound = 0
            try {
                candidates.forEach { song ->
                    if (repository.hasSavedLyrics(song.id)) {
                        skipped++
                    } else {
                        val result = repository.getLyrics(song)
                        if (result.lines.isNotEmpty() || result.isInstrumental) downloaded++ else notFound++
                    }
                    checked++
                    showLyricsSyncStatus("Checking $checked/${candidates.size} • fetched $downloaded • already saved $skipped • not found $notFound")
                }
                val summary = when {
                    downloaded == 0 && skipped == candidates.size -> "All ${candidates.size} matching tracks already have saved lyrics. Nothing needed fetching."
                    else -> "Finished: $downloaded fetched, $skipped already saved, $notFound not found."
                }
                showLyricsSyncStatus(summary)
                context?.let { Toast.makeText(it, "Lyrics sync complete: $downloaded fetched, $notFound not found", Toast.LENGTH_LONG).show() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showLyricsSyncStatus("Sync stopped: ${error.localizedMessage ?: "please try again"}")
            } finally {
                _binding?.let {
                    it.btnSyncMissingLyrics.isEnabled = true
                    it.btnSyncLyricsAfterDate.isEnabled = true
                }
            }
        }
    }

    private fun showLyricsSyncStatus(message: String) {
        _binding?.tvLyricsSyncStatus?.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
