package com.opendrawmusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

const val CORE_SOUNDFONT_ID = "core"

/** Lowest bank used for drum kits. Both 120 ("... Kit") and 128 entries are drum patches. */
const val DRUM_BANK = 120

/**
 * Core-bundle preset categories, in the original app's instrument-group order.
 * The first 16 are the General MIDI families (program / 8); the last is the drum bank.
 */
val presetCategories = listOf(
    "Piano",
    "Chromatic percussion",
    "Organ",
    "Guitar",
    "Bass",
    "Strings",
    "Ensemble",
    "Brass",
    "Reed",
    "Pipe",
    "Synth lead",
    "Synth pad",
    "Synth effects",
    "Ethnic",
    "Percussive",
    "Sound effects",
    "Drum kits"
)

/**
 * Instrument-family category for a preset taken straight from the core SoundFont.
 *
 * Derived from the preset's General MIDI family (its program / 8) plus the drum bank, so a
 * preset lands in the category it belongs to by what instrument it is. This reproduces the
 * original app's own grouping exactly: every one of the 274 entries in its
 * instrument_color_map.json falls in the group this returns for its bank/program.
 */
fun categoryForPreset(bank: Int, program: Int): String {
    if (bank >= DRUM_BANK) return "Drum kits"
    val family = program / 8
    return presetCategories[family.coerceIn(0, 15)]
}

data class SoundFontEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val bundledAsset: String? = null
)

data class InstrumentOption(
    val id: String,
    val name: String,
    val group: String,
    val soundFontId: String,
    val bank: Int,
    val program: Int
)

data class LoadedSoundFont(
    val entry: SoundFontEntry,
    val sfid: Int,
    val presets: List<SoundFontPreset>
)

data class SoundFontPreset(val bank: Int, val program: Int, val name: String)

data class SoundFontConfigItem(
    val id: String,
    val displayName: String,
    val isCore: Boolean,
    val entry: SoundFontEntry? = null
)

object SoundFontRegistry {
    const val SAMPLE_LIST_PREFS = "soundfont_config"
    const val KEY_HIDDEN_SAMPLES = "hidden_sample_names"
    val defaultSampleNames = listOf("FluidR3 GM", "Arachno SoundFont")

    fun soundFontsDir(context: Context): File = File(context.filesDir, "soundfonts").apply { mkdirs() }

    fun registryFile(context: Context): File = File(context.filesDir, "soundfonts.json")

    fun hiddenSampleNames(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(SAMPLE_LIST_PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_HIDDEN_SAMPLES, emptySet())?.toSet() ?: emptySet()
    }

    fun hideSampleName(context: Context, name: String) {
        if (name == "GeneralUser GS") return
        val prefs = context.getSharedPreferences(SAMPLE_LIST_PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getStringSet(KEY_HIDDEN_SAMPLES, emptySet()) ?: emptySet()).toMutableSet()
        next.add(name)
        prefs.edit().putStringSet(KEY_HIDDEN_SAMPLES, next).apply()
    }

    fun unhideSampleName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(SAMPLE_LIST_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_HIDDEN_SAMPLES, emptySet()) ?: return
        if (name in current) {
            val next = current.toMutableSet().apply { remove(name) }
            prefs.edit().putStringSet(KEY_HIDDEN_SAMPLES, next).apply()
        }
    }

    fun removeEntry(context: Context, entryId: String): SoundFontEntry? {
        val entries = loadEntries(context)
        val target = entries.firstOrNull { it.id == entryId } ?: return null
        val remaining = entries.filterNot { it.id == entryId }
        saveEntries(context, remaining)
        try {
            fileFor(context, target).delete()
        } catch (_: Exception) {}
        return target
    }

    fun buildConfigItems(
        hiddenSamples: Set<String>,
        loadedEntries: List<SoundFontEntry>
    ): List<SoundFontConfigItem> {
        val items = mutableListOf<SoundFontConfigItem>()
        items += SoundFontConfigItem(
            id = CORE_SOUNDFONT_ID,
            displayName = "GeneralUser GS",
            isCore = true,
            entry = null
        )
        defaultSampleNames.filterNot { it in hiddenSamples }.forEach { sampleName ->
            items += SoundFontConfigItem(
                id = "sample:$sampleName",
                displayName = sampleName,
                isCore = false,
                entry = null
            )
        }
        loadedEntries.forEach { entry ->
            if (entry.displayName != "GeneralUser GS") {
                items += SoundFontConfigItem(
                    id = entry.id,
                    displayName = entry.displayName,
                    isCore = false,
                    entry = entry
                )
            }
        }
        return items
    }

    fun loadEntries(context: Context): MutableList<SoundFontEntry> {
        val file = registryFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SoundFontEntry(
                    id = o.optString("id"),
                    displayName = o.optString("displayName"),
                    fileName = o.optString("fileName"),
                    bundledAsset = o.optString("bundledAsset").ifBlank { null }
                )
            }.filter { it.id.isNotBlank() && it.fileName.isNotBlank() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveEntries(context: Context, entries: List<SoundFontEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("displayName", e.displayName)
                    .put("fileName", e.fileName)
                    .put("bundledAsset", e.bundledAsset)
            )
        }
        registryFile(context).writeText(arr.toString(2))
    }

    fun ensureBundled(context: Context, assetPath: String, displayName: String): SoundFontEntry {
        val entries = loadEntries(context)
        val existing = entries.firstOrNull { it.bundledAsset == assetPath }
        if (existing != null) {
            val file = File(soundFontsDir(context), existing.fileName)
            if (!file.exists()) {
                context.assets.open(assetPath).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            return existing
        }
        val fileName = assetPath.substringAfterLast('/')
        val target = File(soundFontsDir(context), fileName)
        context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        val entry = SoundFontEntry(
            id = "bundled-" + UUID.randomUUID().toString().take(8),
            displayName = displayName,
            fileName = fileName,
            bundledAsset = assetPath
        )
        entries.add(entry)
        saveEntries(context, entries)
        return entry
    }

    fun addUserSoundFont(context: Context, sourceName: String, bytes: ByteArray): SoundFontEntry {
        val safeBase = sourceName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_").ifBlank { "soundfont.sf2" }
        val finalName = if (safeBase.lowercase().endsWith(".sf2")) safeBase else "$safeBase.sf2"
        val dir = soundFontsDir(context)
        var target = File(dir, finalName)
        var n = 1
        while (target.exists()) {
            val stem = finalName.removeSuffix(".sf2")
            target = File(dir, "$stem-$n.sf2")
            n++
        }
        target.writeBytes(bytes)
        val display = target.nameWithoutExtension
        unhideSampleName(context, display)
        val entry = SoundFontEntry(id = "user-" + UUID.randomUUID().toString().take(8), displayName = display, fileName = target.name)
        val entries = loadEntries(context)
        entries.add(entry)
        saveEntries(context, entries)
        return entry
    }

    fun fileFor(context: Context, entry: SoundFontEntry): File = File(soundFontsDir(context), entry.fileName)
}

object Sf2Parser {
    fun parsePresets(file: File): List<SoundFontPreset> {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length().toInt().coerceAtLeast(0)
            val data = ByteArray(size)
            raf.readFully(data)
            return parsePresets(data)
        }
    }

    fun parsePresets(data: ByteArray): List<SoundFontPreset> {
        if (data.size < 12) return emptyList()
        if (String(data, 0, 4, Charsets.US_ASCII) != "RIFF") return emptyList()
        if (String(data, 8, 4, Charsets.US_ASCII) != "sfbk") return emptyList()
        var i = 12
        while (i + 8 <= data.size) {
            val cid = String(data, i, 4, Charsets.US_ASCII)
            val csize = leInt(data, i + 4)
            val ds = i + 8
            val de = (ds + csize).coerceAtMost(data.size)
            if (cid == "LIST" && ds + 4 <= de) {
                val ltype = String(data, ds, 4, Charsets.US_ASCII)
                if (ltype == "pdta") {
                    return parsePdta(data, ds + 4, de)
                }
            }
            i = ds + csize + (csize and 1)
        }
        return emptyList()
    }

    private fun parsePdta(data: ByteArray, start: Int, end: Int): List<SoundFontPreset> {
        var i = start
        while (i + 8 <= end) {
            val cid = String(data, i, 4, Charsets.US_ASCII)
            val csize = leInt(data, i + 4)
            val ds = i + 8
            val de = (ds + csize).coerceAtMost(end)
            if (cid == "phdr") {
                val recSize = 38
                val count = (de - ds) / recSize
                val out = mutableListOf<SoundFontPreset>()
                for (j in 0 until count) {
                    val off = ds + j * recSize
                    val nameBytes = data.copyOfRange(off, off + 20)
                    val nul = nameBytes.indexOf(0)
                    val name = String(nameBytes, 0, if (nul >= 0) nul else 20, Charsets.ISO_8859_1).trim()
                    val preset = leUShort(data, off + 20)
                    val bank = leUShort(data, off + 22)
                    // The final phdr record is the "EOP" terminator, not a preset. Its bank
                    // and program fields are not always 255 (this bundle writes 0/0), so
                    // test the name only - otherwise it duplicates the 0/0 preset.
                    if (name == "EOP") continue
                    out.add(SoundFontPreset(bank, preset, name.ifBlank { "Preset $bank/$preset" }))
                }
                return out
            }
            i = ds + csize + (csize and 1)
        }
        return emptyList()
    }

    private fun leInt(data: ByteArray, off: Int): Int =
        ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leUShort(data: ByteArray, off: Int): Int =
        ByteBuffer.wrap(data, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
}

object InstrumentCatalog {
    fun coreOptions(): List<InstrumentOption> = instruments.map {
        InstrumentOption(
            id = it.id,
            name = it.name,
            group = it.group,
            soundFontId = CORE_SOUNDFONT_ID,
            bank = 0,
            program = it.program
        )
    }

    fun customOptions(entry: SoundFontEntry, presets: List<SoundFontPreset>): List<InstrumentOption> =
        presets.map { p ->
            InstrumentOption(
                id = customId(entry.id, p.bank, p.program),
                name = p.name,
                group = entry.displayName,
                soundFontId = entry.id,
                bank = p.bank,
                program = p.program
            )
        }

    fun customId(soundFontId: String, bank: Int, program: Int): String = "sf:$soundFontId:$bank:$program"

    fun isCustomId(id: String): Boolean = id.startsWith("sf:")
}

fun resolveInstrument(id: String, extraOptions: List<InstrumentOption>): InstrumentOption? {
    InstrumentCatalog.coreOptions().firstOrNull { it.id == id }?.let { return it }
    extraOptions.firstOrNull { it.id == id }?.let { return it }
    if (InstrumentCatalog.isCustomId(id)) {
        val parts = id.split(":")
        if (parts.size >= 4) {
            val bank = parts[parts.size - 2].toIntOrNull()
            val program = parts.last().toIntOrNull()
            val sfId = parts.drop(1).dropLast(2).joinToString(":")
            if (bank != null && program != null) {
                return InstrumentOption(id, "Preset $bank/$program", "Custom", sfId, bank, program)
            }
        }
    }
    return null
}
