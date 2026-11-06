package com.mylegguy.gmsf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object McpServer {

    const val SERVER_NAME = "gmsf-studio"
    const val SERVER_VERSION = "2.0.0"
    private const val DEFAULT_PROTOCOL = "2024-11-05"

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null
    private var repo: SongRepository? = null
    private var audio: AudioEngine? = null
    private var sequencer: Sequencer? = null

    @Volatile private var running = false

    fun isRunning(): Boolean = running

    @Synchronized
    fun start(context: Context, repository: SongRepository, engine: AudioEngine, player: Sequencer, port: Int = 8765): Boolean {
        if (running) return true
        repo = repository
        audio = engine
        sequencer = player
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            running = true
            AppState.mcpRunning = true
            AppState.mcpError = null
            AppState.mcpPort = port
            worker = thread(isDaemon = true, name = "gmsf-mcp-http") {
                while (running) {
                    val active = serverSocket ?: break
                    try {
                        val client = active.accept()
                        thread(isDaemon = true, name = "gmsf-mcp-client") { handle(client) }
                    } catch (error: IOException) {
                        if (running) {
                            AppState.mcpError = error.message
                        }
                    }
                }
            }
            AppState.notifyChanged()
            true
        } catch (error: Throwable) {
            running = false
            AppState.mcpRunning = false
            AppState.mcpError = error.message ?: error.javaClass.simpleName
            AppState.notifyChanged()
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        worker?.interrupt()
        worker = null
        AppState.mcpRunning = false
        AppState.notifyChanged()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeResponse(output, 400, JSONObject().put("error", "bad request").toString())
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?").ifBlank { "/" }
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readBody(input, length)
            AppState.mcpRequests.incrementAndGet()

            when {
                method == "OPTIONS" -> writeEmpty(output, 204)
                method == "GET" && path == "/health" -> {
                    val result = JSONObject()
                        .put("status", "ok")
                        .put("service", SERVER_NAME)
                        .put("version", SERVER_VERSION)
                        .put("port", AppState.mcpPort)
                        .put("song", AppState.song.name)
                        .put("notes", AppState.song.notes.size)
                        .put("playing", AppState.playing)
                    writeResponse(output, 200, result.toString())
                }
                method == "GET" && path == "/state" -> {
                    writeResponse(output, 200, stateJson().toString())
                }
                method == "GET" && (path == "/mcp/tools" || path == "/tools") -> {
                    writeResponse(output, 200, JSONObject().put("tools", toolsArray()).toString())
                }
                method == "GET" && path == "/mcp" -> {
                    writeResponse(output, 405, JSONObject().put("error", "use POST for MCP JSON-RPC").toString())
                }
                method == "DELETE" && path == "/mcp" -> writeEmpty(output, 204)
                method == "GET" && path == "/" -> {
                    writeResponse(output, 200, infoJson().toString())
                }
                method == "POST" && (path == "/mcp" || path == "/" || path == "/rpc") -> {
                    if (body.isBlank()) {
                        writeResponse(output, 400, errorJson(null, -32700, "Parse error: empty body").toString())
                        return
                    }
                    val response = dispatch(body)
                    if (response == null) {
                        writeEmpty(output, 202)
                    } else {
                        writeResponse(output, 200, response.toString())
                    }
                }
                else -> writeResponse(output, 404, JSONObject().put("error", "not found").toString())
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun dispatch(body: String): Any? {
        return try {
            if (body.trimStart().startsWith("[")) {
                val requests = JSONArray(body)
                val responses = JSONArray()
                for (index in 0 until requests.length()) {
                    val request = requests.optJSONObject(index) ?: continue
                    val response = dispatchOne(request)
                    if (response != null) responses.put(response)
                }
                if (responses.length() == 0) null else responses
            } else {
                dispatchOne(JSONObject(body))
            }
        } catch (error: Throwable) {
            errorJson(null, -32700, "Parse error: ${error.message}")
        }
    }

    private fun dispatchOne(request: JSONObject): JSONObject? {
        val hasId = request.has("id") && !request.isNull("id")
        val id = if (hasId) request.opt("id") else null
        val method = request.optString("method", "")
        val params = request.optJSONObject("params")
        val reply: JSONObject = when (method) {
            "initialize" -> {
                val requested = params?.optString("protocolVersion", DEFAULT_PROTOCOL) ?: DEFAULT_PROTOCOL
                val protocol = if (requested.isBlank()) DEFAULT_PROTOCOL else requested
                resultJson(id, JSONObject()
                    .put("protocolVersion", protocol)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject()
                        .put("name", SERVER_NAME)
                        .put("version", SERVER_VERSION))
                    .put("instructions", instructions()))
            }
            "notifications/initialized", "initialized", "notifications/cancelled" -> return null
            "ping" -> resultJson(id, JSONObject())
            "tools/list" -> resultJson(id, JSONObject().put("tools", toolsArray()))
            "resources/list" -> resultJson(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> resultJson(id, JSONObject().put("prompts", JSONArray()))
            "tools/call" -> {
                val name = params?.optString("name", "") ?: ""
                val arguments = params?.optJSONObject("arguments") ?: JSONObject()
                try {
                    val value = callTool(name, arguments)
                    val content = JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", value.toString(2)))
                    resultJson(id, JSONObject()
                        .put("content", content)
                        .put("structuredContent", value)
                        .put("isError", false))
                } catch (error: Throwable) {
                    val message = error.message ?: error.javaClass.simpleName
                    val content = JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", "Error: $message"))
                    resultJson(id, JSONObject()
                        .put("content", content)
                        .put("isError", true))
                }
            }
            else -> errorJson(id, -32601, "Method not found: $method")
        }
        return if (hasId) reply else null
    }

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val repository = repo ?: throw IllegalStateException("server not initialized")
        val engine = audio ?: throw IllegalStateException("audio not initialized")
        val player = sequencer ?: throw IllegalStateException("sequencer not initialized")
        return when (name) {
            "get_state" -> stateJson()
            "get_song" -> SongJson.toJson(AppState.song)
            "set_song" -> {
                val metadataJson = args.optJSONObject("metadata")
                val metadata = if (metadataJson != null) {
                    SongMetadata(
                        metadataJson.optString("title", ""),
                        metadataJson.optString("author", ""),
                        metadataJson.optString("description", "")
                    )
                } else AppState.song.metadata
                val notes = ArrayList<Note>()
                val notesJson = args.optJSONArray("notes")
                if (notesJson != null) {
                    for (index in 0 until notesJson.length()) {
                        val item = notesJson.optJSONObject(index) ?: continue
                        SongJson.parseNote(item, index)?.let { notes.add(it) }
                    }
                } else {
                    notes.addAll(AppState.song.notes)
                }
                var width = args.optInt("width", AppState.song.width).coerceIn(4, 400)
                val maxCol = notes.maxOfOrNull { it.col } ?: -1
                if (maxCol >= width) width = (maxCol + 1).coerceAtMost(400)
                val song = Song(
                    name = args.optString("name", AppState.song.name).ifBlank { "Untitled" },
                    bpm = args.optInt("bpm", AppState.song.bpm).coerceIn(20, 999),
                    width = width,
                    metadata = metadata,
                    notes = notes.sortedWith(compareBy({ it.col }, { it.row })),
                    repeatStart = if (args.has("repeatStart") && !args.isNull("repeatStart")) args.optInt("repeatStart") else null,
                    repeatEnd = if (args.has("repeatEnd") && !args.isNull("repeatEnd")) args.optInt("repeatEnd") else null
                )
                player.stop()
                AppState.replaceSong(song)
                player.onTempoChanged()
                AppState.notifyChanged()
                SongJson.toJson(song)
            }
            "set_bpm" -> {
                val bpm = AppState.setBpm(args.optInt("bpm", AppState.song.bpm))
                player.onTempoChanged()
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("bpm", bpm)
            }
            "set_width" -> {
                val width = AppState.setWidth(args.optInt("width", AppState.song.width))
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("width", width)
            }
            "set_metadata" -> {
                val metadata = SongMetadata(
                    args.optString("title", AppState.song.metadata.title),
                    args.optString("author", AppState.song.metadata.author),
                    args.optString("description", AppState.song.metadata.description)
                )
                AppState.setMetadata(metadata)
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("metadata", songMetadataJson(metadata))
            }
            "add_note" -> {
                val note = noteFromArgs(args, 0)
                var width = AppState.song.width
                if (note.col >= width) width = AppState.setWidth(note.col + 1)
                AppState.putNote(note)
                engine.preload(note.instrument)
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("note", SongJson.noteToJson(note)).put("width", width)
            }
            "add_notes" -> {
                val array = args.optJSONArray("notes") ?: throw IllegalArgumentException("notes array is required")
                val notes = ArrayList<Note>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    notes.add(noteFromArgs(item, index))
                }
                val maxCol = notes.maxOfOrNull { it.col } ?: -1
                if (maxCol >= AppState.song.width) AppState.setWidth(maxCol + 1)
                val count = AppState.putNotes(notes)
                notes.forEach { engine.preload(it.instrument) }
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("added", count).put("notes", notesToJson(notes))
            }
            "remove_note" -> {
                val removed = if (args.has("id")) {
                    AppState.removeNoteById(args.optLong("id", -1L))
                } else {
                    val row = args.optInt("row", -1)
                    val col = args.optInt("col", args.optInt("column", -1))
                    if (row !in 0..13 || col < 0) throw IllegalArgumentException("id or row+col is required")
                    AppState.removeNoteAt(row, col)
                }
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("removed", removed)
            }
            "clear_song" -> {
                AppState.clearNotes()
                AppState.notifyChanged()
                JSONObject().put("ok", true)
            }
            "set_instrument" -> {
                val family = args.optString("instrument", AppState.selectedFamily)
                if (!Instruments.byKey.containsKey(family)) throw IllegalArgumentException("unknown instrument: $family")
                val accidental = Instruments.parseAccidental(args.opt("accidental")) ?: AppState.selectedAccidental
                AppState.selectedFamily = family
                AppState.selectedAccidental = accidental
                engine.preload(family)
                AppState.notifyChanged()
                JSONObject().put("ok", true)
                    .put("instrument", family)
                    .put("accidental", accidental)
                    .put("accidentalName", Instruments.accidentalName(accidental))
            }
            "set_playing" -> {
                val playing = args.optBoolean("playing", true)
                if (playing) player.play(args.optBoolean("from_start", false)) else player.stop()
                JSONObject().put("ok", true).put("playing", playing)
            }
            "play" -> {
                player.play(args.optBoolean("from_start", false))
                JSONObject().put("ok", true).put("playing", true)
            }
            "stop" -> {
                player.stop()
                JSONObject().put("ok", true).put("playing", false)
            }
            "seek" -> {
                val col = args.optInt("column", args.optInt("col", 0))
                player.seek(col)
                JSONObject().put("ok", true).put("column", AppState.playColumn)
            }
            "set_volume" -> {
                val settings = AppState.settings
                if (args.has("master")) settings.masterVolume = volumeValue(args.optDouble("master", settings.masterVolume.toDouble()))
                if (args.has("music")) settings.musicVolume = volumeValue(args.optDouble("music", settings.musicVolume.toDouble()))
                if (args.has("sfx")) settings.sfxVolume = volumeValue(args.optDouble("sfx", settings.sfxVolume.toDouble()))
                AppState.notifyChanged()
                JSONObject().put("ok", true)
                    .put("master", settings.masterVolume)
                    .put("music", settings.musicVolume)
                    .put("sfx", settings.sfxVolume)
            }
            "save_song" -> {
                val name = args.optString("name", AppState.song.name).ifBlank { AppState.song.name }
                val song = AppState.song.copy(name = name)
                AppState.song = song
                AppState.dirty = false
                val file = repository.save(song)
                AppState.notifyChanged()
                JSONObject().put("ok", true)
                    .put("name", name)
                    .put("file", file.absolutePath)
                    .put("notes", song.notes.size)
            }
            "load_song" -> {
                val name = args.optString("name", "")
                if (name.isBlank()) throw IllegalArgumentException("name is required")
                val song = repository.load(name) ?: throw IllegalArgumentException("song not found: $name")
                player.stop()
                AppState.replaceSong(song)
                player.onTempoChanged()
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("song", SongJson.toJson(song))
            }
            "load_demo" -> {
                val song = DemoSong.create()
                player.stop()
                AppState.replaceSong(song)
                player.onTempoChanged()
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("song", SongJson.toJson(song))
            }
            "list_songs" -> {
                val array = JSONArray()
                for (song in repository.list()) {
                    array.put(JSONObject()
                        .put("name", song.name)
                        .put("notes", song.noteCount)
                        .put("modified", song.modified)
                        .put("sizeBytes", song.sizeBytes))
                }
                JSONObject().put("songs", array)
            }
            "delete_song" -> {
                val name = args.optString("name", "")
                if (name.isBlank()) throw IllegalArgumentException("name is required")
                JSONObject().put("ok", repository.delete(name)).put("name", name)
            }
            "export_song" -> {
                val format = args.optString("format", "json").lowercase()
                val text = if (format == "gtmusic") SongJson.toGtmusic(AppState.song) else SongJson.toJson(AppState.song).toString(2)
                JSONObject().put("format", format).put("data", text)
            }
            "import_song" -> {
                val data = args.optString("data", "")
                if (data.isBlank()) throw IllegalArgumentException("data is required")
                val song = SongJson.fromText(data)
                player.stop()
                AppState.replaceSong(song)
                player.onTempoChanged()
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("song", SongJson.toJson(song))
            }
            "get_instruments" -> {
                val families = JSONArray()
                for (family in Instruments.families) {
                    families.put(JSONObject()
                        .put("instrument", family.key)
                        .put("label", family.label)
                        .put("sound", family.kind.name.lowercase())
                        .put("legacyId", family.legacyBaseId))
                }
                val choices = JSONArray()
                for (choice in Instruments.choices) {
                    choices.put(JSONObject()
                        .put("instrument", choice.family)
                        .put("label", choice.label)
                        .put("accidental", choice.accidental)
                        .put("accidentalName", Instruments.accidentalName(choice.accidental))
                        .put("legacyId", Instruments.legacyId(choice.family, choice.accidental)))
                }
                JSONObject().put("families", families).put("choices", choices).put("rows", JSONArray(NoteNames.rowNames))
            }
            "set_theme" -> {
                val index = args.optInt("index", AppState.settings.themeIndex).coerceIn(0, AssetStore.themeCount() - 1)
                AppState.settings.themeIndex = index
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("themeIndex", index)
            }
            "set_repeat" -> {
                val start = if (args.has("start") && !args.isNull("start")) args.optInt("start") else null
                val end = if (args.has("end") && !args.isNull("end")) args.optInt("end") else null
                if (start != null && end != null && end < start) throw IllegalArgumentException("end must be >= start")
                AppState.setRepeat(start, end)
                AppState.notifyChanged()
                JSONObject().put("ok", true).put("repeatStart", start ?: JSONObject.NULL).put("repeatEnd", end ?: JSONObject.NULL)
            }
            "describe" -> infoJson()
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    private fun noteFromArgs(args: JSONObject, index: Int): Note {
        var row = args.optInt("row", -1)
        var accidental = Instruments.parseAccidental(args.opt("accidental")) ?: AppState.selectedAccidental
        val noteName = args.optString("note", "")
        if (noteName.isNotBlank()) {
            val parsed = NoteNames.rowOf(noteName)
            if (parsed != null) {
                row = parsed.first
                if (!args.has("accidental")) accidental = parsed.second
            }
        }
        if (row !in 0..13) {
            val legacy = args.optInt("legacyId", args.optInt("noteId", -1))
            if (legacy > 0) {
                val pair = Instruments.fromLegacyId(legacy)
                if (args.optString("instrument", "").isBlank()) {
                    row = 6
                    return Note(
                        args.optLong("id", AppState.nextId()),
                        row,
                        args.optInt("col", args.optInt("column", args.optInt("time", index))).coerceAtLeast(0),
                        pair.first,
                        pair.second,
                        args.optInt("duration", 1).coerceIn(1, 64),
                        args.optDouble("velocity", 1.0).toFloat().coerceIn(0f, 2f)
                    )
                }
            }
            throw IllegalArgumentException("row (0..13) or note (e.g. C4) is required")
        }
        var family = args.optString("instrument", AppState.selectedFamily)
        if (!Instruments.byKey.containsKey(family)) family = "piano"
        val col = args.optInt("col", args.optInt("column", args.optInt("time", index))).coerceAtLeast(0)
        val velocity = args.optDouble("velocity", 1.0).toFloat().coerceIn(0f, 2f)
        val duration = args.optInt("duration", 1).coerceIn(1, 64)
        val id = args.optLong("id", 0L).takeIf { it > 0 } ?: AppState.nextId()
        return Note(id, row, col, family, accidental, duration, velocity)
    }

    private fun stateJson(): JSONObject {
        val song = AppState.song
        return JSONObject()
            .put("song", SongJson.toJson(song))
            .put("name", song.name)
            .put("bpm", song.bpm)
            .put("width", song.width)
            .put("notes", song.notes.size)
            .put("playing", AppState.playing)
            .put("playColumn", AppState.playColumn)
            .put("selectedInstrument", AppState.selectedFamily)
            .put("selectedAccidental", AppState.selectedAccidental)
            .put("eraser", AppState.eraser)
            .put("mcp", JSONObject()
                .put("running", AppState.mcpRunning)
                .put("port", AppState.mcpPort)
                .put("requests", AppState.mcpRequests.get())
                .put("error", AppState.mcpError ?: JSONObject.NULL))
    }

    private fun infoJson(): JSONObject = JSONObject()
        .put("name", SERVER_NAME)
        .put("version", SERVER_VERSION)
        .put("transport", "http-json-rpc")
        .put("endpoint", "/mcp")
        .put("port", AppState.mcpPort)
        .put("protocol", DEFAULT_PROTOCOL)
        .put("tools", toolsArray().length())

    private fun instructions(): String = buildString {
        append("GMSF Studio music simulator. ")
        append("The board has 14 rows (0 = B5, 13 = C4) and up to 400 columns. ")
        append("Use add_notes to compose in bulk, then play. ")
        append("Instruments: piano, bass, drum, sax, flute, guitar, violin, lyre, electric, spooky, festive, blank, audioGear, repeatStart, repeatEnd. ")
        append("Accidental: -1 flat, 0 natural, 1 sharp. ")
        append("Note names like C4, D#4, Bb5 are accepted instead of row. ")
        append("Call get_instruments and get_state first when unsure.")
    }

    private fun songMetadataJson(metadata: SongMetadata): JSONObject = JSONObject()
        .put("title", metadata.title)
        .put("author", metadata.author)
        .put("description", metadata.description)

    private fun notesToJson(notes: List<Note>): JSONArray {
        val array = JSONArray()
        notes.forEach { array.put(SongJson.noteToJson(it)) }
        return array
    }

    private fun volumeValue(value: Double): Float {
        val normalized = if (value > 1.0) value / 100.0 else value
        return normalized.coerceIn(0.0, 1.0).toFloat()
    }

    private fun toolsArray(): JSONArray = JSONArray().apply {
        put(tool("get_state", "Get the current song, playback state, selected instrument and MCP status."))
        put(tool("get_song", "Get the full song as JSON (schema gmsf-song/2)."))
        put(tool("set_song", "Replace the whole song. Accepts name, bpm, width, metadata and notes.", mapOf(
            "name" to stringProp("Song name"),
            "bpm" to intProp("Tempo 20..999", 20, 999),
            "width" to intProp("Song width in columns 4..400", 4, 400),
            "metadata" to objectProp("Metadata with title, author and description"),
            "notes" to arrayProp("Array of note objects"),
            "repeatStart" to intProp("Repeat start column"),
            "repeatEnd" to intProp("Repeat end column")
        )))
        put(tool("set_bpm", "Set the song tempo.", mapOf("bpm" to intProp("Tempo 20..999", 20, 999)), listOf("bpm")))
        put(tool("set_width", "Set the song width in columns.", mapOf("width" to intProp("Columns 4..400", 4, 400)), listOf("width")))
        put(tool("set_metadata", "Set song metadata.", mapOf(
            "title" to stringProp("Title"),
            "author" to stringProp("Author"),
            "description" to stringProp("Description")
        )))
        put(tool("add_note", "Add or replace one note at a row/column.", mapOf(
            "row" to intProp("Row 0..13 (top to bottom)", 0, 13),
            "col" to intProp("Column (beat) 0..399", 0, 399),
            "note" to stringProp("Optional note name such as C4 or D#4"),
            "instrument" to stringProp("Instrument key"),
            "accidental" to stringProp("flat, natural or sharp (or -1/0/1)"),
            "velocity" to numberProp("Velocity 0..2"),
            "duration" to intProp("Duration in columns", 1, 64),
            "id" to intProp("Optional stable note id")
        )))
        put(tool("add_notes", "Add or replace many notes in one call.", mapOf(
            "notes" to arrayProp("Array of note objects using the add_note fields")
        ), listOf("notes")))
        put(tool("remove_note", "Remove a note by id or by row+col.", mapOf(
            "id" to intProp("Note id"),
            "row" to intProp("Row 0..13", 0, 13),
            "col" to intProp("Column", 0, 399)
        )))
        put(tool("clear_song", "Remove every note from the current song."))
        put(tool("set_instrument", "Select the instrument/accidental used by the editor.", mapOf(
            "instrument" to stringProp("Instrument key"),
            "accidental" to stringProp("flat, natural or sharp")
        ), listOf("instrument")))
        put(tool("set_playing", "Start or stop playback.", mapOf(
            "playing" to booleanProp("True to play, false to stop"),
            "from_start" to booleanProp("Restart at column 0")
        ), listOf("playing")))
        put(tool("play", "Start playback.", mapOf("from_start" to booleanProp("Restart at column 0"))))
        put(tool("stop", "Stop playback and reset the playhead."))
        put(tool("seek", "Move the playhead to a column.", mapOf("column" to intProp("Column", 0, 399)), listOf("column")))
        put(tool("set_volume", "Set master, music and/or SFX volume (0..1 or 0..100).", mapOf(
            "master" to numberProp("Master volume"),
            "music" to numberProp("Music volume"),
            "sfx" to numberProp("Sound effect volume")
        )))
        put(tool("save_song", "Save the current song to internal storage.", mapOf("name" to stringProp("Optional new name"))))
        put(tool("load_song", "Load a saved song by name.", mapOf("name" to stringProp("Song name")), listOf("name")))
        put(tool("load_demo", "Load the built-in demo melody."))
        put(tool("list_songs", "List saved songs."))
        put(tool("delete_song", "Delete a saved song.", mapOf("name" to stringProp("Song name")), listOf("name")))
        put(tool("export_song", "Export the song as json or gtmusic text.", mapOf("format" to stringProp("json or gtmusic"))))
        put(tool("import_song", "Import a song from JSON or GTMUSIC text.", mapOf(
            "data" to stringProp("Song text"),
            "format" to stringProp("json, gtmusic or auto")
        ), listOf("data")))
        put(tool("get_instruments", "List instruments, variants, legacy IDs and row note names."))
        put(tool("set_theme", "Choose a background theme 0..5.", mapOf("index" to intProp("Theme index", 0, 5)), listOf("index")))
        put(tool("set_repeat", "Set or clear the repeat region.", mapOf(
            "start" to intProp("Start column", 0, 399),
            "end" to intProp("End column", 0, 399)
        )))
        put(tool("describe", "Describe the MCP endpoint and tool inventory."))
    }

    private fun tool(name: String, description: String, properties: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val props = JSONObject()
        for ((key, value) in properties) props.put(key, value)
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required))
                .put("additionalProperties", true))
    }

    private fun stringProp(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String, minimum: Int? = null, maximum: Int? = null): JSONObject {
        val value = JSONObject().put("type", "integer").put("description", description)
        if (minimum != null) value.put("minimum", minimum)
        if (maximum != null) value.put("maximum", maximum)
        return value
    }

    private fun numberProp(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)

    private fun booleanProp(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)

    private fun objectProp(description: String): JSONObject = JSONObject().put("type", "object").put("description", description)

    private fun arrayProp(description: String): JSONObject = JSONObject().put("type", "array").put("description", description).put("items", JSONObject().put("type", "object"))

    private fun resultJson(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun errorJson(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("UTF-8")
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(data, 0, offset, Charsets.UTF_8)
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeEmpty(output: OutputStream, status: Int) {
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Accept, Authorization, Mcp-Session-Id, MCP-Protocol-Version\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }
}
