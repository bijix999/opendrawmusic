package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run 7 Fix 11 unit verification for [SequencerScheduler]:
 * - Consecutive same-pitch steps stay held (legato, no noteOff/re-attack).
 * - Frame transitions and loop wraps preserve held notes seamlessly.
 * - Different-pitch consecutive steps produce separate noteOn events.
 */
class SeamlessPlaybackSchedulerTest {

    private fun stroke(instrument: String, vararg cells: Int) = ProjectStroke(
        instrument = instrument,
        tool = "BRUSH",
        points = listOf(0.5f to 0.5f),
        cells = cells.toList()
    )

    @Test
    fun samePitchConsecutiveStepsStayHeldWithoutReattack() {
        // Line 25 (0-based row 24 = C4/MIDI 60) on steps 0, 1, 2
        val project = MusicProject(
            frames = listOf(
                ProjectFrame(listOf(stroke("SF_SOFT_KEY", 24 * 24 + 0, 24 * 24 + 1, 24 * 24 + 2)))
            )
        )
        var held = emptySet<SequencerNoteKey>()

        val d0 = SequencerScheduler.diffStep(held, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 0))
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 60)), d0.noteOns)
        assertTrue(d0.noteOffs.isEmpty())
        held = d0.nextHeld

        val d1 = SequencerScheduler.diffStep(held, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 1))
        assertTrue("step 1 must not re-attack same pitch", d1.noteOns.isEmpty())
        assertTrue("step 1 must not release held note", d1.noteOffs.isEmpty())
        held = d1.nextHeld

        val d2 = SequencerScheduler.diffStep(held, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 2))
        assertTrue("step 2 must not re-attack same pitch", d2.noteOns.isEmpty())
        assertTrue("step 2 must not release held note", d2.noteOffs.isEmpty())
        held = d2.nextHeld

        val d3 = SequencerScheduler.diffStep(held, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 3))
        assertTrue(d3.noteOns.isEmpty())
        assertEquals("step 3 releases held note via noteOff", listOf(SequencerNoteKey("SF_SOFT_KEY", 60)), d3.noteOffs)
    }

    @Test
    fun frameTransitionAndLoopWrapKeepSamePitchHeld() {
        // Frame 0 step 23 and Frame 1 step 0 both hold line 25 (MIDI 60);
        // Frame 1 step 23 and Frame 0 step 0 both hold line 25 (MIDI 60) on loop wrap.
        val cellStep0 = 24 * 24 + 0
        val cellStep23 = 24 * 24 + 23
        val project = MusicProject(
            frames = listOf(
                ProjectFrame(listOf(stroke("SF_SOFT_KEY", cellStep0, cellStep23))),
                ProjectFrame(listOf(stroke("SF_SOFT_KEY", cellStep0, cellStep23)))
            )
        )
        // Frame 0 step 23
        val dF0S23 = SequencerScheduler.diffStep(
            emptySet(),
            SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 23)
        )
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 60)), dF0S23.noteOns)

        // Frame 1 step 0 (transition 1 -> 2)
        val dF1S0 = SequencerScheduler.diffStep(
            dF0S23.nextHeld,
            SequencerScheduler.activeNotesAtStep(project, project.frames[1].strokes, 0)
        )
        assertTrue("frame 1->2 transition keeps note held", dF1S0.noteOffs.isEmpty())
        assertTrue("frame 1->2 transition does not re-attack", dF1S0.noteOns.isEmpty())

        // Frame 1 step 23 -> Frame 0 step 0 (loop wrap)
        val dF1S23 = SequencerScheduler.diffStep(
            dF1S0.nextHeld,
            SequencerScheduler.activeNotesAtStep(project, project.frames[1].strokes, 23)
        )
        val dLoopWrap = SequencerScheduler.diffStep(
            dF1S23.nextHeld,
            SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 0)
        )
        assertTrue("loop wrap keeps note held", dLoopWrap.noteOffs.isEmpty())
        assertTrue("loop wrap does not re-attack", dLoopWrap.noteOns.isEmpty())
    }

    @Test
    fun differentPitchConsecutiveStepsProduceSeparateOnsets() {
        // Step 0: line 25 (MIDI 60), Step 1: line 23 (MIDI 62), Step 2: line 21 (MIDI 64)
        val project = MusicProject(
            frames = listOf(
                ProjectFrame(listOf(
                    stroke("SF_SOFT_KEY", 24 * 24 + 0, 22 * 24 + 1, 20 * 24 + 2)
                ))
            )
        )
        val d0 = SequencerScheduler.diffStep(emptySet(), SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 0))
        val d1 = SequencerScheduler.diffStep(d0.nextHeld, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 1))
        val d2 = SequencerScheduler.diffStep(d1.nextHeld, SequencerScheduler.activeNotesAtStep(project, project.frames[0].strokes, 2))

        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 60)), d0.noteOns)
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 60)), d1.noteOffs)
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 62)), d1.noteOns)
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 62)), d2.noteOffs)
        assertEquals(listOf(SequencerNoteKey("SF_SOFT_KEY", 64)), d2.noteOns)
    }
}
