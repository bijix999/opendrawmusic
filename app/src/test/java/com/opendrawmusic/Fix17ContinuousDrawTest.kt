package com.opendrawmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fix17ContinuousDrawTest {

    private class Harness(initialFrames: Int = 1, existingFrame2Strokes: List<ProjectStroke> = emptyList()) {
        var project = MusicProject(
            bpm = 132,
            scale = "MINOR",
            rootMidi = 50,
            selectedInstrument = "SF_MARIMBA",
            frames = List(initialFrames) { idx ->
                if (idx == 1) ProjectFrame(existingFrame2Strokes) else ProjectFrame()
            },
            selectedFrame = 0
        )
        val history = FrameHistory().apply { ensureSize(initialFrames) }

        fun xForStep(step: Int): Float = (step + 0.5f) / 24f
        fun yForLine(line: Int): Float = (line + 0.5f) / 36f

        fun dragSequence(
            coords: List<Pair<Float, Float>>,
            isBrush: Boolean = true,
            onAfterAdvance: ((Int) -> Unit)? = null
        ) {
            val tracker = CrossFrameStrokeTracker(project.steps, project.rows)
            for ((x, y) in coords) {
                when (val res = tracker.onPoint(x, y, isBrush)) {
                    CrossFrameStrokeTracker.EventResult.Ignored -> Unit
                    is CrossFrameStrokeTracker.EventResult.Draw -> Unit
                    is CrossFrameStrokeTracker.EventResult.AdvanceEdge -> {
                        commit(res.committedCells, res.committedPoints, isBrush)
                        val (next, appended) = project.advanceOrAppendEmptyFrame()
                        if (appended) history.addFrame()
                        project = next
                        onAfterAdvance?.invoke(tracker.paintedCells.size)
                    }
                }
            }
            if (tracker.paintedCells.isNotEmpty()) {
                commit(tracker.paintedCells.toSet(), tracker.points.toList(), isBrush)
            }
        }

        private fun commit(cells: Set<Int>, points: List<Pair<Float, Float>>, isBrush: Boolean) {
            val idx = project.selectedFrame
            val before = project.selectedStrokes
            val nextStrokes = if (isBrush) {
                before + ProjectStroke(project.selectedInstrument, "BRUSH", points, cells.toList())
            } else {
                before.mapNotNull { st ->
                    val rem = st.cells.filterNot(cells::contains)
                    if (rem.isEmpty()) null else st.copy(cells = rem)
                }
            }
            if (nextStrokes != before) {
                history.recordEdit(idx, before)
                project = project.withFrameStrokes(idx, nextStrokes)
            }
        }

        fun cellsInFrame(frameIndex: Int): List<Int> =
            project.frames[frameIndex].strokes.flatMap { it.cells }

        fun stepsInFrame(frameIndex: Int): Set<Int> =
            cellsInFrame(frameIndex).map { it % project.steps }.toSet()

        fun lineForStep(frameIndex: Int, step: Int): Int? =
            cellsInFrame(frameIndex).firstOrNull { it % project.steps == step }?.div(project.steps)
    }

    @Test
    fun `check a - continuous draw with connection cell step 0 line 17 and drag left then right 10 steps`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        val sweep1 = (0..23).map { h.xForStep(it) to y17 }
        val repoLeft = listOf(h.xForStep(15) to y17, h.xForStep(5) to y17, h.xForStep(0) to y17)
        val drawRight10 = (1..10).map { (h.xForStep(0) + it / 24f) to y17 }

        var liveCountRightAfterAdvance = -1
        h.dragSequence(sweep1 + repoLeft + drawRight10) { liveCount ->
            liveCountRightAfterAdvance = liveCount
        }

        assertEquals(1, liveCountRightAfterAdvance)
        assertEquals(2, h.project.frames.size)
        assertEquals(1, h.project.selectedFrame)
        assertEquals(24, h.cellsInFrame(0).distinct().size)
        assertEquals(11, h.cellsInFrame(1).distinct().size)
        assertEquals((0..10).toSet(), h.stepsInFrame(1))
        assertEquals(17, h.lineForStep(1, 0))
        assertEquals(1, h.history.undoSize(0))
        assertEquals(1, h.history.undoSize(1))
    }

    @Test
    fun `check b - connection line matches step 23 line 20 while step 1-3 follow y line 5`() {
        val h = Harness(initialFrames = 1)
        val sweep1 = (0..22).map { h.xForStep(it) to h.yForLine(12) } + listOf(h.xForStep(23) to h.yForLine(20))
        val repoLeft = listOf(h.xForStep(18) to h.yForLine(15), h.xForStep(10) to h.yForLine(5))
        val drawRight3 = (1..3).map { (h.xForStep(10) + it / 24f) to h.yForLine(5) }

        h.dragSequence(sweep1 + repoLeft + drawRight3)

        assertEquals(20, h.lineForStep(0, 23))
        assertEquals(20, h.lineForStep(1, 0))
        assertEquals(5, h.lineForStep(1, 1))
        assertEquals(5, h.lineForStep(1, 2))
        assertEquals(5, h.lineForStep(1, 3))
    }

    @Test
    fun `check c - short ratchet 3 cells left then 4 cells right gives steps 0 to 4`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        val sweep1 = (0..22).map { h.xForStep(it) to y17 } + listOf((23.0f / 24f) to y17)
        val shortLeft3 = listOf((20.0f / 24f) to y17) // 3 cellWidths left from step 23
        val right4 = (1..4).map { ((20.0f + it) / 24f) to y17 }

        h.dragSequence(sweep1 + shortLeft3 + right4)

        assertEquals(5, h.cellsInFrame(1).distinct().size)
        assertEquals((0..4).toSet(), h.stepsInFrame(1))
    }

    @Test
    fun `check d - chained draw across 3 frames`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        val sweep1 = (0..23).map { h.xForStep(it) to y17 }
        val repo1 = listOf(h.xForStep(0) to y17)
        val sweep2 = (1..23).map { (h.xForStep(0) + it / 24f) to y17 }
        val repo2 = listOf(h.xForStep(0) to y17)
        val draw5 = (1..5).map { (h.xForStep(0) + it / 24f) to y17 }

        h.dragSequence(sweep1 + repo1 + sweep2 + repo2 + draw5)

        assertEquals(3, h.project.frames.size)
        assertEquals(2, h.project.selectedFrame)
        assertEquals(24, h.cellsInFrame(0).distinct().size)
        assertEquals(24, h.cellsInFrame(1).distinct().size)
        assertEquals(6, h.cellsInFrame(2).distinct().size)
        assertEquals((0..5).toSet(), h.stepsInFrame(2))
    }

    @Test
    fun `check e - 20 MOVE events at same x after advance do not trigger re-advance`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        val sweep1 = (0..23).map { h.xForStep(it) to y17 }
        val stationary20 = List(20) { h.xForStep(23) to y17 }

        h.dragSequence(sweep1 + stationary20)

        assertEquals(2, h.project.frames.size)
        assertEquals(1, h.project.selectedFrame)
        assertEquals(1, h.cellsInFrame(1).distinct().size) // only connection cell step 0, line 17
    }

    @Test
    fun `check f - per-frame undo and redo`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        val sweep1 = (0..23).map { h.xForStep(it) to y17 }
        val repo = listOf(h.xForStep(0) to y17)
        val draw10 = (1..10).map { (h.xForStep(0) + it / 24f) to y17 }
        h.dragSequence(sweep1 + repo + draw10)

        // Undo frame 2 -> frame 2 empty, frame 1 still 24
        val f2Undo = h.history.undo(1, h.project.frames[1].strokes)!!
        h.project = h.project.withFrameStrokes(1, f2Undo)
        assertEquals(0, h.cellsInFrame(1).size)
        assertEquals(24, h.cellsInFrame(0).size)

        // Undo frame 1 -> frame 1 empty
        h.project = h.project.copy(selectedFrame = 0)
        val f1Undo = h.history.undo(0, h.project.frames[0].strokes)!!
        h.project = h.project.withFrameStrokes(0, f1Undo)
        assertEquals(0, h.cellsInFrame(0).size)

        // Redo frame 1 -> frame 1 back to 24, frame 2 still empty
        val f1Redo = h.history.redo(0, h.project.frames[0].strokes)!!
        h.project = h.project.withFrameStrokes(0, f1Redo)
        assertEquals(24, h.cellsInFrame(0).size)
        assertEquals(0, h.cellsInFrame(1).size)

        // Redo frame 2 -> frame 2 back to 11
        h.project = h.project.copy(selectedFrame = 1)
        val f2Redo = h.history.redo(1, h.project.frames[1].strokes)!!
        h.project = h.project.withFrameStrokes(1, f2Redo)
        assertEquals(11, h.cellsInFrame(1).size)
    }

    @Test
    fun `check g - advance into existing empty and non-empty frame 2`() {
        // Non-empty frame 2 with steps 0..5 on line 3 (6 cells)
        val preExisting = listOf(ProjectStroke("SF_SOFT_KEY", "BRUSH", emptyList(), (0..5).map { 3 * 24 + it }))
        val h = Harness(initialFrames = 2, existingFrame2Strokes = preExisting)
        val y17 = h.yForLine(17)
        val sweep1 = (0..23).map { h.xForStep(it) to y17 }
        val repo = listOf(h.xForStep(10) to y17)
        val draw2 = (1..2).map { h.xForStep(10 + it) to y17 }

        h.dragSequence(sweep1 + repo + draw2)

        assertEquals(2, h.project.frames.size)
        assertEquals(1, h.project.selectedFrame)
        assertEquals(9, h.cellsInFrame(1).distinct().size) // 6 existing + 3 new (step 0..2 line 17)
        val restored = h.history.undo(1, h.project.frames[1].strokes)!!
        h.project = h.project.withFrameStrokes(1, restored)
        assertEquals(6, h.cellsInFrame(1).size) // restores exact 6 existing cells
    }

    @Test
    fun `check h and j and k - finger lift resets absolute mapping, regression checks, and project settings preserved`() {
        val h = Harness(initialFrames = 1)
        val y17 = h.yForLine(17)
        // Cross into frame 2 and lift
        h.dragSequence((0..23).map { h.xForStep(it) to y17 })
        assertEquals(2, h.project.frames.size)
        assertEquals(1, h.cellsInFrame(1).size) // connection cell only

        // New touch at X(8) uses absolute mapping (step 8)
        h.dragSequence(listOf(h.xForStep(8) to y17))
        assertTrue((17 * 24 + 8) in h.cellsInFrame(1))

        // Mid-grid stop at step 10, step 0, and eraser at step 23 do not advance
        h.dragSequence((0..10).map { h.xForStep(it) to y17 })
        h.dragSequence(listOf(h.xForStep(0) to y17))
        h.dragSequence((0..23).map { h.xForStep(it) to y17 }, isBrush = false)
        assertEquals(2, h.project.frames.size)
        assertEquals(132, h.project.bpm)
        assertEquals("MINOR", h.project.scale)
        assertEquals("SF_MARIMBA", h.project.selectedInstrument)
    }
}
