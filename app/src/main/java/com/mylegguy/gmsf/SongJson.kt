package com.mylegguy.gmsf

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object SongJson {

    const val SCHEMA = "gmsf-song/2"

    fun toJson(song: Song): JSONObject {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("name", song.name)
        root.put("bpm", song.bpm)
        root.put("width", song.width)
        root.put("metadata", JSONObject().apply {
            put("title", song.metadata.title)
            put("author", song.metadata.author)
            put("description", song.metadata.description)
        })
        if (song.repeatStart != null) root.put("repeatStart", song.repeatStart)
        if (song.repeatEnd != null) root.put("repeatEnd", song.repeatEnd)
        val array = JSONArray()
        for (note in song.notes.sortedWith(compareBy({ it.col }, { it.row }))) {
            array.put(noteToJson(note))
        }
        root.put("notes", array)
        return root
    }

    fun noteToJson(note: Note): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("row", note.row)
        put("col", note.col)
        put("time", note.col)
        put("note", NoteNames.nameOf(note.row, note.accidental))
        put("instrument", note.instrument)
        put("accidental", note.accidental)
        put("duration", note.duration)
        put("velocity", note.velocity.toDouble())
        put("legacyId", Instruments.legacyId(note.instrument, note.accidental))
    }

    fun fromJson(root: JSONObject): Song {
        val metadataJson = root.optJSONObject("metadata")
        val metadata = SongMetadata(
            title = metadataJson?.optString("title", "") ?: root.optString("title", ""),
            author = metadataJson?.optString("author", "") ?: root.optString("author", ""),
            description = metadataJson?.optString("description", "") ?: root.optString("description", "")
        )
        var width = root.optInt("width", 400).coerceIn(4, 400)
        val notesJson = root.optJSONArray("notes") ?: JSONArray()
        val parsed = ArrayList<Note>()
        for (index in 0 until notesJson.length()) {
            val item = notesJson.optJSONObject(index) ?: continue
            val note = parseNote(item, index) ?: continue
            parsed.add(note)
            if (note.col >= width) width = (note.col + 1).coerceAtMost(400)
        }
        return Song(
            name = root.optString("name", "Untitled").ifBlank { "Untitled" },
            bpm = root.optInt("bpm", 120).coerceIn(20, 999),
            width = width,
            metadata = metadata,
            notes = parsed.sortedWith(compareBy({ it.col }, { it.row })),
            repeatStart = if (root.has("repeatStart") && !root.isNull("repeatStart")) root.optInt("repeatStart") else null,
            repeatEnd = if (root.has("repeatEnd") && !root.isNull("repeatEnd")) root.optInt("repeatEnd") else null
        )
    }

    fun parseNote(item: JSONObject, index: Int): Note? {
        val instrument = item.optString("instrument", "")
        val accidentalValue = Instruments.parseAccidental(
            if (item.has("accidental")) item.opt("accidental") else null
        )
        var row = if (item.has("row")) item.optInt("row", -1) else -1
        var accidental = accidentalValue ?: 0
        val noteName = item.optString("note", "").ifBlank { null }
        if (noteName != null) {
            val parsed = NoteNames.rowOf(noteName)
            if (parsed != null) {
                row = parsed.first
                if (accidentalValue == null) accidental = parsed.second
            }
        }
        if (row !in 0..13) return null
        var col = when {
            item.has("col") -> item.optInt("col", 0)
            item.has("column") -> item.optInt("column", 0)
            item.has("time") -> item.optDouble("time", 0.0).roundToInt()
            else -> index
        }
        if (col < 0) col = 0
        var family = instrument
        val legacyId = item.optInt("legacyId", item.optInt("noteId", -1))
        if (legacyId > 0) {
            val fromLegacy = Instruments.fromLegacyId(legacyId)
            if (family.isBlank()) family = fromLegacy.first
            if (accidentalValue == null) accidental = fromLegacy.second
        } else if (family.isBlank()) {
            family = "piano"
        }
        if (!Instruments.byKey.containsKey(family)) family = "piano"
        val velocity = item.optDouble("velocity", 1.0).toFloat().coerceIn(0f, 2f)
        val duration = item.optInt("duration", 1).coerceIn(1, 64)
        val id = item.optLong("id", 0L).takeIf { it > 0 } ?: AppState.nextId()
        return Note(id, row, col, family, accidental, duration, velocity)
    }

    fun fromText(text: String): Song {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            return fromGtmusic(trimmed)
        }
        return fromJson(JSONObject(trimmed))
    }

    fun fromGtmusic(text: String): Song {
        var title = ""
        var author = ""
        var description = ""
        var bpm = 120
        var width = 32
        val notes = ArrayList<Note>()
        var inNotes = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("title:", true) -> title = line.substringAfter(":").trim()
                line.startsWith("author:", true) -> author = line.substringAfter(":").trim()
                line.startsWith("description:", true) -> description = line.substringAfter(":").trim()
                line.startsWith("bpm:", true) -> bpm = line.substringAfter(":").trim().toIntOrNull() ?: 120
                line.startsWith("width:", true) -> width = line.substringAfter(":").trim().toIntOrNull() ?: 32
                line.startsWith("notes:", true) || line.equals("[notes]", true) -> inNotes = true
                line.startsWith("#") || line.startsWith("//") -> Unit
                inNotes -> {
                    val parts = line.split(",").map { it.trim() }
                    if (parts.size >= 3) {
                        val legacy = parts.getOrNull(3)?.toIntOrNull()
                        val pair = if (legacy != null) Instruments.fromLegacyId(legacy) else "piano" to 0
                        val row = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..13 }
                            ?: parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..13 }
                            ?: 6
                        val col = parts.getOrNull(1)?.toIntOrNull() ?: parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val id = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: AppState.nextId()
                        notes.add(Note(id, row, col, pair.first, pair.second))
                    }
                }
            }
        }
        val maxCol = notes.maxOfOrNull { it.col } ?: -1
        width = width.coerceAtLeast(maxCol + 1)
        return Song(
            name = title.ifBlank { "Imported" },
            bpm = bpm.coerceIn(20, 999),
            width = width.coerceIn(4, 400),
            metadata = SongMetadata(title, author, description),
            notes = notes
        )
    }

    fun toGtmusic(song: Song): String {
        val builder = StringBuilder()
        builder.appendLine("title:${song.metadata.title.ifBlank { song.name }}")
        builder.appendLine("author:${song.metadata.author}")
        builder.appendLine("description:${song.metadata.description}")
        builder.appendLine("bpm:${song.bpm}")
        builder.appendLine("width:${song.width}")
        builder.appendLine("height:14")
        builder.appendLine("notes:")
        for (note in song.notes) {
            val id = Instruments.legacyId(note.instrument, note.accidental)
            builder.appendLine("${note.id},${note.col},${note.row},$id")
        }
        return builder.toString()
    }
}
