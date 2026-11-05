package com.mylegguy.gmsf

import android.content.Context
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject

class McpServer(
    private val context: Context,
    private val audioEngine: AudioEngine,
    private val sequencer: Sequencer,
    private val repo: SongRepository,
    private val songHolder: SongHolder
) {
    private var server: ServerSocket? = null
    private var running = false
    private val gson = Gson()

    fun start(port: Int = 8765) {
        if (running) return
        running = true
        thread(isDaemon = true, name = "McpServer") {
            server = ServerSocket(port).apply { reuseAddress = true }
            while (running) {
                try {
                    val client = server!!.accept()
                    thread(isDaemon = true, name = "McpClient") { handleClient(client) }
                } catch (e: IOException) {
                    if (running) e.printStackTrace()
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handleClient(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, "UTF-8"))
        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, "UTF-8"))
        try {
            while (running && !client.isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val response = dispatch(line)
                if (response != null) {
                    writer.write(response)
                    writer.newLine()
                    writer.flush()
                }
            }
        } catch (_: IOException) {
            // client disconnected
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun dispatch(line: String): String? {
        val req = JSONObject(line)
        val id = if (req.has("id") && !req.isNull("id")) req.get("id") else JSONObject.NULL
        val method = req.optString("method", null)
        val params = req.optJSONObject("params")
        if (method == null) return null

        return when (method) {
            "initialize" -> makeResult(id, JSONObject(mapOf(
                "capabilities" to JSONObject(mapOf("tools" to JSONObject(mapOf("listChanged" to false)))),
                "serverInfo" to JSONObject(mapOf("name" to "gmsf-android-mcp", "version" to "1.0.0"))
            )))
            "initialized" -> null
            "tools/list" -> makeResult(id, JSONObject(mapOf(
                "tools" to listOf(
                    toolDef("get_state", "Ambil state lagu"),
                    toolDef("set_bpm", "Atur tempo", listOf("bpm")),
                    toolDef("set_playing", "Putar/berhenti", listOf("playing")),
                    toolDef("play_note", "Mainkan nada", listOf("note", "duration", "velocity")),
                    toolDef("add_note", "Tambah catatan sequencer", listOf("time", "note", "duration", "velocity")),
                    toolDef("remove_note", "Hapus catatan", listOf("id")),
                    toolDef("clear_song", "Kosongkan lagu"),
                    toolDef("load_song", "Muat lagu", listOf("name")),
                    toolDef("save_song", "Simpan lagu", listOf("name", "bpm", "notes")),
                    toolDef("list_songs", "Daftar lagu"),
                    toolDef("delete_song", "Hapus lagu", listOf("name"))
                )
            )))
            "tools/call" -> {
                val name = params?.optString("name") ?: ""
                val args = params?.optJSONObject("arguments") ?: JSONObject()
                try {
                    val out = when (name) {
                        "get_state" -> getState()
                        "set_bpm" -> {
                            val b = args.optInt("bpm", 120).coerceIn(20, 999)
                            sequencer.setBpm(b)
                            songHolder.song = songHolder.song.copy(bpm = b)
                            mapOf("ok" to true, "bpm" to b)
                        }
                        "set_playing" -> {
                            if (args.optBoolean("playing")) sequencer.play() else sequencer.stop()
                            mapOf("ok" to true, "playing" to args.optBoolean("playing"))
                        }
                        "play_note" -> {
                            audioEngine.playNote(args.optString("note", "C4"), args.optDouble("duration", 0.5).toFloat(), args.optDouble("velocity", 1.0).toFloat())
                            mapOf("ok" to true, "note" to args.optString("note", "C4"))
                        }
                        "add_note" -> addNote(args)
                        "remove_note" -> removeNote(args.optInt("id"))
                        "clear_song" -> clearSong()
                        "load_song" -> loadSong(args.optString("name"))
                        "save_song" -> saveSong(args)
                        "list_songs" -> listSongs()
                        "delete_song" -> deleteSong(args.optString("name"))
                        else -> throw IllegalArgumentException("Unknown tool: $name")
                    }
                    makeResult(id, JSONObject(mapOf(
                        "content" to listOf(JSONObject(mapOf("type" to "text", "text" to gson.toJson(out)))),
                        "isError" to false
                    )))
                } catch (e: Exception) {
                    makeError(id, -32603, e.message ?: "Error", null)
                }
            }
            else -> makeError(id, -32601, "Method not found: $method", null)
        }
    }

    private fun toolDef(name: String, desc: String, props: List<String> = emptyList()) =
        JSONObject(mapOf(
            "name" to name,
            "description" to desc,
            "inputSchema" to JSONObject(mapOf(
                "type" to "object",
                "properties" to JSONObject(props.associate { it to JSONObject(mapOf("type" to "string")) }),
                "required" to props.toTypedArray()
            ))
        ))

    private fun makeResult(id: Any, result: JSONObject): String =
        JSONObject(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)).toString()

    private fun makeError(id: Any, code: Int, msg: String?, data: Any?): String =
        JSONObject(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "error" to JSONObject(mapOf("code" to code, "message" to msg).also { if (data != null) it.put("data", data) })
        )).toString()

    private fun getState(): Map<String, Any> = mapOf(
        "songName" to songHolder.song.name,
        "bpm" to songHolder.song.bpm,
        "playing" to sequencer.isPlaying(),
        "position" to sequencer.getPosition(),
        "notes" to songHolder.song.notes,
        "noteCount" to songHolder.song.notes.size
    )

    private fun addNote(args: JSONObject): Map<String, Any> {
        val note = Note(songHolder.song.notes.size + 1, args.optDouble("time", 0.0).toFloat(), args.optString("note", "C4"), args.optDouble("duration", 0.5).toFloat(), args.optDouble("velocity", 1.0).toFloat())
        songHolder.song = songHolder.song.copy(notes = (songHolder.song.notes + note).sortedBy { it.time })
        return mapOf("ok" to true, "note" to note)
    }

    private fun removeNote(id: Int): Map<String, Any> {
        val before = songHolder.song.notes.size
        songHolder.song = songHolder.song.copy(notes = songHolder.song.notes.filter { it.id != id })
        return mapOf("ok" to true, "removed" to before - songHolder.song.notes.size)
    }

    private fun clearSong(): Map<String, Any> {
        songHolder.song = Song(songHolder.song.name, songHolder.song.bpm, emptyList())
        sequencer.stop()
        return mapOf("ok" to true)
    }

    private fun loadSong(name: String): Map<String, Any> {
        repo.load(name)?.let { song ->
            songHolder.song = song
            sequencer.setBpm(song.bpm)
            return mapOf("ok" to true, "song" to song)
        }
        return mapOf("ok" to false, "error" to "not found")
    }

    private fun saveSong(args: JSONObject): Map<String, Any> {
        val name = args.optString("name", songHolder.song.name)
        val bpm = args.optInt("bpm", songHolder.song.bpm).coerceIn(20, 999)
        val notesArg = args.optJSONArray("notes")
        val notes = if (notesArg != null) {
            (0 until notesArg.length()).mapNotNull { i ->
                val o = notesArg.optJSONObject(i) ?: return@mapNotNull null
                Note(o.optInt("id", i + 1), o.optDouble("time", 0.0).toFloat(), o.optString("note", "C4"), o.optDouble("duration", 0.5).toFloat(), o.optDouble("velocity", 1.0).toFloat())
            }
        } else songHolder.song.notes
        songHolder.song = Song(name, bpm, notes)
        val file = repo.save(songHolder.song)
        return mapOf("ok" to true, "file" to file.absolutePath, "song" to songHolder.song)
    }

    private fun listSongs(): Map<String, Any> = mapOf("songs" to repo.list())

    private fun deleteSong(name: String): Map<String, Any> = mapOf("ok" to repo.delete(name))
}
