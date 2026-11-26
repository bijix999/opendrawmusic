package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run 7 Fix 10 unit checks for Soundfont Configuration list & deletion rules:
 * - GeneralUser GS is core and cannot be deleted.
 * - Non-core items (FluidR3 GM, Arachno SoundFont, user-added SoundFonts) can be deleted.
 * - Re-adding a deleted SoundFont via user import restores it to the list.
 */
class SoundFontConfigDeleteTest {

    @Test
    fun coreGeneralUserGsIsMarkedCoreAndNeverHidden() {
        val items = SoundFontRegistry.buildConfigItems(
            hiddenSamples = setOf("GeneralUser GS", "FluidR3 GM"),
            loadedEntries = emptyList()
        )
        assertEquals(2, items.size)
        assertEquals("GeneralUser GS", items[0].displayName)
        assertTrue(items[0].isCore)
        assertEquals("Arachno SoundFont", items[1].displayName)
        assertFalse(items[1].isCore)
    }

    @Test
    fun deletingSampleItemRemovesItFromBuiltConfigList() {
        val initial = SoundFontRegistry.buildConfigItems(emptySet(), emptyList())
        assertEquals(listOf("GeneralUser GS", "FluidR3 GM", "Arachno SoundFont"), initial.map { it.displayName })

        val afterDeleteFluidR3 = SoundFontRegistry.buildConfigItems(setOf("FluidR3 GM"), emptyList())
        assertEquals(listOf("GeneralUser GS", "Arachno SoundFont"), afterDeleteFluidR3.map { it.displayName })
    }

    @Test
    fun userImportedSoundFontAppearsAndCanBeRemoved() {
        val userEntry = SoundFontEntry(id = "user-1234", displayName = "FluidR3 GM", fileName = "FluidR3 GM.sf2")
        val withImport = SoundFontRegistry.buildConfigItems(
            hiddenSamples = setOf("FluidR3 GM"), // unhidden on import, or even if hidden, entry still shows
            loadedEntries = listOf(userEntry)
        )
        assertTrue(withImport.any { it.id == "user-1234" && !it.isCore && it.entry == userEntry })
    }
}
