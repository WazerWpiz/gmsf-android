package com.mylegguy.gmsf

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class AudioEngine(context: Context) {

    private val assets: AssetManager = context.applicationContext.assets
    private val soundPool: SoundPool
    private val loaded = ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val naturalRows = intArrayOf(24, 22, 20, 18, 17, 15, 13, 12, 10, 8, 6, 5, 3, 1)
    private val flatRows = intArrayOf(23, 21, 19, 17, 16, 14, 12, 11, 9, 7, 5, 4, 2, 0)
    private val sharpRows = intArrayOf(25, 23, 21, 19, 18, 16, 14, 13, 11, 9, 7, 5, 3, 1)
    private val singleRows = intArrayOf(24, 22, 20, 18, 17, 15, 13, 12, 10, 8, 6, 5, 3, 1)

    init {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(48)
            .setAudioAttributes(attributes)
            .build()
    }

    fun preloadAllAsync() {
        thread(isDaemon = true, name = "gmsf-preload") {
            InstrumentCatalog.keys.forEach { preload(it) }
        }
    }

    fun preload(familyKey: String) {
        if (loaded.containsKey(familyKey) || !loading.add(familyKey)) return
        val family = Instruments.family(familyKey)
        val prefix = family.soundPrefix ?: run {
            loading.remove(familyKey)
            loaded[familyKey] = ConcurrentHashMap()
            return
        }
        val indices = when (family.kind) {
            SoundKind.TRIPLE -> (0..25).toList()
            SoundKind.HALF -> (0..6).toList()
            SoundKind.SINGLE -> singleRows.toList()
            SoundKind.NONE -> emptyList()
        }
        val map = ConcurrentHashMap<Int, Int>()
        for (index in indices) {
            val path = "assets/Proprietary/Sound/${prefix}_$index.wav"
            try {
                assets.openFd(path).use { descriptor ->
                    val id = soundPool.load(descriptor, 1)
                    if (id > 0) map[index] = id
                }
            } catch (_: Throwable) {
            }
        }
        loaded[familyKey] = map
        loading.remove(familyKey)
    }

    fun playNote(note: Note) {
        val family = Instruments.family(note.instrument)
        if (family.kind == SoundKind.NONE) return
        val sample = sampleIndex(family, note.accidental, note.row)
        if (sample < 0) return
        val map = loaded[note.instrument]
        if (map == null) {
            preload(note.instrument)
            return
        }
        val soundId = map[sample] ?: return
        val settings = AppState.settings
        val volume = (note.velocity * settings.sfxVolume * settings.masterVolume).coerceIn(0f, 1f)
        if (volume <= 0f) return
        soundPool.play(soundId, volume, volume, 1, 0, 1f)
    }

    fun preview(familyKey: String, accidental: Int, row: Int = 6) {
        playNote(Note(0L, row, 0, familyKey, accidental, 1, 1f))
    }

    fun sampleIndex(family: InstrumentFamily, accidental: Int, row: Int): Int {
        val safeRow = row.coerceIn(0, 13)
        return when (family.kind) {
            SoundKind.TRIPLE -> when (accidental) {
                -1 -> flatRows[safeRow]
                1 -> sharpRows[safeRow]
                else -> naturalRows[safeRow]
            }
            SoundKind.HALF -> safeRow % 7
            SoundKind.SINGLE -> naturalRows[safeRow]
            SoundKind.NONE -> -1
        }
    }

    fun release() {
        try {
            soundPool.release()
        } catch (_: Throwable) {
        }
    }
}

object InstrumentCatalog {
    val keys: List<String> = Instruments.families.map { it.key }
}
