package com.ultradeep.music.ui.lyrics

import android.os.Bundle
import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ultradeep.music.R
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.databinding.DialogEditLyricsBinding
import com.ultradeep.music.util.LrcParser
import kotlinx.coroutines.launch
import java.util.Locale

class EditLyricsBottomSheet : BottomSheetDialogFragment {

    private var song: Song? = null
    private var initialContent = ""
    private var onLyricsUpdated: (() -> Unit)? = null
    private var editing = false
    private val rows = mutableListOf<RowViews>()

    constructor() : super()
    constructor(song: Song, initialContent: String, onLyricsUpdated: () -> Unit) : super() {
        this.song = song
        this.initialContent = initialContent
        this.onLyricsUpdated = onLyricsUpdated
    }

    private var _binding: DialogEditLyricsBinding? = null
    private val binding get() = _binding!!
    private data class RowViews(val time: EditText, val lyric: EditText, var baselineMs: Long? = null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            setOnShowListener {
                val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
                val height = (resources.displayMetrics.heightPixels * 0.96f).toInt()
                sheet.layoutParams = sheet.layoutParams.apply { this.height = height }
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                    peekHeight = height
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogEditLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentSong = song ?: run { dismissAllowingStateLoss(); return }
        binding.tvSongSubtitle.text = "${currentSong.title} • ${currentSong.artist}"
        populateRows(initialContent)
        setEditing(false)

        binding.btnEditMode.setOnClickListener { setEditing(true) }
        binding.btnAddLyricLine.setOnClickListener { addRow("", "") }
        binding.btnPasteLyrics.setOnClickListener { showPasteLyricsDialog() }
        binding.btnApplyTimeShift.setOnClickListener {
            val count = applyTimeShift()
            if (count > 0) {
                Toast.makeText(requireContext(), "Updated timestamps for $count lines", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "No timestamp changes detected", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCancelEdit.setOnClickListener {
            populateRows(initialContent)
            setEditing(false)
        }
        binding.btnFetchUrl.setOnClickListener { importFromUrl() }
        binding.btnSaveLyrics.setOnClickListener { saveRows(currentSong) }
        binding.btnResetLyrics.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                UltraDeepApplication.instance.lyricsRepository.resetLyrics(currentSong.id)
                Toast.makeText(requireContext(), "Saved lyrics removed", Toast.LENGTH_SHORT).show()
                onLyricsUpdated?.invoke()
                dismiss()
            }
        }
    }

    private fun populateRows(content: String) {
        rows.clear()
        binding.lyricsRowsContainer.removeAllViews()
        val parsed = LrcParser.parse(content, true)
        if (parsed.lines.isEmpty()) addRow("", "No lyrics saved. Tap Edit to paste or import an LRC file.")
        else parsed.lines.forEach { line ->
            addRow(if (line.timestampMs >= 0) formatTimestamp(line.timestampMs) else "", line.text)
        }
    }

    private fun addRow(timestamp: String, lyricText: String) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val horizontalStartPadding = (16 * density).toInt()
        val horizontalEndPadding = (12 * density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val time = EditText(context).apply {
            setText(timestamp)
            hint = "HH:MM:SS"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setHorizontallyScrolling(true)
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_lyrics_editor)
            setPadding(horizontalStartPadding, 0, horizontalEndPadding, 0)
            layoutParams = LinearLayout.LayoutParams((126 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt())
        }
        val lyric = EditText(context).apply {
            setText(lyricText)
            hint = "Lyric"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setHorizontallyScrolling(true)
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_lyrics_editor)
            setPadding(horizontalStartPadding, 0, horizontalEndPadding, 0)
            layoutParams = LinearLayout.LayoutParams(0, (48 * resources.displayMetrics.density).toInt(), 1f).apply {
                marginStart = (7 * resources.displayMetrics.density).toInt()
            }
        }
        row.addView(time); row.addView(lyric)
        binding.lyricsRowsContainer.addView(row)
        val parsedMs = parseTimestamp(timestamp)
        rows += RowViews(time, lyric, baselineMs = parsedMs)
    }

    private fun setEditing(enabled: Boolean) {
        editing = enabled
        rows.forEach { (time, lyric) ->
            // Keep read-only rows touchable so a long timestamp or lyric can still be
            // horizontally scrolled; focus and editing stay disabled until Edit is tapped.
            listOf(time, lyric).forEach { field ->
                field.isEnabled = true
                field.isFocusable = enabled
                field.isFocusableInTouchMode = enabled
                field.isCursorVisible = enabled
            }
        }
        binding.btnEditMode.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.autoShiftContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.editActions.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnAddLyricLine.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnPasteLyrics.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.urlFetchContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnResetLyrics.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun showPasteLyricsDialog() {
        val context = requireContext()
        val currentText = buildString {
            rows.forEach { (time, lyric) ->
                val t = time.text.toString().trim()
                val l = lyric.text.toString().trim()
                if (l.isNotEmpty() || t.isNotEmpty()) {
                    if (t.isNotEmpty()) append("[$t] ")
                    appendLine(l)
                }
            }
        }.trim()

        val editText = EditText(context).apply {
            hint = "Paste lyrics with or without timestamps [mm:ss.xx]...\nLeave empty and tap OK to clear all lyrics."
            setText(currentText)
            setSelection(text.length)
            isSingleLine = false
            minLines = 7
            maxLines = 14
            isVerticalScrollBarEnabled = true
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_lyrics_editor)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Paste Lyrics")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                applyPastedLyrics(editText.text?.toString() ?: "")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPastedLyrics(rawText: String) {
        rows.clear()
        binding.lyricsRowsContainer.removeAllViews()

        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            // User pasted nothing / cleared: remove all boxes and keep 1 empty row as placeholder
            addRow("", "")
            setEditing(true)
            Toast.makeText(requireContext(), "All lyric lines cleared", Toast.LENGTH_SHORT).show()
            return
        }

        val timeRegex = Regex("^\\[?(\\d{1,2}:)?(\\d{1,2}):(\\d{2})(?:[\\.:](\\d{1,3}))?\\]?\\s*(.*)$")
        var addedCount = 0

        trimmed.lineSequence().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // Skip LRC metadata tags like [ti:...], [ar:...], [al:...]
            if (trimmedLine.startsWith("[") && trimmedLine.contains(":") && !trimmedLine.substring(1, 2).all { it.isDigit() }) {
                return@forEach
            }

            val match = timeRegex.matchEntire(trimmedLine)
            if (match != null) {
                val hours = match.groupValues[1].removeSuffix(":").toLongOrNull() ?: 0L
                val minutes = match.groupValues[2].toLongOrNull() ?: 0L
                val seconds = match.groupValues[3].toLongOrNull() ?: 0L
                val fraction = match.groupValues[4].padEnd(3, '0').toLongOrNull() ?: 0L
                val lyricText = match.groupValues[5].trim()

                if (minutes < 60 && seconds < 60) {
                    val totalMs = ((hours * 3600 + minutes * 60 + seconds) * 1000) + fraction
                    addRow(formatTimestamp(totalMs), lyricText)
                    addedCount++
                    return@forEach
                }
            }

            // Normal text line without timestamp
            addRow("", trimmedLine)
            addedCount++
        }

        if (addedCount == 0) {
            addRow("", "")
        }
        setEditing(true)
        Toast.makeText(requireContext(), "Imported $addedCount lyric lines", Toast.LENGTH_SHORT).show()
    }

    private fun importFromUrl() {
        val url = binding.etLrcUrl.text?.toString()?.trim()
        if (url.isNullOrEmpty()) { Toast.makeText(requireContext(), "Enter an LRC URL", Toast.LENGTH_SHORT).show(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnFetchUrl.isEnabled = false
            val fetched = UltraDeepApplication.instance.lyricsRepository.fetchLrcFromUrl(url)
            binding.btnFetchUrl.isEnabled = true
            if (fetched.isNullOrBlank()) Toast.makeText(requireContext(), "Could not import LRC", Toast.LENGTH_SHORT).show()
            else { populateRows(fetched); setEditing(true); Toast.makeText(requireContext(), "LRC imported — review and Save", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun applyTimeShift(): Int {
        var activeShiftMs: Long? = null
        var lastValidTimestampMs: Long? = null
        var updatedCount = 0

        if (!binding.cbAutoTimeShift.isChecked) {
            rows.forEach { row ->
                val curr = parseTimestamp(row.time.text.toString())
                if (curr != row.baselineMs) {
                    row.baselineMs = curr
                    updatedCount++
                }
            }
            return updatedCount
        }

        for (row in rows) {
            val currentParsed = parseTimestamp(row.time.text.toString())
            val oldBaseline = row.baselineMs
            val hasLyric = row.lyric.text.toString().trim().isNotEmpty()

            if (oldBaseline != null && currentParsed != null) {
                if (currentParsed != oldBaseline) {
                    activeShiftMs = currentParsed - oldBaseline
                    row.baselineMs = currentParsed
                    lastValidTimestampMs = currentParsed
                    updatedCount++
                } else if (activeShiftMs != null && activeShiftMs != 0L) {
                    val shiftedMs = maxOf(0L, oldBaseline + activeShiftMs)
                    row.time.setText(formatTimestamp(shiftedMs))
                    row.baselineMs = shiftedMs
                    lastValidTimestampMs = shiftedMs
                    updatedCount++
                } else {
                    lastValidTimestampMs = currentParsed
                }
            } else if (currentParsed != null) {
                row.baselineMs = currentParsed
                lastValidTimestampMs = currentParsed
                updatedCount++
            } else {
                // currentParsed is null (empty timestamp box)
                if (hasLyric && lastValidTimestampMs != null) {
                    // Auto-fill empty box with +2 second incremental gap
                    val autoMs = lastValidTimestampMs + 2000L
                    row.time.setText(formatTimestamp(autoMs))
                    row.baselineMs = autoMs
                    lastValidTimestampMs = autoMs
                    updatedCount++
                } else {
                    row.baselineMs = null
                }
            }
        }
        return updatedCount
    }

    private fun saveRows(currentSong: Song) {
        if (binding.cbAutoTimeShift.isChecked) {
            applyTimeShift()
        }
        val output = buildString {
            rows.forEach { (time, lyric) ->
                val text = lyric.text.toString().trim()
                if (text.isEmpty()) return@forEach
                val ms = parseTimestamp(time.text.toString())
                if (ms == null) appendLine(text) else appendLine("[${formatLrcTimestamp(ms)}]$text")
            }
        }.trim()
        if (output.isBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                UltraDeepApplication.instance.lyricsRepository.resetLyrics(currentSong.id)
                Toast.makeText(requireContext(), "Saved lyrics removed", Toast.LENGTH_SHORT).show()
                onLyricsUpdated?.invoke()
                dismiss()
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            UltraDeepApplication.instance.lyricsRepository.saveCustomLyrics(currentSong, output)
            initialContent = output
            Toast.makeText(requireContext(), R.string.lyrics_saved, Toast.LENGTH_SHORT).show()
            onLyricsUpdated?.invoke()
            dismiss()
        }
    }

    /** Supports HH:MM:SS(.mmm), MM:SS(.mmm), and an empty value for plain lyrics. */
    private fun parseTimestamp(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        val match = Regex("^(\\d{1,2}:)?(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?$").matchEntire(raw) ?: return null
        val hours = match.groupValues[1].removeSuffix(":").toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (minutes > 59 || seconds > 59) return null
        val fraction = match.groupValues[4].padEnd(3, '0').toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000) + fraction
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60, ms % 1000)
    }
    private fun formatLrcTimestamp(ms: Long): String = String.format(Locale.US, "%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000)

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
    companion object { const val TAG = "EditLyricsBottomSheet" }
}
