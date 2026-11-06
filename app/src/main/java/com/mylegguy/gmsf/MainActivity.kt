package com.mylegguy.gmsf

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mylegguy.gmsf.databinding.ActivityMainBinding
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: GmsfApp
    private lateinit var repository: SongRepository
    private lateinit var sequencer: Sequencer
    private lateinit var board: SongBoardView
    private var playButton: ImageButton? = null
    private var eraserButton: ImageButton? = null
    private var toolbarCount: TextView? = null

    private val stateListener: () -> Unit = { runOnUiThread { refreshUi() } }

    private val STORAGE_PERMISSION_REQUEST = 1001
    private val OPEN_DOCUMENT_REQUEST = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as GmsfApp
        repository = app.repository
        sequencer = app.sequencer
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        board = binding.board
        board.onPlaceNote = { note ->
            if (AppState.settings.playOnPlace) app.audio.playNote(note)
        }
        setupToolbar()
        setupBottomBar()
        AppState.addListener(stateListener)
        requestNotificationPermission()
        startMcpService()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        AppState.removeListener(stateListener)
        board.onPlaceNote = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("Storage permission granted")
            } else {
                toast("Storage permission denied")
            }
        }
    }

    private fun setupToolbar() {
        playButton = addTool("playButton", "Play or stop") { togglePlay() }
        addTool("bpmButton", "Tempo") { showBpmDialog() }
        addTool("saveButton", "Save song (internal)") { saveSong() }
        addTool("loadButton", "Load song (internal)") { showLoadDialog() }
        addTool("leftButton", "Scroll left") { board.scrollPage(-1) }
        addTool("rightButton", "Scroll right") { board.scrollPage(1) }
        eraserButton = addTool("eraser", "Eraser mode") { toggleEraser() }
        addTool("yellowPlayButton", "Play from the beginning") { playFromStart() }
        addTool("resizeButton", "Choose instrument") { showInstrumentDialog() }
        addTool("settingsButton", "Settings") { showSettingsDialog() }
        addTool("countButton", "Song length") { showWidthDialog() }
        addTool("creditsButton", "Credits") { showCreditsDialog() }
        addTool("metadataButton", "Song metadata") { showMetadataDialog() }
        addTool("zoomIn", "Zoom in") { board.zoomBy(0.15f) }
        addTool("zoomOut", "Zoom out") { board.zoomBy(-0.15f) }
        addTool("upButton", "Export JSON (Downloads)") { exportSongToPublic() }
        addTool("downButton", "Import JSON (Downloads)") { importSongFromPublic() }

        val countView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 12f
            setPadding(dp(6), 0, dp(6), 0)
            text = "1/400"
        }
        toolbarCount = countView
        binding.toolbar.addView(countView)
    }

    private fun addTool(asset: String, description: String, action: () -> Unit): ImageButton {
        val size = dp(40)
        val button = ImageButton(this).apply {
            val drawable = AssetStore.drawable(this@MainActivity, "assets/Free/Images/$asset.png")
            if (drawable != null) setImageDrawable(drawable) else setImageResource(android.R.drawable.ic_media_play)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(2)
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }
        binding.toolbar.addView(button)
        return button
    }

    private fun setupBottomBar() {
        binding.songLabel.setOnClickListener { showMetadataDialog() }
        binding.instrumentLabel.setOnClickListener { showInstrumentDialog() }
        binding.mcpToggle.setOnClickListener { toggleMcpService() }
        binding.menuButton.setOnClickListener { showMenu() }
    }

    private fun refreshUi() {
        val song = AppState.song
        binding.songLabel.text = song.name.ifBlank { "Untitled" }
        val family = Instruments.family(AppState.selectedFamily)
        val suffix = when (AppState.selectedAccidental) {
            -1 -> " ♭"
            1 -> " ♯"
            else -> ""
        }
        binding.instrumentLabel.text = family.label + suffix
        binding.noteCount.text = "${song.notes.size} notes · ${song.width} cols"
        binding.mcpStatus.text = if (AppState.mcpRunning) "MCP :${AppState.mcpPort}" else "MCP off"
        binding.mcpStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (AppState.mcpRunning) R.color.playing else R.color.muted
            )
        )
        binding.mcpToggle.text = if (AppState.mcpRunning) "Stop MCP" else "Start MCP"
        toolbarCount?.text = "${(AppState.playColumn + 1).coerceIn(1, song.width)}/${song.width}"

        val playAsset = if (AppState.playing) "stopButton" else "playButton"
        playButton?.setImageDrawable(AssetStore.drawable(this, "assets/Free/Images/$playAsset.png"))
        val eraserAsset = if (AppState.eraser) "eraserTurnOff" else "eraser"
        eraserButton?.setImageDrawable(AssetStore.drawable(this, "assets/Free/Images/$eraserAsset.png"))

        val theme = AssetStore.theme(AppState.settings.themeIndex)
        if (theme != null) {
            binding.backgroundImage.setImageBitmap(theme)
            binding.backgroundImage.visibility = View.VISIBLE
        } else {
            binding.backgroundImage.visibility = View.GONE
        }
        board.setPlayColumn(AppState.playColumn, AppState.playing)
        board.invalidate()
    }

    private fun togglePlay() {
        if (sequencer.isPlaying()) {
            sequencer.stop()
        } else {
            if (AppState.song.notes.isEmpty()) {
                toast("Add a few notes first, or load the demo from the menu.")
                return
            }
            sequencer.play()
        }
    }

    private fun playFromStart() {
        if (AppState.song.notes.isEmpty()) {
            toast("Add a few notes first, or load the demo from the menu.")
            return
        }
        sequencer.stop()
        sequencer.seek(0)
        sequencer.play(fromStart = true)
    }

    private fun toggleEraser() {
        AppState.eraser = !AppState.eraser
        toast(if (AppState.eraser) "Eraser on" else "Eraser off")
        AppState.notifyChanged()
    }

    private fun cycleZoom() {
        val levels = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val current = board.zoomLevel()
        val next = levels.firstOrNull { it > current + 0.01f } ?: levels.first()
        board.setZoomLevel(next)
    }

    private fun showBpmDialog() {
        showInputDialog("Tempo (BPM)", AppState.song.bpm.toString(), true) { value ->
            val bpm = value.toIntOrNull()
            if (bpm != null) {
                AppState.setBpm(bpm)
                sequencer.onTempoChanged()
                AppState.notifyChanged()
            }
        }
    }

    private fun showWidthDialog() {
        showInputDialog("Song length (4-400 columns)", AppState.song.width.toString(), true) { value ->
            val width = value.toIntOrNull()
            if (width != null) {
                AppState.setWidth(width)
                AppState.notifyChanged()
            }
        }
    }

    private fun showMetadataDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val title = EditText(this).apply {
            hint = "Title"
            setText(AppState.song.metadata.title)
            maxLines = 1
        }
        val author = EditText(this).apply {
            hint = "Author"
            setText(AppState.song.metadata.author)
            maxLines = 1
        }
        val description = EditText(this).apply {
            hint = "Description"
            setText(AppState.song.metadata.description)
            minLines = 2
            maxLines = 4
        }
        container.addView(title)
        container.addView(author)
        container.addView(description)
        AlertDialog.Builder(this)
            .setTitle("Song metadata")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val metadata = SongMetadata(
                    title = title.text.toString().trim(),
                    author = author.text.toString().trim(),
                    description = description.text.toString().trim()
                )
                AppState.setMetadata(metadata)
                if (metadata.title.isNotBlank()) {
                    AppState.song = AppState.song.copy(name = metadata.title)
                }
                AppState.notifyChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val playOnPlace = CheckBox(this).apply {
            text = "Preview note when placed"
            isChecked = AppState.settings.playOnPlace
        }
        val loop = CheckBox(this).apply {
            text = "Loop playback"
            isChecked = AppState.settings.loop
        }
        val center = CheckBox(this).apply {
            text = "Keep playhead centered"
            isChecked = AppState.settings.centerPlayhead
        }
        container.addView(playOnPlace)
        container.addView(loop)
        container.addView(center)

        val master = addVolumeSlider(container, "Master volume", AppState.settings.masterVolume)
        val music = addVolumeSlider(container, "Music volume", AppState.settings.musicVolume)
        val sfx = addVolumeSlider(container, "SFX volume", AppState.settings.sfxVolume)

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                AppState.settings.playOnPlace = playOnPlace.isChecked
                AppState.settings.loop = loop.isChecked
                AppState.settings.centerPlayhead = center.isChecked
                AppState.settings.masterVolume = master.progress / 100f
                AppState.settings.musicVolume = music.progress / 100f
                AppState.settings.sfxVolume = sfx.progress / 100f
                AppState.notifyChanged()
            }
            .setNeutralButton("Theme") { _, _ -> cycleTheme() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addVolumeSlider(parent: LinearLayout, label: String, value: Float): SeekBar {
        val title = TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = (value.coerceIn(0f, 1f) * 100).toInt()
        }
        parent.addView(title)
        parent.addView(bar)
        return bar
    }

    private fun saveSong() {
        val current = AppState.song.name.trim()
        if (current.isBlank() || current == "Untitled") {
            showInputDialog("Save song as", current.ifBlank { "My song" }, false) { name ->
                performSave(name)
            }
        } else {
            performSave(current)
        }
    }

    private fun performSave(name: String) {
        val safeDisplayName = name.trim().ifBlank { "Untitled" }
        AppState.song = AppState.song.copy(name = safeDisplayName)
        AppState.dirty = false
        val file = repository.save(AppState.song)
        toast("Saved ${file.name}")
        AppState.notifyChanged()
    }

    private fun showLoadDialog() {
        val songs = repository.list()
        if (songs.isEmpty()) {
            toast("No saved songs yet.")
            return
        }
        val labels = songs.map { "${it.name}  (${it.noteCount} notes)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load song (internal)")
            .setItems(labels) { _, which ->
                repository.load(songs[which].name)?.let { loadSongInternal(it) }
            }
            .setNeutralButton("Delete...") { _, _ -> showDeleteDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog() {
        val songs = repository.list()
        if (songs.isEmpty()) return
        val labels = songs.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Delete song")
            .setItems(labels) { _, which ->
                val name = songs[which].name
                AlertDialog.Builder(this)
                    .setTitle("Delete $name?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        repository.delete(name)
                        toast("Deleted $name")
                        AppState.notifyChanged()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSongInternal(song: Song) {
        sequencer.stop()
        AppState.replaceSong(song)
        board.resetScroll()
        AppState.notifyChanged()
    }

    private fun exportSongToPublic() {
        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }
        val song = AppState.song
        val json = SongJson.toJson(song).toString(2)
        val fileName = "${repository.safeName(song.name)}.json"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { output ->
                output.write(json.toByteArray())
                toast("Exported to Downloads/$fileName")
            } ?: toast("Export failed: cannot open stream")
        } ?: toast("Export failed: cannot create file")
    }

    private fun importSongFromPublic() {
        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json"))
        }
        startActivityForResult(intent, 2001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openInputStream(uri)?.use { input ->
                    val json = input.readBytes().decodeToString()
                    try {
                        val song = SongJson.fromText(json)
                        sequencer.stop()
                        AppState.replaceSong(song)
                        board.resetScroll()
                        AppState.notifyChanged()
                        toast("Imported ${song.name}")
                    } catch (e: Exception) {
                        toast("Import failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, 1001)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    private fun showCreditsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Credits")
            .setMessage(
                "GMSF Studio\n\n" +
                    "Original Growtopia Music Simulator Final by MyLegGuy.\n" +
                    "All music samples, board cells and toolbar art come from the original APK.\n\n" +
                    "MCP bridge and editor rewrite for agent-driven composition."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showHelpDialog() {
        val port = AppState.mcpPort
        val message = buildString {
            appendLine("MCP endpoint: http://127.0.0.1:$port/mcp")
            appendLine("Health: http://127.0.0.1:$port/health")
            appendLine("State: http://127.0.0.1:$port/state")
            appendLine("Tools: http://127.0.0.1:$port/mcp/tools")
            appendLine()
            appendLine("From your computer run:")
            appendLine("adb forward tcp:$port tcp:$port")
            appendLine()
            appendLine("Then POST JSON-RPC, for example:")
            appendLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
            appendLine()
            appendLine("Compose with tools/call add_notes, set_bpm,")
            appendLine("set_instrument, play, save_song and export_song.")
            appendLine()
            appendLine("Rows: 0=B5 down to 13=C4.")
            append("Status: ").append(if (AppState.mcpRunning) "running on port $port" else "stopped")
        }
        AlertDialog.Builder(this)
            .setTitle("MCP agent connection")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNeutralButton(if (AppState.mcpRunning) "Stop server" else "Start server") { _, _ ->
                toggleMcpService()
            }
            .show()
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.menuButton)
        popup.menu.add(0, 1, 0, "New song")
        popup.menu.add(0, 2, 1, "Load song (internal)...")
        popup.menu.add(0, 3, 2, "Save as... (internal)")
        popup.menu.add(0, 4, 3, "Song metadata...")
        popup.menu.add(0, 5, 4, "Background theme")
        popup.menu.add(0, 6, 5, "Load demo")
        popup.menu.add(0, 7, 6, "MCP help")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> confirmNewSong()
                2 -> showLoadDialog()
                3 -> showInputDialog("Save song as", AppState.song.name.ifBlank { "My song" }, false) { performSave(it) }
                4 -> showMetadataDialog()
                5 -> cycleTheme()
                6 -> loadSongInternal(DemoSong.create())
                7 -> showHelpDialog()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun confirmNewSong() {
        AlertDialog.Builder(this)
            .setTitle("New song")
            .setMessage("Discard the current song? Save it first if you need it.")
            .setPositiveButton("New song") { _, _ ->
                sequencer.stop()
                AppState.replaceSong(Song())
                board.resetScroll()
                AppState.notifyChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstrumentDialog() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dialog: AlertDialog? = null
        for (family in Instruments.families) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val name = TextView(this).apply {
                text = family.label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                layoutParams = LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(name)
            val choices = Instruments.choices.filter { it.family == family.key }
            for (choice in choices) {
                val selected = choice.family == AppState.selectedFamily && choice.accidental == AppState.selectedAccidental
                val button = ImageButton(this).apply {
                    val drawable = AssetStore.drawable(this@MainActivity, choice.iconPath)
                    if (drawable != null) setImageDrawable(drawable) else setImageResource(android.R.drawable.ic_media_play)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = choice.label
                    setBackgroundColor(if (selected) 0x332563EB else Color.TRANSPARENT)
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        marginEnd = dp(4)
                    }
                    setOnClickListener {
                        AppState.selectedFamily = choice.family
                        AppState.selectedAccidental = choice.accidental
                        AppState.eraser = false
                        app.audio.preview(choice.family, choice.accidental)
                        AppState.notifyChanged()
                        dialog?.dismiss()
                    }
                }
                row.addView(button)
            }
            list.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Instrument")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dlg.show()
    }

    private fun cycleTheme() {
        val count = AssetStore.themeCount()
        AppState.settings.themeIndex = (AppState.settings.themeIndex + 1) % count
        toast("Theme ${AppState.settings.themeIndex + 1}/$count")
        AppState.notifyChanged()
    }

    private fun toggleMcpService() {
        if (AppState.mcpRunning) {
            stopService(Intent(this, McpService::class.java))
            AppState.mcpRunning = false
            AppState.mcpError = null
            AppState.notifyChanged()
        } else {
            startMcpService()
        }
    }

    private fun startMcpService() {
        try {
            val intent = Intent(this, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: Throwable) {
            toast("MCP service failed: ${error.message}")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9001)
            }
        }
    }

    private fun showInputDialog(
        title: String,
        initial: String,
        numeric: Boolean,
        onOk: (String) -> Unit
    ) {
        val edit = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            maxLines = 1
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
