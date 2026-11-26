package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Locks "one drawn note = one step" at the MIDI-export level.
 *
 * The tests call the real [MidiExporter.export] and parse the bytes it produces, so they fail
 * if the exported file ever carries a note that outlives its own step.
 */
class NoteStepDurationTest {

    private val row = 20   // row 20 is C4 under the original 32-row logic

    /** Legacy row -> 0-based 36-line index (old row r lives on line r + 5; same pitch). */
    private fun cell(step: Int) = (row + 4) * 24 + step

    private fun oneNoteAtStep(step: Int, bpm: Int = 120) = MusicProject(
        title = "step-$step",
        bpm = bpm,
        selectedInstrument = "SF_SOFT_KEY",
        frames = listOf(ProjectFrame(listOf(
            ProjectStroke(
                instrument = "SF_SOFT_KEY",
                tool = "BRUSH",
                points = listOf(step.toFloat() / 24f to (row + 4).toFloat() / 36f),
                cells = listOf(cell(step))
            )
        )))
    )

    /** Minimal MIDI reader: returns (ticksPerQuarter, list of (tick, status, data1, data2)). */
    private fun parse(bytes: ByteArray): Pair<Int, List<IntArray>> {
        var i = 0
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "MThd") { "not a MIDI file" }
        i += 4
        val headerLen = beInt(bytes, i); i += 4
        i += 2                                  // format = 0 (single track)
        val tracks = beShort(bytes, i); i += 2
        assertEquals("single track", 1, tracks)
        val ticksPerQuarter = beShort(bytes, i); i += 2
        i += headerLen - 6
        require(String(bytes, i, 4, Charsets.US_ASCII) == "MTrk") { "no MTrk chunk" }
        i += 4
        val trackLen = beInt(bytes, i); i += 4
        val end = i + trackLen

        val events = mutableListOf<IntArray>()
        var tick = 0
        var running = 0
        while (i < end) {
            var delta = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                delta = (delta shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            tick += delta
            var status = bytes[i].toInt() and 0xFF
            if (status and 0x80 != 0) {
                i++
                running = status
            } else {
                status = running
            }
            when (status and 0xF0) {
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> {
                    val d1 = bytes[i++].toInt() and 0xFF
                    val d2 = bytes[i++].toInt() and 0xFF
                    events += intArrayOf(tick, status, d1, d2)
                }
                0xC0, 0xD0 -> {
                    val d1 = bytes[i++].toInt() and 0xFF
                    events += intArrayOf(tick, status, d1, 0)
                }
                0xF0 -> {
                    while (i < end) {                       // skip meta/sysex payload
                        val b = bytes[i++].toInt() and 0xFF
                        var len = 0
                        while (true) {
                            val l = bytes[i++].toInt() and 0xFF
                            len = (len shl 7) or (l and 0x7F)
                            if (l and 0x80 == 0) break
                        }
                        i += len
                        break
                    }
                }
                else -> error("unexpected status 0x%02X".format(status))
            }
        }
        return ticksPerQuarter to events
    }

    private fun beShort(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun beInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun noteOns(events: List<IntArray>) = events.filter { (it[1] and 0xF0) == 0x90 && it[3] > 0 }
    private fun noteOffs(events: List<IntArray>) = events.filter { (it[1] and 0xF0) == 0x80 }

    @Test
    fun aNoteOnStepZeroStartsAtTickZeroAndEndsInsideItsOwnStep() {
        val (tpq, events) = parse(MidiExporter.export(oneNoteAtStep(0)))

        assertEquals("ticks per quarter note", 96, tpq)
        assertEquals("ticks per step (4 steps per quarter)", 24, MidiExporter.TICKS_PER_STEP)

        val ons = noteOns(events)
        assertEquals("exactly one note-on", 1, ons.size)
        assertEquals("note-on tick", 0, ons[0][0])

        val offs = noteOffs(events)
        assertEquals("exactly one note-off", 1, offs.size)
        assertEquals("note-off tick", 23, offs[0][0])
        assertEquals("note-off must land before the next step starts",
            true, offs[0][0] < 24)
        assertEquals("note-off tick, expressed in steps", 23, MidiExporter.NOTE_TICKS)
    }

    @Test
    fun consecutiveStepsGiveOneNotePerStepWithNoOverlap() {
        val project = MusicProject(
            title = "consecutive",
            bpm = 120,
            selectedInstrument = "SF_SOFT_KEY",
            frames = listOf(ProjectFrame(listOf(
                ProjectStroke(
                    instrument = "SF_SOFT_KEY",
                    tool = "BRUSH",
                    points = listOf(0f to 0.5f),
                    cells = listOf(cell(0), cell(1), cell(2), cell(3))
                )
            )))
        )
        val (tpq, events) = parse(MidiExporter.export(project))
        assertEquals(96, tpq)

        val ons = noteOns(events).sortedBy { it[0] }
        val offs = noteOffs(events).sortedBy { it[0] }
        assertEquals("four notes, one per step", 4, ons.size)
        assertEquals("four releases", 4, offs.size)
        assertEquals("start ticks", listOf(0, 24, 48, 72), ons.map { it[0] })
        assertEquals("end ticks", listOf(23, 47, 71, 95), offs.map { it[0] })

        // No note may still be sounding when the following step begins.
        ons.zip(offs).forEachIndexed { step, (on, off) ->
            assertTrue(
                "step $step: release tick ${off[0]} must be before next step tick ${on[0] + 24}",
                off[0] < on[0] + 24
            )
        }
    }

    @Test
    fun noteLengthFollowsTheStepGridAtOneHundredTwentyBpm() {
        val bpm = 120
        val stepSeconds = 60.0 / bpm / 4.0
        assertEquals("step duration at 120 BPM", 0.125, stepSeconds, 1e-9)

        val (tpq, events) = parse(MidiExporter.export(oneNoteAtStep(4, bpm)))
        val ticksPerQuarter = tpq.toDouble()
        val secondsPerTick = 60.0 / bpm / ticksPerQuarter

        val ons = noteOns(events)
        val offs = noteOffs(events)
        assertEquals("note-on at step 4", 4 * 24, ons[0][0])

        val seconds = (offs[0][0] - ons[0][0]) * secondsPerTick
        assertTrue("note lasts under one step ($seconds s)", seconds < stepSeconds)
        assertTrue("note lasts most of its step ($seconds s)", seconds > stepSeconds * 0.9)
    }

    @Test
    fun aNoteIsNotSustainedAcrossLaterSteps() {
        // A one-cell note must never emit a release outside its own step, so the sounding
        // span can never reach into step 1.
        val (_, events) = parse(MidiExporter.export(oneNoteAtStep(0)))
        val offs = noteOffs(events)
        assertEquals(1, offs.size)
        assertTrue(
            "release tick ${offs[0][0]} must stay inside step 0 (0..23)",
            offs[0][0] in 0..23
        )
    }

    @Test
    fun midiHeaderDeclaresTheExportGridConstants() {
        // Guards the constants against silent drift from the written header.
        val bytes = MidiExporter.export(oneNoteAtStep(0))
        val declared = beShort(bytes, 12)
        assertEquals(MidiExporter.TICKS_PER_QUARTER, declared)
        assertEquals(4, MidiExporter.TICKS_PER_QUARTER / MidiExporter.TICKS_PER_STEP)
        assertEquals(MidiExporter.TICKS_PER_STEP - 1, MidiExporter.NOTE_TICKS)
        assertTrue(ByteArrayOutputStream().use { true })
    }
}
