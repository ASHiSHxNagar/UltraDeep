package com.ultradeep.music.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.databinding.DialogSettingsBinding
import com.ultradeep.music.scanner.AudioScanner
import com.ultradeep.music.service.MusicController
import kotlinx.coroutines.launch

class SettingsBottomSheet(
    private val onLibraryRescanned: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val prefs = requireContext().getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("custom_ambient_uri", uri.toString())
                .putBoolean("custom_ambient_enabled", true)
                .apply()

            binding.switchCustomAmbient.isChecked = true
            Toast.makeText(requireContext(), "Custom background set", Toast.LENGTH_SHORT).show()
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
        val isCustomEnabled = prefs.getBoolean("custom_ambient_enabled", false)
        binding.switchCustomAmbient.isChecked = isCustomEnabled

        binding.switchCustomAmbient.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("custom_ambient_enabled", isChecked).apply()
        }

        binding.btnPickCustomBg.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnClearCustomBg.setOnClickListener {
            prefs.edit()
                .remove("custom_ambient_uri")
                .putBoolean("custom_ambient_enabled", false)
                .apply()
            binding.switchCustomAmbient.isChecked = false
            Toast.makeText(requireContext(), R.string.clear_custom_bg, Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
