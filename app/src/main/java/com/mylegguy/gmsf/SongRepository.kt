package com.mylegguy.gmsf

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

class SongRepository(private val context: Context) {

    private val gson = Gson()
    private val notesListType: Type = object : TypeToken<List<Note>>() {}.type

    private fun songsDir(): File {
        val dir = File(context.filesDir, "gmsf/songs")
        dir.mkdirs()
        return dir
    }

    private fun safeName(name: String): String = name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    fun save(song: Song): File {
        val file = File(songsDir(), "${safeName(song.name)}.json")
        file.writeText(gson.toJson(song))
        return file
    }

    fun load(name: String): Song? {
        val file = File(songsDir(), "${safeName(name)}.json")
        return if (file.exists()) gson.fromJson(file.readText(), Song::class.java) else null
    }

    fun list(): List<String> {
        return songsDir().listFiles()
            ?.filter { it.extension == "json" }
            ?.map { it.name.removeSuffix(".json") }
            .orEmpty()
    }

    fun delete(name: String): Boolean {
        return File(songsDir(), "${safeName(name)}.json").delete()
    }
}
