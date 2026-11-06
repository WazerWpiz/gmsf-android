package com.mylegguy.gmsf

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object AppState {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ids = AtomicLong(1000L)

    @Volatile var song: Song = Song()
    @Volatile var settings: Settings = Settings()
    @Volatile var selectedFamily: String = "piano"
    @Volatile var selectedAccidental: Int = 0
    @Volatile var eraser: Boolean = false
    @Volatile var playing: Boolean = false
    @Volatile var playColumn: Int = 0
    @Volatile var mcpRunning: Boolean = false
    @Volatile var mcpError: String? = null
    @Volatile var mcpRequests: AtomicLong = AtomicLong(0L)
    @Volatile var mcpPort: Int = 8765
    @Volatile var dirty: Boolean = false

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun nextId(): Long = ids.incrementAndGet()

    @Synchronized
    fun replaceSong(value: Song) {
        song = value
        playColumn = 0
        dirty = false
    }

    @Synchronized
    fun updateSong(block: (Song) -> Song) {
        song = block(song)
        dirty = true
    }

    @Synchronized
    fun putNote(note: Note): Note {
        val filtered = song.notes.filterNot { it.row == note.row && it.col == note.col }
        song = song.copy(notes = (filtered + note).sortedWith(compareBy({ it.col }, { it.row })))
        dirty = true
        return note
    }

    @Synchronized
    fun putNotes(notes: List<Note>): Int {
        if (notes.isEmpty()) return 0
        val cells = notes.map { it.row to it.col }.toSet()
        val filtered = song.notes.filterNot { (it.row to it.col) in cells }
        song = song.copy(notes = (filtered + notes).sortedWith(compareBy({ it.col }, { it.row })))
        dirty = true
        return notes.size
    }

    @Synchronized
    fun removeNoteAt(row: Int, col: Int): Int {
        val before = song.notes.size
        song = song.copy(notes = song.notes.filterNot { it.row == row && it.col == col })
        if (song.notes.size != before) dirty = true
        return before - song.notes.size
    }

    @Synchronized
    fun removeNoteById(id: Long): Int {
        val before = song.notes.size
        song = song.copy(notes = song.notes.filterNot { it.id == id })
        if (song.notes.size != before) dirty = true
        return before - song.notes.size
    }

    @Synchronized
    fun clearNotes() {
        song = song.copy(notes = emptyList())
        dirty = true
    }

    @Synchronized
    fun setBpm(value: Int): Int {
        val bpm = value.coerceIn(20, 999)
        song = song.copy(bpm = bpm)
        dirty = true
        return bpm
    }

    @Synchronized
    fun setWidth(value: Int): Int {
        val width = value.coerceIn(4, 400)
        song = song.copy(width = width, notes = song.notes.filter { it.col in 0 until width })
        dirty = true
        return width
    }

    @Synchronized
    fun setMetadata(metadata: SongMetadata) {
        song = song.copy(metadata = metadata)
        dirty = true
    }

    @Synchronized
    fun setRepeat(start: Int?, end: Int?) {
        song = song.copy(
            repeatStart = start?.coerceIn(0, song.width - 1),
            repeatEnd = end?.coerceIn(0, song.width - 1)
        )
        dirty = true
    }
}
