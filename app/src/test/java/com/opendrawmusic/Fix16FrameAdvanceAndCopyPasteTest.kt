package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Fix16FrameAdvanceAndCopyPasteTest {

    @Test
    fun `Part A - advanceOrAppendEmptyFrame moves to existing frame 2 and keeps step 23 cell in frame 1`() {
        val strokeWithStep23 = ProjectStroke("SF_SOFT_KEY", "BRUSH", listOf(0.5f to 0.5f, 0.99f to 0.5f), listOf(120, 143))
        var project = MusicProject(frames = listOf(ProjectFrame(listOf(strokeWithStep23)), ProjectFrame()), selectedFrame = 0)
        val history = FrameHistory().apply { ensureSize(2) }

        history.recordEdit(0, emptyList())
        val (advanced, appended) = project.advanceOrAppendEmptyFrame()
        project = advanced

        assertFalse(appended)
        assertEquals(1, project.selectedFrame)
        assertEquals(2, project.frames.size)
        assertTrue(143 in project.frames[0].strokes.single().cells) // step 23 = 5 * 24 + 23 = 143
        assertEquals(0, project.frames[1].strokes.size)
        assertEquals(1, history.undoSize(0))
        val undone = history.undo(0, project.frames[0].strokes)
        assertEquals(emptyList<ProjectStroke>(), undone)
        assertEquals(1, project.selectedFrame) // selectedFrame stays on frame 2
    }

    @Test
    fun `Part A - advanceOrAppendEmptyFrame on last frame appends empty frame with same project settings`() {
        var project = MusicProject(bpm = 135, scale = "MINOR", rootMidi = 52, selectedInstrument = "SF_MARIMBA")
        val history = FrameHistory().apply { ensureSize(1) }

        val (p2, appended1) = project.advanceOrAppendEmptyFrame()
        if (appended1) history.addFrame()
        project = p2
        assertTrue(appended1)
        assertEquals(2, project.frames.size)
        assertEquals(1, project.selectedFrame)
        assertEquals(0, project.frames[1].strokes.size)
        assertEquals(135, project.bpm)
        assertEquals("MINOR", project.scale)
        assertEquals("SF_MARIMBA", project.selectedInstrument)

        val (p3, appended2) = project.advanceOrAppendEmptyFrame()
        if (appended2) history.addFrame()
        project = p3
        assertTrue(appended2)
        assertEquals(3, project.frames.size)
        assertEquals(2, project.selectedFrame)
    }

    @Test
    fun `Part B - copy and paste before, after, repeated, empty, and json round-trip`() {
        val cells1 = (0 until 10).toList()
        val f1 = ProjectFrame(listOf(ProjectStroke("SF_SOFT_KEY", "BRUSH", emptyList(), cells1)))
        val f2 = ProjectFrame(listOf(ProjectStroke("SF_MARIMBA", "BRUSH", emptyList(), listOf(24, 25))))
        val f3 = ProjectFrame()
        var project = MusicProject(frames = listOf(f1, f2, f3), selectedFrame = 0)
        val history = FrameHistory().apply { ensureSize(3) }

        val clip = project.frames[0].deepCopy()
        assertNotSame(project.frames[0], clip)
        assertEquals(10, project.frames[0].strokes.single().cells.size)

        // Paste after frame 1 (insertIndex = 1)
        history.insertFrame(1)
        project = project.insertFrameAt(1, clip.deepCopy())
        assertEquals(4, project.frames.size)
        assertEquals(1, project.selectedFrame)
        assertEquals(cells1, project.frames[1].strokes.single().cells)
        assertEquals(cells1, project.frames[0].strokes.single().cells)

        // Paste before frame index 2 (original f2 is now at index 2 -> insertIndex = 2)
        history.insertFrame(2)
        project = project.insertFrameAt(2, clip.deepCopy())
        assertEquals(5, project.frames.size)
        assertEquals(2, project.selectedFrame)
        assertEquals(cells1, project.frames[2].strokes.single().cells)

        // Paste empty frame
        val emptyClip = ProjectFrame().deepCopy()
        history.insertFrame(0)
        project = project.insertFrameAt(0, emptyClip)
        assertEquals(6, project.frames.size)
        assertEquals(0, project.frames[0].strokes.size)

        // JSON persistence
        val reloaded = MusicProject.fromJson(project.toJson())
        assertEquals(project.frames.size, reloaded.frames.size)
        assertEquals(cells1, reloaded.frames[2].strokes.single().cells)
    }
}
