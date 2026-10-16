package com.ultradeep.music.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.data.model.SystemPlaylist
import com.ultradeep.music.util.ArtworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MusicController {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null
    private var appContext: Context? = null

    // Library & Queue State
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _recentSongIds = MutableStateFlow<List<Long>>(emptyList())
    /** Most-recent first. Kept separately from saved playlists because it is automatic. */
    val recentSongIds: StateFlow<List<Long>> = _recentSongIds.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()
    val shuffleMode: StateFlow<Boolean> get() = isShuffle

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    fun init(context: Context) {
        if (controllerFuture != null) return
        appContext = context.applicationContext
        loadRecentSongs()

        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupPlayerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        val player = mediaController ?: return

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                    persistLastPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _duration.value = player.duration.coerceAtLeast(0L)
                        _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        skipNext()
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs.coerceAtLeast(0L)
            }
        })
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            var counter = 0
            while (isActive) {
                mediaController?.let { player ->
                    _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    _duration.value = player.duration.coerceAtLeast(0L)
                }
                counter++
                if (counter % 30 == 0) { // Every ~3 seconds persist position
                    persistLastPosition()
                }
                delay(100) // 100ms for smooth lyric synchronization
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun persistLastPosition() {
        val song = _currentSong.value ?: return
        val pos = _currentPosition.value
        appContext?.getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putLong("last_played_song_id", song.id)
            ?.putLong("last_played_song_pos", pos)
            ?.apply()
    }

    private fun loadRecentSongs() {
        val rawIds = appContext
            ?.getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
            ?.getString("recently_played_song_ids", "")
            .orEmpty()
        _recentSongIds.value = rawIds
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .distinct()
            .take(SystemPlaylist.MAX_LAST_PLAYED_TRACKS)
    }

    private fun recordRecentlyPlayed(songId: Long) {
        val updatedIds = (listOf(songId) + _recentSongIds.value.filterNot { it == songId })
            .take(SystemPlaylist.MAX_LAST_PLAYED_TRACKS)
        _recentSongIds.value = updatedIds
        appContext?.getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putString("recently_played_song_ids", updatedIds.joinToString(","))
            ?.apply()
    }

    fun restoreLastPlayedSong(song: Song) {
        if (_currentSong.value != null) return
        _currentSong.value = song
        _duration.value = song.duration
        val prefs = appContext?.getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)
        val lastPos = prefs?.getLong("last_played_song_pos", 0L) ?: 0L
        _currentPosition.value = lastPos
        if (_queue.value.isEmpty()) {
            _queue.value = listOf(song)
        }
    }

    fun updateLibrary(songs: List<Song>) {
        _allSongs.value = songs
        if (_queue.value.isEmpty()) {
            _queue.value = songs
        }
    }

    fun playSong(song: Song, newQueue: List<Song> = _queue.value) {
        val player = mediaController ?: return
        _queue.value = if (newQueue.isNotEmpty()) newQueue else listOf(song)
        val index = _queue.value.indexOfFirst { it.id == song.id }
        _currentIndex.value = if (index >= 0) index else 0
        _currentSong.value = song
        recordRecentlyPlayed(song.id)

        // Save last played song id to SharedPreferences
        appContext?.getSharedPreferences("ultradeep_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putLong("last_played_song_id", song.id)
            ?.apply()

        // Resolve artwork
        val customArt = appContext?.let { ArtworkManager.getArtworkForSong(it, song) }
        val artworkUri = when (customArt) {
            is Uri -> customArt
            else -> song.albumArtUri?.let { Uri.parse(it) }
        }

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(artworkUri)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.contentUri)
            .setMediaMetadata(mediaMetadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        _isPlaying.value = true
        _duration.value = song.duration
        _currentPosition.value = 0L
        startProgressTracker()
    }

    fun togglePlayPause() {
        val player = mediaController ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            if (player.playbackState == Player.STATE_IDLE && _currentSong.value != null) {
                playSong(_currentSong.value!!)
            } else {
                player.play()
                _isPlaying.value = true
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaController ?: return
        try {
            val maxDur = (_duration.value.takeIf { it > 0 } ?: _currentSong.value?.duration ?: Long.MAX_VALUE)
            val safePosition = positionMs.coerceIn(0L, maxDur)
            player.seekTo(safePosition)
            _currentPosition.value = safePosition
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Used by the now-playing press-and-hold gesture; it never changes the queue. */
    fun setPlaybackSpeed(speed: Float) {
        try {
            mediaController?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2f))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun skipNext() {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return

        val nextIndex = if (_isShuffle.value) {
            if (currentQueue.size > 1) {
                var rand = currentQueue.indices.random()
                while (rand == _currentIndex.value && currentQueue.size > 1) {
                    rand = currentQueue.indices.random()
                }
                rand
            } else 0
        } else {
            val idx = _currentIndex.value + 1
            if (idx >= currentQueue.size) {
                if (_repeatMode.value == Player.REPEAT_MODE_ALL) 0 else return
            } else idx
        }

        val nextSong = currentQueue.getOrNull(nextIndex) ?: return
        try {
            playSong(nextSong, currentQueue)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun skipPrevious() {
        val player = mediaController
        if (player != null) {
            try {
                if (player.currentPosition > 3000L) {
                    seekTo(0L)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return

        val prevIndex = if (_currentIndex.value - 1 < 0) {
            if (_repeatMode.value == Player.REPEAT_MODE_ALL) currentQueue.size - 1 else 0
        } else {
            _currentIndex.value - 1
        }
        val prevSong = currentQueue.getOrNull(prevIndex) ?: return
        try {
            playSong(prevSong, currentQueue)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        val player = mediaController ?: return
        val nextMode = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        _repeatMode.value = nextMode
    }
}
