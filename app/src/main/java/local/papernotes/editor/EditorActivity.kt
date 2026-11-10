package local.papernotes.editor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.core.content.edit
import android.text.InputFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import local.papernotes.NotesLocation
import local.papernotes.R
import local.papernotes.export.AudioExport
import local.papernotes.export.ExportFlow
import local.papernotes.home.PreviewStamper
import local.papernotes.model.AudioAttachment
import local.papernotes.storage.BackupPolicy
import local.papernotes.storage.BackupStore
import local.papernotes.storage.NoteRepository
import local.papernotes.storage.NoteTitles
import local.papernotes.storage.PnoteCodec
import local.papernotes.storage.PnoteResult
import local.papernotes.widget.NotesWidgetUpdater
import local.papernotes.sync.SyncCoordinator
import java.io.File
import java.text.NumberFormat
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Editor: Kopfzeile, Werkzeugleiste und Unterleiste (Stift/Radierer) um die [PageCanvasView]. Die Logik liegt in
 * [NoteSession] (Noten) und [EditorToolState] (Werkzeuge); diese Klasse verbindet nur die Views.
 * Es werden bewusst weder Titel, Pfade noch Strichdaten geloggt.
 */
class EditorActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var io: ExecutorService? = null
    private var session: NoteSession? = null
    private lateinit var tools: EditorToolState

    private var lastBackupTime = 0L
    private var modifiedSinceBackup = false
    private val backupTick = object : Runnable {
        override fun run() {
            checkAndRunBackup(isClosing = false)
            if (!isDestroyed && !isFinishing) {
                ui.postDelayed(this, BACKUP_TICK_MS)
            }
        }
    }

    private lateinit var canvas: PageCanvasView
    private lateinit var textTitle: TextView
    private lateinit var textPage: TextView
    private lateinit var textZoom: TextView
    private lateinit var buttonPrev: View
    private lateinit var buttonNext: View
    private lateinit var buttonUndo: View
    private lateinit var buttonRedo: View

    private lateinit var toolBar: View
    private lateinit var popupView: View
    private lateinit var popup: PopupWindow
    private lateinit var buttonPin: View
    private lateinit var penBar: View
    private lateinit var eraserBar: View
    private lateinit var seekPen: SeekBar
    private lateinit var seekEraser: SeekBar
    private lateinit var textPenWidth: TextView
    private lateinit var textEraserSize: TextView

    private lateinit var lassoBar: View
    private lateinit var buttonLassoInk: ImageButton
    private lateinit var buttonLassoText: ImageButton
    private lateinit var buttonLassoImage: ImageButton

    private lateinit var audioBar: View
    private lateinit var buttonAudioExport: ImageButton
    private lateinit var buttonAudioRecord: ImageButton
    private lateinit var buttonAudioBack: ImageButton
    private lateinit var buttonAudioPlay: ImageButton
    private lateinit var buttonAudioForward: ImageButton
    private lateinit var buttonAudioTrack: TextView
    private lateinit var textAudioName: TextView
    private lateinit var buttonAudioRename: ImageButton
    private lateinit var buttonAudioDelete: ImageButton
    private lateinit var seekAudio: SeekBar
    private lateinit var textAudioTime: TextView
    private lateinit var textMicBadge: TextView

    // Audio: höchstens ein Recorder und ein Player gleichzeitig; Ticks laufen nur, solange etwas aufnimmt oder spielt.
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordPageId: String? = null
    private var recordStartedAt = 0L
    private var player: MediaPlayer? = null
    private var playFile: File? = null
    private var audioIndex = 0
    private var seekDragging = false
    private val audioTick = object : Runnable {
        override fun run() {
            refreshAudioUi()
            if (recorder != null || player?.isPlaying == true) ui.postDelayed(this, AUDIO_TICK_MS)
        }
    }

    private val toolButtons = HashMap<PageCanvasView.Tool, View>()
    private val penButtons = HashMap<String, View>()
    private val colorButtons = HashMap<PenColor, View>()
    private val eraserButtons = HashMap<PageCanvasView.EraserStyle, View>()

    @Suppress("DEPRECATION") // Keine Übergangsanimation (E-Ink); die Theme-Attribute decken den Rest ab.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val noteId = intent?.getStringExtra(EXTRA_NOTE_ID)
        if (noteId == null || !PnoteCodec.isValidId(noteId)) {
            finish()
            return
        }
        tools = EditorToolState(PrefsStore(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)))
        setContentView(R.layout.activity_editor)
        bindViews()
        trackKeyboard()
        val executor = Executors.newSingleThreadExecutor()
        io = executor
        if (!canvas.isInkAvailable) toast(R.string.error_no_ink)
        applyPen()
        applyEraser()
        canvas.setTool(tools.tool)
        refreshToolUi()
        loadNote(executor, noteId)
    }

    /**
     * Im Vollbild-Theme ignoriert Android `adjustResize`. Die Höhe der Tastatur wird deshalb aus dem sichtbaren Bereich
     * des Fensters ermittelt und als unterer Rand gesetzt: Blatt und Formatleiste (A3) enden über der Tastatur.
     */
    private fun trackKeyboard() {
        val content = findViewById<View>(android.R.id.content)
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            decor.getWindowVisibleDisplayFrame(visible)
            val gap = decor.rootView.height - visible.bottom
            val keyboard = if (gap > decor.rootView.height / 6) gap else 0
            if (content.paddingBottom != keyboard) content.setPadding(0, 0, 0, keyboard)
        }
    }

    private fun bindViews() {
        canvas = findViewById(R.id.pageCanvas)
        toolBar = findViewById(R.id.toolBar)
        // Stift-/Radierer-Leiste als eigenes Fenster über dem Blatt (nicht fokussierbar, Stift-Eingaben daneben gehen durch).
        popupView = layoutInflater.inflate(R.layout.popup_bars, null)
        popup = PopupWindow(popupView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isOutsideTouchable = false
            isClippingEnabled = false
            animationStyle = 0
        }
        // Fläche des Fensters vom nativen Schreiben ausnehmen (sonst werden Stiftantippen dort zu Strichen).
        popupView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWriteExclusion() }
        buttonPin = popupView.findViewById(R.id.buttonPinBar)
        buttonPin.setOnClickListener { tools.togglePinned(); refreshToolUi() }
        textTitle = findViewById(R.id.textTitle)
        textTitle.setOnClickListener { askRenameNote() }
        textMicBadge = findViewById(R.id.textMicBadge)
        textPage = findViewById(R.id.textPage)
        textZoom = findViewById(R.id.textZoom)
        canvas.onZoomChanged = { percent ->
            // Nur bei Änderung (auf 5 % gerundet) neu zeichnen, damit E-Ink ruhig bleibt.
            if (ZoomLogic.isVisible(percent)) {
                textZoom.text = getString(R.string.zoom_percent, percent)
                textZoom.visibility = View.VISIBLE
            } else {
                textZoom.visibility = View.GONE
            }
        }
        buttonPrev = findViewById(R.id.buttonPrevPage)
        buttonNext = findViewById(R.id.buttonNextPage)
        buttonUndo = findViewById(R.id.buttonUndo)
        buttonRedo = findViewById(R.id.buttonRedo)
        penBar = popupView.findViewById(R.id.penBar)
        eraserBar = popupView.findViewById(R.id.eraserBar)
        seekPen = popupView.findViewById(R.id.seekPenWidth)
        seekEraser = popupView.findViewById(R.id.seekEraserSize)
        textPenWidth = popupView.findViewById(R.id.textPenWidth)
        textEraserSize = popupView.findViewById(R.id.textEraserSize)

        lassoBar = popupView.findViewById(R.id.lassoBar)
        buttonLassoInk = popupView.findViewById(R.id.buttonLassoInk)
        buttonLassoText = popupView.findViewById(R.id.buttonLassoText)
        buttonLassoImage = popupView.findViewById(R.id.buttonLassoImage)

        buttonLassoInk.setOnClickListener {
            tools.setLassoSelectInk(!tools.lassoSelectInk)
            refreshToolUi()
        }
        buttonLassoText.setOnClickListener {
            tools.setLassoSelectText(!tools.lassoSelectText)
            refreshToolUi()
        }
        buttonLassoImage.setOnClickListener {
            tools.setLassoSelectImage(!tools.lassoSelectImage)
            refreshToolUi()
        }

        audioBar = popupView.findViewById(R.id.audioBar)
        buttonAudioExport = popupView.findViewById(R.id.buttonAudioExport)
        buttonAudioRecord = popupView.findViewById(R.id.buttonAudioRecord)
        buttonAudioBack = popupView.findViewById(R.id.buttonAudioBack)
        buttonAudioPlay = popupView.findViewById(R.id.buttonAudioPlay)
        buttonAudioForward = popupView.findViewById(R.id.buttonAudioForward)
        buttonAudioTrack = popupView.findViewById(R.id.buttonAudioTrack)
        textAudioName = popupView.findViewById(R.id.textAudioName)
        buttonAudioRename = popupView.findViewById(R.id.buttonAudioRename)
        buttonAudioDelete = popupView.findViewById(R.id.buttonAudioDelete)
        seekAudio = popupView.findViewById(R.id.seekAudio)
        textAudioTime = popupView.findViewById(R.id.textAudioTime)
        buttonAudioRecord.setOnClickListener { if (recorder != null) stopRecording() else startRecording() }
        buttonAudioPlay.setOnClickListener { togglePlayback() }
        buttonAudioBack.setOnClickListener { skipPlayback(-AudioLogic.SKIP_MS) }
        buttonAudioForward.setOnClickListener { skipPlayback(AudioLogic.SKIP_MS) }
        buttonAudioTrack.setOnClickListener {
            stopPlayback()
            audioIndex = AudioLogic.nextIndex(audioIndex, currentAudios().size)
            refreshAudioUi()
            currentAudios().getOrNull(audioIndex)?.let {
                toast(it.title)
            }
        }
        buttonAudioExport.setOnClickListener {
            val audio = currentAudios().getOrNull(audioIndex) ?: return@setOnClickListener
            val bytes = session?.note?.media?.get(audio.filePath)
            if (bytes == null) toast(R.string.error_audio_export) else audioExport.choose(audio.title, bytes)
        }
        buttonAudioRename.setOnClickListener {
            val audios = currentAudios()
            val audio = audios.getOrNull(audioIndex) ?: return@setOnClickListener
            val input = EditText(this).apply {
                setText(audio.title)
                setSelection(audio.title.length)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_rename_audio)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newTitle = input.text.toString().trim()
                    if (newTitle.isNotEmpty()) {
                        val updated = audio.copy(title = newTitle)
                        val newAudios = audios.mapIndexed { idx, item -> if (idx == audioIndex) updated else item }
                        session?.onAudiosChanged(newAudios)
                        refreshAudioUi()
                        toast(newTitle)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        textAudioName.setOnClickListener { if (buttonAudioRename.isEnabled) buttonAudioRename.performClick() }
        buttonAudioDelete.setOnClickListener {
            val audios = currentAudios()
            val audio = audios.getOrNull(audioIndex) ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_audio)
                .setPositiveButton(R.string.delete) { _, _ ->
                    stopPlayback()
                    val newAudios = audios.filterIndexed { idx, _ -> idx != audioIndex }
                    session?.onAudiosChanged(newAudios)
                    audioIndex = AudioLogic.clampIndex(audioIndex, newAudios.size)
                    refreshAudioUi()
                    refreshPageAudioIndicator()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        seekAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                player?.seekTo(progress)
                textAudioTime.text = audioTimeText(progress.toLong(), bar.max.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seekDragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seekDragging = false
                refreshAudioUi()
            }
        })

        findViewById<View>(R.id.buttonBack).setOnClickListener { finish() }
        buttonPrev.setOnClickListener { switchPage { it.goToPage(it.pageIndex - 1) } }
        buttonNext.setOnClickListener { switchPage { it.goToPage(it.pageIndex + 1) } }
        buttonUndo.setOnClickListener { canvas.undo(); updateChrome() }
        buttonRedo.setOnClickListener { canvas.redo(); updateChrome() }
        for (id in intArrayOf(R.id.buttonAddPage, R.id.buttonToolPageAdd)) {
            findViewById<View>(id).setOnClickListener { switchPage { it.addPage(); true } }
        }
        findViewById<View>(R.id.buttonDeletePage).setOnClickListener { confirmDeletePage() }
        findViewById<View>(R.id.buttonMore).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.buttonTemplate).setOnClickListener { showTemplateDialog() }
        findViewById<View>(R.id.buttonSidebar).setOnClickListener { showSidebar() }

        toolButtons[PageCanvasView.Tool.PEN] = findViewById(R.id.buttonToolPen)
        toolButtons[PageCanvasView.Tool.ERASER] = findViewById(R.id.buttonToolEraser)
        toolButtons[PageCanvasView.Tool.LASSO] = findViewById(R.id.buttonToolLasso)
        toolButtons[PageCanvasView.Tool.TEXT] = findViewById(R.id.buttonToolText)
        toolButtons[PageCanvasView.Tool.IMAGE] = findViewById(R.id.buttonToolImage)
        toolButtons[PageCanvasView.Tool.AUDIO] = findViewById(R.id.buttonMic)
        for ((tool, button) in toolButtons) {
            button.setOnClickListener {
                tools.selectTool(tool)
                if (tools.tool != PageCanvasView.Tool.AUDIO) stopAudio()
                canvas.setTool(tools.tool)
                refreshToolUi()
            }
        }

        // Reihenfolge der Ids entspricht PenCatalog.pens (Füllfeder, Kugelschreiber, Bleistift, Marker).
        val penIds = intArrayOf(R.id.buttonPenFountain, R.id.buttonPenBallpoint, R.id.buttonPenPencil, R.id.buttonPenMarker)
        for ((i, spec) in PenCatalog.pens.withIndex()) {
            val button = popupView.findViewById<View>(penIds[i])
            penButtons[spec.id] = button
            button.setOnClickListener {
                tools.selectPen(spec)
                applyPen()
                refreshToolUi()
            }
        }
        colorButtons[PenColor.BLACK] = popupView.findViewById(R.id.buttonColorBlack)
        colorButtons[PenColor.GRAY] = popupView.findViewById(R.id.buttonColorGray)
        colorButtons[PenColor.WHITE] = popupView.findViewById(R.id.buttonColorWhite)
        for ((color, button) in colorButtons) {
            button.setOnClickListener {
                tools.selectColor(color)
                applyPen()
                refreshToolUi()
            }
        }
        popupView.findViewById<View>(R.id.buttonPenSmaller).setOnClickListener { tools.stepPenWidth(-1); applyPen(); refreshToolUi() }
        popupView.findViewById<View>(R.id.buttonPenLarger).setOnClickListener { tools.stepPenWidth(1); applyPen(); refreshToolUi() }
        seekPen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tools.setPenWidth(tools.pen.minWidth + progress)
                textPenWidth.text = num(tools.penWidth)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                applyPen()
            }
        })

        eraserButtons[PageCanvasView.EraserStyle.STANDARD] = popupView.findViewById(R.id.buttonEraserStandard)
        eraserButtons[PageCanvasView.EraserStyle.AREA] = popupView.findViewById(R.id.buttonEraserArea)
        eraserButtons[PageCanvasView.EraserStyle.STROKES] = popupView.findViewById(R.id.buttonEraserStrokes)
        for ((style, button) in eraserButtons) {
            button.setOnClickListener {
                tools.selectEraserStyle(style)
                applyEraser()
                refreshToolUi()
            }
        }
        popupView.findViewById<View>(R.id.buttonEraserSmaller).setOnClickListener { tools.stepEraserRadius(-1); applyEraser(); refreshToolUi() }
        popupView.findViewById<View>(R.id.buttonEraserLarger).setOnClickListener { tools.stepEraserRadius(1); applyEraser(); refreshToolUi() }
        seekEraser.max = EditorToolState.MAX_ERASER_RADIUS - EditorToolState.MIN_ERASER_RADIUS
        seekEraser.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tools.setEraserRadius(EditorToolState.MIN_ERASER_RADIUS + progress)
                textEraserSize.text = num(tools.eraserRadius)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                applyEraser()
            }
        })
        setEnabledAll(false)
    }

    private fun num(value: Int): String = NumberFormat.getInstance().format(value)

    private fun applyPen() {
        canvas.setPen(tools.pen.frameworkType, tools.pen.colorFor(tools.penColor.argb), tools.penWidth.toFloat())
    }

    private fun applyEraser() {
        canvas.setEraser(tools.eraserStyle, tools.eraserRadius)
    }

    /** Aktualisiert Auswahlzustand und Sichtbarkeit der Unterleiste. Ändert nie die Größe der Schreibfläche. */
    private fun refreshToolUi() {
        for ((tool, button) in toolButtons) button.isSelected = tool == tools.tool
        val showPen = tools.barVisible && tools.tool == PageCanvasView.Tool.PEN
        val showEraser = tools.barVisible && tools.tool == PageCanvasView.Tool.ERASER
        val showLasso = tools.barVisible && tools.tool == PageCanvasView.Tool.LASSO
        val showAudio = tools.barVisible && tools.tool == PageCanvasView.Tool.AUDIO
        audioBar.visibility = if (showAudio) View.VISIBLE else View.GONE
        penBar.visibility = if (showPen) View.VISIBLE else View.GONE
        eraserBar.visibility = if (showEraser) View.VISIBLE else View.GONE
        lassoBar.visibility = if (showLasso) View.VISIBLE else View.GONE
        buttonPin.isSelected = tools.barPinned
        updatePopup(showPen || showEraser || showLasso || showAudio)

        for ((id, button) in penButtons) button.isSelected = id == tools.pen.id
        for ((color, button) in colorButtons) button.isSelected = color == tools.penColor
        seekPen.max = tools.pen.maxWidth - tools.pen.minWidth
        seekPen.progress = tools.penWidth - tools.pen.minWidth
        textPenWidth.text = num(tools.penWidth)

        for ((style, button) in eraserButtons) button.isSelected = style == tools.eraserStyle
        seekEraser.progress = tools.eraserRadius - EditorToolState.MIN_ERASER_RADIUS
        textEraserSize.text = num(tools.eraserRadius)

        buttonLassoInk.isSelected = tools.lassoSelectInk
        buttonLassoText.isSelected = tools.lassoSelectText
        buttonLassoImage.isSelected = tools.lassoSelectImage
        canvas.setLassoFilters(tools.lassoSelectInk, tools.lassoSelectText, tools.lassoSelectImage)
        refreshAudioUi()
    }

    // ---------------------------------------------------------------- Audio

    private fun currentAudios(): List<AudioAttachment> = session?.currentPage?.audios.orEmpty()

    private fun audioTimeText(position: Long, duration: Long): String =
        AudioLogic.formatTime(position) + " / " + AudioLogic.formatTime(duration)

    /** Gleicht die Audioleiste mit Aufnahme-/Abspielzustand ab. Billig; wird auch von [refreshToolUi] aufgerufen. */
    private fun refreshAudioUi() {
        if (!::audioBar.isInitialized) return
        val audios = currentAudios()
        audioIndex = AudioLogic.clampIndex(audioIndex, audios.size)
        val recording = recorder != null
        val p = player
        val playing = p?.isPlaying == true
        val hasTrack = audios.isNotEmpty()
        buttonAudioRecord.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_tool_mic)
        buttonAudioRecord.contentDescription = getString(if (recording) R.string.audio_stop else R.string.audio_record)
        buttonAudioPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        buttonAudioPlay.contentDescription = getString(if (playing) R.string.audio_pause else R.string.audio_play)
        val canPlay = hasTrack && !recording
        buttonAudioPlay.isEnabled = canPlay
        buttonAudioBack.isEnabled = canPlay && p != null
        buttonAudioForward.isEnabled = canPlay && p != null
        buttonAudioTrack.isEnabled = audios.size > 1 && !recording
        buttonAudioTrack.text = if (hasTrack) "${audioIndex + 1}/${audios.size}" else getString(R.string.audio_no_track)
        textAudioName.text = audios.getOrNull(audioIndex)?.title.orEmpty()
        buttonAudioExport.isEnabled = hasTrack && !recording
        buttonAudioRename.isEnabled = hasTrack && !recording
        buttonAudioDelete.isEnabled = hasTrack && !recording
        if (recording) {
            seekAudio.isEnabled = false
            seekAudio.max = 1
            seekAudio.progress = 0
            textAudioTime.text = AudioLogic.formatTime(SystemClock.elapsedRealtime() - recordStartedAt)
            return
        }
        seekAudio.isEnabled = canPlay && p != null
        val duration = if (p != null && p.duration > 0) p.duration else (audios.getOrNull(audioIndex)?.durationMs ?: 0L).toInt()
        val position = if (p != null) p.currentPosition else 0
        seekAudio.max = duration.coerceAtLeast(1)
        if (!seekDragging) {
            seekAudio.progress = position.coerceIn(0, seekAudio.max)
            textAudioTime.text = audioTimeText(position.toLong(), duration.toLong())
        }
    }

    @Suppress("DEPRECATION") // MediaRecorder(Context) gibt es erst ab API 31, minSdk ist 29.
    private fun startRecording() {
        if (session == null || recorder != null) return
        stopPlayback()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        val file = File(cacheDir, "rec-${UUID.randomUUID()}.m4a")
        val r = MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            r.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            // Eine Aufnahme muss in einen Paketeintrag passen (PnoteCodec.MAX_ENTRY_BYTES); mit Reserve.
            r.setMaxFileSize(PnoteCodec.MAX_ENTRY_BYTES - AUDIO_SIZE_MARGIN)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording()
            }
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            toast(R.string.error_audio_record)
            return
        }
        recorder = r
        recordFile = file
        recordPageId = session?.currentPage?.id
        recordStartedAt = SystemClock.elapsedRealtime()
        ui.removeCallbacks(audioTick)
        ui.post(audioTick)
    }

    /** Beendet die Aufnahme und hängt sie an die Seite, auf der sie begonnen wurde. Liest synchron (lokale, kleine Datei). */
    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        val file = recordFile
        val pageId = recordPageId
        recordFile = null
        recordPageId = null
        val duration = SystemClock.elapsedRealtime() - recordStartedAt
        var stopped = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            stopped = false // zu kurz oder keine Daten
        }
        r.release()
        val s = session
        if (stopped && file != null && pageId != null && s != null) {
            val bytes = try {
                if (file.length() in 1..PnoteCodec.MAX_ENTRY_BYTES) file.readBytes() else null
            } catch (e: java.io.IOException) {
                null
            }
            val page = s.note.pages[pageId]
            if (bytes == null || page == null) {
                toast(R.string.error_audio_record)
            } else {
                val audio = AudioAttachment(
                    id = UUID.randomUUID().toString(),
                    filePath = "${UUID.randomUUID()}.m4a",
                    title = getString(R.string.audio_title_format, page.audios.size + 1),
                    durationMs = duration,
                )
                if (s.onAudioAdded(pageId, audio, bytes)) {
                    modifiedSinceBackup = true
                    if (s.currentPage.id == pageId) {
                        audioIndex = page.audios.size
                        refreshPageAudioIndicator()
                    }
                }
            }
        }
        file?.delete()
        refreshAudioUi()
    }

    /** Abspielen starten, pausieren oder fortsetzen. Die Bytes der Spur werden dazu kurz in den Cache entpackt. */
    private fun togglePlayback() {
        val audio = currentAudios().getOrNull(audioIndex) ?: return
        val existing = player
        if (existing != null) {
            if (existing.isPlaying) {
                existing.pause()
            } else {
                existing.start()
                ui.removeCallbacks(audioTick)
                ui.post(audioTick)
            }
            refreshAudioUi()
            return
        }
        val bytes = session?.note?.media?.get(audio.filePath)
        if (bytes == null) {
            toast(R.string.error_audio_play)
            return
        }
        val file = File(cacheDir, "play-${UUID.randomUUID()}.m4a")
        val mp = MediaPlayer()
        try {
            file.writeBytes(bytes)
            mp.setDataSource(file.path)
            mp.prepare()
            mp.setOnCompletionListener { stopPlayback() }
            mp.start()
        } catch (e: Exception) {
            mp.release()
            file.delete()
            toast(R.string.error_audio_play)
            return
        }
        player = mp
        playFile = file
        ui.removeCallbacks(audioTick)
        ui.post(audioTick)
    }

    private fun skipPlayback(deltaMs: Int) {
        val p = player ?: return
        p.seekTo(AudioLogic.seekTarget(p.currentPosition, deltaMs, p.duration))
        refreshAudioUi()
    }

    private fun stopPlayback() {
        val p = player
        player = null
        if (p != null) {
            try {
                p.stop()
            } catch (e: IllegalStateException) {
                // schon beendet
            }
            p.release()
        }
        playFile?.delete()
        playFile = null
        if (recorder == null) ui.removeCallbacks(audioTick)
        refreshAudioUi()
    }

    private fun stopAudio() {
        stopRecording()
        stopPlayback()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording() else toast(R.string.error_audio_permission)
    }

    /** Zeigt oder versteckt die Unterleiste als Fenster direkt unter der Werkzeugzeile. */
    private fun updatePopup(show: Boolean) {
        if (!show) {
            if (popup.isShowing) popup.dismiss()
            canvas.setWriteExclusion(null)
            return
        }
        if (isFinishing || isDestroyed) return
        toolBar.post {
            if (isFinishing || isDestroyed || !tools.barVisible || popup.isShowing) return@post
            popup.showAsDropDown(toolBar)
            popupView.post { updateWriteExclusion() }
        }
    }

    private fun updateWriteExclusion() {
        if (!::popup.isInitialized || !popup.isShowing) return
        val loc = IntArray(2)
        popupView.getLocationOnScreen(loc)
        canvas.setWriteExclusion(Rect(loc[0], loc[1], loc[0] + popupView.width, loc[1] + popupView.height))
    }

    /** Dialoge und Menüs liegen über dem Blatt: solange sie offen sind, schreibt der Stift nicht. */
    private var overlayCount = 0

    private fun overlayOpened() {
        if (overlayCount++ == 0) canvas.suspendWriting(true)
    }

    private fun overlayClosed() {
        if (overlayCount > 0 && --overlayCount == 0) canvas.suspendWriting(false)
    }

    private val exportFlow by lazy { ExportFlow(this, ::overlayOpened, ::overlayClosed) }
    private val audioExport by lazy { AudioExport(this, ::overlayOpened, ::overlayClosed) }

    private fun showMoreMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SHARE, 0, R.string.action_share)
        menu.menu.add(0, MENU_EXPORT_PDF, 1, R.string.menu_export_pdf)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> exportPdf(share = true)
                MENU_EXPORT_PDF -> exportPdf(share = false)
            }
            true
        }
        menu.setOnDismissListener { overlayClosed() }
        overlayOpened()
        menu.show()
    }

    /** Dialog zum Umbenennen der offenen Notiz (Klick auf den Titel); der Titel wird über die [NoteSession] gespeichert. */
    private fun askRenameNote() {
        val s = session ?: return
        val input = EditText(this).apply {
            setText(s.note.title)
            setSelection(text.length)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(NoteTitles.MAX_LENGTH))
        }
        overlayOpened()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_note)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = NoteTitles.sanitize(input.text.toString())
                if (!NoteTitles.isValid(title)) {
                    toast(R.string.error_title_invalid)
                } else if (s.rename(title)) {
                    textTitle.text = title
                    modifiedSinceBackup = true
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { overlayClosed() }
            .show()
    }

    /** Speichert die Notiz und exportiert den gespeicherten Stand als PDF: [share] teilt es, sonst wird es in Dokumente gespeichert. */
    private fun exportPdf(share: Boolean) {
        val s = session ?: return
        canvas.flush()
        s.save()
        val note = s.note // unveränderliche Momentaufnahme, wird im Hintergrund-Thread gezeichnet
        val sources = listOf(ExportFlow.Source(note.title) { note })
        if (share) exportFlow.share(sources) else exportFlow.save(sources)
    }

    private fun loadNote(executor: ExecutorService, noteId: String) {
        if (!NotesLocation.hasAccess(this)) {
            toast(R.string.error_no_storage)
            finish()
            return
        }
        val root = NotesLocation.root(this)
        executor.execute {
            val result = try {
                NoteRepository(root).load(noteId)
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val note = (result as? PnoteResult.Ok)?.value
                if (note == null || note.pageIds.isEmpty()) {
                    toast(R.string.error_open)
                    finish()
                } else {
                    startSession(NoteRepository(root), note)
                }
            }
        }
    }

    private fun startSession(repo: NoteRepository, note: local.papernotes.model.Note) {
        val executor = io ?: return
        val stamper = PreviewStamper(this)
        val s = NoteSession(note, {
            repo.save(stamper.stamp(it)).also { r -> if (r is PnoteResult.Ok) NotesWidgetUpdater.requestUpdate(this) }
        }, executor, HandlerScheduler(ui))
        s.onSaveResult = { ok -> if (!ok) ui.post { if (!isDestroyed) toast(R.string.error_save) } }
        session = s
        lastBackupTime = System.currentTimeMillis()
        modifiedSinceBackup = false
        ui.removeCallbacks(backupTick)
        ui.postDelayed(backupTick, BACKUP_TICK_MS)
        canvas.onChanged = { strokes ->
            s.onStrokesChanged(strokes)
            modifiedSinceBackup = true
            updateChrome()
            if (tools.barVisible && !tools.barPinned) {
                tools.onContentChanged()
                refreshToolUi()
            }
        }
        canvas.onTextboxesChanged = { boxes ->
            s.onTextboxesChanged(boxes)
            modifiedSinceBackup = true
        }
        canvas.onRequestImagePick = { pickImage() }
        canvas.onTextEditChanged = { edit -> findViewById<TextFormatBar>(R.id.textFormatBar).attach(edit) }
        canvas.onImagesChanged = { images, added ->
            s.onImagesChanged(images, added)
            modifiedSinceBackup = true
        }
        textTitle.text = note.title
        setEnabledAll(true)
        showCurrentPage()
    }

    private fun showCurrentPage() {
        val s = session ?: return
        canvas.showPage(s.currentPage, s.currentHistory, s.note.media)
        stopAudio()
        audioIndex = 0
        refreshAudioUi()
        updateChrome()
        refreshPageAudioIndicator()
    }

    private fun refreshPageAudioIndicator() {
        if (!::textMicBadge.isInitialized) return
        val count = currentAudios().size
        if (count > 0) {
            textMicBadge.text = count.toString()
            textMicBadge.visibility = View.VISIBLE
        } else {
            textMicBadge.visibility = View.GONE
        }
    }

    /** Erfasst offene Änderungen, führt die Seitenaktion aus und zeigt danach die (neue) aktuelle Seite. */
    private fun switchPage(action: (NoteSession) -> Boolean) {
        val s = session ?: return
        canvas.flush()
        if (action(s)) {
            modifiedSinceBackup = true
            showCurrentPage()
        }
    }

    /** Vorlagendialog; wie alle Overlays mit [overlayOpened]/[overlayClosed] gekoppelt, damit der Stift die Knöpfe bedienen kann. */
    private fun showTemplateDialog() {
        val s = session ?: return
        val page = s.currentPage
        val dialog = TemplateDialog(this, page.template, page.width, page.height) { template, allPages ->
            canvas.flush()
            if (s.setTemplate(template, allPages)) {
                modifiedSinceBackup = true
                canvas.setTemplate(s.currentPage.template)
            }
        }
        dialog.onDismiss = { overlayClosed() }
        overlayOpened()
        dialog.show()
    }

    /** Seitenleiste über dem Blatt; wie der Vorlagendialog mit [overlayOpened]/[overlayClosed] gekoppelt (Stift bedient die Leiste). */
    private fun showSidebar() {
        val s = session ?: return
        canvas.flush()
        val loc = IntArray(2)
        canvas.getLocationOnScreen(loc)
        val area = Rect(loc[0], loc[1], loc[0] + canvas.width, loc[1] + canvas.height)
        val sidebar = PageSidebar(this, s, area, object : PageSidebar.Actions {
            override fun jump(index: Int) {
                switchPage { it.goToPage(index) }
            }

            override fun newPage() {
                switchPage { it.addPage(atEnd = true); true }
            }

            override fun deletePage(index: Int) {
                canvas.flush()
                val wasCurrent = index == s.pageIndex
                if (s.deletePageAt(index)) {
                    if (wasCurrent) showCurrentPage() else updateChrome()
                }
            }

            override fun setBookmark(index: Int, bookmarked: Boolean) {
                if (s.setBookmark(index, bookmarked)) {
                    modifiedSinceBackup = true
                }
            }
        })
        sidebar.onDismiss = { overlayClosed() }
        overlayOpened()
        sidebar.show()
    }

    private fun confirmDeletePage() {
        val s = session ?: return
        if (s.pageCount <= 1) {
            toast(R.string.error_last_page)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete_page)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> switchPage { it.deletePage() } }
            .setOnDismissListener { overlayClosed() }
            .also { overlayOpened() }
            .show()
    }

    private fun updateChrome() {
        val s = session ?: return
        textPage.text = s.currentPageNumberText()
        buttonPrev.isEnabled = s.pageIndex > 0
        buttonNext.isEnabled = s.pageIndex < s.pageCount - 1
        buttonUndo.isEnabled = canvas.canUndo
        buttonRedo.isEnabled = canvas.canRedo
    }

    private fun setEnabledAll(enabled: Boolean) {
        for (id in CONTROL_IDS) findViewById<View>(id).isEnabled = enabled
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Öffnet die Bildauswahl (Storage Access Framework); das Ergebnis kommt in [onActivityResult]. */
    private fun pickImage() {
        if (session == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PICK_IMAGE)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(R.string.import_error_picker)
        }
    }

    @Deprecated("Activity-API ohne AndroidX; hier bewusst genutzt.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val executor = io ?: return
        executor.execute {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { readLimited(it) }
            } catch (e: java.io.IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            ui.post {
                if (isDestroyed || isFinishing || session == null) return@post
                if (bytes == null || !canvas.addImage(bytes)) toast(R.string.error_image) else toast(R.string.image_menu_hint)
            }
        }
    }

    /** Liest höchstens [PnoteCodec.MAX_ENTRY_BYTES] Bytes; null, wenn die Datei größer ist. */
    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > PnoteCodec.MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onPause() {
        super.onPause()
        SyncCoordinator.editing = false
        if (::popup.isInitialized && popup.isShowing) popup.dismiss()
        stopAudio()
        persist()
    }

    override fun onStop() {
        super.onStop()
        persist()
    }

    private fun checkAndRunBackup(isClosing: Boolean) {
        val s = session ?: return
        if (s.hasUnsavedChanges) modifiedSinceBackup = true
        val prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        val intervalMinutes = prefs.getInt(BackupPolicy.PREF_KEY_INTERVAL_MINUTES, BackupPolicy.DEFAULT_INTERVAL_MINUTES)
        val now = System.currentTimeMillis()
        if (!BackupPolicy.shouldBackup(now, lastBackupTime, intervalMinutes, modifiedSinceBackup)) return

        if (isClosing) canvas.flush() // im laufenden Betrieb nicht in den Stift-Strich eingreifen
        s.save()
        val noteId = s.note.id
        val root = NotesLocation.root(this)
        modifiedSinceBackup = false
        lastBackupTime = now

        val executor = io ?: return
        executor.execute {
            val file = NoteRepository(root).noteFile(noteId) ?: return@execute
            try {
                BackupStore(root).backup(noteId, file, now)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun persist() {
        val s = session ?: return
        canvas.flush()
        s.save()
        checkAndRunBackup(isClosing = true)
    }

    override fun onResume() {
        super.onResume()
        SyncCoordinator.editing = true // während des Schreibens kein Hochladen (Stiftlatenz, Akku)
        if (::tools.isInitialized && ::popup.isInitialized) refreshToolUi()
    }

    override fun onDestroy() {
        ui.removeCallbacks(backupTick)
        exportFlow.cancel()
        checkAndRunBackup(isClosing = true)
        if (::popup.isInitialized && popup.isShowing) popup.dismiss()
        session?.close()
        session?.onSaveResult = null
        session = null
        if (::canvas.isInitialized) {
            canvas.onChanged = null
            canvas.onTextboxesChanged = null
            canvas.onImagesChanged = null
            canvas.onZoomChanged = null
            canvas.onRequestImagePick = null
        }
        io?.shutdown() // bereits eingereihte Schreibvorgänge laufen noch zu Ende
        super.onDestroy()
    }

    private class HandlerScheduler(private val handler: Handler) : SaveScheduler {
        override fun postDelayed(delayMs: Long, task: Runnable): Any {
            handler.postDelayed(task, delayMs)
            return task
        }

        override fun cancel(token: Any) = handler.removeCallbacks(token as Runnable)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : ToolSettingsStore {
        override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
        override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    }

    companion object {
        private const val EXTRA_NOTE_ID = "local.papernotes.NOTE_ID"
        private const val MENU_EXPORT_PDF = 1
        private const val MENU_SHARE = 2
        private const val REQUEST_PICK_IMAGE = 1
        private const val REQUEST_RECORD_AUDIO = 2
        private const val AUDIO_TICK_MS = 1_000L
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BIT_RATE = 64_000
        private const val AUDIO_SIZE_MARGIN = 256L * 1024
        private const val PREFS_NAME = "editor_tools"
        private const val PREFS_SETTINGS = "settings"
        private const val BACKUP_TICK_MS = 10_000L

        // Erst nach dem Laden der Notiz bedienbar. Werkzeuge und Menü sind immer aktiv; Platzhalter bleiben deaktiviert.
        private val CONTROL_IDS = intArrayOf(
            R.id.buttonPrevPage, R.id.buttonNextPage, R.id.buttonUndo, R.id.buttonRedo, R.id.buttonAddPage,
            R.id.buttonToolPageAdd, R.id.buttonDeletePage, R.id.buttonTemplate, R.id.buttonSidebar,
        )

        fun intent(context: Context, noteId: String): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_NOTE_ID, noteId)
    }
}
