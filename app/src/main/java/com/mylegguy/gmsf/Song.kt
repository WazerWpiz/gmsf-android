package com.mylegguy.gmsf

data class Song(
    val name: String,
    val bpm: Int = 120,
    val notes: List<Note> = emptyList()
)

data class Note(
    val id: Int,
    val time: Float, // beats
    val note: String, // e.g. "C4"
    val duration: Float = 0.5f,
    val velocity: Float = 1.0f
)

class SongHolder(var song: Song)
