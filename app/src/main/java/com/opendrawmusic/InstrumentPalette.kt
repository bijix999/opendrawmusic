package com.opendrawmusic

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Instrument colour + icon palette from the ORIGINAL app's verified data (Run 7 §3.7).
 *
 * ORIGINAL data (never invented, never re-hued):
 *  - assets/instrument_color_map.json — the original app's `instrument_color_map.json`,
 *    164,695 bytes, SHA-256
 *    fe69bff31f186eda6e9855c3a0cfe604725d8340ec907fc6c8eb0f78104facc5
 *    (byte-identical copy of the decompiled original's assets file). Every one of its
 *    274 instruments carries a `color` (#RRGGBB tile colour) and an `icon` (drawable
 *    name). Verified in this run: 274 distinct (bank, program) keys, 0 duplicates, and
 *    they cover 274 of this app's 287 presets directly.
 *  - res/drawable/ic_inst_<bank>_<program>.xml — the original icon vectors (274 files,
 *    1:1 with the color map's `icon` values, 0 missing / 0 orphans).
 *
 * PROPOSALs (NOT original data — labelled PROPOSAL everywhere they surface):
 *  - CATEGORY_MAIN_COLORS: the original app stores NO category colour (its groups have
 *    only name/code/columns/instruments). Each main colour is the extraction run's
 *    PROPOSAL: the highest-saturation instrument colour in the group.
 *  - The 13 bank-120 drum kits are not in the original color map (the original Drum kits
 *    group covers bank 128 only). Each reuses the matching bank-128 kit's colour + icon:
 *    identical program numbers and matching kit names (verified against the SF2 phdr:
 *    bank-120 "Room Kit" p008 ↔ bank-128 "Room" p008, ... 13/13 matched). PROPOSAL.
 *
 * Glyph tint rule (original runtime, VERIFIED in the decompiled smali —
 * InstrumentWheelView2.smali, debug lines .line 1134–1155):
 *   invoke ColorUtils.calculateLuminance(cellColor)  // smali: ColorUtils;->e(I)D
 *   const-wide 0x3fc70a3d70a3d70aL  # 0.18
 *   if (luminance < 0.18) tint = 0xFFFFFFFF (white)
 *   else tint = Color.rgb(0x1f, 0x23, 0x21) = #1F2321
 *   applied with PorterDuff.SRC_IN (drawables use the same SRC_IN in Compose).
 * The exact androidx ColorUtils.calculateLuminance is called here — the smali's
 * `ColorUtils;->e(I)D` IS calculateLuminance (its body: colorToXYZ → Y/100) — so no
 * other luminance formula is substituted.
 */
object InstrumentPalette {

    data class Entry(val color: String, val iconName: String?, val bank120Proposal: Boolean)

    /** (bank → (program → entry)) built from the original color map in [init]. */
    private var byBankProgram: Map<Int, Map<Int, Entry>> = emptyMap()
    private var categoryFirstIcons: Map<String, String> = emptyMap()
    private var iconResIds: Map<String, Int> = emptyMap()

    /**
     * PROPOSAL (not original data) — one main colour per category, from the extraction
     * report's PROPOSAL table (highest-saturation instrument colour per group).
     * value = hex; source instrument recorded in CATEGORY_MAIN_COLOR_SOURCES.
     */
    val CATEGORY_MAIN_COLORS: Map<String, String> = linkedMapOf(
        "Piano" to "#FF8726",
        "Chromatic percussion" to "#E89E09",
        "Synth pad" to "#D05928",
        "Guitar" to "#C46D46",
        "Pipe" to "#FFF000",
        "Ensemble" to "#FFBC08",
        "Bass" to "#168F88",
        "Synth lead" to "#00944C",
        "Reed" to "#00878E",
        "Organ" to "#00BACB",
        "Strings" to "#00B7D9",
        "Brass" to "#0097C9",
        "Synth effects" to "#4245A3",
        "Ethnic" to "#6A88C2",
        "Percussive" to "#BD8AE6",
        "Sound effects" to "#ED5D47",
        "Drum kits" to "#BB434E"
    )

    /** PROPOSAL source instrument per category (from the extraction report). */
    val CATEGORY_MAIN_COLOR_SOURCES: Map<String, String> = mapOf(
        "Piano" to "SF Clavinet",
        "Chromatic percussion" to "SF Marimba",
        "Synth pad" to "SF Warm Synth",
        "Guitar" to "SF Guitar",
        "Pipe" to "SF Pan Flute",
        "Ensemble" to "SF Synth Voice",
        "Bass" to "SF Electronic Bass",
        "Synth lead" to "SF 5th Saw Wave",
        "Reed" to "SF Clarinet",
        "Organ" to "SF Pipe Organ",
        "Strings" to "SF Double Bass",
        "Brass" to "SF Trombone 2",
        "Synth effects" to "SF Brightness",
        "Ethnic" to "SF Banjo",
        "Percussive" to "SF Cymbal Crash",
        "Sound effects" to "SF Starship",
        "Drum kits" to "SF CM-64/32L"
    )

    /** Parses the color map once (called from MainActivity.onCreate). */
    fun init(context: Context) {
        if (byBankProgram.isNotEmpty()) return
        val json = context.assets.open("instrument_color_map.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val map = mutableMapOf<Int, MutableMap<Int, Entry>>()
        val catIcons = mutableMapOf<String, String>()
        val groups = root.getJSONArray("groups")
        for (gi in 0 until groups.length()) {
            val groupObj = groups.getJSONObject(gi)
            val groupName = groupObj.optString("name", "")
            val instruments = groupObj.getJSONArray("instruments")
            for (ii in 0 until instruments.length()) {
                val ins = instruments.getJSONObject(ii)
                val sound = ins.optJSONObject("sound") ?: continue
                val bank = sound.getInt("bank")
                val program = sound.getInt("program")
                val color = ins.optString("color", "")
                if (color.isEmpty()) continue
                val icon = if (ins.isNull("icon")) null else ins.optString("icon", null)
                map.getOrPut(bank) { mutableMapOf() }[program] = Entry(color, icon, bank120Proposal = false)
                if (ii == 0 && groupName.isNotEmpty() && !icon.isNullOrEmpty()) {
                    catIcons[groupName] = icon
                }
            }
        }
        categoryFirstIcons = catIcons
        // PROPOSAL: the 13 bank-120 kits reuse the matching bank-128 kit (same program).
        // Verified: bank-128 has exactly the 13 programs bank-120 uses.
        val b128 = map[128].orEmpty()
        val b120 = mutableMapOf<Int, Entry>()
        b128.forEach { (program, entry) ->
            b120[program] = Entry(entry.color, entry.iconName, bank120Proposal = true)
        }
        if (b120.isNotEmpty()) map[120] = b120
        byBankProgram = map

        val pkg = context.packageName
        val ids = mutableMapOf<String, Int>()
        val names = mutableSetOf<String>()
        map.values.forEach { perBank -> perBank.values.forEach { e -> e.iconName?.let { names.add(it) } } }
        names.forEach { name ->
            val id = context.resources.getIdentifier(name, "drawable", pkg)
            if (id != 0) ids[name] = id
        }
        iconResIds = ids
    }

    /** PROPOSAL category main colour (#RRGGBB) from Run 7 PROPOSAL table. */
    fun categoryColorHex(group: String): String? = CATEGORY_MAIN_COLORS[group]

    /** Icon drawable name of the FIRST instrument in [group] per the original JSON. */
    fun categoryIconName(group: String): String? = categoryFirstIcons[group]

    /** Resolved drawable resource id of the FIRST instrument in [group]. */
    fun categoryIconRes(group: String): Int? = categoryIconName(group)?.let { iconResIds[it] }

    /** Original stored colour (#RRGGBB) for (bank, program), or null if the original data has none. */
    fun colorHex(bank: Int, program: Int): String? = byBankProgram[bank]?.get(program)?.color

    /** Icon drawable name (bank-120 = PROPOSAL reuse of the bank-128 kit's icon). */
    fun iconName(bank: Int, program: Int): String? = byBankProgram[bank]?.get(program)?.iconName

    /** Resolved drawable resource id, or null when no icon exists (show a placeholder). */
    fun iconRes(bank: Int, program: Int): Int? = iconName(bank, program)?.let { iconResIds[it] }

    /** True when this entry is the bank-120 PROPOSAL (colour + icon reused from bank-128). */
    fun isBank120Proposal(bank: Int, program: Int): Boolean =
        byBankProgram[bank]?.get(program)?.bank120Proposal == true

    /** Normalizes original extraction values (#RRGGBB) to Android #AARRGGBB. */
    fun normalizeHex(hex: String): String {
        val value = hex.trim()
        if (value.startsWith("#")) {
            val digits = value.substring(1)
            return when (digits.length) {
                6 -> "#FF$digits"
                8 -> value
                else -> throw IllegalArgumentException("Invalid instrument color: $hex")
            }
        }
        return when (value.length) {
            6 -> "#FF$value"
            8 -> "#$value"
            else -> throw IllegalArgumentException("Invalid instrument color: $hex")
        }
    }

    /** Parses "#RRGGBB" / "#AARRGGBB" to an Android colour int. */
    fun parseColor(hex: String): Int =
        AndroidColor.parseColor(normalizeHex(hex))

    /**
     * Original glyph tint rule (smali .line 1134–1155): luminance < 0.18 → white,
     * else #1F2321. Uses androidx ColorUtils.calculateLuminance, the same call the
     * smali makes (ColorUtils;->e(I)D).
     */
    fun glyphColor(colorInt: Int): Color {
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(colorInt)
        return if (luminance < 0.18) Color(0xFFFFFFFF) else Color(0xFF1F2321)
    }

    fun glyphColorHex(hex: String): Color = glyphColor(parseColor(hex))
}
