package com.mylegguy.gmsf

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

object AssetStore {

    private lateinit var assets: AssetManager
    private val cache = LruCache<String, Bitmap>(96)
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val themeFiles = listOf(
        "assets/Free/Images/pcBackground0.png",
        "assets/Free/Images/pcBackground1.png",
        "assets/Free/Images/pcBackground2.png",
        "assets/Free/Images/pcBackground3.png",
        "assets/Free/Images/pcBackground4.png",
        "assets/Free/Images/pcBackgroundClassic.png"
    )

    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    fun bitmap(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (missing.contains(path)) return null
        return try {
            assets.open(path).use { stream ->
                val options = BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                if (bitmap != null) cache.put(path, bitmap) else missing.add(path)
                bitmap
            }
        } catch (_: Throwable) {
            missing.add(path)
            null
        }
    }

    fun drawable(context: Context, path: String): BitmapDrawable? {
        val bitmap = bitmap(path) ?: return null
        return BitmapDrawable(context.resources, bitmap).apply {
            setFilterBitmap(false)
        }
    }

    fun emptyCell(row: Int): Bitmap? = bitmap("assets/Free/Images/PartBG/empty${row % 7}.png")

    fun rowLabel(row: Int): Bitmap? = bitmap("assets/Free/Images/PartBG/label$row.png")

    fun noteIcon(family: String, accidental: Int): Bitmap? = bitmap(Instruments.noteIconPath(family, accidental))

    fun free(path: String): Bitmap? = bitmap("assets/Free/Images/$path")

    fun proprietary(path: String): Bitmap? = bitmap("assets/Proprietary/Images/$path")

    fun theme(index: Int): Bitmap? {
        val safe = index.coerceIn(0, themeFiles.lastIndex)
        return bitmap(themeFiles[safe])
    }

    fun themeCount(): Int = themeFiles.size

    fun preloadCore() {
        val paths = ArrayList<String>()
        for (i in 0 until 7) paths.add("assets/Free/Images/PartBG/empty$i.png")
        for (i in 0 until 14) paths.add("assets/Free/Images/PartBG/label$i.png")
        for (choice in Instruments.choices) paths.add(choice.iconPath)
        for (path in listOf(
            "playButton", "stopButton", "newButton", "loadButton", "saveButton", "bpmButton",
            "countButton", "metadataButton", "settingsButton", "creditsButton", "themeButton",
            "volumeButton", "eraser", "eraserTurnOff", "leftButton", "rightButton",
            "zoomIn", "zoomOut", "yellowPlayButton", "infoButton", "moreUI", "scriptButton",
            "optionsButton", "resizeButton"
        )) {
            paths.add("assets/Free/Images/$path.png")
        }
        for (path in paths) {
            bitmap(path)
        }
    }
}
