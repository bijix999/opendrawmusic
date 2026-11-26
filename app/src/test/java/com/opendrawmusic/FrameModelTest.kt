package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run 7 Step 1 checks: multi-frame storage, legacy single-grid load, and per-frame
 * history isolation (§3.1 + §3.1.5).
 */
class FrameModelTest {

    @Test
    fun originalRgbHexNormalizesToArgbWithoutDuplicatingHash() {
        assertEquals("#FF8277DB", InstrumentPalette.normalizeHex("#8277DB"))
        assertEquals("#FF8277DB", InstrumentPalette.normalizeHex("8277DB"))
        assertEquals("#FF8277DB", InstrumentPalette.normalizeHex("#FF8277DB"))
    }

    private fun stroke(instrument: String, vararg cells: Int) = ProjectStroke(
        instrument = instrument,
        tool = "BRUSH",
        points = listOf(0.5f to 0.5f),
        cells = cells.toList()
    )

    private fun threeFrameProject() = MusicProject(
        title = "three-frames",
        frames = listOf(
            ProjectFrame(listOf(stroke("SF_SOFT_KEY", 0, 25, 96))),          // line 1 step 0, line 2 step 1, line 5 step 0
            ProjectFrame(listOf(stroke("SF_MARIMBA", 47, 400))),             // line 2 step 23, line 17 step 16
            ProjectFrame(listOf(stroke("SF_8_BIT", 863)))                    // line 36 step 23 (last cell)
        ),
        selectedFrame = 1
    )

    @Test
    fun threeFrameRoundTripKeepsEveryCell() {
        val original = threeFrameProject()
        val reloaded = MusicProject.fromJson(original.toJson())

        assertEquals("frame count", 3, reloaded.frames.size)
        assertEquals("selected frame index", 1, reloaded.selectedFrame)
        original.frames.forEachIndexed { index, frame ->
            assertEquals("frame $index cells",
                frame.strokes.flatMap { it.cells }.sorted(),
                reloaded.frames[index].strokes.flatMap { it.cells }.sorted())
            assertEquals("frame $index instruments",
                frame.strokes.map { it.instrument },
                reloaded.frames[index].strokes.map { it.instrument })
        }
        assertEquals("title", "three-frames", reloaded.title)
        assertEquals("rows is 36", PITCH_LINES, reloaded.rows)
    }

    @Test
    fun tenFrameRoundTripHasNoCap() {
        // Unlimited frames (§3.1.2): 10 frames store and reload with no limit constant.
        val frames = (0 until 10).map { i ->
            ProjectFrame(listOf(stroke("SF_SOFT_KEY", i * 24)))
        }
        val project = MusicProject(title = "ten", frames = frames)
        val reloaded = MusicProject.fromJson(project.toJson())
        assertEquals(10, reloaded.frames.size)
        reloaded.frames.forEachIndexed { i, f ->
            assertEquals(listOf(i * 24), f.strokes.flatMap { it.cells })
        }
    }

    @Test
    fun legacySingleGridProjectLoadsAsExactlyOneFrameAtLines5To36() {
        // A pre-Run-7 save: version 1, top-level strokes, cells over 24 x 32 rows.
        // Old row r must land on line r + 5 (cell + 96) with the same pitch.
        val legacyCells = listOf(
            0 * 24 + 0,   // old row 0  -> line 5  (C6 at default)
            10 * 24 + 3,  // old row 10 -> line 15
            21 * 24 + 7,  // old row 21 -> line 26 (C4 at default)
            31 * 24 + 23  // old row 31 -> line 36 (C3 at default)
        )
        val legacyJson = """
            {
              "version": 1,
              "id": "legacy-1",
              "title": "Old project",
              "steps": 24,
              "rows": 32,
              "bpm": 120,
              "rootMidi": 48,
              "scale": "PENTATONIC",
              "selectedInstrument": "SF_SOFT_KEY",
              "selectedTool": "BRUSH",
              "strokes": [
                {"instrument": "SF_SOFT_KEY", "tool": "BRUSH",
                 "points": [[0.1, 0.2]], "cells": [${legacyCells.joinToString(",")}]}
              ]
            }
        """.trimIndent()

        val loaded = MusicProject.fromJson(legacyJson)

        assertEquals("legacy project becomes exactly 1 frame", 1, loaded.frames.size)
        assertEquals("selected frame", 0, loaded.selectedFrame)
        val cells = loaded.frames[0].strokes.flatMap { it.cells }.sorted()
        assertEquals("old rows 0-31 map to lines 5-36 (cell + 96)",
            legacyCells.map { it + LEGACY_LINE_OFFSET }.sorted(), cells)

        // Nothing re-pitched: the mapped line plays the same MIDI as the old row did.
        val expectedMidi = listOf(
            midiForRow(0, 48, "PENTATONIC"),
            midiForRow(10, 48, "PENTATONIC"),
            midiForRow(21, 48, "PENTATONIC"),
            midiForRow(31, 48, "PENTATONIC")
        )
        val lineOf = listOf(5, 15, 26, 36)
        lineOf.forEachIndexed { i, line ->
            assertEquals("line $line keeps old row pitch",
                expectedMidi[i], midiForLine(line, 48, "PENTATONIC"))
        }
        // New lines 1-4 start empty: no legacy cell maps below line 5.
        assertTrue("all mapped cells are on lines 5-36", cells.all { it >= LEGACY_LINE_OFFSET })

        // The loaded legacy project can then gain a 2nd frame via + (§T24).
        val withSecond = loaded.copy(frames = loaded.frames + ProjectFrame())
        assertEquals(2, withSecond.frames.size)
        assertTrue(withSecond.frames[1].strokes.isEmpty())
        // ...and the legacy frame still round-trips in the new format.
        val reloaded = MusicProject.fromJson(withSecond.toJson())
        assertEquals(2, reloaded.frames.size)
        assertEquals(cells, reloaded.frames[0].strokes.flatMap { it.cells }.sorted())
    }

    @Test
    fun clearAffectsOnlyTheSelectedFrameAndIsUndoable() {
        // §3.1.5a/d: Clear empties only the selected frame and is one undoable entry.
        var project = threeFrameProject()
        val history = FrameHistory()
        history.reset(project.frames.size)

        val before = project.selectedStrokes
        history.recordEdit(project.selectedFrame, before)
        project = project.withFrameStrokes(project.selectedFrame, emptyList())

        assertTrue("frame 2 (index 1) cleared", project.frames[1].strokes.isEmpty())
        assertEquals("frame 1 untouched", 1, project.frames[0].strokes.size)
        assertEquals("frame 3 untouched", 1, project.frames[2].strokes.size)

        val restored = history.undo(project.selectedFrame, project.selectedStrokes)
        assertNotNull("clear is undoable", restored)
        project = project.withFrameStrokes(project.selectedFrame, restored!!)
        assertEquals("undo restores the cleared notes",
            before.flatMap { it.cells }, project.frames[1].strokes.flatMap { it.cells })
    }

    @Test
    fun perFrameHistoryIsolation() {
        // §3.1.5b: undoing in frame 1 never changes frame 2; histories are separate.
        var project = threeFrameProject()
        val history = FrameHistory()
        history.reset(project.frames.size)

        // Two edits, one per frame (like one stroke in each).
        val f0Before = project.frames[0].strokes
        project = project.withFrameStrokes(0, f0Before + stroke("SF_SOFT_KEY", 1, 2))
        history.recordEdit(0, f0Before)

        val f1Before = project.frames[1].strokes
        project = project.withFrameStrokes(1, f1Before + stroke("SF_MARIMBA", 3, 4))
        history.recordEdit(1, f1Before)

        // Undo in frame 1 (index 0): only frame 1's edit disappears.
        val undone0 = history.undo(0, project.frames[0].strokes)
        assertNotNull(undone0)
        project = project.withFrameStrokes(0, undone0!!)
        assertEquals(f0Before.flatMap { it.cells }, project.frames[0].strokes.flatMap { it.cells })
        assertEquals("frame 2 unchanged by frame 1's undo",
            (f1Before + stroke("SF_MARIMBA", 3, 4)).flatMap { it.cells },
            project.frames[1].strokes.flatMap { it.cells })

        // Frame 2 still has its own undo entry; frame 1 has none left.
        assertFalse("frame 1 stack empty", history.canUndo(0))
        assertTrue("frame 2 stack still has its entry", history.canUndo(1))
        assertNull("undo in frame 1 now does nothing", history.undo(0, project.frames[0].strokes))

        // Frame 1's undo left its own redo entry (redoSize 1); it must survive frame 2's
        // undo untouched - the histories never merge (§3.1.5b/c).
        val redo0AfterUndo0 = history.redoSize(0)
        assertEquals("frame 1 has exactly its own redo entry", 1, redo0AfterUndo0)

        // Redo in frame 2 restores frame 2 only; frame 1's state is untouched.
        val undone1 = history.undo(1, project.frames[1].strokes)
        assertNotNull(undone1)
        project = project.withFrameStrokes(1, undone1!!)
        assertEquals(f1Before.flatMap { it.cells }, project.frames[1].strokes.flatMap { it.cells })
        assertEquals("frame 1's redo state unaffected by frame 2's undo",
            redo0AfterUndo0, history.redoSize(0))
        assertTrue("frame 2 has a redo entry", history.canRedo(1))

        val redone1 = history.redo(1, project.frames[1].strokes)
        assertNotNull(redone1)
        project = project.withFrameStrokes(1, redone1!!)
        assertEquals((f1Before + stroke("SF_MARIMBA", 3, 4)).flatMap { it.cells },
            project.frames[1].strokes.flatMap { it.cells })
        assertEquals("frame 1 still exactly as left",
            f0Before.flatMap { it.cells }, project.frames[0].strokes.flatMap { it.cells })
    }

    @Test
    fun historyFollowsFrameIdentityAcrossDeleteAndAdd() {
        // §3.3.8c: deleting a frame discards only its history; others stay attached.
        val history = FrameHistory()
        history.reset(3)
        history.recordEdit(0, listOf(stroke("SF_SOFT_KEY", 1)))
        history.recordEdit(1, listOf(stroke("SF_MARIMBA", 2)))
        history.recordEdit(2, listOf(stroke("SF_8_BIT", 3)))
        assertEquals(listOf(1, 1, 1), (0..2).map { history.undoSize(it) })

        history.deleteFrame(1)   // middle frame's history discarded
        assertEquals("remaining histories shift with their frames",
            listOf(1, 1), (0..1).map { history.undoSize(it) })
        assertEquals("the deleted frame's entry is gone", 0, history.redoSize(1))

        // Adding a frame (+) gives it an empty history and is not an undo entry.
        history.addFrame()
        assertEquals(0, history.undoSize(2))
        assertFalse(history.canUndo(2))
        assertFalse(history.canRedo(2))
        assertEquals("other frames' stacks unchanged by +",
            listOf(1, 1, 0), (0..2).map { history.undoSize(it) })
    }

    @Test
    fun reloadStartsEveryFrameWithEmptyHistory() {
        // §3.1.5f: history is memory-only; after reload every frame starts empty.
        val history = FrameHistory()
        history.reset(3)
        history.recordEdit(0, listOf(stroke("SF_SOFT_KEY", 1)))
        assertTrue(history.canUndo(0))

        val reloaded = MusicProject.fromJson(threeFrameProject().toJson())
        history.reset(reloaded.frames.size)
        assertFalse("history empty after reload", history.canUndo(0))
        assertEquals(0, history.undoSize(0))
        assertEquals(0, history.redoSize(0))
    }

    @Test
    fun undoHistoryIsCappedAtOneHundredEntriesPerFrame() {
        val history = FrameHistory()
        history.reset(1)
        repeat(FrameHistory.MAX_ENTRIES + 25) {
            history.recordEdit(0, listOf(stroke("SF_SOFT_KEY", it)))
        }
        assertEquals(FrameHistory.MAX_ENTRIES, history.undoSize(0))
    }
}
