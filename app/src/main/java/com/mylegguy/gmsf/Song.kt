package com.mylegguy.gmsf

data class SongMetadata(
    val title: String = "",
    val author: String = "",
    val description: String = ""
)

data class Note(
    val id: Long = 0L,
    val row: Int = 0,
    val col: Int = 0,
    val instrument: String = "piano",
    val accidental: Int = 0,
    val duration: Int = 1,
    val velocity: Float = 1f
)

data class Song(
    val name: String = "Untitled",
    val bpm: Int = 120,
    val width: Int = 400,
    val metadata: SongMetadata = SongMetadata(),
    val notes: List<Note> = emptyList(),
    val repeatStart: Int? = null,
    val repeatEnd: Int? = null
)

data class Settings(
    var masterVolume: Float = 1f,
    var musicVolume: Float = 0.85f,
    var sfxVolume: Float = 1f,
    var playOnPlace: Boolean = true,
    var loop: Boolean = true,
    var centerPlayhead: Boolean = true,
    var themeIndex: Int = 0,
    var autoFollow: Boolean = true
)
