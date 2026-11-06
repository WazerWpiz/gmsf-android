package com.mylegguy.gmsf

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Sequencer(private val audio: AudioEngine) {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gmsf-sequencer").apply { isDaemon = true }
    }
    private val lock = Any()
    private var future: ScheduledFuture<*>? = null

    @Volatile private var playing = false
    @Volatile private var column = 0

    fun toggle(fromStart: Boolean = false) {
        if (playing) stop() else play(fromStart)
    }

    fun play(fromStart: Boolean = false) {
        synchronized(lock) {
            if (playing) return
            if (fromStart) column = 0
            playing = true
            AppState.playing = true
            AppState.playColumn = column
            scheduleLocked()
        }
        AppState.notifyChanged()
    }

    fun stop() {
        synchronized(lock) {
            playing = false
            future?.cancel(false)
            future = null
        }
        AppState.playing = false
        AppState.notifyChanged()
    }

    fun onTempoChanged() {
        synchronized(lock) {
            if (playing) scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        future?.cancel(false)
        val period = (60_000.0 / AppState.song.bpm.coerceIn(20, 999)).toLong().coerceAtLeast(20L)
        future = executor.scheduleAtFixedRate({ tick() }, 0L, period, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (!playing) return
        val song = AppState.song
        val current = column.coerceIn(0, (song.width - 1).coerceAtLeast(0))
        if (current in 0 until song.width) {
            for (note in song.notes) {
                if (note.col == current) audio.playNote(note)
            }
        }
        var next = current + 1
        val repeatStart = song.repeatStart
        val repeatEnd = song.repeatEnd
        if (repeatStart != null && repeatEnd != null && repeatEnd >= repeatStart && repeatEnd < song.width) {
            if (next > repeatEnd) next = repeatStart
        } else if (next >= song.width) {
            if (AppState.settings.loop) {
                next = 0
            } else {
                stop()
                return
            }
        }
        column = next
        AppState.playColumn = next
        AppState.notifyChanged()
    }

    fun seek(position: Int) {
        column = position.coerceIn(0, (AppState.song.width - 1).coerceAtLeast(0))
        AppState.playColumn = column
        AppState.notifyChanged()
    }

    fun isPlaying(): Boolean = playing

    fun currentColumn(): Int = column
}
