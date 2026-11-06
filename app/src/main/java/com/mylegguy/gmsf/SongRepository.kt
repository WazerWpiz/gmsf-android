package com.mylegguy.gmsf

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SongFileInfo(
    val name: String,
    val modified: Long,
    val noteCount: Int,
    val sizeBytes: Long
)

class SongRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("gmsf", Context.MODE_PRIVATE)

    fun songsDir(): File {
        val dir = File(appContext.filesDir, "songs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun safeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^a-zA-Z0-9 _.-]"), "_").trim()
        return cleaned.ifBlank { "Untitled" }.take(64)
    }

    fun save(song: Song): File {
        val file = File(songsDir(), "${safeName(song.name)}.json")
        file.writeText(SongJson.toJson(song).toString(2))
        prefs.edit().putString("last_song", file.nameWithoutExtension).apply()
        return file
    }

    fun load(name: String): Song? {
        val file = File(songsDir(), "${safeName(name)}.json")
        if (!file.exists()) return null
        return try {
            SongJson.fromJson(org.json.JSONObject(file.readText()))
        } catch (_: Throwable) {
            null
        }
    }

    fun loadLast(): Song? {
        val name = prefs.getString("last_song", null) ?: return null
        return load(name)
    }

    fun rememberCurrent(song: Song) {
        prefs.edit().putString("last_song", safeName(song.name)).apply()
    }

    fun list(): List<SongFileInfo> {
        val files = songsDir().listFiles()?.filter { it.extension.equals("json", true) } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val root = org.json.JSONObject(file.readText())
                SongFileInfo(
                    name = file.nameWithoutExtension,
                    modified = file.lastModified(),
                    noteCount = root.optJSONArray("notes")?.length() ?: 0,
                    sizeBytes = file.length()
                )
            } catch (_: Throwable) {
                SongFileInfo(file.nameWithoutExtension, file.lastModified(), 0, file.length())
            }
        }.sortedByDescending { it.modified }
    }

    fun delete(name: String): Boolean {
        val file = File(songsDir(), "${safeName(name)}.json")
        return file.delete()
    }

    fun formatDate(millis: Long): String {
        return SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(millis))
    }
}
