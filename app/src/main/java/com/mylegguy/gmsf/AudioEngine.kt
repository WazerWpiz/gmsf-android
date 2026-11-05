package com.mylegguy.gmsf

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.*

object AudioEngine {

    private const val SAMPLE_RATE = 44100
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var track: AudioTrack
    private var isPlaying = false
    private var writePosition = 0L

    private val NOTE_FREQ = mapOf(
        "C3" to 130.81, "C#3" to 138.59, "D3" to 146.83, "D#3" to 155.56, "E3" to 164.81, "F3" to 174.61,
        "F#3" to 185.00, "G3" to 196.00, "G#3" to 207.65, "A3" to 220.00, "A#3" to 233.08, "B3" to 246.94,
        "C4" to 261.63, "C#4" to 277.18, "D4" to 293.66, "D#4" to 311.13, "E4" to 329.63, "F4" to 349.23,
        "F#4" to 370.00, "G4" to 392.00, "G#4" to 415.30, "A4" to 440.00, "A#4" to 466.16, "B4" to 493.88,
        "C5" to 523.25
    )

    fun init(context: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack(attrs, format, max(bufferSize, 4096), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        track.play()
    }

    fun playNote(note: String, durationSec: Float, velocity: Float) {
        val freq = NOTE_FREQ[note] ?: 440.0
        executor.execute {
            renderTone(freq, durationSec, velocity)
        }
    }

    private fun renderTone(freq: Double, durationSec: Float, velocity: Float) {
        val samples = (SAMPLE_RATE * durationSec).toInt()
        val buffer = FloatArray(samples)
        val twoPi = 2.0 * Math.PI
        for (i in buffer.indices) {
            val t = i / SAMPLE_RATE.toDouble()
            // triangle-ish wave with attack/release envelope (mirrors inspected bpmFormula/note shaping)
            val env = if (i < samples * 0.1) i / (samples * 0.1).toFloat() else 1f
            buffer[i] = (Math.sin(twoPi * freq * t) * 0.5 + Math.sin(twoPi * freq * 2 * t) * 0.25).toFloat() * env * velocity * 0.2f
        }
        track.write(buffer, 0, samples)
    }

    fun release() {
        try { track.stop(); track.release() } catch (_: Exception) {}
    }
}
