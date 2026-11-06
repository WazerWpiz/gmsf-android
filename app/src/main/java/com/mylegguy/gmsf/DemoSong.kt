package com.mylegguy.gmsf

object DemoSong {

    fun create(): Song {
        val notes = ArrayList<Note>()
        val melody = listOf(
            11, 11, 10, 9, 9, 10, 11, 12, 13, 13, 12, 11, 11, 12, 12,
            11, 11, 10, 9, 9, 10, 11, 12, 13, 13, 12, 11, 12, 13, 13
        )
        for ((index, row) in melody.withIndex()) {
            notes.add(Note(AppState.nextId(), row, index, "piano", 0, 1, 0.9f))
        }
        val bassRows = listOf(13, 13, 13, 13, 12, 12, 13, 13)
        for (index in 0 until 30 step 4) {
            notes.add(Note(AppState.nextId(), bassRows[(index / 4) % bassRows.size], index, "bass", 0, 1, 0.55f))
        }
        return Song(
            name = "Demo - Ode to Joy",
            bpm = 120,
            width = 64,
            metadata = SongMetadata(
                title = "Ode to Joy",
                author = "Ludwig van Beethoven",
                description = "Built-in demo for GMSF Studio"
            ),
            notes = notes.sortedWith(compareBy({ it.col }, { it.row })),
            repeatStart = 0,
            repeatEnd = 29
        )
    }
}
