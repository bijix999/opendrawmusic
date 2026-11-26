package com.opendrawmusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Emits the real MIDI files used as Run 6 evidence, by calling the production
 * [MidiExporter.export]. The files are parsed separately by a host-side script, so the
 * evidence does not depend on this test's own assertions.
 *
 * Run 7 note: cells are 36-line cells now, so legacy row 20 (C4 under the original
 * 32-row logic) is written as pitch line 25 (0-based index 24) - the same pitch.
 */
class Run6EvidenceWriter {

    private val outDir = File("D:/vibecoded/Open Draw Music/run6-evidence")

    private val row = 20 // C4 under the original 32-row logic

    /** Legacy row -> 0-based 36-line index (old row r lives on line r + 5). */
    private fun cell(step: Int) = (row + 4) * 24 + step

    @Test
    fun writeConsecutiveStepMidi() {
        val project = MusicProject(
            title = "run6-consecutive",
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
        outDir.mkdirs()
        val f = File(outDir, "run6-consecutive.mid")
        f.writeBytes(MidiExporter.export(project))
        assertTrue("expected a non-empty MIDI file", f.length() > 0)
    }

    @Test
    fun writeEveryStepMidi() {
        val project = MusicProject(
            title = "run6-everystep",
            bpm = 120,
            selectedInstrument = "SF_SOFT_KEY",
            frames = listOf(ProjectFrame(listOf(
                ProjectStroke(
                    instrument = "SF_SOFT_KEY",
                    tool = "BRUSH",
                    points = listOf(0f to 0.5f),
                    cells = (0 until 24).map { cell(it) }
                )
            )))
        )
        outDir.mkdirs()
        val f = File(outDir, "run6-everystep.mid")
        f.writeBytes(MidiExporter.export(project))
        assertTrue(f.length() > 0)
    }

    @Test
    fun writeRowSpanMidi() {
        val rows = listOf(0, 10, 20, 21, 31)
        val project = MusicProject(
            title = "run6-rows",
            bpm = 120,
            selectedInstrument = "SF_SOFT_KEY",
            frames = listOf(ProjectFrame(
                rows.mapIndexed { i, r ->
                    ProjectStroke(
                        instrument = "SF_SOFT_KEY",
                        tool = "BRUSH",
                        points = listOf(0f to 0.5f),
                        cells = listOf((r + 4) * 24 + i)
                    )
                }
            ))
        )
        outDir.mkdirs()
        val f = File(outDir, "run6-rows.mid")
        f.writeBytes(MidiExporter.export(project))
        assertTrue(f.length() > 0)
    }
}
