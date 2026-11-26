package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the parsed preset inventory of the bundled core SoundFont against the real asset.
 *
 * The SoundFont's final `phdr` record is the `EOP` terminator. It must never appear as a
 * playable preset, and no two presets may share a bank/program pair.
 */
class PresetInventoryTest {

    private val sf2 = File("src/main/assets/core.sf2")

    @Test
    fun theSoundFontAssetIsPresent() {
        assertTrue("core.sf2 missing at ${sf2.absolutePath}", sf2.isFile)
        assertTrue("core.sf2 looks empty", sf2.length() > 1_000_000)
    }

    @Test
    fun theTerminatorRecordIsNotOfferedAsAPreset() {
        val presets = Sf2Parser.parsePresets(sf2)
        assertTrue("expected a populated preset list", presets.size > 100)
        assertFalse(
            "the EOP terminator leaked in as a preset: ${presets.filter { it.name == "EOP" }}",
            presets.any { it.name == "EOP" }
        )
    }

    @Test
    fun noTwoPresetsShareABankAndProgram() {
        val presets = Sf2Parser.parsePresets(sf2)
        val duplicates = presets.groupBy { it.bank to it.program }.filterValues { it.size > 1 }
        assertTrue("duplicate bank/program presets: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun theBundleHoldsTheExpectedNumberOfDistinctPresets() {
        val presets = Sf2Parser.parsePresets(sf2)
        assertEquals("distinct presets in core.sf2", 287, presets.size)
        assertEquals(287, presets.map { it.bank to it.program }.toSet().size)
    }

    @Test
    fun everyPresetLandsInExactlyOneFamilyCategory() {
        val presets = Sf2Parser.parsePresets(sf2)
        presets.forEach { p ->
            val category = categoryForPreset(p.bank, p.program)
            assertTrue("${p.name} (${p.bank}/${p.program}) -> '$category' not a known category",
                category in presetCategories)
            assertEquals(
                "category must be stable for ${p.name}",
                category, categoryForPreset(p.bank, p.program)
            )
        }
        // And no category label may carry a "Core: " prefix.
        presetCategories.forEach { assertFalse(it.contains("Core: ")) }
    }

    @Test
    fun aRepresentativePresetOfEachFamilyResolvesToItsFamily() {
        val presets = Sf2Parser.parsePresets(sf2)
        val byPair = presets.associateBy { it.bank to it.program }

        val expectations = mapOf(
            (0 to 0) to "Piano",                       // Grand Piano
            (0 to 5) to "Piano",                       // FM Electric Piano
            (0 to 12) to "Chromatic percussion",       // Marimba
            (0 to 10) to "Chromatic percussion",       // Music Box
            (0 to 16) to "Organ",                      // Tonewheel Organ
            (0 to 24) to "Guitar",                     // Nylon Guitar
            (0 to 38) to "Bass",                       // Synth Bass 1
            (0 to 48) to "Ensemble",                   // Fast Strings
            (0 to 56) to "Brass",                      // Trumpet
            (0 to 71) to "Reed",                       // Clarinet
            (0 to 73) to "Pipe",                       // Flute
            (0 to 80) to "Synth lead",                 // Square Lead
            (0 to 88) to "Synth pad",                  // Fantasia
            (0 to 98) to "Synth effects",              // Crystal
            (0 to 104) to "Ethnic",                    // Sitar
            (0 to 115) to "Percussive",                // Wood Block
            (0 to 122) to "Sound effects",             // Seashore
            (120 to 0) to "Drum kits",                 // Standard 1 Kit
            (128 to 0) to "Drum kits"                  // Standard 1
        )

        expectations.forEach { (pair, expected) ->
            val preset = byPair[pair] ?: error("preset $pair missing from core.sf2")
            assertEquals("${preset.name} at ${pair.first}/${pair.second}", expected,
                categoryForPreset(preset.bank, preset.program))
        }
    }
}