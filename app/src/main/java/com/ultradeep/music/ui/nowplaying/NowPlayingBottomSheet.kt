package com.ultradeep.music.ui.nowplaying

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.db.entity.FavoriteSongEntity
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.databinding.DialogNowPlayingBinding
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.ui.adapter.LyricsAdapter
import com.ultradeep.music.ui.lyrics.EditLyricsBottomSheet
import com.ultradeep.music.util.ArtworkManager
import com.ultradeep.music.util.PaletteUtil
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class NowPlayingBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogNowPlayingBinding? = null
    private val binding get() = _binding!!

    private lateinit var lyricsAdapter: LyricsAdapter
    private var isLyricsModeActive = false
    private var isUserTrackingSeekBar = false
    private var currentSongId: Long = -1L
    private var rawLyricsCache: String = ""
    private var currentSongOffsetMs: Long = 0L

    private val prefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLyricsRecyclerView()
        setupListeners()
        observeMusicController()
        checkCustomBackground()
    }

    private fun setupLyricsRecyclerView() {
        lyricsAdapter = LyricsAdapter { timestampMs ->
            val seekTarget = (timestampMs - currentSongOffsetMs).coerceAtLeast(0L)
            MusicController.seekTo(seekTarget)
        }

        binding.rvLyrics.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lyricsAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    lyricsAdapter.setUserScrolling(newState != RecyclerView.SCROLL_STATE_IDLE)
                }
            })
        }
    }

    private fun setupListeners() {
        binding.btnCollapse.setOnClickListener {
            dismiss()
        }

        binding.btnQueue.setOnClickListener {
            val queueSheet = QueueBottomSheet()
            queueSheet.show(parentFragmentManager, QueueBottomSheet.TAG)
        }

        binding.btnPlayPause.setOnClickListener {
            MusicController.togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            MusicController.skipNext()
        }

        binding.btnPrevious.setOnClickListener {
            MusicController.skipPrevious()
        }

        binding.btnShuffle.setOnClickListener {
            MusicController.toggleShuffle()
        }

        binding.btnRepeat.setOnClickListener {
            MusicController.toggleRepeat()
        }

        binding.btnToggleLyricsMode.setOnClickListener {
            toggleLyricsMode(!isLyricsModeActive)
        }

        binding.btnEditLyrics.setOnClickListener {
            val currentSong = MusicController.currentSong.value ?: return@setOnClickListener
            val editDialog = EditLyricsBottomSheet(currentSong, rawLyricsCache) {
                // Refresh lyrics immediately after edit
                loadLyricsForSong(currentSong, forceRefresh = true)
            }
            editDialog.show(parentFragmentManager, EditLyricsBottomSheet.TAG)
        }

        binding.btnFavorite.setOnClickListener {
            val song = MusicController.currentSong.value ?: return@setOnClickListener
            toggleFavorite(song)
        }

        // Lyrics Sync Offset Listeners
        binding.btnOffsetMinus.setOnClickListener {
            adjustSyncOffset(-500L)
        }

        binding.btnOffsetPlus.setOnClickListener {
            adjustSyncOffset(500L)
        }

        binding.btnOffsetReset.setOnClickListener {
            setSyncOffset(0L)
        }

        binding.tvCurrentOffset.setOnClickListener {
            showCustomOffsetInputDialog()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = MusicController.duration.value
                    val targetMs = (progress / 1000f * duration).toLong()
                    binding.tvCurrentTime.text = formatTime(targetMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                val duration = MusicController.duration.value
                val progress = seekBar?.progress ?: 0
                val targetMs = (progress / 1000f * duration).toLong()
                MusicController.seekTo(targetMs)
            }
        })
    }

    private fun adjustSyncOffset(deltaMs: Long) {
        setSyncOffset(currentSongOffsetMs + deltaMs)
    }

    private fun setSyncOffset(newOffsetMs: Long) {
        currentSongOffsetMs = newOffsetMs
        if (currentSongId != -1L) {
            prefs.edit().putLong("lyrics_offset_$currentSongId", currentSongOffsetMs).apply()
        }
        updateOffsetUI()
        lyricsAdapter.updatePosition(MusicController.currentPosition.value, binding.rvLyrics, currentSongOffsetMs)
    }

    private fun updateOffsetUI() {
        val seconds = currentSongOffsetMs / 1000f
        binding.tvCurrentOffset.text = String.format(Locale.getDefault(), "Sync: %+.1fs", seconds)
    }

    private fun showCustomOffsetInputDialog() {
        val input = EditText(requireContext()).apply {
            hint = "e.g. 0.5 or -2.0 or 5.0"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.getDefault(), "%.1f", currentSongOffsetMs / 1000f))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Lyrics Delay Timer")
            .setMessage("Enter seconds to advance (+) or delay (-) the synchronized lyrics for this song:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val sec = input.text.toString().toDoubleOrNull() ?: 0.0
                setSyncOffset((sec * 1000).toLong())
                Toast.makeText(requireContext(), "Lyrics offset saved permanently!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleLyricsMode(showLyrics: Boolean) {
        isLyricsModeActive = showLyrics
        if (showLyrics) {
            binding.artworkViewLayout.visibility = View.GONE
            binding.lyricsViewLayout.visibility = View.VISIBLE
            binding.btnToggleLyricsMode.text = getString(R.string.now_playing)
            binding.btnToggleLyricsMode.setIconResource(R.drawable.ic_album)
        } else {
            binding.lyricsViewLayout.visibility = View.GONE
            binding.artworkViewLayout.visibility = View.VISIBLE
            binding.btnToggleLyricsMode.text = getString(R.string.lyrics_title)
            binding.btnToggleLyricsMode.setIconResource(R.drawable.ic_lyrics)
        }
    }

    private fun observeMusicController() {
        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.currentSong.collectLatest { song ->
                if (song != null) {
                    updateSongUI(song)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.isPlaying.collectLatest { isPlaying ->
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.currentPosition.collectLatest { posMs ->
                if (!isUserTrackingSeekBar) {
                    val duration = MusicController.duration.value
                    if (duration > 0) {
                        val progress = ((posMs.toFloat() / duration) * 1000).toInt()
                        binding.seekBar.progress = progress
                    }
                    binding.tvCurrentTime.text = formatTime(posMs)
                }
                lyricsAdapter.updatePosition(posMs, binding.rvLyrics, currentSongOffsetMs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.duration.collectLatest { durMs ->
                binding.tvTotalDuration.text = formatTime(durMs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.isShuffle.collectLatest { isShuffle ->
                binding.btnShuffle.imageTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    if (isShuffle) R.color.neon_cyan else R.color.text_secondary
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.repeatMode.collectLatest { repeatMode ->
                val iconRes = when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                    else -> R.drawable.ic_repeat
                }
                val colorRes = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> R.color.text_secondary
                    else -> R.color.neon_cyan
                }
                binding.btnRepeat.setImageResource(iconRes)
                binding.btnRepeat.imageTintList = ContextCompat.getColorStateList(requireContext(), colorRes)
            }
        }
    }

    private fun updateSongUI(song: Song) {
        val isNewSong = song.id != currentSongId
        currentSongId = song.id

        if (isNewSong) {
            currentSongOffsetMs = prefs.getLong("lyrics_offset_${song.id}", 0L)
            updateOffsetUI()
        }

        binding.tvTitle.text = song.title
        binding.tvTitle.isSelected = true // Enable marquee scrolling
        binding.tvArtist.text = song.artist

        // Load album art via ArtworkManager
        val art = ArtworkManager.getArtworkForSong(requireContext(), song)
        binding.ivAlbumArt.load(art) {
            crossfade(true)
            placeholder(R.drawable.bg_artwork_placeholder)
            error(R.drawable.bg_artwork_placeholder)
        }

        // Extract palette ambient colors
        viewLifecycleOwner.lifecycleScope.launch {
            val artUri = if (art is Uri) art.toString() else song.albumArtUri
            val colors = PaletteUtil.extractColorsFromUri(requireContext(), artUri)
            binding.ambientBackground.setAmbientColors(colors)
        }

        // Check custom background
        checkCustomBackground()

        // Observe favorite state
        val favoriteDao = UltraDeepApplication.instance.database.favoriteDao()
        viewLifecycleOwner.lifecycleScope.launch {
            favoriteDao.isFavorite(song.id).collectLatest { isFav ->
                binding.btnFavorite.setImageResource(
                    if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart
                )
                binding.btnFavorite.imageTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    if (isFav) R.color.favorite_active else R.color.text_primary
                )
            }
        }

        // Fetch lyrics
        loadLyricsForSong(song, forceRefresh = false)
    }

    private fun loadLyricsForSong(song: Song, forceRefresh: Boolean) {
        binding.tvLyricsStatus.visibility = View.VISIBLE
        binding.tvLyricsStatus.text = getString(R.string.lyrics_loading)

        val repository = UltraDeepApplication.instance.lyricsRepository
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getLyrics(song, forceRefresh)
            rawLyricsCache = result.plainText

            if (result.isInstrumental) {
                binding.tvLyricsStatus.visibility = View.VISIBLE
                binding.tvLyricsStatus.text = getString(R.string.lyrics_instrumental)
                lyricsAdapter.submitLines(emptyList())
            } else if (result.lines.isEmpty()) {
                binding.tvLyricsStatus.visibility = View.VISIBLE
                binding.tvLyricsStatus.text = getString(R.string.lyrics_none)
                lyricsAdapter.submitLines(emptyList())
            } else {
                binding.tvLyricsStatus.visibility = View.GONE
                lyricsAdapter.submitLines(result.lines)
                lyricsAdapter.updatePosition(MusicController.currentPosition.value, binding.rvLyrics, currentSongOffsetMs)
            }
        }
    }

    private fun toggleFavorite(song: Song) {
        val favoriteDao = UltraDeepApplication.instance.database.favoriteDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val isFav = favoriteDao.isFavoriteSync(song.id)
            if (isFav) {
                favoriteDao.removeFavorite(song.id)
            } else {
                favoriteDao.addFavorite(FavoriteSongEntity(song.id))
            }
        }
    }

    private fun checkCustomBackground() {
        val currentSong = MusicController.currentSong.value
        if (currentSong != null) {
            val customSongArt = ArtworkManager.getCustomArtworkFile(requireContext(), currentSong.id)
            if (customSongArt.exists() && customSongArt.length() > 0) {
                binding.ambientBackground.setCustomBackgroundImage(Uri.fromFile(customSongArt))
                return
            }
        }

        val isCustomEnabled = prefs.getBoolean("custom_ambient_enabled", false)
        val globalCustomBg = ArtworkManager.getGlobalCustomBgFile(requireContext())

        if (isCustomEnabled && globalCustomBg.exists() && globalCustomBg.length() > 0) {
            binding.ambientBackground.setCustomBackgroundImage(Uri.fromFile(globalCustomBg))
        } else {
            binding.ambientBackground.setCustomBackgroundImage(null)
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NowPlayingBottomSheet"
    }
}
