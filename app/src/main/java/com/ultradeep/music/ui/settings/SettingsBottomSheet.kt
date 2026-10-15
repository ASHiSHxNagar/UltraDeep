package com.ultradeep.music.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.R
import com.ultradeep.music.databinding.DialogSettingsBinding
import com.ultradeep.music.scanner.AudioScanner
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.util.ArtworkManager
import com.ultradeep.music.util.FileUtil
import kotlinx.coroutines.launch

class SettingsBottomSheet(
    private val onLibraryRescanned: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val dest = ArtworkManager.getGlobalCustomBgFile(requireContext())
            val success = FileUtil.copyUriToFile(requireContext(), uri, dest)
            if (success) {
                val prefs = requireContext().getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("custom_ambient_enabled", true).apply()
                updateBackgroundStatus()
                Toast.makeText(requireContext(), "Custom background image saved!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to save image", Toast.LENGTH_SHORT).show()
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
            prefs.edit().putInt("theme_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        updateBackgroundStatus()

        binding.btnPickCustomBg.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnClearCustomBg.setOnClickListener {
            val file = ArtworkManager.getGlobalCustomBgFile(requireContext())
            if (file.exists()) file.delete()
            prefs.edit().putBoolean("custom_ambient_enabled", false).apply()
            updateBackgroundStatus()
            Toast.makeText(requireContext(), "Reverted to 10 generative artworks", Toast.LENGTH_SHORT).show()
        }

        binding.btnRescanStorage.setOnClickListener {
            binding.tvRescanStatus.text = getString(R.string.rescanning)
            binding.btnRescanStorage.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                val songs = AudioScanner.scanAudioFiles(requireContext())
                MusicController.updateLibrary(songs)
                binding.btnRescanStorage.isEnabled = true
                binding.tvRescanStatus.text = getString(R.string.rescan_complete)
                Toast.makeText(requireContext(), "${songs.size} tracks found", Toast.LENGTH_SHORT).show()
                onLibraryRescanned()
            }
        }
    }

    private fun updateBackgroundStatus() {
        val file = ArtworkManager.getGlobalCustomBgFile(requireContext())
        val prefs = requireContext().getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("custom_ambient_enabled", false)

        if (isEnabled && file.exists() && file.length() > 0) {
            binding.tvCustomBgStatus.text = "Active: Global Custom Wallpaper"
            binding.tvCustomBgStatus.setTextColor(requireContext().getColor(R.color.neon_cyan))
        } else {
            binding.tvCustomBgStatus.text = "Active: 10 Generative AI Artworks & Dynamic Ambient"
            binding.tvCustomBgStatus.setTextColor(requireContext().getColor(R.color.text_primary))
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
