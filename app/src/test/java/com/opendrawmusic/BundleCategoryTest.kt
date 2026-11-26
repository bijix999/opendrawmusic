package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the instrument-family categorisation of the core SoundFont presets.
 *
 * The rule under test is that a preset is filed by what instrument it is: its General MIDI
 * family (program / 8) for melodic banks, and the drum bank for drum patches. This is the same
 * grouping the original app's own instrument_color_map.json uses.
 */
class BundleCategoryTest {

    @Test
    fun generalMidiFamilyBoundariesMapToTheirFamily() {
        val expected = mapOf(
            0 to "Piano", 7 to "Piano",
            8 to "Chromatic percussion", 15 to "Chromatic percussion",
            16 to "Organ", 23 to "Organ",
            24 to "Guitar", 31 to "Guitar",
            32 to "Bass", 39 to "Bass",
            40 to "Strings", 47 to "Strings",
            48 to "Ensemble", 55 to "Ensemble",
            56 to "Brass", 63 to "Brass",
            64 to "Reed", 71 to "Reed",
            72 to "Pipe", 79 to "Pipe",
            80 to "Synth lead", 87 to "Synth lead",
            88 to "Synth pad", 95 to "Synth pad",
            96 to "Synth effects", 103 to "Synth effects",
            104 to "Ethnic", 111 to "Ethnic",
            112 to "Percussive", 119 to "Percussive",
            120 to "Sound effects", 127 to "Sound effects"
        )
        expected.forEach { (program, category) ->
            assertEquals("bank 0 program $program", category, categoryForPreset(0, program))
        }
    }

    @Test
    fun drumBanksMapToDrumKits() {
        // Both the bank-120 "... Kit" presets and the bank-128 kits are drum patches.
        listOf(0, 8, 16, 24, 25, 32, 40, 48, 56, 127).forEach { program ->
            assertEquals("bank 120 program $program", "Drum kits", categoryForPreset(120, program))
            assertEquals("bank 128 program $program", "Drum kits", categoryForPreset(128, program))
        }
    }

    @Test
    fun everyMelodicProgramInTheBundleLandsInExactlyOneFamilyCategory() {
        // A bank-0 preset must never fall through to the drum category.
        (0..127).forEach { program ->
            val category = categoryForPreset(0, program)
            assertTrue("program $program -> $category", category in presetCategories)
            assertFalse("program $program wrongly drum", category == "Drum kits")
        }
    }

    @Test
    fun everyCategoryLabelIsAPlainInstrumentFamilyName() {
        // Categories are named by instrument family only: no "Core: " prefix anywhere.
        presetCategories.forEach { label ->
            assertFalse("label '$label' still carries a prefix", label.contains("Core: "))
            assertFalse("label '$label' is not trimmed", label != label.trim())
            assertTrue("label '$label' is blank", label.isNotBlank())
        }
        assertEquals(17, presetCategories.size)
        assertEquals(17, presetCategories.toSet().size)
    }

    @Test
    fun theSeventeenBrandedCategoriesFromTheOriginalAppAreAllPresent() {
        // The family names the original app uses, plus the drum bank.
        val required = listOf(
            "Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble",
            "Brass", "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "Ethnic",
            "Percussive", "Sound effects", "Drum kits"
        )
        required.forEach { assertTrue("missing category $it", it in presetCategories) }
    }
}
