package com.mylegguy.gmsf

enum class SoundKind { TRIPLE, HALF, SINGLE, NONE }

data class InstrumentFamily(
    val key: String,
    val label: String,
    val kind: SoundKind,
    val soundPrefix: String?,
    val gear: String?,
    val iconNatural: String,
    val iconSharp: String? = null,
    val iconFlat: String? = null,
    val legacyBaseId: Int = 0
)

data class InstrumentChoice(
    val family: String,
    val label: String,
    val accidental: Int,
    val iconPath: String
) {
    val key: String get() = "$family:${accidental + 1}"
}

object Instruments {

    val families: List<InstrumentFamily> = listOf(
        InstrumentFamily("piano", "Piano", SoundKind.TRIPLE, "piano", "P", "piano.png", "pianoSharp.png", "pianoFlat.png", 1),
        InstrumentFamily("bass", "Bass", SoundKind.TRIPLE, "bass", "B", "bass.png", "bassSharp.png", "bassFlat.png", 4),
        InstrumentFamily("drum", "Drum", SoundKind.HALF, "drum", "D", "drum.png", legacyBaseId = 7),
        InstrumentFamily("blank", "Blank", SoundKind.NONE, null, null, "blank.png", legacyBaseId = 8),
        InstrumentFamily("sax", "Sax", SoundKind.TRIPLE, "sax", "S", "sax.png", "saxSharp.png", "saxFlat.png", 9),
        InstrumentFamily("repeatStart", "Repeat Start", SoundKind.NONE, null, null, "repeatStart.png", legacyBaseId = 12),
        InstrumentFamily("repeatEnd", "Repeat End", SoundKind.NONE, null, null, "repeatEnd.png", legacyBaseId = 13),
        InstrumentFamily("spooky", "Spooky", SoundKind.SINGLE, "spooky", null, "spooky.png", legacyBaseId = 14),
        InstrumentFamily("audioGear", "Audio Gear", SoundKind.NONE, null, null, "audioGear.png", legacyBaseId = 15),
        InstrumentFamily("flute", "Flute", SoundKind.TRIPLE, "flute", "F", "flute.png", "fluteSharp.png", "fluteFlat.png", 16),
        InstrumentFamily("festive", "Festive", SoundKind.SINGLE, "festive", null, "festive.png", legacyBaseId = 19),
        InstrumentFamily("guitar", "Guitar", SoundKind.TRIPLE, "spanish_guitar", "G", "guitar.png", "guitarSharp.png", "guitarFlat.png", 20),
        InstrumentFamily("violin", "Violin", SoundKind.TRIPLE, "violin", "V", "violin.png", "violinSharp.png", "violinFlat.png", 23),
        InstrumentFamily("lyre", "Lyre", SoundKind.TRIPLE, "lyre", "L", "lyre.png", "lyreSharp.png", "lyreFlat.png", 26),
        InstrumentFamily("electric", "Electric Guitar", SoundKind.TRIPLE, "electric_guitar", "E", "electricg.png", "electricgsharp.png", "electricgflat.png", 29)
    )

    val byKey: Map<String, InstrumentFamily> = families.associateBy { it.key }

    val choices: List<InstrumentChoice> = buildList {
        for (family in families) {
            when (family.kind) {
                SoundKind.TRIPLE -> {
                    add(choice(family, -1, family.iconFlat))
                    add(choice(family, 0, family.iconNatural))
                    add(choice(family, 1, family.iconSharp))
                }
                else -> add(choice(family, 0, family.iconNatural))
            }
        }
    }

    private fun choice(family: InstrumentFamily, accidental: Int, icon: String?): InstrumentChoice {
        val suffix = when (accidental) {
            -1 -> " Flat"
            1 -> " Sharp"
            else -> ""
        }
        return InstrumentChoice(
            family = family.key,
            label = family.label + suffix,
            accidental = accidental,
            iconPath = "assets/Proprietary/Images/${icon ?: family.iconNatural}"
        )
    }

    fun family(key: String): InstrumentFamily = byKey[key] ?: byKey.getValue("piano")

    fun noteIconPath(familyKey: String, accidental: Int): String {
        val family = family(familyKey)
        val name = when (accidental) {
            -1 -> family.iconFlat ?: family.iconNatural
            1 -> family.iconSharp ?: family.iconNatural
            else -> family.iconNatural
        }
        return "assets/Proprietary/Images/$name"
    }

    fun accidentalName(value: Int): String = when (value) {
        -1 -> "flat"
        1 -> "sharp"
        else -> "natural"
    }

    fun parseAccidental(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt().coerceIn(-1, 1)
            is String -> when (value.lowercase()) {
                "flat", "b", "-1", "♭" -> -1
                "sharp", "#", "1", "♯" -> 1
                "natural", "n", "0" -> 0
                else -> null
            }
            else -> null
        }
    }

    fun legacyId(familyKey: String, accidental: Int): Int {
        val family = family(familyKey)
        return when (family.kind) {
            SoundKind.TRIPLE -> family.legacyBaseId + when (accidental) {
                1 -> 1
                -1 -> 2
                else -> 0
            }
            else -> family.legacyBaseId
        }
    }

    fun fromLegacyId(id: Int): Pair<String, Int> {
        for (family in families) {
            if (family.kind == SoundKind.TRIPLE) {
                if (id in family.legacyBaseId..(family.legacyBaseId + 2)) {
                    val accidental = when (id - family.legacyBaseId) {
                        1 -> 1
                        2 -> -1
                        else -> 0
                    }
                    return family.key to accidental
                }
            } else if (id == family.legacyBaseId) {
                return family.key to 0
            }
        }
        return "piano" to 0
    }
}

object NoteNames {

    val rowNames: List<String> = listOf(
        "B5", "A5", "G5", "F5", "E5", "D5", "C5",
        "B4", "A4", "G4", "F4", "E4", "D4", "C4"
    )

    private val letterToRow = mapOf(
        'B' to 0, 'A' to 1, 'G' to 2, 'F' to 3, 'E' to 4, 'D' to 5, 'C' to 6
    )

    fun rowOf(note: String): Pair<Int, Int>? {
        val text = note.trim().replace("♭", "b").replace("♯", "#")
        if (text.length < 2) return null
        val letter = text[0].uppercaseChar()
        if (!letterToRow.containsKey(letter)) return null
        var accidental = 0
        var octaveText = text.substring(1)
        if (octaveText.startsWith("#")) {
            accidental = 1
            octaveText = octaveText.substring(1)
        } else if (octaveText.startsWith("b")) {
            accidental = -1
            octaveText = octaveText.substring(1)
        }
        val octave = octaveText.toIntOrNull() ?: return null
        if (octave != 4 && octave != 5) return null
        val baseRow = letterToRow.getValue(letter) + if (octave == 5) 0 else 7
        return baseRow to accidental
    }

    fun nameOf(row: Int, accidental: Int): String {
        val base = rowNames.getOrElse(row) { "C4" }
        return when (accidental) {
            -1 -> base[0] + "b" + base.substring(1)
            1 -> base[0] + "#" + base.substring(1)
            else -> base
        }
    }

    fun label(row: Int): String = rowNames.getOrElse(row) { "C4" }.dropLast(1)
}
