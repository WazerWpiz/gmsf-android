package com.mylegguy.gmsf

import android.os.Handler
import android.os.Looper
import java.util.concurrent.*

class Sequencer(private val onNote: (String, Float) -> Unit) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var bpm = 120
    private var notes: List<Note> = emptyList()
    private var isPlaying = false
    private var position: Float = 0f
    private var job: ScheduledFuture<?>? = null

    private val secPerBeat: Float
        get() = 60f / bpm

    fun setBpm(value: Int) { bpm = value.coerceIn(20, 999) }

    fun setNotes(list: List<Note>) { notes = list.sortedBy { it.time } }

    fun play() {
        if (isPlaying) return
        isPlaying = true
        position = 0f
        val stepSec = secPerBeat / 4f // 16th note grid
        job = scheduler.scheduleAtFixedRate({
            handler.post {
                val beat = position
                notes.filter { Math.abs(it.time - beat) < 0.01f }.forEach { n ->
                    onNote(n.note, n.velocity)
                }
                position += 0.25f
                if (position >= notes.maxOfOrNull { it.time }?.plus(1f) ?: 4f) position = 0f
            }
        }, 0, (stepSec * 1000).toLong(), TimeUnit.MILLISECONDS)
    }

    fun stop() {
        job?.cancel(false)
        job = null
        isPlaying = false
        position = 0f
    }

    fun isPlaying(): Boolean = isPlaying
    fun getPosition(): Float = position
    fun getBpm(): Int = bpm
}
