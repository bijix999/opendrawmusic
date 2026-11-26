package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run 7 Fix 9 verification: grid follows the currently playing frame during playback,
 * wraps on Loop, still responds to manual thumbnail taps, keeps frame 1 -> 2 -> 3 audio
 * order in MidiExporter, and aligns playColumn with the current step of the playing frame.
 */
class GridPlaybackFrameTest {

    private fun stroke(instrument: String, vararg cells: Int) = ProjectStroke(
        instrument = instrument,
        tool = "BRUSH",
        points = listOf(0.5f to 0.5f),
        cells = cells.toList()
    )

    private fun threeFrameProject() = MusicProject(
        title = "grid-playback-test",
        frames = listOf(
            // Frame 1 (index 0): line 1 step 0 (cell 0, MIDI 93 = A6)
            ProjectFrame(listOf(stroke("SF_SOFT_KEY", 0))),
            // Frame 2 (index 1): line 15 step 5 (cell 14*24+5 = 341, MIDI 72 = C5)
            ProjectFrame(listOf(stroke("SF_SOFT_KEY", 341))),
            // Frame 3 (index 2): line 36 step 12 (cell 35*24+12 = 852, MIDI 48 = C3)
            ProjectFrame(listOf(stroke("SF_SOFT_KEY", 852)))
        ),
        selectedFrame = 0
    )

    @Test
    fun checkA_advanceFrame1To2ShowsFrame2CellsOnGrid() {
        val project = threeFrameProject()
        val displayed = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = true,
            playbackFrame = 1
        )
        assertEquals(1, displayed)
        assertEquals(
            project.frames[1].strokes.flatMap { it.cells },
            project.frames[displayed].strokes.flatMap { it.cells }
        )
    }

    @Test
    fun checkB_advanceFrame2To3ShowsFrame3CellsOnGrid() {
        val project = threeFrameProject()
        val displayed = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = true,
            playbackFrame = 2
        )
        assertEquals(2, displayed)
        assertEquals(
            project.frames[2].strokes.flatMap { it.cells },
            project.frames[displayed].strokes.flatMap { it.cells }
        )
    }

    @Test
    fun checkC_loopWrap3To1ShowsFrame1CellsOnGrid() {
        val project = threeFrameProject()
        val displayed = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = true,
            playbackFrame = 0
        )
        assertEquals(0, displayed)
        assertEquals(
            project.frames[0].strokes.flatMap { it.cells },
            project.frames[displayed].strokes.flatMap { it.cells }
        )
    }

    @Test
    fun checkD_manualThumbnailTapSelectsFrame2OnGrid() {
        val project = threeFrameProject().copy(selectedFrame = 1)
        val displayedStopped = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = false,
            playbackFrame = -1
        )
        assertEquals(1, displayedStopped)
        assertEquals(
            project.frames[1].strokes.flatMap { it.cells },
            project.frames[displayedStopped].strokes.flatMap { it.cells }
        )

        // While playing, tapping thumbnail 2 sets selectedFrame = 1 and jumps playbackFrame to 1.
        val displayedPlayingJump = displayedFrameIndex(
            selectedFrame = project.selectedFrame,
            frameCount = project.frames.size,
            isPlaying = true,
            playbackFrame = 1
        )
        assertEquals(1, displayedPlayingJump)
        assertEquals(
            project.frames[1].strokes.flatMap { it.cells },
            project.frames[displayedPlayingJump].strokes.flatMap { it.cells }
        )
    }

    @Test
    fun checkE_audioSequenceFollowsFrame1To2To3InMidiExport() {
        val project = threeFrameProject()
        val midiBytes = MidiExporter.export(project) { 0 to 0 }
        // Parse Note-On events (0x90, note, velocity > 0) in chronological order from SMF track.
        val notes = mutableListOf<Int>()
        var i = 0
        while (i + 2 < midiBytes.size) {
            val b = midiBytes[i].toInt() and 0xFF
            if ((b and 0xF0) == 0x90) {
                val note = midiBytes[i + 1].toInt() and 0xFF
                val vel = midiBytes[i + 2].toInt() and 0xFF
                if (vel > 0) notes.add(note)
                i += 3
            } else {
                i++
            }
        }
        val expectedF1 = midiForLine(1, project.rootMidi, project.scale)!!
        val expectedF2 = midiForLine(15, project.rootMidi, project.scale)!!
        val expectedF3 = midiForLine(36, project.rootMidi, project.scale)!!
        assertEquals(listOf(expectedF1, expectedF2, expectedF3), notes)
    }

    @Test
    fun checkF_playheadStepAlignsAcrossFrameSequenceAndLoop() {
        val project = threeFrameProject()
        // Simulate step loop matching MainActivity.startPlayback:
        // frameIndex advances at step == project.steps and wraps to 0 on loop.
        val trace = mutableListOf<Pair<Int, Int>>()
        var frameIndex = 0
        var step = 0
        val totalTicks = project.steps * 4 // 1 full 3-frame pass + 1 wrap into frame 1
        repeat(totalTicks) {
            if (step >= project.steps) {
                step = 0
                frameIndex = (frameIndex + 1) % project.frames.size
            }
            val displayed = displayedFrameIndex(
                selectedFrame = 0,
                frameCount = project.frames.size,
                isPlaying = true,
                playbackFrame = frameIndex
            )
            trace.add(displayed to step)
            step++
        }
        assertEquals(0 to 0, trace[0])
        assertEquals(0 to 23, trace[23])
        assertEquals(1 to 0, trace[24])
        assertEquals(1 to 5, trace[24 + 5])
        assertEquals(2 to 0, trace[48])
        assertEquals(2 to 12, trace[48 + 12])
        assertEquals(0 to 0, trace[72])
        assertTrue(trace.all { (f, s) -> f in 0..2 && s in 0 until project.steps })
    }
}
