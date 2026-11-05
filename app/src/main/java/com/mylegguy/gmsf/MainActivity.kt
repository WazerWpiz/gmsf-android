package com.mylegguy.gmsf

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mylegguy.gmsf.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SongRepository
    private lateinit var sequencer: Sequencer
    private lateinit var mcp: McpServer

    val songHolder = SongHolder(Song("Untitled"))
    private val noteNames = listOf("C3","D3","E3","F3","G3","A3","B3","C4","D4","E4","F4","G4","A4","B4","C5")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AudioEngine.init(this)
        repo = SongRepository(this)
        sequencer = Sequencer { note, vel ->
            AudioEngine.playNote(note, 0.5f, vel)
        }
        mcp = McpServer(this, AudioEngine, sequencer, repo, songHolder)
        mcp.start(8765)

        setupUi()
        loadDemoOrEmpty()
    }

    private fun setupUi() {
        binding.btnPlay.setOnClickListener { sequencer.play(); binding.pill.text = "Memutar" }
        binding.btnStop.setOnClickListener { sequencer.stop(); binding.pill.text = "Siap" }
        binding.btnClear.setOnClickListener { clearSong() }
        binding.btnAdd.setOnClickListener { addNote() }

        binding.bpm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.EditorInfo.IME_ACTION_DONE) {
                val v = binding.bpm.text.toString().toIntOrNull() ?: 120
                sequencer.setBpm(v)
                songHolder.song = songHolder.song.copy(bpm = v)
                binding.bpmText.text = "$v bpm"
                true
            } else false
        }

        binding.songName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                songHolder.song = songHolder.song.copy(name = s?.toString() ?: "Untitled")
            }
        })

        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnLoad.setOnClickListener { showLoadDialog() }

        renderKeyboard()
        renderSequencer()
        refreshMeta()
    }

    private fun renderKeyboard() {
        binding.keyboard.removeAllViews()
        val whiteWidth = resources.displayMetrics.widthPixels / 7
        for (note in noteNames) {
            val btn = Button(this).apply {
                text = note
                setTextSize(2, 10f)
                setOnTouchListener { _, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> { AudioEngine.playNote(note, 0.6f, 1f); setBackgroundColor(0xFF3B82F6); true }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { setBackgroundColor(Color.TRANSPARENT); true }
                        else -> false
                    }
                }
            }
            binding.keyboard.addView(btn)
        }
    }

    private fun renderSequencer() {
        binding.seqGrid.removeAllViews()
        val stepCount = 16
        val rowHeight = 40f

        // header row
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (rowHeight).toInt()) }
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(40, MATCH_PARENT) })
        for (i in 0 until stepCount) {
            header.addView(TextView(this).apply {
                text = (i + 1).toString()
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                setTextSize(2, 9f)
            })
        }
        binding.seqGrid.addView(header)

        for (note in noteNames) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, (rowHeight).toInt()) }
            row.addView(TextView(this).apply {
                text = note
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(40, MATCH_PARENT)
                setTextSize(2, 10f)
            })
            for (step in 0 until stepCount) {
                val cell = Button(this).apply {
                    val t = step / 4f
                    val has = songHolder.song.notes.any { it.note == note && Math.round(it.time * 4) == step }
                    if (has) setBackgroundColor(0xFF2563EB)
                    text = ""
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                    setOnClickListener {
                        toggleCell(note, step, this)
                    }
                }
                row.addView(cell)
            }
            binding.seqGrid.addView(row)
        }
    }

    private fun toggleCell(note: String, step: Int, cell: Button) {
        val time = step / 4f
        val idx = songHolder.song.notes.indexOfFirst { it.note == note && Math.round(it.time * 4) == step }
        if (idx >= 0) {
            songHolder.song = songHolder.song.copy(notes = songHolder.song.notes.filterNot { it.note == note && Math.round(it.time * 4) == step })
            cell.setBackgroundColor(Color.TRANSPARENT)
        } else {
            val newNote = Note(songHolder.song.notes.size + 1, time, note)
            songHolder.song = songHolder.song.copy(notes = (songHolder.song.notes + newNote).sortedBy { it.time })
            cell.setBackgroundColor(0xFF2563EB)
        }
        refreshMeta()
    }

    private fun addNote() {
        val note = noteNames[songHolder.song.notes.size % noteNames.size]
        val time = songHolder.song.notes.maxOfOrNull { it.time }?.plus(0.5f) ?: 0.5f
        val newNote = Note(songHolder.song.notes.size + 1, time, note)
        songHolder.song = songHolder.song.copy(notes = (songHolder.song.notes + newNote).sortedBy { it.time })
        renderSequencer()
        refreshMeta()
    }

    private fun clearSong() {
        songHolder.song = Song(songHolder.song.name, songHolder.song.bpm, emptyList())
        sequencer.stop()
        renderSequencer()
        refreshMeta()
        binding.pill.text = "Siap"
    }

    private fun saveCurrent() {
        val name = binding.songName.text.toString().takeIf { it.isNotEmpty() } ?: "Untitled"
        songHolder.song = songHolder.song.copy(name = name)
        sequencer.setBpm(songHolder.song.bpm)
        repo.save(songHolder.song)
        Toast.makeText(this, "Tersimpan: $name.json", Toast.LENGTH_SHORT).show()
    }

    private fun showLoadDialog() {
        val songs = repo.list()
        if (songs.isEmpty()) { Toast.makeText(this, "Tidak ada lagu tersimpan", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Muat Lagu")
            .setItems(songs.toTypedArray()) { _, which ->
                val name = songs[which]
                repo.load(name)?.let { song ->
                    songHolder.song = song
                    binding.songName.setText(song.name)
                    binding.bpm.setText(song.bpm.toString())
                    binding.bpmText.text = "${song.bpm} bpm"
                    sequencer.setBpm(song.bpm)
                    renderSequencer()
                    refreshMeta()
                }
            }
            .show()
    }

    private fun refreshMeta() {
        binding.meta.text = "${songHolder.song.notes.size} catatan"
        renderSequencer()
    }

    private fun loadDemoOrEmpty() {
        repo.load("demo")?.let { song ->
            songHolder.song = song
            binding.songName.setText(song.name)
            binding.bpm.setText(song.bpm.toString())
            binding.bpmText.text = "${song.bpm} bpm"
            sequencer.setBpm(song.bpm)
            renderSequencer()
            refreshMeta()
        }
    }

    override fun onDestroy() {
        mcp.stop()
        sequencer.stop()
        AudioEngine.release()
        super.onDestroy()
    }
}
