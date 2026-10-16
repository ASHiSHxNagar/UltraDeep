package com.ultradeep.music.ui.nowplaying

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class NowPlayingBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogNowPlayingBinding? = null
    private val binding get() = _binding!!

    private lateinit var lyricsAdapter: LyricsAdapter
    private var isLyricsModeActive = false
    private var isUserTrackingSeekBar = false

    private var rawLyricsCache: String = ""
    private var currentSongOffsetMs: Long = 0L
    private var currentSongId: Long = -1L
    private var lyricsRequestSongId: Long = -1L
    private var speedHoldArmed = false
    private var isSpeedHoldActive = false
    private var speedHoldHost: View? = null
    private val speedHoldRunnable = Runnable {
        if (speedHoldArmed && MusicController.isPlaying.value) {
            isSpeedHoldActive = true
            MusicController.setPlaybackSpeed(2f)
            _binding?.tvHeaderTitle?.text = "2× SPEED"
            _binding?.contentContainer?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private var userScrollResumeRunnable: Runnable? = null
    private val userScrollResumeDelayMs = 3500L
    private var paletteJob: Job? = null
    private var favoriteJob: Job? = null
    private var lyricsJob: Job? = null

    private lateinit var prefs: SharedPreferences

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
        prefs = requireContext().getSharedPreferences("lyrics_timer_prefs", Context.MODE_PRIVATE)
        _binding = DialogNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLyricsRecyclerView()
        setupListeners()
        setupSwipeGestures()
        observeMusicController()
        updateIndicators(isLyrics = false)
    }

    private fun setupLyricsRecyclerView() {
        val ctx = context ?: return
        lyricsAdapter = LyricsAdapter { timestampMs ->
            val seekTarget = (timestampMs + currentSongOffsetMs).coerceAtLeast(0L)
            MusicController.seekTo(seekTarget)
        }

        binding.rvLyrics.apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = lyricsAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        lyricsAdapter.setUserScrolling(true)
                        userScrollResumeRunnable?.let { recyclerView.removeCallbacks(it) }
                    } else if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                        lyricsAdapter.setUserScrolling(true)
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        userScrollResumeRunnable?.let { recyclerView.removeCallbacks(it) }
                        val resumeTask = Runnable {
                            lyricsAdapter.setUserScrolling(false)
                        }
                        userScrollResumeRunnable = resumeTask
                        recyclerView.postDelayed(resumeTask, userScrollResumeDelayMs)
                    }
                }
            })
        }
    }

    private fun setupListeners() {
        binding.btnCollapse.setOnClickListener {
            dismiss()
        }

        binding.btnQueue.setOnClickListener {
            if (!parentFragmentManager.isStateSaved) {
                val existing = parentFragmentManager.findFragmentByTag(QueueBottomSheet.TAG)
                if (existing == null || !existing.isAdded) {
                    val queueSheet = QueueBottomSheet()
                    queueSheet.show(parentFragmentManager, QueueBottomSheet.TAG)
                }
            }
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
            if (!parentFragmentManager.isStateSaved) {
                val existing = parentFragmentManager.findFragmentByTag(EditLyricsBottomSheet.TAG)
                if (existing == null || !existing.isAdded) {
                    val editDialog = EditLyricsBottomSheet(currentSong, rawLyricsCache) {
                        // Refresh lyrics immediately after edit
                        loadLyricsForSong(currentSong, forceRefresh = true)
                    }
                    editDialog.show(parentFragmentManager, EditLyricsBottomSheet.TAG)
                }
            }
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

        binding.dotArtwork.setOnClickListener {
            if (isLyricsModeActive) toggleLyricsMode(false)
        }

        binding.dotLyrics.setOnClickListener {
            if (!isLyricsModeActive) toggleLyricsMode(true)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = MusicController.duration.value
                    val targetMs = (progress / 1000f * duration).toLong()
                    _binding?.tvCurrentTime?.text = formatTime(targetMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = false
                val duration = MusicController.duration.value.takeIf { it > 0 } ?: MusicController.currentSong.value?.duration ?: 0L
                val progress = seekBar?.progress ?: 0
                val targetMs = if (duration > 0) (progress / 1000f * duration).toLong() else 0L
                MusicController.seekTo(targetMs)
                _binding?.let { b ->
                    lyricsAdapter.updatePosition(
                        targetMs,
                        b.rvLyrics,
                        currentSongOffsetMs,
                        forceScroll = true,
                        immediate = true
                    )
                }
            }
        })
    }

    private fun setupSwipeGestures() {
        val ctx = context ?: return
        val b = _binding ?: return

        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val swipeThreshold = 56 * resources.displayMetrics.density
        val minFlingVelocity = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity

        // ── 1. ARTWORK GESTURES (Swipe left or right to switch to Lyrics) ───────────
        val artworkGestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.rawX - e1.rawX
                val diffY = e2.rawY - e1.rawY
                val absDiffX = kotlin.math.abs(diffX)
                val absDiffY = kotlin.math.abs(diffY)
                if (absDiffX > absDiffY * 1.15f && absDiffX > touchSlop && kotlin.math.abs(velocityX) > minFlingVelocity) {
                    toggleLyricsMode(showLyrics = true, slideLeft = (diffX < 0))
                    return true
                }
                return false
            }
        })

        var artStartRawX = 0f
        var artStartRawY = 0f
        var isArtDragging = false

        val artworkTouchHandler: (View, MotionEvent) -> Boolean = { view, event ->
            handleSpeedHold(view, event)
            artworkGestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    artStartRawX = event.rawX
                    artStartRawY = event.rawY
                    isArtDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - artStartRawX
                    val dy = event.rawY - artStartRawY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    if (!isArtDragging && absDx > touchSlop && absDx > absDy * 1.15f) {
                        isArtDragging = true
                        stopSpeedHold(view)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isArtDragging) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - artStartRawX
                    val dy = event.rawY - artStartRawY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    if (isArtDragging && absDx > swipeThreshold && absDx > absDy) {
                        toggleLyricsMode(showLyrics = true, slideLeft = (dx < 0))
                    }
                    isArtDragging = false
                    stopSpeedHold(view)
                }
                MotionEvent.ACTION_CANCEL -> {
                    isArtDragging = false
                    stopSpeedHold(view)
                }
            }
            true
        }

        b.artworkViewLayout.setOnTouchListener(artworkTouchHandler)
        b.ivAlbumArt.setOnTouchListener(artworkTouchHandler)
        b.contentContainer.setOnTouchListener { view, event ->
            if (!isLyricsModeActive) {
                artworkTouchHandler(view, event)
            } else {
                false
            }
        }

        // ── 2. LYRICS GESTURES (Swipe left or right to switch to Artwork) ───────────
        val lyricsGestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.rawX - e1.rawX
                val diffY = e2.rawY - e1.rawY
                val absDiffX = kotlin.math.abs(diffX)
                val absDiffY = kotlin.math.abs(diffY)
                if (absDiffX > absDiffY * 1.25f && absDiffX > touchSlop && kotlin.math.abs(velocityX) > minFlingVelocity) {
                    toggleLyricsMode(showLyrics = false, slideLeft = (diffX < 0))
                    return true
                }
                return false
            }
        })

        var lyricsStartRawX = 0f
        var lyricsStartRawY = 0f
        var isLyricsDragging = false

        val lyricsTouchHandler: (View, MotionEvent) -> Boolean = { view, event ->
            lyricsGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lyricsStartRawX = event.rawX
                    lyricsStartRawY = event.rawY
                    isLyricsDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lyricsStartRawX
                    val dy = event.rawY - lyricsStartRawY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    if (!isLyricsDragging && absDx > touchSlop && absDx > absDy * 1.25f) {
                        isLyricsDragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isLyricsDragging) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - lyricsStartRawX
                    val dy = event.rawY - lyricsStartRawY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)

                    if (isLyricsDragging && absDx > swipeThreshold && absDx > absDy) {
                        toggleLyricsMode(showLyrics = false, slideLeft = (dx < 0))
                    }
                    isLyricsDragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isLyricsDragging = false
                }
            }
            true
        }

        b.lyricsViewLayout.setOnTouchListener(lyricsTouchHandler)
        b.lyricsGlassContainer.setOnTouchListener(lyricsTouchHandler)

        // Intercept horizontal gestures inside RecyclerView without disturbing vertical lyrics scrolling
        b.rvLyrics.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var rvStartRawX = 0f
            private var rvStartRawY = 0f
            private var isHorizontalInterception = false
            private var isInterceptionLocked = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                lyricsGestureDetector.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        rvStartRawX = e.rawX
                        rvStartRawY = e.rawY
                        isHorizontalInterception = false
                        isInterceptionLocked = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - rvStartRawX
                        val dy = e.rawY - rvStartRawY
                        val absDx = kotlin.math.abs(dx)
                        val absDy = kotlin.math.abs(dy)

                        if (!isInterceptionLocked && absDx > touchSlop) {
                            if (absDx > absDy * 1.35f) {
                                // Definite horizontal swipe gesture
                                isHorizontalInterception = true
                                isInterceptionLocked = true
                                rv.parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            } else {
                                // Vertical scroll - let RecyclerView handle it
                                isInterceptionLocked = true
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHorizontalInterception = false
                        isInterceptionLocked = false
                    }
                }
                return isHorizontalInterception
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                lyricsGestureDetector.onTouchEvent(e)
                rv.parent?.requestDisallowInterceptTouchEvent(true)
                when (e.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        val dx = e.rawX - rvStartRawX
                        val dy = e.rawY - rvStartRawY
                        val absDx = kotlin.math.abs(dx)
                        val absDy = kotlin.math.abs(dy)

                        if (absDx > swipeThreshold && absDx > absDy) {
                            toggleLyricsMode(showLyrics = false, slideLeft = (dx < 0))
                        }
                        isHorizontalInterception = false
                        isInterceptionLocked = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isHorizontalInterception = false
                        isInterceptionLocked = false
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                // No-op
            }
        })
    }


    /**
     * Holding the top-right 40% x 40% of artwork temporarily plays at 2×.
     * A normal touch, scroll or swipe never changes speed; releasing always restores 1×.
     */
    private fun handleSpeedHold(view: View, event: MotionEvent) {
        val inSpeedZone = event.x >= view.width * 0.60f && event.y <= view.height * 0.40f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (inSpeedZone && MusicController.isPlaying.value) {
                    speedHoldArmed = true
                    speedHoldHost = view
                    view.postDelayed(speedHoldRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> if (!inSpeedZone) stopSpeedHold(view)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopSpeedHold(view)
        }
    }

    private fun stopSpeedHold(view: View? = null) {
        view?.removeCallbacks(speedHoldRunnable)
        speedHoldHost?.removeCallbacks(speedHoldRunnable)
        speedHoldHost = null
        _binding?.let { b ->
            b.contentContainer.removeCallbacks(speedHoldRunnable)
            b.rvLyrics.removeCallbacks(speedHoldRunnable)
            if (isSpeedHoldActive) {
                isSpeedHoldActive = false
                MusicController.setPlaybackSpeed(1f)
                b.tvHeaderTitle.setText(R.string.now_playing)
            }
        }
        speedHoldArmed = false
    }

    private fun adjustSyncOffset(deltaMs: Long) {
        setSyncOffset(currentSongOffsetMs + deltaMs)
    }

    private fun setSyncOffset(newOffsetMs: Long) {
        currentSongOffsetMs = newOffsetMs
        if (currentSongId != -1L) {
            prefs.edit().putLong("lyrics_offset_$currentSongId", currentSongOffsetMs).apply()
        }
        val b = _binding ?: return
        updateOffsetUI()
        lyricsAdapter.updatePosition(
            MusicController.currentPosition.value,
            b.rvLyrics,
            currentSongOffsetMs,
            forceScroll = true,
            immediate = true
        )
    }

    private fun updateOffsetUI() {
        val seconds = currentSongOffsetMs / 1000f
        _binding?.tvCurrentOffset?.text = if (currentSongOffsetMs == 0L) "Sync: 0.0s" else String.format(Locale.getDefault(), "Sync: %+.1fs", seconds)
    }

    private fun showCustomOffsetInputDialog() {
        val currentPosSec = MusicController.currentPosition.value / 1000f

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 24, 60, 12)
        }

        val tvInfo = TextView(requireContext()).apply {
            text = String.format(
                Locale.getDefault(),
                "Use + to show lyrics later and − to show them earlier.\nFormats: +1.5, -0.75, or +00:00:01.500"
            )
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvInfo)

        val input = EditText(requireContext()).apply {
            hint = "e.g. +1.5 or -00:00:00.750"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(String.format(Locale.getDefault(), "%+.3f", currentSongOffsetMs / 1000f))
        }
        layout.addView(input)

        val btnUseCurrent = MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = String.format(Locale.getDefault(), "Set to current position (+%.1fs)", currentPosSec)
            setOnClickListener {
                input.setText(String.format(Locale.getDefault(), "%+.3f", currentPosSec))
            }
        }
        layout.addView(btnUseCurrent)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Set Lyrics Sync Offset")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val offset = parseSignedOffsetMs(input.text.toString())
                val hostContext = context ?: return@setPositiveButton
                if (offset == null) {
                    Toast.makeText(hostContext, "Enter +1.5, -0.75, or HH:MM:SS", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setSyncOffset(offset)
                Toast.makeText(
                    hostContext,
                    String.format(Locale.getDefault(), "Lyrics offset set to %+.2fs", offset / 1000f),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseSignedOffsetMs(value: String): Long? {
        val raw = value.trim().replace('−', '-')
        if (raw.isBlank()) return null
        raw.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        val match = Regex("^([+-])?(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?$").matchEntire(raw) ?: return null
        val hours = match.groupValues[2].toLongOrNull() ?: return null
        val minutes = match.groupValues[3].toLongOrNull() ?: return null
        val seconds = match.groupValues[4].toLongOrNull() ?: return null
        if (minutes > 59 || seconds > 59) return null
        val millis = match.groupValues[5].padEnd(3, '0').toLongOrNull() ?: 0L
        val positive = ((hours * 3600 + minutes * 60 + seconds) * 1000) + millis
        return if (match.groupValues[1] == "-") -positive else positive
    }

    private fun toggleLyricsMode(showLyrics: Boolean, slideLeft: Boolean = showLyrics) {
        if (isLyricsModeActive == showLyrics) return
        val b = _binding ?: return
        isLyricsModeActive = showLyrics
        val width = b.contentContainer.width.toFloat().let { if (it > 0) it else 1000f }

        b.artworkViewLayout.animate().cancel()
        b.lyricsViewLayout.animate().cancel()

        try {
            b.contentContainer.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}

        val enterStart = if (slideLeft) width else -width
        val exitEnd = if (slideLeft) -width else width

        if (showLyrics) {
            // Animate Artwork sliding out, Lyrics sliding in
            b.lyricsViewLayout.translationX = enterStart
            b.lyricsViewLayout.alpha = 0f
            b.lyricsViewLayout.visibility = View.VISIBLE

            b.artworkViewLayout.animate()
                .translationX(exitEnd)
                .alpha(0f)
                .setDuration(280)
                .withEndAction {
                    _binding?.artworkViewLayout?.visibility = View.GONE
                }
                .start()

            b.lyricsViewLayout.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(280)
                .start()

            b.btnToggleLyricsMode.text = getString(R.string.now_playing)
            b.btnToggleLyricsMode.setIconResource(R.drawable.ic_album)
            updateIndicators(isLyrics = true)
        } else {
            // Animate Lyrics sliding out, Artwork sliding in
            b.artworkViewLayout.translationX = enterStart
            b.artworkViewLayout.alpha = 0f
            b.artworkViewLayout.visibility = View.VISIBLE

            b.lyricsViewLayout.animate()
                .translationX(exitEnd)
                .alpha(0f)
                .setDuration(280)
                .withEndAction {
                    _binding?.lyricsViewLayout?.visibility = View.GONE
                }
                .start()

            b.artworkViewLayout.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(280)
                .start()

            b.btnToggleLyricsMode.text = getString(R.string.lyrics_title)
            b.btnToggleLyricsMode.setIconResource(R.drawable.ic_lyrics)
            updateIndicators(isLyrics = false)
        }
    }

    private fun updateIndicators(isLyrics: Boolean) {
        val ctx = context ?: return
        val b = _binding ?: return
        val activeColor = ContextCompat.getColor(ctx, R.color.neon_cyan)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.text_tertiary)

        b.dotArtwork.backgroundTintList = ColorStateList.valueOf(if (!isLyrics) activeColor else inactiveColor)
        b.dotLyrics.backgroundTintList = ColorStateList.valueOf(if (isLyrics) activeColor else inactiveColor)

        val activeWidth = (16 * resources.displayMetrics.density).toInt()
        val inactiveWidth = (6 * resources.displayMetrics.density).toInt()

        b.dotArtwork.layoutParams = b.dotArtwork.layoutParams.apply {
            width = if (!isLyrics) activeWidth else inactiveWidth
        }
        b.dotLyrics.layoutParams = b.dotLyrics.layoutParams.apply {
            width = if (isLyrics) activeWidth else inactiveWidth
        }
    }

    private fun observeMusicController() {
        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.currentSong.collectLatest { song ->
                if (song != null && isAdded && _binding != null) {
                    updateSongUI(song)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.isPlaying.collectLatest { isPlaying ->
                if (!isAdded || _binding == null) return@collectLatest
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.currentPosition.collectLatest { posMs ->
                if (!isAdded || _binding == null) return@collectLatest
                if (!isUserTrackingSeekBar) {
                    val duration = MusicController.duration.value
                    if (duration > 0) {
                        val progress = ((posMs.toFloat() / duration) * 1000).toInt()
                        binding.seekBar.progress = progress
                    }
                    binding.tvCurrentTime.text = formatTime(posMs)
                }

                // Sync lyrics auto-scroll and highlight
                lyricsAdapter.updatePosition(posMs, binding.rvLyrics, currentSongOffsetMs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.duration.collectLatest { durationMs ->
                if (!isAdded || _binding == null) return@collectLatest
                binding.tvTotalDuration.text = formatTime(durationMs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.repeatMode.collectLatest { mode ->
                if (!isAdded || _binding == null) return@collectLatest
                when (mode) {
                    Player.REPEAT_MODE_OFF -> {
                        binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                        binding.btnRepeat.alpha = 0.5f
                    }
                    Player.REPEAT_MODE_ALL -> {
                        binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                        binding.btnRepeat.alpha = 1.0f
                    }
                    Player.REPEAT_MODE_ONE -> {
                        binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                        binding.btnRepeat.alpha = 1.0f
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MusicController.isShuffle.collectLatest { shuffleOn ->
                if (!isAdded || _binding == null) return@collectLatest
                binding.btnShuffle.alpha = if (shuffleOn) 1.0f else 0.5f
            }
        }
    }

    private fun updateSongUI(song: Song) {
        val ctx = context ?: return
        val b = _binding ?: return
        if (!isAdded) return

        paletteJob?.cancel()
        favoriteJob?.cancel()
        lyricsJob?.cancel()

        currentSongId = song.id
        currentSongOffsetMs = prefs.getLong("lyrics_offset_${song.id}", 0L)
        updateOffsetUI()

        b.tvTitle.text = song.title
        b.tvArtist.text = song.artist

        // Load album art via ArtworkManager
        val art = ArtworkManager.getArtworkForSong(ctx, song)
        b.ivAlbumArt.load(art) {
            crossfade(true)
            placeholder(R.drawable.bg_artwork_placeholder)
            error(R.drawable.bg_artwork_placeholder)
        }

        // Extract palette ambient colors and illuminate the ambient glow
        val songIdSnapshot = song.id
        paletteJob = viewLifecycleOwner.lifecycleScope.launch {
            val artUri = if (art is Uri) art.toString() else null
            val colors = PaletteUtil.extractColorsFromUri(ctx, artUri)
            if (!isAdded || _binding == null || currentSongId != songIdSnapshot) return@launch
            _binding?.ambientBackground?.setAmbientColors(colors)
            _binding?.ambientGlowView?.setGlowColors(colors.topColor, colors.centerColor)
        }

        // Observe favorite state
        val favoriteDao = UltraDeepApplication.instance.database.favoriteDao()
        favoriteJob = viewLifecycleOwner.lifecycleScope.launch {
            favoriteDao.isFavorite(song.id).collectLatest { isFav ->
                val currentB = _binding ?: return@collectLatest
                val currentCtx = context ?: return@collectLatest
                currentB.btnFavorite.setImageResource(
                    if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart
                )
                currentB.btnFavorite.imageTintList = ContextCompat.getColorStateList(
                    currentCtx,
                    if (isFav) R.color.favorite_active else R.color.text_primary
                )
            }
        }

        // Fetch lyrics
        loadLyricsForSong(song, forceRefresh = false)
    }

    private fun loadLyricsForSong(song: Song, forceRefresh: Boolean) {
        lyricsRequestSongId = song.id
        val b = _binding ?: return
        b.tvLyricsStatus.visibility = View.VISIBLE
        b.tvLyricsStatus.text = "Loading saved lyrics…"

        val repository = UltraDeepApplication.instance.lyricsRepository
        lyricsJob?.cancel()
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Playback never starts a network request on its own. Fetching is explicit
                // from the track menu or the Settings bulk actions, and every successful
                // response is persisted before this screen renders it.
                if (!repository.hasSavedLyrics(song.id)) {
                    val currentB = _binding ?: return@launch
                    if (isAdded && lyricsRequestSongId == song.id && currentSongId == song.id) {
                        rawLyricsCache = ""
                        lyricsAdapter.submitLines(emptyList(), currentB.rvLyrics)
                        currentB.tvLyricsStatus.text = "Lyrics not saved yet • use Fetch lyrics in the track menu"
                    }
                    return@launch
                }
                val result = repository.getLyrics(song, forceRefresh)
                val currentB = _binding ?: return@launch
                // Requests can finish out of order when the user skips quickly.
                if (!isAdded || lyricsRequestSongId != song.id || currentSongId != song.id) return@launch
                rawLyricsCache = if (result.isSynced) {
                    result.lines.joinToString("\n") { line ->
                        val minutes = line.timestampMs / 60_000
                        val seconds = (line.timestampMs / 1_000) % 60
                        val millis = line.timestampMs % 1_000
                        String.format(Locale.US, "[%02d:%02d.%03d]%s", minutes, seconds, millis, line.text)
                    }
                } else result.plainText

                if (result.isInstrumental) {
                    currentB.tvLyricsStatus.visibility = View.VISIBLE
                    currentB.tvLyricsStatus.text = getString(R.string.lyrics_instrumental)
                    lyricsAdapter.submitLines(emptyList(), currentB.rvLyrics)
                } else if (result.lines.isEmpty()) {
                    currentB.tvLyricsStatus.visibility = View.VISIBLE
                    currentB.tvLyricsStatus.text = getString(R.string.lyrics_none)
                    lyricsAdapter.submitLines(emptyList(), currentB.rvLyrics)
                } else {
                    currentB.tvLyricsStatus.visibility = View.GONE
                    lyricsAdapter.submitLines(result.lines, currentB.rvLyrics)
                    updateOffsetUI()
                    lyricsAdapter.updatePosition(
                        MusicController.currentPosition.value,
                        currentB.rvLyrics,
                        currentSongOffsetMs,
                        forceScroll = true,
                        immediate = true
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val currentB = _binding
                if (isAdded && currentB != null && lyricsRequestSongId == song.id && currentSongId == song.id) {
                    currentB.tvLyricsStatus.visibility = View.VISIBLE
                    currentB.tvLyricsStatus.text = "Lyrics unavailable right now"
                }
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

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    override fun onDestroyView() {
        _binding?.let { b ->
            userScrollResumeRunnable?.let { b.rvLyrics.removeCallbacks(it) }
            b.artworkViewLayout.animate().cancel()
            b.lyricsViewLayout.animate().cancel()
        }
        userScrollResumeRunnable = null
        paletteJob?.cancel()
        favoriteJob?.cancel()
        lyricsJob?.cancel()
        stopSpeedHold()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NowPlayingBottomSheet"
    }
}
