package com.ultradeep.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.data.model.SortOption
import com.ultradeep.music.databinding.ActivityMainBinding
import com.ultradeep.music.scanner.AudioScanner
import com.ultradeep.music.service.MusicController
import com.ultradeep.music.ui.adapter.PlaylistAdapter
import com.ultradeep.music.ui.adapter.SongAdapter
import com.ultradeep.music.ui.nowplaying.NowPlayingBottomSheet
import com.ultradeep.music.ui.playlist.AddToPlaylistBottomSheet
import com.ultradeep.music.ui.playlist.CreatePlaylistDialog
import com.ultradeep.music.ui.settings.SettingsBottomSheet
import com.ultradeep.music.ui.view.AmbientCardAnimator
import com.ultradeep.music.util.BackupManager
import com.ultradeep.music.util.PaletteUtil
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var songAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var allTracks = listOf<Song>()
    private var displayedTracks = listOf<Song>()
    private var currentSortOption = SortOption.DATE_ADDED_DESC
    private var activeTabPosition = 0
    private var currentSearchQuery = ""
    private var favoriteSongIds = setOf<Long>()
    private lateinit var miniPlayerAmbient: AmbientCardAnimator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        if (audioGranted) {
            scanLocalAudio()
        } else {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.tvEmptyMessage.text = getString(R.string.permission_rationale)
            binding.btnEmptyAction.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miniPlayerAmbient = AmbientCardAnimator(binding.miniPlayerLayout.miniPlayerRoot)

        setupRecyclerViews()
        setupMiniPlayer()
        setupListeners()
        setupTabs()
        observeMusicController()
        observeFavorites()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            scanLocalAudio()
        }
    }

    private fun scanLocalAudio() {
        lifecycleScope.launch {
            val songs = AudioScanner.scanAudioFiles(this@MainActivity)
            allTracks = songs
            MusicController.updateLibrary(songs)
            applyFilterAndSort()

            // Restore last played song so it appears in the bottom mini-player
            if (MusicController.currentSong.value == null) {
                val prefs = getSharedPreferences("ultradeep_prefs", android.content.Context.MODE_PRIVATE)
                val lastId = prefs.getLong("last_played_song_id", -1L)
                if (lastId != -1L) {
                    val lastSong = songs.firstOrNull { it.id == lastId }
                    if (lastSong != null) {
                        MusicController.restoreLastPlayedSong(lastSong)
                    }
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        songAdapter = SongAdapter(
            onSongClicked = { song, queue ->
                MusicController.playSong(song, queue)
                val nowPlayingSheet = NowPlayingBottomSheet()
                nowPlayingSheet.show(supportFragmentManager, NowPlayingBottomSheet.TAG)
            },
            onSongMoreClicked = { song ->
                showSongOptionsBottomSheet(song)
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }

        val playlistDao = UltraDeepApplication.instance.database.playlistDao()
        playlistAdapter = PlaylistAdapter(
            onPlaylistClicked = { playlist ->
                val detailSheet = com.ultradeep.music.ui.playlist.PlaylistDetailBottomSheet(playlist)
                detailSheet.show(supportFragmentManager, com.ultradeep.music.ui.playlist.PlaylistDetailBottomSheet.TAG)
            },
            onDeleteClicked = { playlist ->
                lifecycleScope.launch {
                    playlistDao.deletePlaylist(playlist.id)
                    Toast.makeText(this@MainActivity, "Playlist deleted", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = playlistAdapter
        }

        lifecycleScope.launch {
            playlistDao.getAllPlaylists().collectLatest { playlists ->
                playlistAdapter.submitList(playlists)
            }
        }
    }

    private fun setupMiniPlayer() {
        val mini = binding.miniPlayerLayout

        mini.miniPlayerRoot.setOnClickListener {
            val sheet = NowPlayingBottomSheet()
            sheet.show(supportFragmentManager, NowPlayingBottomSheet.TAG)
        }

        mini.btnMiniPlayPause.setOnClickListener {
            MusicController.togglePlayPause()
        }

        mini.btnMiniNext.setOnClickListener {
            MusicController.skipNext()
        }
    }

    private fun setupListeners() {
        binding.btnEmptyAction.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.btnSort.setOnClickListener {
            showSortDialog()
        }

        binding.btnQuickRandomize.setOnClickListener {
            currentSortOption = SortOption.RANDOM
            applyFilterAndSort()
            Toast.makeText(this, "Order randomized 🎲", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettings.setOnClickListener {
            val settingsSheet = SettingsBottomSheet {
                scanLocalAudio()
            }
            settingsSheet.show(supportFragmentManager, SettingsBottomSheet.TAG)
        }

        binding.btnLyricsSync.setOnClickListener {
            SettingsBottomSheet({ scanLocalAudio() }, openLyricsSyncSection = true)
                .show(supportFragmentManager, SettingsBottomSheet.TAG)
        }

        binding.fabAddPlaylist.setOnClickListener {
            val dialog = CreatePlaylistDialog {}
            dialog.show(supportFragmentManager, CreatePlaylistDialog.TAG)
        }

        binding.etSearch.doAfterTextChanged { text ->
            currentSearchQuery = text?.toString()?.trim() ?: ""
            applyFilterAndSort()
        }

        binding.tilSearch.setEndIconOnClickListener {
            binding.etSearch.text?.clear()
            binding.etSearch.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                activeTabPosition = tab?.position ?: 0
                when (activeTabPosition) {
                    0 -> { // Tracks
                        binding.rvSongs.visibility = View.VISIBLE
                        binding.rvPlaylists.visibility = View.GONE
                        binding.fabAddPlaylist.visibility = View.GONE
                    }
                    1 -> { // Playlists
                        binding.rvSongs.visibility = View.GONE
                        binding.rvPlaylists.visibility = View.VISIBLE
                        binding.fabAddPlaylist.visibility = View.VISIBLE
                    }
                    2 -> { // Favorites
                        binding.rvSongs.visibility = View.VISIBLE
                        binding.rvPlaylists.visibility = View.GONE
                        binding.fabAddPlaylist.visibility = View.GONE
                    }
                }
                applyFilterAndSort()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun observeFavorites() {
        val favoriteDao = UltraDeepApplication.instance.database.favoriteDao()
        lifecycleScope.launch {
            favoriteDao.getAllFavoriteSongIds().collectLatest { ids ->
                favoriteSongIds = ids.toSet()
                if (activeTabPosition == 2) {
                    applyFilterAndSort()
                }
            }
        }
    }

    private fun observeMusicController() {
        val mini = binding.miniPlayerLayout

        lifecycleScope.launch {
            MusicController.currentSong.collectLatest { song ->
                if (song != null) {
                    mini.miniPlayerRoot.visibility = View.VISIBLE
                    mini.tvMiniTitle.text = song.title
                    mini.tvMiniTitle.isSelected = true
                    mini.tvMiniArtist.text = song.artist

                    val art = com.ultradeep.music.util.ArtworkManager.getArtworkForSong(this@MainActivity, song)
                    mini.ivMiniThumbnail.load(art) {
                        crossfade(true)
                        placeholder(R.drawable.bg_artwork_placeholder)
                        error(R.drawable.bg_artwork_placeholder)
                    }

                    songAdapter.setActiveSong(song.id)
                    lifecycleScope.launch {
                        val artUri = (art as? android.net.Uri)?.toString()
                        val colors = PaletteUtil.extractColorsFromUri(this@MainActivity, artUri)
                        // An old artwork job must not recolour a newly-selected track.
                        if (MusicController.currentSong.value?.id == song.id) {
                            miniPlayerAmbient.setColors(colors.topColor, colors.centerColor)
                            mini.wavyAmbientBorderView.setColors(colors.topColor, colors.centerColor)
                            songAdapter.setActiveSong(song.id, colors.topColor, colors.centerColor)
                        }
                    }
                } else {
                    mini.miniPlayerRoot.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            MusicController.isPlaying.collectLatest { isPlaying ->
                mini.btnMiniPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                mini.equalizerBarsView.setPlaying(isPlaying)
                mini.wavyAmbientBorderView.setPlaying(isPlaying)
            }
        }

        lifecycleScope.launch {
            MusicController.currentPosition.collectLatest { posMs ->
                val duration = MusicController.duration.value
                if (duration > 0) {
                    val progress = ((posMs.toFloat() / duration) * 1000).toInt()
                    mini.pbMiniProgress.progress = progress
                }
            }
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_date_added),
            getString(R.string.sort_date_added_oldest),
            getString(R.string.sort_title),
            getString(R.string.sort_artist),
            getString(R.string.sort_duration),
            getString(R.string.sort_random)
        )

        val selectedIndex = when (currentSortOption) {
            SortOption.DATE_ADDED_DESC -> 0
            SortOption.DATE_ADDED_ASC -> 1
            SortOption.TITLE_ASC -> 2
            SortOption.ARTIST_ASC -> 3
            SortOption.DURATION_DESC -> 4
            SortOption.RANDOM -> 5
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                currentSortOption = when (which) {
                    0 -> SortOption.DATE_ADDED_DESC
                    1 -> SortOption.DATE_ADDED_ASC
                    2 -> SortOption.TITLE_ASC
                    3 -> SortOption.ARTIST_ASC
                    4 -> SortOption.DURATION_DESC
                    else -> SortOption.RANDOM
                }
                applyFilterAndSort()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyFilterAndSort() {
        var baseList = when (activeTabPosition) {
            2 -> allTracks.filter { favoriteSongIds.contains(it.id) }
            else -> allTracks
        }

        // Apply Search Query Filter
        if (currentSearchQuery.isNotEmpty()) {
            baseList = baseList.filter {
                it.title.contains(currentSearchQuery, ignoreCase = true) ||
                        it.artist.contains(currentSearchQuery, ignoreCase = true) ||
                        it.album.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        // Apply Sorting
        val sortedList = when (currentSortOption) {
            SortOption.DATE_ADDED_DESC -> {
                binding.tvActiveSort.text = getString(R.string.sort_date_added)
                baseList.sortedByDescending { it.dateAdded }
            }
            SortOption.DATE_ADDED_ASC -> {
                binding.tvActiveSort.text = getString(R.string.sort_date_added_oldest)
                baseList.sortedBy { it.dateAdded }
            }
            SortOption.TITLE_ASC -> {
                binding.tvActiveSort.text = getString(R.string.sort_title)
                baseList.sortedBy { it.title.lowercase() }
            }
            SortOption.ARTIST_ASC -> {
                binding.tvActiveSort.text = getString(R.string.sort_artist)
                baseList.sortedBy { it.artist.lowercase() }
            }
            SortOption.DURATION_DESC -> {
                binding.tvActiveSort.text = getString(R.string.sort_duration)
                baseList.sortedByDescending { it.duration }
            }
            SortOption.RANDOM -> {
                binding.tvActiveSort.text = getString(R.string.sort_random)
                baseList.shuffled()
            }
        }

        displayedTracks = sortedList
        songAdapter.submitList(sortedList)

        if (activeTabPosition != 1) {
            if (sortedList.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.btnEmptyAction.visibility = View.GONE
                binding.tvEmptyMessage.text = if (activeTabPosition == 2) {
                    "No favorite tracks yet ❤️"
                } else {
                    getString(R.string.no_tracks_found)
                }
            } else {
                binding.layoutEmpty.visibility = View.GONE
            }
        } else {
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun showSongOptionsBottomSheet(song: Song) {
        val optionsSheet = com.ultradeep.music.ui.options.SongOptionsBottomSheet(song) {
            applyFilterAndSort()
        }
        optionsSheet.show(supportFragmentManager, com.ultradeep.music.ui.options.SongOptionsBottomSheet.TAG)
    }

    override fun onStop() {
        super.onStop()
        UltraDeepApplication.instance.applicationScope.launch {
            BackupManager.updateAutoBackup(this@MainActivity)
        }
    }

    override fun onDestroy() {
        miniPlayerAmbient.release()
        super.onDestroy()
    }
}
