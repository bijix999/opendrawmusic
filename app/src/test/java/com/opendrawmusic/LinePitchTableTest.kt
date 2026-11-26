package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the Run 7 36-line table (§3.4) against the output of `run7_line_table.py`
 * (the script the User locked: the table must be computed, not hand-derived).
 *
 * Golden values below are copied from `run7-line-table-output.md` produced by:
 *   python run7_line_table.py
 */
class LinePitchTableTest {

    /** Default key C, major pentatonic: lines 1..36 straight from the script output. */
    private val defaultCPentatonic = listOf(
        93, 91, 88, 86,             // lines 1-4  (new lines above line 5): A6 G6 E6 D6
        84, 84, 81, 81, 79, 79,     // lines 5-10
        76, 76, 74, 74, 72, 72,     // lines 11-16
        69, 69, 67, 67, 64, 64,     // lines 17-22
        62, 62, 60, 60, 57, 57,     // lines 23-28
        55, 55, 52, 52, 50, 50,     // lines 29-34
        48, 48                      // lines 35-36
    )

    @Test
    fun defaultTableMatchesScriptOutput() {
        val actual = (1..PITCH_LINES).map { midiForLine(it, 48, "PENTATONIC") }
        assertEquals("36-line C pentatonic table from run7_line_table.py", defaultCPentatonic, actual)
    }

    @Test
    fun line5IsC6AndLines1To4RiseAboveIt() {
        assertEquals("line 5 = C6 (old row 0)", 84, midiForLine(5, 48, "PENTATONIC"))
        assertEquals("line 4 = D6", 86, midiForLine(4, 48, "PENTATONIC"))
        assertEquals("line 3 = E6", 88, midiForLine(3, 48, "PENTATONIC"))
        assertEquals("line 2 = G6", 91, midiForLine(2, 48, "PENTATONIC"))
        assertEquals("line 1 = A6 (top = highest pitch)", 93, midiForLine(1, 48, "PENTATONIC"))
    }

    @Test
    fun lines5To36KeepTheOldThirtyTwoRowPitchesForEveryScaleAndKey() {
        // T16/T29: for every scale and key, line r+5 must equal old row r exactly.
        musicalScales.forEach { scale ->
            musicalKeys.forEach { (_, root) ->
                (0 until LEGACY_ROWS).forEach { row ->
                    assertEquals("scale ${scale.id} key $root row $row",
                        midiForRow(row, root, scale.id),
                        midiForLine(row + 5, root, scale.id))
                }
            }
        }
    }

    @Test
    fun legacySpotChecksFromTheRun6GoldenTable() {
        // T16: old rows 21 and 31 are now lines 26 and 36.
        assertEquals("old row 21 -> line 26 = C4 (60)", 60, midiForLine(26, 48, "PENTATONIC"))
        assertEquals("old row 31 -> line 36 = C3 (48)", 48, midiForLine(36, 48, "PENTATONIC"))
        assertEquals("old row 20 -> line 25 = C4 (60)", 60, midiForLine(25, 48, "PENTATONIC"))
        assertEquals("old row 0 -> line 5 = C6 (84)", 84, midiForLine(5, 48, "PENTATONIC"))
    }

    @Test
    fun lineTableStaysWithinMidiRangeAtExtremeKeys() {
        // B key (root 59) puts line 1 at degree span+4: must not exceed MIDI 127.
        musicalKeys.forEach { (_, root) ->
            (1..PITCH_LINES).forEach { line ->
                val midi = midiForLine(line, root, "CHROMATIC")
                require(midi in 0..127) { "line $line root $root -> $midi out of range" }
            }
        }
    }
}
